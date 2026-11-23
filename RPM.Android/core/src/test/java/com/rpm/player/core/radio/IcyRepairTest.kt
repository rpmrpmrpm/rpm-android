package com.rpm.player.core.radio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Announcements a station garbled by turning them into UTF-8 twice are read back as they were meant. */
class IcyRepairTest {
    @Test
    fun doubleEncodedTextIsRepaired() {
        assertEquals("TIËSTO & AVA MAX - THE MOTTO", Icy.repaired("TIÃ‹STO & AVA MAX - THE MOTTO"))
        assertEquals("Beyoncé - Halo", Icy.repaired("BeyoncÃ© - Halo"))
        assertEquals(Icy.Said.Song("TIËSTO & AVA MAX", "THE MOTTO"), Icy.said("TIÃ‹STO & AVA MAX - THE MOTTO", "Radio 538"))
    }

    @Test
    fun correctTextIsLeftAlone() {
        assertEquals("Beyoncé - Halo", Icy.repaired("Beyoncé - Halo"))
        assertEquals("Ã la carte", Icy.repaired("Ã la carte"))
        assertEquals("Sigur Rós - Hoppípolla", Icy.repaired("Sigur Rós - Hoppípolla"))
        assertEquals("", Icy.repaired(""))
    }
}
