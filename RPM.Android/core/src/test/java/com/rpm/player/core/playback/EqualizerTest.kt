package com.rpm.player.core.playback

import androidx.media3.common.C
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The equaliser: the filter pinned to the desktop's own cases (RPM.Core.Tests/EqualizerTests.cs), the presets and the
 * stored curve, and the PCM the sink reads and writes.
 */
class EqualizerTest {
    private val rate = 44100

    private fun tone(hz: Double, frames: Int, channels: Int, amplitude: Double = 0.5, sampleRate: Int = rate): FloatArray {
        val buf = FloatArray(frames * channels)
        for (i in 0 until frames) {
            val v = (amplitude * sin(2 * PI * hz * i / sampleRate)).toFloat()
            for (c in 0 until channels) buf[i * channels + c] = v
        }
        return buf
    }

    /** RMS of the back half, so the filter's start does not change the reading. */
    private fun settledRms(buf: FloatArray): Double {
        val from = buf.size / 2
        var sum = 0.0
        for (i in from until buf.size) sum += buf[i].toDouble() * buf[i]
        return sqrt(sum / (buf.size - from))
    }

    private fun rms(buf: FloatArray): Double = sqrt(buf.sumOf { it.toDouble() * it } / buf.size)

    private fun gains(band: Int, db: Double): List<Double> = List(Equalizer.BANDS.size) { if (it == band) db else 0.0 }

    private fun levelAt(hz: Double, band: Int, db: Double, sampleRate: Int = rate): Double {
        val eq = Equalizer(sampleRate, 1)
        eq.setGains(0.0, gains(band, db))
        val buf = tone(hz, sampleRate / 2, 1, sampleRate = sampleRate)
        val before = settledRms(buf)
        eq.process(buf, buf.size)
        return settledRms(buf) / before
    }

    // ── bypass ──

    @Test
    fun A_flat_equaliser_is_bypassed_and_leaves_the_signal_untouched() {
        val eq = Equalizer(rate, 2)
        assertTrue(eq.isBypassed)
        val buf = tone(1000.0, 512, 2)
        val original = buf.copyOf()
        eq.process(buf, 512)
        assertArrayEquals(original, buf, 0f)
    }

    @Test
    fun Any_band_or_the_preamp_alone_leaves_bypass() {
        val eq = Equalizer(rate, 2)
        eq.setGains(0.0, gains(4, 6.0))
        assertFalse(eq.isBypassed)
        eq.setGains(3.0, gains(-1, 0.0))
        assertFalse(eq.isBypassed)
        eq.setGains(0.0, gains(-1, 0.0))
        assertTrue(eq.isBypassed)
    }

    // ── band gain ──

    @Test
    fun Boosting_a_band_makes_a_tone_at_that_frequency_louder() {
        assertTrue(levelAt(1000.0, band = 4, db = 12.0) > 1.5)
    }

    @Test
    fun Cutting_a_band_makes_a_tone_at_that_frequency_quieter() {
        assertTrue(levelAt(1000.0, band = 4, db = -12.0) < 0.7)
    }

    @Test
    fun Boosting_one_band_barely_touches_a_distant_frequency() {
        assertEquals(1.0, levelAt(10000.0, band = 0, db = 12.0), 0.15)
    }

    @Test
    fun Every_band_responds_at_its_own_centre_frequency() {
        for ((band, hz) in Equalizer.BANDS.withIndex()) {
            val ratio = levelAt(hz.toDouble(), band, 12.0)
            assertTrue("band $hz Hz: $ratio", ratio > 1.3)
        }
    }

    @Test
    fun At_the_centre_a_band_gives_its_own_gain() {
        // +6 dB is twice the amplitude.
        assertEquals(2.0, levelAt(1000.0, band = 4, db = 6.0), 0.05)
    }

    // ── pre-amp and clipping ──

    @Test
    fun A_negative_preamp_attenuates_the_whole_signal() {
        val eq = Equalizer(rate, 2)
        eq.setGains(-6.0, gains(-1, 0.0))
        val buf = tone(440.0, 4096, 2)
        val before = rms(buf)
        eq.process(buf, 4096)
        assertEquals(0.5, rms(buf) / before, 0.1)
    }

    @Test
    fun The_output_is_held_to_full_scale() {
        val eq = Equalizer(rate, 2)
        eq.setGains(12.0, List(Equalizer.BANDS.size) { 12.0 })
        val buf = tone(1000.0, 4096, 2, amplitude = 0.9)
        eq.process(buf, 4096)
        assertTrue(buf.all { it in -1f..1f })
    }

    @Test
    fun Gains_are_held_to_twelve_dB() {
        val sane = levelAt(1000.0, band = 4, db = 12.0)
        val wild = levelAt(1000.0, band = 4, db = 40.0)
        assertEquals(sane, wild, 1e-3)
    }

    // ── state ──

    @Test
    fun Reset_clears_the_filter_memory_so_the_same_input_gives_the_same_output() {
        val eq = Equalizer(rate, 2)
        eq.setGains(0.0, gains(0, 12.0))
        val first = tone(60.0, 2048, 2)
        eq.process(first, 2048)
        eq.reset()
        val second = tone(60.0, 2048, 2)
        eq.process(second, 2048)
        assertArrayEquals(first, second, 0f)
    }

    @Test
    fun Stereo_is_filtered_per_channel_and_identical_channels_stay_identical() {
        val eq = Equalizer(rate, 2)
        eq.setGains(2.0, EqPreset.ALL[1].gains)
        val buf = tone(300.0, 2048, 2)
        eq.process(buf, 2048)
        for (i in 0 until 2048) assertEquals(buf[i * 2], buf[i * 2 + 1], 1e-6f)
    }

    @Test
    fun Six_channels_are_filtered_as_six() {
        val eq = Equalizer(48000, 6)
        eq.setGains(0.0, gains(5, 12.0))
        val buf = tone(3000.0, 4800, 6, sampleRate = 48000)
        val before = settledRms(buf)
        eq.process(buf, 4800)
        assertTrue(settledRms(buf) / before > 1.5)
        for (i in 0 until 4800) assertEquals(buf[i * 6], buf[i * 6 + 5], 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun A_sample_rate_too_low_is_refused() {
        Equalizer(4000, 2)
    }

    // ── streams below 44.1 kHz ──

    @Test
    fun Bands_past_what_a_slow_stream_can_carry_are_left_flat_and_stay_stable() {
        // At 22.05 kHz the 12, 14 and 16 kHz bands lie past the highest frequency (11.025 kHz). Boosted, the cookbook
        // filter would grow without end; left flat, the signal stays as it was.
        val eq = Equalizer(22050, 1)
        eq.setGains(0.0, listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 12.0, 12.0, 12.0))
        assertFalse(eq.isBypassed)
        val buf = tone(5000.0, 22050, 1, sampleRate = 22050)
        val original = buf.copyOf()
        eq.process(buf, buf.size)
        assertTrue(buf.all { it.isFinite() })
        assertArrayEquals(original, buf, 1e-6f)
    }

    // ── presets ──

    @Test
    fun The_presets_are_the_desktops_in_its_order() {
        assertEquals(8, EqPreset.ALL.size)
        assertTrue(EqPreset.ALL.all { it.gains.size == Equalizer.BANDS.size })
        assertEquals(List(10) { 0.0 }, EqPreset.ALL[0].gains)
        assertEquals(listOf(7.0, 6.0, 5.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0), EqPreset.ALL[5].gains)
    }

    @Test
    fun A_preset_sets_the_bands_and_leaves_the_switch_and_the_preamp() {
        val curve = EqCurve.OFF.withPreamp(-4.0).withPreset(EqPreset.ALL[5])
        assertFalse(curve.on)
        assertEquals(-4.0, curve.preamp, 0.0)
        assertSame(EqPreset.ALL[5], curve.preset())
        assertNull(curve.withBand(0, 3.0).preset())
    }

    @Test
    fun Flat_clears_the_bands_and_the_preamp_and_keeps_the_switch() {
        val curve = EqCurve.OFF.copy(on = true).withPreamp(-3.0).withPreset(EqPreset.ALL[1]).flattened()
        assertTrue(curve.on)
        assertEquals(EqCurve.OFF.copy(on = true), curve)
        assertSame(EqPreset.ALL[0], curve.preset())
    }

    @Test
    fun Moves_are_held_to_the_range() {
        val curve = EqCurve.OFF.withBand(2, 20.0).withPreamp(-30.0).withBand(99, 5.0)
        assertEquals(12.0, curve.gains[2], 0.0)
        assertEquals(-12.0, curve.preamp, 0.0)
    }

    // ── room for the bands ──

    @Test
    fun A_flat_curve_has_no_peak() {
        assertEquals(0.0, Equalizer.peakGain(emptyList()), 0.0)
        assertEquals(0.0, Equalizer.peakGain(EqPreset.ALL[0].gains), 0.001)
    }

    @Test
    fun One_raised_band_peaks_at_its_own_gain() {
        assertEquals(6.0, Equalizer.peakGain(gains(4, 6.0)), 0.05)
        assertEquals(6.0, Equalizer.peakGain(gains(0, 6.0)), 0.05)
    }

    @Test
    fun Raised_neighbours_add_up() {
        // The bands overlap, so ten bands at +6 dB peak higher than one: about +15 dB near 14 kHz.
        val peak = Equalizer.peakGain(List(10) { 6.0 })
        assertTrue(peak >= 6.0)
        assertEquals(15.15, peak, 0.1)
    }

    @Test
    fun Cuts_alone_never_peak_above_zero() {
        assertTrue(Equalizer.peakGain(listOf(-3.0, -6.0, 0.0, 0.0, -2.0, 0.0, 0.0, 0.0, 0.0, -12.0)) <= 0.0)
        assertTrue(Equalizer.peakGain(List(10) { -12.0 }) <= 0.0)
    }

    @Test
    fun Turning_the_equaliser_on_lowers_the_preamp_to_make_room() {
        val before = EqCurve.OFF.withPreset(EqPreset.ALL[5])
        val after = before.copy(on = true).guarded(before)
        // Bass boost peaks at about +9.4 dB.
        assertEquals(-10.0, after.preamp, 0.0)
        assertFalse(after.clips)
    }

    @Test
    fun A_band_raised_while_on_lowers_the_preamp() {
        val before = EqCurve.OFF.copy(on = true)
        val after = before.withBand(4, 6.0).guarded(before)
        assertEquals(-6.0, after.preamp, 0.0)
        assertFalse(after.clips)
    }

    @Test
    fun A_preset_chosen_while_on_lowers_the_preamp() {
        val before = EqCurve.OFF.copy(on = true)
        val after = before.withPreset(EqPreset.ALL[2]).guarded(before)
        assertTrue(after.preamp < 0.0)
        assertFalse(after.clips)
    }

    @Test
    fun The_preamp_is_never_raised() {
        val before = EqCurve.OFF.copy(on = true).withPreamp(-12.0)
        assertEquals(-12.0, before.withBand(4, 3.0).guarded(before).preamp, 0.0)
        assertEquals(-12.0, before.withRoom().preamp, 0.0)
    }

    @Test
    fun A_preamp_raised_by_the_listener_is_kept_and_clips() {
        val safe = EqCurve.OFF.copy(on = true).withBand(4, 6.0).withPreamp(-6.0)
        assertFalse(safe.clips)
        val loud = safe.withPreamp(0.0).guarded(safe)
        assertEquals(0.0, loud.preamp, 0.0)
        assertTrue(loud.clips)
    }

    @Test
    fun Flat_and_cuts_need_no_room() {
        val before = EqCurve.OFF.copy(on = true)
        val cut = before.withBand(1, -6.0).guarded(before)
        assertEquals(0.0, cut.preamp, 0.0)
        assertFalse(cut.clips)
        val flat = before.withPreset(EqPreset.ALL[1]).withPreamp(-12.0).flattened()
        assertEquals(0.0, flat.guarded(before).preamp, 0.0)
        assertFalse(flat.clips)
    }

    @Test
    fun A_curve_that_is_off_is_left_alone_and_does_not_warn() {
        val before = EqCurve.OFF
        val after = before.withBand(4, 6.0).guarded(before)
        assertEquals(0.0, after.preamp, 0.0)
        assertFalse(after.clips)
        assertEquals(after, after.withRoom())
    }

    @Test
    fun A_curve_that_needs_more_than_twelve_decibels_still_clips() {
        // Treble peaks at about +16.5 dB, past what the pre-amp can take away.
        val curve = EqCurve.OFF.withPreset(EqPreset.ALL[6]).copy(on = true).withRoom()
        assertEquals(-12.0, curve.preamp, 0.0)
        assertTrue(curve.clips)
    }

    // ── stored ──

    @Test
    fun A_curve_reads_back_as_it_was_stored() {
        val curve = EqCurve(true, -3.5, listOf(5.0, 4.0, 2.0, 0.0, -1.0, 0.0, 2.0, 4.0, 5.0, 5.0))
        assertEquals(curve, EqCurve.parse(curve.stored()))
        assertEquals(EqCurve.OFF, EqCurve.parse(EqCurve.OFF.stored()))
    }

    @Test
    fun Anything_unreadable_is_off() {
        assertEquals(EqCurve.OFF, EqCurve.parse(null))
        assertEquals(EqCurve.OFF, EqCurve.parse(""))
        assertEquals(EqCurve.OFF, EqCurve.parse("1;x;0,0,0,0,0,0,0,0,0,0"))
        assertEquals(EqCurve.OFF, EqCurve.parse("1;0;0,0,0"))
        assertEquals(EqCurve.OFF, EqCurve.parse("1;NaN;0,0,0,0,0,0,0,0,0,0"))
    }

    @Test
    fun A_stored_value_out_of_range_is_held_to_it() {
        val curve = EqCurve.parse("1;40;99,0,0,0,0,0,0,0,0,-99")
        assertEquals(12.0, curve.preamp, 0.0)
        assertEquals(12.0, curve.gains[0], 0.0)
        assertEquals(-12.0, curve.gains[9], 0.0)
    }

    // ── PCM ──

    private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun Sixteen_bit_samples_go_through_unchanged_when_nothing_is_done_to_them() {
        val from = le(10).putShort(0).putShort(16384).putShort(-32768).putShort(32767).put(7).put(9)
        from.flip()
        val samples = FloatArray(8)
        val count = EqPcm.read(from, C.ENCODING_PCM_16BIT, 2, samples)
        assertEquals(4, count)
        assertEquals(0.5f, samples[1], 0f)
        assertEquals(-1f, samples[2], 0f)
        val to = le(10)
        EqPcm.write(samples, count, C.ENCODING_PCM_16BIT, from, to)
        assertArrayEquals(from.array(), to.array())
        // The part frame at the end is copied as it was.
        assertEquals(7.toByte(), to.get(8))
    }

    @Test
    fun Written_samples_are_held_to_the_encoding() {
        val from = le(4)
        val to = le(4)
        EqPcm.write(floatArrayOf(2f, -2f), 2, C.ENCODING_PCM_16BIT, from, to)
        assertEquals(32767.toShort(), to.getShort(0))
        assertEquals((-32768).toShort(), to.getShort(2))
    }

    @Test
    fun Twenty_four_bit_and_float_samples_read_back_as_they_were() {
        val from24 = le(6).put(0).put(0).put(0x40).put(0).put(0).put(0xC0.toByte())
        from24.flip()
        val samples = FloatArray(2)
        assertEquals(2, EqPcm.read(from24, C.ENCODING_PCM_24BIT, 1, samples))
        assertEquals(0.5f, samples[0], 0f)
        assertEquals(-0.5f, samples[1], 0f)
        val to24 = le(6)
        EqPcm.write(samples, 2, C.ENCODING_PCM_24BIT, from24, to24)
        assertArrayEquals(from24.array(), to24.array())

        val fromF = le(8).putFloat(0.25f).putFloat(-0.75f)
        fromF.flip()
        assertEquals(2, EqPcm.read(fromF, C.ENCODING_PCM_FLOAT, 2, samples))
        assertEquals(-0.75f, samples[1], 0f)
    }
}
