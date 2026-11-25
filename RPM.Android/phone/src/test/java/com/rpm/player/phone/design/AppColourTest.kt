package com.rpm.player.phone.design

import androidx.compose.ui.graphics.Color
import com.rpm.player.phone.ui.Ground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The stored colour of the app's glass ([AppColour]) and the pages' colours over it ([PageColours]). */
class AppColourTest {
    @Test fun theBadgeFormIsRead() {
        assertEquals(0x7A1FD1L, AppColour.value("#7A1FD1"))
        assertEquals(0x2563EBL, AppColour.value("#2563eb"))
        assertEquals(Color(0xFF0E9F95), AppColour.colour("#0E9F95"))
    }

    /** Nothing, or anything that is not `#RRGGBB`, is no colour: the white glass. */
    @Test fun anythingElseIsNone() {
        for (stored in listOf("", "#", "7A1FD1", "#7A1FD", "#7A1FD12", "#GGGGGG", "#-1FD10")) {
            assertNull(stored, AppColour.value(stored))
            assertNull(stored, AppColour.colour(stored))
        }
    }

    /** The glass is white frost unless the person picks a colour, on every background. */
    @Test fun nothingStoredIsNoColour() {
        for (ground in Ground.entries) assertNull(ground.name, AppColour.glass("", ground))
        assertNull(AppColour.glass("none", Ground.Standard))
    }

    /** The background's colour follows the background, and a colour stays the same on every background. */
    @Test fun theBackgroundsColourFollowsTheBackground() {
        assertEquals(0x2563EBL, AppColour.glass(AppColour.MATCHES_BACKGROUND, Ground.Standard))
        assertEquals(0xD12A4EL, AppColour.glass(AppColour.MATCHES_BACKGROUND, Ground.Pegasus))
        assertEquals(0x8E939BL, AppColour.glass(AppColour.MATCHES_BACKGROUND, Ground.Graphite))
        for (ground in Ground.entries) assertEquals(ground.name, 0x0E9F95L, AppColour.glass("#0E9F95", ground))
    }

    @Test fun aColourIsWrittenAsTheBadgeWritesIt() {
        assertEquals("#2563EB", AppColour.wire(0x2563EB))
        assertEquals("#0E9F95", AppColour.wire(0x0E9F95))
        for (value in AppColour.offered) assertEquals(value, AppColour.value(AppColour.wire(value)))
    }

    private val playlist = Any()
    private val record = Any()
    private val red = Color.Red
    private val blue = Color.Blue

    /** A record opened from a playlist colours the glass with the record; going back gives the playlist's again. */
    @Test fun thePageShownLastWins() {
        val pages = PageColours()
        assertNull(pages.current)
        pages.hold(red, playlist)
        pages.hold(blue, record)
        assertEquals(blue, pages.current)
        pages.release(record)
        assertEquals(red, pages.current)
        pages.release(playlist)
        assertNull(pages.current)
    }

    /** A page whose cover arrives late holds its colour then, and comes last. */
    @Test fun aNewColourIsHeldLast() {
        val pages = PageColours()
        pages.hold(red, playlist)
        pages.hold(blue, record)
        pages.hold(Color.Green, playlist)
        assertEquals(Color.Green, pages.current)
        pages.release(playlist)
        assertEquals(blue, pages.current)
    }

    /** A page with no colour holds none, and lets go of one it held. */
    @Test fun noColourLetsGo() {
        val pages = PageColours()
        pages.hold(red, playlist)
        pages.hold(null, record)
        assertEquals(red, pages.current)
        pages.hold(null, playlist)
        assertNull(pages.current)
        // Letting go twice, as the back key and then the page leaving do, is harmless.
        pages.release(playlist)
        assertNull(pages.current)
    }
}
