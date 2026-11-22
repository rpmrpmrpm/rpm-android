package com.rpm.player.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumTitleTest {
    @Test fun anEditionAtTheEndGoesOnItsOwnLine() {
        val t = AlbumTitle.split("AM I THE DRAMA? (Bonus Edition)")
        assertEquals("AM I THE DRAMA?", t.name)
        assertEquals(listOf("Bonus Edition"), t.editions)
        assertEquals("AM I THE DRAMA? (Bonus Edition)", t.full)
    }

    @Test fun twoEditionsComeOffInReadingOrder() {
        val t = AlbumTitle.split("Rumours (Deluxe) [2004 Remaster]")
        assertEquals("Rumours", t.name)
        assertEquals(listOf("Deluxe", "2004 Remaster"), t.editions)
    }

    @Test fun aNestedGroupComesOffWhole() {
        val t = AlbumTitle.split("Album (Deluxe (2019))")
        assertEquals("Album", t.name)
        assertEquals(listOf("Deluxe (2019)"), t.editions)
    }

    @Test fun bracketsThatAreTheNameStay() {
        assertEquals("The Guide (Wommat)", AlbumTitle.split("The Guide (Wommat)").name)
        assertEquals("(What's the Story) Morning Glory?", AlbumTitle.split("(What's the Story) Morning Glory?").name)
        assertEquals("( )", AlbumTitle.split("( )").name)
        assertEquals("Album (Deluxe", AlbumTitle.split("Album (Deluxe").name)
        assertTrue(AlbumTitle.split("Short N' Sweet").editions.isEmpty())
    }

    @Test fun aCreditIsNeverAnEdition() {
        assertFalse(AlbumTitle.isAside("feat. The Mix Masters"))
        assertFalse(AlbumTitle.isAside("with Radio Slave"))
        assertFalse(AlbumTitle.isAside("Feathers"))
        assertTrue(AlbumTitle.isAside("Taylor's Version"))
        assertTrue(AlbumTitle.isAside("12\" Mix"))
    }
}
