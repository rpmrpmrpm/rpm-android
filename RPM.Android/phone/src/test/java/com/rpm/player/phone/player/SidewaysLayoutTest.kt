package com.rpm.player.phone.player

import com.rpm.player.phone.player.SidewaysLayout.Companion.CABIN_ASCENT
import com.rpm.player.phone.player.SidewaysLayout.Companion.CABIN_INK
import com.rpm.player.phone.player.SidewaysLayout.Companion.CABIN_LINE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The modern player on its side against the drawing it follows: every place in the table of measurements, as shares
 * of the full screen, on an iPhone on its side (2532 by 1170 pixels at 3 to the point) and on a phone of 2340 by 1080
 * pixels at 2.8125 to the dp.
 */
class SidewaysLayoutTest {
    private val iphone = SidewaysLayout.of(844f, 390f, 0f, 0f, 0f, 0f)
    private val phone = SidewaysLayout.of(832f, 384f, 0f, 0f, 0f, 0f)

    @Test fun theIphoneMatchesTheDrawing() = matchesTheDrawing(iphone)

    @Test fun theWiderPhoneMatchesTheDrawing() = matchesTheDrawing(phone)

    private fun matchesTheDrawing(laid: SidewaysLayout) {
        val w = laid.width
        val h = laid.height
        val s = laid.scale
        fun across(expected: Float, actual: Float, what: String, slack: Float = 0.002f) = assertEquals(what, expected, actual / w, slack)
        fun down(expected: Float, actual: Float, what: String, slack: Float = 0.002f) = assertEquals(what, expected, actual / h, slack)

        // The cover: two thirds of the height, square.
        across(0.079f, laid.cover.x, "cover's left")
        down(0.170f, laid.cover.y, "cover's top")
        down(0.837f, laid.cover.bottom, "cover's foot")
        assertEquals(laid.cover.width, laid.cover.height, 0.001f)
        across(0.233f, laid.cover.centreX, "cover's middle")

        // The spectrum runs from near the left edge, past the cover, to short of the middle, on the cover's rows.
        across(0.004f, laid.spectrum.x, "spectrum's left")
        across(0.4625f, laid.spectrum.right, "spectrum's right")
        assertEquals(laid.cover.y, laid.spectrum.y, 0.001f)
        assertEquals(laid.cover.height, laid.spectrum.height, 0.001f)

        // The column's two edges.
        across(0.557f, laid.column.x, "column's left")
        across(0.961f, laid.column.right, "column's right")
        assertEquals(laid.column.x, laid.words.x, 0.001f)
        assertEquals(laid.column.x, laid.needle.x, 0.001f)
        assertEquals(laid.column.right, laid.needle.right, 0.001f)

        // The title, its letters centred in its line box, and the artist under it.
        val title = 22f * s
        val titleBaseline = laid.title.y + (laid.title.height - title * CABIN_LINE) / 2 + title * CABIN_ASCENT
        down(0.388f, titleBaseline - title * CABIN_INK, "title's top")
        down(0.431f, titleBaseline, "title's foot")
        across(0.557f, laid.title.x, "title's left")
        val artist = 15f * s
        val artistBaseline = laid.artist.y + (laid.artist.height - artist * CABIN_LINE) / 2 + artist * CABIN_ASCENT
        down(0.461f, artistBaseline - artist * CABIN_INK, "artist's top")
        down(0.498f, artistBaseline + artist * 0.242f, "artist's foot")

        // The heart's glyph ends on the column's right edge, level with the title.
        across(0.961f, laid.heart.centreX + 7.5f * s, "heart's right", 0.0015f)
        down(0.427f, laid.heart.centreY, "heart's middle", 0.003f)

        // The seek bar's line, its knob, and the clocks under it.
        down(0.555f, laid.seek.centreY, "seek line")
        down(0.5376f, laid.seek.centreY - 7f * s, "knob's top")
        down(0.5726f, laid.seek.centreY + 7f * s, "knob's foot", 0.0025f)
        across(0.557f, laid.seek.x, "seek bar's left")
        across(0.961f, laid.seek.right, "seek bar's right")
        val clocks = 11f * s
        down(0.598f, laid.times.y + clocks * (CABIN_ASCENT - 0.7145f), "clocks' top")
        down(0.618f, laid.times.y + clocks * CABIN_ASCENT, "clocks' foot", 0.0015f)

        // The transport: previous and next glyphs at their edges, play in the middle.
        down(0.743f, laid.transport.centreY, "transport's line")
        across(0.6445f, laid.transport.x + (28f - 11f) * s, "previous glyph's left")
        across(0.874f, laid.transport.right - (28f - 11f) * s, "next glyph's right")
        across(0.759f, laid.transport.centreX, "play's middle")
        down(0.057f, 26f * 0.875f * s, "pause glyph's height")

        // The capsule of keys.
        across(0.757f, laid.capsule.centreX, "capsule's middle")
        down(0.903f, laid.capsule.centreY, "capsule's line")
        across(0.145f, laid.capsule.width, "capsule's width", 0.004f)
        down(0.097f, laid.capsule.height, "capsule's height", 0.006f)
        assertEquals(laid.capsule.bottom, laid.column.bottom, 0.001f)

        // The speaker key in the corner, and the visualiser key 10 dp to its left on its line.
        across(0.959f, laid.cast.centreX, "speaker key's middle")
        down(0.084f, laid.cast.centreY, "speaker key's line")
        down(0.103f, laid.cast.height, "speaker key's size")
        assertEquals(laid.cast.centreY, laid.visual.centreY, 0.001f)
        assertEquals(10f * s, laid.cast.x - laid.visual.right, 0.001f)
        assertEquals(laid.cast.width, laid.visual.width, 0.001f)
    }

    /** An iPhone's notch and home bar: what reached into them moves in by the difference, and nothing else moves. */
    @Test fun nothingIsUnderTheNotch() {
        val laid = SidewaysLayout.of(844f, 390f, 47f, 0f, 47f, 21f)
        val safe = laid.safe
        for (frame in listOf(laid.cover, laid.spectrum, laid.lyrics, laid.column, laid.words, laid.needle, laid.transport, laid.capsule, laid.cast, laid.visual)) {
            assertTrue("$frame", frame.x >= safe.x - 0.001f && frame.right <= safe.right + 0.001f)
            assertTrue("$frame", frame.y >= safe.y - 0.001f && frame.bottom <= safe.bottom + 0.001f)
        }
        // The spectrum is cut at the notch; the cover stays where it was.
        assertEquals(47f, laid.spectrum.x, 0.001f)
        assertEquals(iphone.cover, laid.cover)
        // The column's right edge, the speaker key and the capsule move in by the difference only.
        assertEquals(797f, laid.column.right, 0.001f)
        assertEquals(iphone.column.x, laid.column.x, 0.001f)
        assertEquals(797f, laid.cast.right, 0.001f)
        assertEquals(369f, laid.capsule.bottom, 0.001f)
        assertEquals(iphone.capsule.centreX, laid.capsule.centreX, 0.001f)
        assertEquals(iphone.transport, laid.transport)
    }

    /** A phone much less wide than the drawing keeps the rules, with the cover held to its share of the width. */
    @Test fun aSquarerScreenHoldsTheCoverToItsWidth() {
        val laid = SidewaysLayout.of(600f, 400f, 0f, 0f, 0f, 0f)
        assertEquals(0.42f * 600f, laid.cover.width, 0.001f)
        assertEquals(0.233f * 600f, laid.cover.centreX, 0.001f)
        assertTrue(laid.cover.right < laid.column.x)
    }
}
