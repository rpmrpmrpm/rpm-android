package com.rpm.player.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The copyright and label lines under an album's songs. The iOS app checks the same (AlbumCreditsTests.swift). */
class AlbumCreditsTest {

    @Test fun bothTagsGiveTwoLinesCopyrightFirst() {
        val credits = AlbumCredits(copyright = "℗ 2012 Downtown Records", label = "Atlantic")
        assertEquals(listOf("℗ 2012 Downtown Records", "Atlantic"), credits.lines)
    }

    @Test fun aLabelTheCopyrightAlreadyNamesIsNotRepeated() {
        val credits = AlbumCredits(copyright = "1998 Warp Records Limited", label = "warp records")
        assertEquals(listOf("© 1998 Warp Records Limited"), credits.lines)
    }

    @Test fun aLabelIsMatchedWithoutItsAccents() {
        val credits = AlbumCredits(copyright = "© 2001 Disques Vogue", label = "Disqués Vogue")
        assertEquals(listOf("© 2001 Disques Vogue"), credits.lines)
    }

    @Test fun aCopyrightWithoutAMarkGetsOne() {
        assertEquals(listOf("© 1990 RCA"), AlbumCredits(copyright = "1990 RCA", label = null).lines)
        assertEquals(listOf("(P) 1990 RCA"), AlbumCredits(copyright = "(P) 1990 RCA", label = null).lines)
        assertEquals(listOf("Copyright 1990 RCA"), AlbumCredits(copyright = "Copyright 1990 RCA", label = null).lines)
        assertEquals(listOf("© 1990 RCA"), AlbumCredits(copyright = "© 1990 RCA", label = null).lines)
    }

    @Test fun aLabelAloneIsOneLine() {
        assertEquals(listOf("Verve"), AlbumCredits(copyright = null, label = "Verve").lines)
    }

    @Test fun blankTagsAreNothing() {
        val credits = AlbumCredits(copyright = "  ", label = "\n")
        assertTrue(credits.lines.isEmpty())
        assertTrue(credits.isEmpty)
        assertEquals(AlbumCredits(copyright = null, label = null), credits)
    }
}
