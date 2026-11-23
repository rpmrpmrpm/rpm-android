package com.rpm.player.phone.player

import com.rpm.player.phone.stage.StageLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The player on its side as it is kept: the style ([LandscapeStyle]) and what is drawn behind it ([LandscapeVisual]). */
class LandscapeSettingTest {
    @Test fun nothingKeptIsTheStage() {
        assertEquals(LandscapeStyle.Classic, LandscapeStyle.read(null))
        assertEquals(LandscapeStyle.Classic, LandscapeStyle.read("sideways"))
    }

    @Test fun eachStyleIsReadBack() {
        for (style in LandscapeStyle.entries) assertEquals(style, LandscapeStyle.read(style.stored))
        assertEquals("classic", LandscapeStyle.Classic.stored)
        assertEquals("modern", LandscapeStyle.Modern.stored)
    }

    @Test fun theRoomIsTheFirstChoice() {
        assertNull(LandscapeVisual.read(null))
        assertNull(LandscapeVisual.read(LandscapeVisual.ROOM))
        assertNull(LandscapeVisual.read("not a look"))
        assertEquals(LandscapeVisual.ROOM, LandscapeVisual.stored(null))
    }

    /** The cover is already on the screen, so the stage's artwork is not offered, and not read back. */
    @Test fun theArtworkIsLeftOut() {
        assertFalse(StageLook.Artwork in LandscapeVisual.offered)
        assertNull(LandscapeVisual.read(StageLook.Artwork.stored.toString()))
    }

    @Test fun eachVisualiserIsReadBack() {
        for (look in LandscapeVisual.offered) assertEquals(look, LandscapeVisual.read(LandscapeVisual.stored(look)))
    }
}
