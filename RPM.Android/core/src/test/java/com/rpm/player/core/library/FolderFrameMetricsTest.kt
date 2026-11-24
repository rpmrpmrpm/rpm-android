package com.rpm.player.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderFrameMetricsTest {
    /** A font that measures every letter half its size wide, with ink from 0.8 of the size above the line to 0.2
     * below it, and [bearing] of the size before the first letter's ink. */
    private fun font(bearing: Float = 0f): (String, Float) -> ListFrameMetrics.Ink = { said, size ->
        ListFrameMetrics.Ink(left = bearing * size, width = said.length * size * 0.5f, top = -size * 0.8f, bottom = size * 0.2f)
    }

    /** A short name is set at the one size every folder's name has, centred across the square by its ink, with the
     * middle of the gauge on the name's line. */
    @Test fun aShortNameIsCentredUnderTheFolder() {
        val edge = 400f
        val measure = font(bearing = 0.1f)
        val set = FolderFrameMetrics.lettering("Moods", edge, measure)!!
        assertEquals(edge * FolderFrameMetrics.NAME_CROSS, set.size, 0.001f)
        val ink = measure("Moods", set.size)
        assertEquals(edge / 2f, set.x + ink.left + ink.width / 2f, 0.001f)
        val gauge = measure(ListFrameMetrics.GAUGE, set.size)
        assertEquals(edge * FolderFrameMetrics.BAR_CENTRE, set.baseline + (gauge.top + gauge.bottom) / 2f, 0.001f)
    }

    /** A name too long for the folder is set smaller, until its ink is as wide as the folder, and stays centred. */
    @Test fun aLongNameShrinksToFit() {
        val edge = 400f
        val measure = font()
        val short = FolderFrameMetrics.lettering("Moods", edge, measure)!!
        val long = FolderFrameMetrics.lettering("A folder with a very long name indeed", edge, measure)!!
        assertTrue(long.size < short.size)
        val ink = measure("A folder with a very long name indeed", long.size)
        assertEquals(edge * FolderFrameMetrics.NAME_RUN, ink.width, 0.01f)
        assertEquals(edge / 2f, long.x + ink.width / 2f, 0.01f)
        val gauge = measure(ListFrameMetrics.GAUGE, long.size)
        assertEquals(edge * FolderFrameMetrics.BAR_CENTRE, long.baseline + (gauge.top + gauge.bottom) / 2f, 0.01f)
    }

    /** No name, no square or a font that draws nothing sets nothing. */
    @Test fun nothingToSetIsNull() {
        assertNull(FolderFrameMetrics.lettering("", 400f, font()))
        assertNull(FolderFrameMetrics.lettering("Moods", 0f, font()))
        assertNull(FolderFrameMetrics.lettering("Moods", 400f) { _, _ -> ListFrameMetrics.Ink(0f, 0f, 0f, 0f) })
    }
}
