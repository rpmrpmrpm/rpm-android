package com.rpm.player.core.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The choice of sheet for an explicit record. LRCLIB often holds a clean sheet and an explicit sheet for one song, and
 * the record marked explicit must get the explicit one. The same cases are tested on the desktop
 * (`RPM.Core.Tests/ExplicitLyricsTests.cs`) and in the iPhone app (RPMKit `ExplicitLyricsTests.swift`).
 */
class ExplicitLyricsTest {

    private val masked = LyricSheet.parse("[00:01.00]You think you can f*** with me\n[00:04.00]Get the **** out of my way")
    private val full = LyricSheet.parse("[00:01.00]You think you can fuck with me\n[00:04.00]Get the fuck out of my way")
    private val dropped = LyricSheet.parse("[00:01.00]You think you can mess with me\n[00:04.00]Get out of my way")
    private val plainA = LyricSheet.parse("[00:01.00]Hello there\n[00:04.00]Nice to meet you")
    private val plainB = LyricSheet.parse("[00:01.10]Hello there\n[00:04.10]Nice to meet you")

    private fun score(lrc: String) = ExplicitLyrics.score(LyricSheet.parse(lrc))

    @Test
    fun aMaskedWordCostsMoreThanAnExplicitWordEarns() {
        assertEquals(-2 * ExplicitLyrics.MASK_PENALTY, ExplicitLyrics.score(masked))
        assertEquals(2, ExplicitLyrics.score(full))
        assertEquals(0, ExplicitLyrics.score(dropped))
    }

    @Test
    fun everyCommonMaskIsSeen() {
        assertEquals(-ExplicitLyrics.MASK_PENALTY, score("[00:01.00]What the sh*t"))
        assertEquals(-ExplicitLyrics.MASK_PENALTY, score("[00:01.00]What the f#ck"))
        assertEquals(-ExplicitLyrics.MASK_PENALTY, score("[00:01.00]What the ###"))
        assertEquals(-ExplicitLyrics.MASK_PENALTY, score("[00:01.00]What the [Censored]"))
    }

    @Test
    fun aNumberSignBeforeADigitIsNotAMask() {
        assertEquals(0, score("[00:01.00]Straight in at #1"))
    }

    @Test
    fun punctuationAndCapitalsDoNotHideAnExplicitWord() {
        assertEquals(2, score("[00:01.00]\"Fuckin',\" she said. SHIT!"))
    }

    @Test
    fun theFullExplicitSheetBeatsTheMaskedCleanSheet() {
        // The exact lookup returned the clean sheet. The search has both.
        assertEquals(full, ExplicitLyrics.pick(masked, listOf(masked, full)))
    }

    @Test
    fun theFullExplicitSheetBeatsACleanSheetThatDropsTheWords() {
        assertEquals(full, ExplicitLyrics.pick(dropped, listOf(full)))
    }

    @Test
    fun aTieKeepsTheExactAnswer() {
        assertEquals(plainB, ExplicitLyrics.pick(plainB, listOf(plainA, plainB)))
    }

    @Test
    fun aTieWithoutAnExactAnswerKeepsLrclibsOrder() {
        assertEquals(plainA, ExplicitLyrics.pick(LyricSheet.EMPTY, listOf(plainA, plainB)))
    }

    @Test
    fun noCandidatesIsNoSheet() {
        assertFalse(ExplicitLyrics.pick(LyricSheet.EMPTY, emptyList()).isSynced)
        assertFalse(ExplicitLyrics.pick(LyricSheet.EMPTY, listOf(LyricSheet.parse("words without times"))).isSynced)
    }
}
