package com.rpm.player.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.rpm.player.core.diag.Diag
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The equaliser: every buffer of decoded audio goes through [Equalizer] with the curve in [settings], before the room,
 * the cast and the phone's own output hear it. It sits in the chain of sinks and not in Android's audio effects, so it
 * works the same on every phone, for files and streams alike, and for the FFmpeg renderer too, which is built with the
 * same sink.
 *
 * The curve is read for every buffer, so a change in the settings is heard as soon as the audio already handed to the
 * output has played (a fraction of a second). Nothing restarts. The filter memory is cleared at each new stream and
 * each seek, as on the desktop.
 *
 * 16-bit, 24-bit, 32-bit and float PCM are filtered. Anything else (a passthrough stream) goes by unchanged, and so does
 * everything while the curve is off or flat.
 *
 * Used on the playback thread only, as every sink is.
 */
@OptIn(UnstableApi::class)
class EqSink(sink: AudioSink, private val settings: Equalizing) : ForwardingAudioSink(sink) {
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var sampleRate = 0

    /** Frames still to measure after a change of curve, and the sums of squares before and after the filter. One line
     * in the log then says how much louder or quieter the curve made the music, so a curve can be checked on a phone
     * without listening. */
    private var measureLeft = 0
    private var sumIn = 0.0
    private var sumOut = 0.0

    /** The filter for the stream being played; null for a stream it cannot filter. */
    private var filter: Equalizer? = null

    /** The curve [filter] was last set from, so the coefficients are worked out once per change and not per buffer. */
    private var applied: EqCurve? = null

    /** Whether the last buffer went through unfiltered. The filter memory is cleared when filtering starts again. */
    private var resting = true

    /** A buffer passed on unchanged that the sink has not taken whole, and its time. */
    private var through: ByteBuffer? = null
    private var throughUs = C.TIME_UNSET

    /** The buffer being handed on in parts, its time, and the filtered copy of it. */
    private var pending: ByteBuffer? = null
    private var pendingUs = C.TIME_UNSET
    private var pendingFrom = 0
    private var copy: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var samples = FloatArray(0)

    override fun configure(config: AudioSink.AudioSinkConfig) {
        val format = config.format
        encoding = if (format.sampleMimeType == MimeTypes.AUDIO_RAW && EqPcm.width(format.pcmEncoding) > 0) format.pcmEncoding else C.ENCODING_INVALID
        channels = format.channelCount
        sampleRate = format.sampleRate
        filter = if (encoding != C.ENCODING_INVALID && format.sampleRate >= 8000 && channels in 1..Equalizer.MAX_CHANNELS) {
            Equalizer(format.sampleRate, channels)
        } else {
            null
        }
        applied = null
        resting = true
        Diag.d(TAG, "stream ${format.sampleRate} Hz, $channels ch, encoding $encoding: ${if (filter == null) "not filtered" else "curve ${settings.curve}"}")
        super.configure(config)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (buffer === pending && presentationTimeUs == pendingUs) return handOn(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (buffer === through && presentationTimeUs == throughUs) return passOn(buffer, presentationTimeUs, encodedAccessUnitCount)

        val eq = filter ?: return passOn(buffer, presentationTimeUs, encodedAccessUnitCount)
        val curve = settings.curve
        if (curve !== applied) {
            if (curve.on) eq.setGains(curve.preamp, curve.gains) else eq.setGains(0.0, EqCurve.OFF.gains)
            applied = curve
            Diag.d(TAG, "curve $curve, bypassed ${eq.isBypassed}")
            measureLeft = sampleRate
            sumIn = 0.0
            sumOut = 0.0
        }
        if (eq.isBypassed) {
            resting = true
            return passOn(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (resting) {
            eq.reset()
            resting = false
        }

        val bytes = buffer.remaining()
        val width = EqPcm.width(encoding)
        val room = bytes / width + 1
        if (samples.size < room) samples = FloatArray(room * 2)
        if (copy.capacity() < bytes) copy = ByteBuffer.allocateDirect(bytes * 2).order(ByteOrder.nativeOrder())
        copy.clear()
        copy.limit(bytes)
        val count = EqPcm.read(buffer, encoding, channels, samples)
        if (measureLeft > 0) sumIn += sumOfSquares(count)
        eq.process(samples, count / channels)
        if (measureLeft > 0) {
            sumOut += sumOfSquares(count)
            measureLeft -= count / channels
            if (measureLeft <= 0 && sumIn > 0) {
                Diag.d(TAG, "level after the curve: %+.1f dB".format(java.util.Locale.ROOT, 10 * kotlin.math.log10(maxOf(sumOut, 1e-12) / sumIn)))
            }
        }
        EqPcm.write(samples, count, encoding, buffer, copy)
        copy.position(0)
        pending = buffer
        pendingUs = presentationTimeUs
        pendingFrom = buffer.position()
        return handOn(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    private fun sumOfSquares(count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
        return sum
    }

    /** Hands [buffer] on unchanged. */
    private fun passOn(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val whole = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        through = if (whole) null else buffer
        throughUs = presentationTimeUs
        return whole
    }

    /** Hands the filtered copy on, and moves [buffer] on as far as the sink took the copy, so the renderer sees how
     * much of its buffer is done. */
    private fun handOn(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val whole = super.handleBuffer(copy, presentationTimeUs, encodedAccessUnitCount)
        buffer.position(pendingFrom + copy.position())
        if (whole) pending = null
        return whole
    }

    override fun flush() {
        pending = null
        through = null
        filter?.reset()
        super.flush()
    }

    override fun reset() {
        pending = null
        through = null
        filter?.reset()
        super.reset()
    }

    private companion object {
        /** For `adb logcat -s rpm.eq`: one line per stream and one per change of curve. */
        const val TAG = "rpm.eq"
    }
}
