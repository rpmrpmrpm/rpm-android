package com.rpm.player.core.playback

import androidx.annotation.StringRes
import androidx.media3.common.C
import com.rpm.player.core.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The ten-band equaliser of the desktop (RPM.Core/Audio/Equalizer.cs), ported number for number: a cascade of RBJ
 * peaking biquads per channel, then the pre-amp, applied to interleaved float samples in place. The tests pin it to
 * the desktop's own cases (RPM.Core.Tests/EqualizerTests.cs).
 *
 * The desktop class also has a bass and a treble shelf. Its player always sets them to 0 dB (they are for vinyl and
 * cassette transfers), so they are not ported.
 *
 * Gains can be changed at any time with [setGains]. The new coefficients are used from the next block, and the
 * filter memory is kept, so the music does not restart or click.
 *
 * Not thread-safe: the sink that owns it ([EqSink]) calls it on the playback thread only.
 */
class Equalizer(private val sampleRate: Int, private val channels: Int) {
    init {
        require(sampleRate >= 8000) { "sampleRate $sampleRate" }
        require(channels in 1..MAX_CHANNELS) { "channels $channels" }
    }

    private val stages = BANDS.size

    // The coefficients of each band, shared by the channels.
    private val b0 = DoubleArray(stages)
    private val b1 = DoubleArray(stages)
    private val b2 = DoubleArray(stages)
    private val a1 = DoubleArray(stages)
    private val a2 = DoubleArray(stages)

    // Transposed direct form II state, per band per channel.
    private val z1 = DoubleArray(stages * channels)
    private val z2 = DoubleArray(stages * channels)

    private var preampGain = 1.0

    /** True when every band and the pre-amp are at 0 dB. [process] then changes nothing. */
    var isBypassed = true
        private set

    init {
        setGains(0.0, DoubleArray(BANDS.size).asList())
    }

    /** Sets the pre-amp and each band's gain, all in dB and each held within [MAX_GAIN_DB] either way. */
    fun setGains(preampDb: Double, bandGainsDb: List<Double>) {
        var flat = abs(preampDb) < 0.01
        preampGain = 10.0.pow(preampDb.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB) / 20)
        for (i in BANDS.indices) {
            val gainDb = bandGainsDb.getOrNull(i)?.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB) ?: 0.0
            if (abs(gainDb) >= 0.01) flat = false
            setPeaking(i, BANDS[i].toDouble(), gainDb)
        }
        isBypassed = flat
    }

    /**
     * RBJ audio-EQ-cookbook peaking filter.
     *
     * A band whose centre is near or above half the sample rate is left flat. The desktop always runs at 44.1 or
     * 48 kHz, but a radio station can stream at 22.05 or 32 kHz. There the top bands lie past the highest frequency the
     * stream can carry, and the cookbook's formula would give a filter that is unstable or that never decays.
     */
    private fun setPeaking(band: Int, centreHz: Double, gainDb: Double) {
        if (centreHz >= sampleRate * HIGHEST_CENTRE || gainDb == 0.0) {
            b0[band] = 1.0
            b1[band] = 0.0
            b2[band] = 0.0
            a1[band] = 0.0
            a2[band] = 0.0
            return
        }
        val a = 10.0.pow(gainDb / 40)
        val w0 = 2 * PI * centreHz / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2 * Q)

        val a0 = 1 + alpha / a
        b0[band] = (1 + alpha * a) / a0
        b1[band] = (-2 * cosW) / a0
        b2[band] = (1 - alpha * a) / a0
        a1[band] = (-2 * cosW) / a0
        a2[band] = (1 - alpha / a) / a0
    }

    /** Clears the filter memory, so the end of the last audio does not carry into the next (a seek, a new track). */
    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    /**
     * Filters [frames] interleaved frames of [samples] in place. The result is held to full scale (-1 to 1), as the
     * desktop holds it: a boost the pre-amp does not make room for clips here, and not in the output.
     */
    fun process(samples: FloatArray, frames: Int) {
        if (isBypassed) return
        val count = frames * channels
        require(count <= samples.size) { "frames $frames" }
        for (i in 0 until count) {
            val ch = i % channels
            var x = samples[i].toDouble()
            for (b in 0 until stages) {
                val s = b * channels + ch
                val y = b0[b] * x + z1[s]
                z1[s] = b1[b] * x - a1[b] * y + z2[s]
                z2[s] = b2[b] * x - a2[b] * y
                x = y
            }
            x *= preampGain
            samples[i] = x.coerceIn(-1.0, 1.0).toFloat()
        }
    }

    companion object {
        /** The centre frequencies in Hz: the desktop's ten bands, which are Winamp's. */
        val BANDS = intArrayOf(60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000)

        /** How far a band or the pre-amp may go, either way, in dB. */
        const val MAX_GAIN_DB = 12.0

        /** About one octave per band. */
        private const val Q = 1.0

        /** A band is used only while its centre is under this share of the sample rate (0.5 is the Nyquist limit). */
        private const val HIGHEST_CENTRE = 0.45

        /** 7.1 surround is the most a sink is configured with. */
        const val MAX_CHANNELS = 8

        /** The label under a band's fader: "60", "1K", "16K". The same as the desktop's; numbers, not words. */
        fun bandLabel(hz: Int): String = if (hz >= 1000) "${hz / 1000}K" else hz.toString()
    }
}

/**
 * One named curve: the desktop's presets (EqService.Presets), in its order, with its values. [gains] holds one value
 * per band, in [Equalizer.BANDS] order.
 */
class EqPreset(@StringRes val name: Int, val gains: List<Double>) {
    companion object {
        val ALL: List<EqPreset> = listOf(
            EqPreset(R.string.Eq_Flat, listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
            EqPreset(R.string.Eq_Rock, listOf(5.0, 4.0, 2.0, 0.0, -1.0, 0.0, 2.0, 4.0, 5.0, 5.0)),
            EqPreset(R.string.Eq_Pop, listOf(-1.0, 2.0, 4.0, 5.0, 4.0, 2.0, 0.0, -1.0, -1.0, -2.0)),
            EqPreset(R.string.Eq_Jazz, listOf(4.0, 3.0, 1.0, 2.0, -1.0, -1.0, 0.0, 1.0, 3.0, 4.0)),
            EqPreset(R.string.Eq_Classical, listOf(5.0, 4.0, 3.0, 2.0, -1.0, -1.0, 0.0, 2.0, 3.0, 4.0)),
            EqPreset(R.string.Eq_BassBoost, listOf(7.0, 6.0, 5.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
            EqPreset(R.string.Eq_Treble, listOf(0.0, 0.0, 0.0, 0.0, 0.0, 2.0, 4.0, 6.0, 7.0, 7.0)),
            EqPreset(R.string.Eq_Vocal, listOf(-2.0, -1.0, 1.0, 3.0, 4.0, 4.0, 3.0, 1.0, 0.0, -1.0)),
        )
    }
}

/**
 * The equaliser settings for one set of speakers, as the desktop keeps them (EqProfile): on or off, the pre-amp, and
 * the ten band gains, all in dB. Immutable, so the playback thread can read one while the main thread makes the next.
 *
 * Off is a bypass for comparison, not a reset: the values are kept, and turning it on again brings them back.
 */
data class EqCurve(val on: Boolean, val preamp: Double, val gains: List<Double>) {
    init {
        require(gains.size == Equalizer.BANDS.size) { "gains ${gains.size}" }
    }

    /** The same curve with one band moved, held to the range. */
    fun withBand(index: Int, db: Double): EqCurve =
        if (index !in gains.indices) this else copy(gains = gains.toMutableList().also { it[index] = clamp(db) })

    fun withPreamp(db: Double): EqCurve = copy(preamp = clamp(db))

    /** The preset's bands. The pre-amp and the switch stay as they are, as on the desktop. */
    fun withPreset(preset: EqPreset): EqCurve = copy(gains = List(gains.size) { clamp(preset.gains.getOrElse(it) { 0.0 }) })

    /** Flat, the pre-amp too. The switch stays where it was. */
    fun flattened(): EqCurve = copy(preamp = 0.0, gains = List(gains.size) { 0.0 })

    /** The preset whose bands this curve has, or null. The pre-amp is not compared, as on the desktop. */
    fun preset(): EqPreset? = EqPreset.ALL.firstOrNull { p -> gains.indices.all { abs(gains[it] - p.gains[it]) < 0.001 } }

    /** As kept in the settings: on (1 or 0), the pre-amp, then the bands, separated by `;` and `,`. */
    fun stored(): String = (if (on) "1" else "0") + ";" + preamp + ";" + gains.joinToString(",")

    companion object {
        /** A set of speakers seen for the first time: flat and off, the same as having no equaliser. */
        val OFF = EqCurve(on = false, preamp = 0.0, gains = List(Equalizer.BANDS.size) { 0.0 })

        /** A curve read back from [stored]. Anything that cannot be read is [OFF]. */
        fun parse(text: String?): EqCurve {
            val parts = text?.split(';') ?: return OFF
            if (parts.size != 3) return OFF
            val preamp = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return OFF
            val gains = parts[2].split(',').map { it.toDoubleOrNull()?.takeIf { v -> v.isFinite() } ?: return OFF }
            if (gains.size != Equalizer.BANDS.size) return OFF
            return EqCurve(parts[0] == "1", clamp(preamp), gains.map(::clamp))
        }

        private fun clamp(db: Double): Double = db.coerceIn(-Equalizer.MAX_GAIN_DB, Equalizer.MAX_GAIN_DB)
    }
}

/**
 * The curve the playback service's sink uses ([EqSink]). The app writes it on the main thread whenever the curve or
 * the speakers change. The sink reads it for every buffer, so a change reaches the music that is already playing.
 * The TV app never writes it, so its sound is never changed.
 */
class Equalizing {
    @Volatile var curve: EqCurve = EqCurve.OFF
}

/**
 * The PCM side of [EqSink], kept apart from Media3 so it can be tested on its own: little-endian samples read into
 * floats from -1 to 1, and written back, held to the range of the encoding.
 */
internal object EqPcm {
    fun width(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    /** Reads the whole frames between [from]'s position and limit into [into]. [from]'s position does not move.
     * Returns the number of samples read. */
    fun read(from: ByteBuffer, encoding: Int, channels: Int, into: FloatArray): Int {
        val src = from.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val width = width(encoding)
        val count = src.remaining() / (width * channels) * channels
        for (i in 0 until count) {
            into[i] = when (encoding) {
                C.ENCODING_PCM_16BIT -> src.short / 32768f
                C.ENCODING_PCM_24BIT -> {
                    val b0 = src.get().toInt() and 0xFF
                    val b1 = src.get().toInt() and 0xFF
                    val b2 = src.get().toInt()
                    ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
                }
                C.ENCODING_PCM_32BIT -> (src.int / 2147483648.0).toFloat()
                else -> src.float
            }
        }
        return count
    }

    /** Writes the first [count] of [samples] to [to] at its position, in the encoding, then copies whatever is left
     * of [from] after those samples unchanged: a part frame at the end. */
    fun write(samples: FloatArray, count: Int, encoding: Int, from: ByteBuffer, to: ByteBuffer) {
        val dst = to.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val v = samples[i]
            when (encoding) {
                C.ENCODING_PCM_16BIT -> dst.putShort((v * 32768f).coerceIn(-32768f, 32767f).toInt().toShort())
                C.ENCODING_PCM_24BIT -> {
                    val out = (v * 8388608f).coerceIn(-8388608f, 8388607f).toInt()
                    dst.put(out.toByte())
                    dst.put((out shr 8).toByte())
                    dst.put((out shr 16).toByte())
                }
                C.ENCODING_PCM_32BIT -> dst.putInt((v * 2147483648.0).coerceIn(-2147483648.0, 2147483647.0).toInt())
                else -> dst.putFloat(v.coerceIn(-1f, 1f))
            }
        }
        val rest = from.duplicate()
        rest.position(rest.position() + count * width(encoding))
        while (rest.hasRemaining()) dst.put(rest.get())
    }
}
