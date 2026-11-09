package com.rpm.player.core.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** [GroundLead]: the vivid colour first, the common one without it, and never brighter than white text allows. */
class GroundLeadTest {

    @Test fun theVividColourLeadsOverTheCommonOne() {
        val red = Rgb.of(150, 30, 40)
        assertEquals(red, GroundLead.of(glow = red, dominant = Rgb.of(120, 120, 120)))
    }

    @Test fun aPhotographWithNoVividColourIsLedByItsCommonOne() {
        val grey = Rgb.of(60, 60, 60)
        assertEquals(grey, GroundLead.of(glow = null, dominant = grey))
    }

    @Test fun aPhotographWithNoColourLeadsNothing() {
        assertNull(GroundLead.of(glow = null, dominant = null))
    }

    @Test fun aBrightColourIsDarkenedToTheCapAndKeepsItsHue() {
        val yellow = Rgb.of(250, 220, 60)
        val led = GroundLead.of(glow = yellow, dominant = null)!!
        assertEquals(GroundLead.MOST_LUMINANCE, luminance(led), 0.002f)
        // The same direction in linear light: the channels keep their ratios.
        assertEquals(linear(yellow.g) / linear(yellow.r), linear(led.g) / linear(led.r), 0.01f)
        assertTrue(led.r < yellow.r)
    }

    private fun linear(v: Float) = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    private fun luminance(c: Rgb) = 0.2126f * linear(c.r) + 0.7152f * linear(c.g) + 0.0722f * linear(c.b)
}
