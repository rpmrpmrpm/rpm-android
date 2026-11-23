package com.rpm.player.phone.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The iPhone's BackdropTests that the phone's [Ground] answers too: which picture the app stands on. */
class GroundTest {
    @Test fun nothingStoredIsTheAppsOwnGround() {
        assertEquals(Ground.Standard, Ground.standing("", gold = false))
    }

    /** Gold, the supporter's, stays last among the tiles. */
    @Test fun goldStaysLast() {
        assertEquals(Ground.Gold, Ground.entries.last())
    }

    /** The phone's own two are kept and shown under their ids, for every account. */
    @Test fun graphiteAndCocoaAreShownForEveryAccount() {
        assertEquals(Ground.Graphite, Ground.standing("graphite", gold = false))
        assertEquals(Ground.Cocoa, Ground.standing("cocoa", gold = false))
        assertFalse(Ground.Graphite.isSupporters)
        assertFalse(Ground.Cocoa.isSupporters)
    }

    @Test fun everyChoiceHasItsOwnId() {
        assertEquals(Ground.entries.size, Ground.entries.map { it.id }.toSet().size)
    }

    /** Graphite is a flat grey chosen for that grey, so it takes no shade; Cocoa takes the usual one. */
    @Test fun graphiteTakesNoShade() {
        assertEquals(0f, Ground.Graphite.shade)
        assertEquals(Ground.Vinyl.shade, Ground.Cocoa.shade)
        assertTrue(Ground.Aero.shade > Ground.Cocoa.shade)
    }
}
