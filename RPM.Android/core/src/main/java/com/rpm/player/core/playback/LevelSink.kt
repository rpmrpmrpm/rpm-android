package com.rpm.player.core.playback

import com.rpm.player.core.diag.Diag
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Volume levelling: every buffer of decoded audio is multiplied by the gain its track is levelled to,
 * before the room, the cast and the TV hear it. The desktop applies its gain before the spectrum
 * (AudioEngine.ApplyGain) and the iOS app before its engine. This sink wraps the other sinks, so all
 * of them get the levelled sound.
 *
 * The sink's configuration says which track a buffer belongs to: since Media3 1.11 every
 * configuration names the queue and the period it is for. A renderer configures the sink again for
 * each new stream before it hands over the stream's first buffer. So the gain changes at the first
 * sample of the new track, and not when the main thread hears about the change. The reading is
 * worked out from the queue at that moment ([QueueRecord]), and the record's classical mark is
 * asked from [classical] then.
 *
 * The gain moves towards a new value by one unit of linear gain in 20 ms (the desktop's slide), so a
 * change of setting or of track does not click. A start or a seek lands on its gain at once. Samples
 * are held to full scale. 16-bit, 24-bit, 32-bit and float PCM are levelled. Anything else (a
 * passthrough stream) goes by unchanged.
 *
 * Used on the playback thread only, as every sink is.
 */
@OptIn(UnstableApi::class)
class LevelSink(
    sink: AudioSink,
    private val settings: Levelling,
    /** Whether the record of a queued item was marked classical by its shelf's owner. */
    private val classical: (MediaItem) -> Boolean,
) : ForwardingAudioSink(sink) {
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var sampleRate = 0
    private var stream = StreamLoudness.NONE

    /** The gain being applied now, linear. */
    private var gainNow = 1f
    private var landed = false

    /** The queue that the last record answer was worked out for, and the answer: one walk of the queue per queue. */
    private var recordOf: Timeline? = null
    private var record = QueueRecord.Answer(false, Level.UNKNOWN)

    /** A buffer passed on unchanged that the sink has not taken whole, and its time. It keeps being passed
     * on unchanged until the sink takes it, whatever the gain has become since: the sink refuses a different buffer in its place. */
    private var through: ByteBuffer? = null
    private var throughUs = C.TIME_UNSET

    /** The buffer being handed on in parts, its time, and the levelled copy of it. */
    private var pending: ByteBuffer? = null
    private var pendingUs = C.TIME_UNSET
    private var pendingFrom = 0
    private var copy: ByteBuffer = ByteBuffer.allocateDirect(0)

    override fun configure(config: AudioSink.AudioSinkConfig) {
        val format = config.format
        encoding = if (format.sampleMimeType == MimeTypes.AUDIO_RAW) format.pcmEncoding else C.ENCODING_INVALID
        channels = format.channelCount
        sampleRate = format.sampleRate
        stream = loudnessOf(config.timeline, config.mediaPeriodId?.periodUid)
        Diag.d(TAG, "stream ${config.mediaPeriodId?.periodUid}: $stream, gain ${Normalization.gainDb(settings.mode, settings.includeClassical, stream)} dB")
        super.configure(config)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (buffer === pending && presentationTimeUs == pendingUs) return handOn(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (buffer === through && presentationTimeUs == throughUs) return passOn(buffer, presentationTimeUs, encodedAccessUnitCount)
        val target = ReplayGain.linear(Normalization.gainDb(settings.mode, settings.includeClassical, stream))
        if (!landed) {
            gainNow = target
            landed = true
        }
        if (target == 1f && gainNow == 1f || !Pcm.levels(encoding)) return passOn(buffer, presentationTimeUs, encodedAccessUnitCount)

        val bytes = buffer.remaining()
        if (copy.capacity() < bytes) copy = ByteBuffer.allocateDirect(bytes * 2).order(ByteOrder.nativeOrder())
        copy.clear()
        copy.limit(bytes)
        gainNow = Pcm.level(buffer, copy, encoding, channels, gainNow, target, stepPerFrame())
        copy.position(0)
        pending = buffer
        pendingUs = presentationTimeUs
        pendingFrom = buffer.position()
        return handOn(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    /** Hands [buffer] on unchanged. */
    private fun passOn(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val whole = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        through = if (whole) null else buffer
        throughUs = presentationTimeUs
        return whole
    }

    /** Hands the levelled copy on, and moves [buffer] on as far as the sink took the copy, so the
     * renderer sees how much of its buffer is done. */
    private fun handOn(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val whole = super.handleBuffer(copy, presentationTimeUs, encodedAccessUnitCount)
        buffer.position(pendingFrom + copy.position())
        if (whole) pending = null
        return whole
    }

    override fun flush() {
        pending = null
        through = null
        landed = false
        super.flush()
    }

    override fun reset() {
        pending = null
        through = null
        landed = false
        recordOf = null
        super.reset()
    }

    /** One unit of linear gain in 20 ms (the desktop's GainStepPerFrame). */
    private fun stepPerFrame(): Float = if (sampleRate > 0) 50f / sampleRate else 1f

    private fun loudnessOf(timeline: Timeline?, periodUid: Any?): StreamLoudness {
        if (timeline == null || periodUid == null || timeline.isEmpty) return StreamLoudness.NONE
        val windowIndex = timeline.getIndexOfPeriod(periodUid).takeIf { it != C.INDEX_UNSET }
            ?.let { timeline.getPeriod(it, Timeline.Period()).windowIndex } ?: return StreamLoudness.NONE
        val item = timeline.getWindow(windowIndex, Timeline.Window()).mediaItem
        if (StationItems.uuidOf(item.mediaId) != null) return StreamLoudness.NONE
        if (timeline !== recordOf) {
            recordOf = timeline
            record = QueueRecord.of(readingsOf(timeline))
        }
        return StreamLoudness(
            track = item.mediaMetadata.extras.level(),
            album = record.album,
            inAlbumOrder = record.inAlbumOrder,
            classical = classical(item),
        )
    }

    private fun readingsOf(timeline: Timeline): List<QueuedReading> {
        val window = Timeline.Window()
        return List(timeline.windowCount) { i ->
            val item = timeline.getWindow(i, window).mediaItem
            val m = item.mediaMetadata
            QueuedReading(
                album = m.albumTitle?.toString().orEmpty(),
                albumArtist = m.albumArtist?.toString().orEmpty(),
                station = StationItems.uuidOf(item.mediaId) != null,
                level = m.extras.level(),
                seconds = (m.durationMs ?: 0L) / 1000.0,
            )
        }
    }

    private companion object {
        /** For `adb logcat -s rpm.level`: one line per stream, saying what it is levelled from and by how much. */
        const val TAG = "rpm.level"
    }
}

/**
 * The arithmetic of [LevelSink], kept apart from Media3 so it can be tested on its own: little-endian PCM
 * read from one buffer, multiplied, held to full scale and written to another buffer of the same length.
 */
internal object Pcm {
    fun levels(encoding: Int): Boolean = encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_24BIT ||
        encoding == C.ENCODING_PCM_32BIT || encoding == C.ENCODING_PCM_FLOAT

    /**
     * Reads every whole frame left in [from] (its position does not move) into [to] at its position, at
     * a gain that starts at [gain] and moves towards [target] by [step] per frame. Returns the gain it
     * ended on. A part frame at the end is copied as it is.
     */
    fun level(from: ByteBuffer, to: ByteBuffer, encoding: Int, channels: Int, gain: Float, target: Float, step: Float): Float {
        val src = from.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val dst = to.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val width = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
        val n = channels.coerceAtLeast(1)
        val frame = width * n
        var g = gain
        while (src.remaining() >= frame) {
            g = when {
                g < target -> minOf(target, g + step)
                g > target -> maxOf(target, g - step)
                else -> g
            }
            when (encoding) {
                C.ENCODING_PCM_16BIT -> repeat(n) { dst.putShort((src.short * g).coerceIn(-32768f, 32767f).toInt().toShort()) }
                C.ENCODING_PCM_24BIT -> repeat(n) {
                    val b0 = src.get().toInt() and 0xFF
                    val b1 = src.get().toInt() and 0xFF
                    val b2 = src.get().toInt()
                    val out = (((b2 shl 16) or (b1 shl 8) or b0) * g).coerceIn(-8388608f, 8388607f).toInt()
                    dst.put(out.toByte())
                    dst.put((out shr 8).toByte())
                    dst.put((out shr 16).toByte())
                }
                C.ENCODING_PCM_32BIT -> repeat(n) { dst.putInt((src.int * g.toDouble()).coerceIn(-2147483648.0, 2147483647.0).toInt()) }
                else -> repeat(n) { dst.putFloat((src.float * g).coerceIn(-1f, 1f)) }
            }
        }
        while (src.hasRemaining()) dst.put(src.get())
        return g
    }
}
