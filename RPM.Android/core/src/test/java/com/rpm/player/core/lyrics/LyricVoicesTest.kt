package com.rpm.player.core.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The second voice: the desktop's `AsideGroups`, `IsAside`, `AsideText` and `Verse`, and how the phone lays it out. */
class LyricVoicesTest {

    private fun split(text: String) = LyricVoices.split(text, LyricTiming.sweep(text, 0.0, 10.0))

    @Test
    fun aWholeBracketedLineIsAnAside() {
        assertTrue(LyricVoices.isAside("(Oh, fuck 'em)"))
        assertTrue(LyricVoices.isAside("  (Oh)  "))
        assertFalse(LyricVoices.isAside("I wish I understood (yeah)"))
        assertFalse(LyricVoices.isAside("(Alter ego) dead to me, rah"))
        assertFalse(LyricVoices.isAside("(a) and (b)"))
        assertFalse(LyricVoices.isAside("()"))
    }

    @Test
    fun theBracketedWordsGoUnderTheVerse() {
        val split = split("Milan, darling, Milan (fuck, fuck)")!!
        assertEquals("Milan, darling, Milan", split.verse.text)
        assertEquals("fuck, fuck", split.voice.text)
    }

    @Test
    fun twoGroupsAreJoinedWithAComma() {
        val split = split("(Alter ego) dead to me (rah)")!!
        assertEquals("dead to me", split.verse.text)
        assertEquals("Alter ego, rah", split.voice.text)
    }

    @Test
    fun onlyTheOutermostBracketsCount() {
        val split = split("I said (go (go) now) okay")!!
        assertEquals("I said okay", split.verse.text)
        assertEquals("go go now", split.voice.text)
    }

    @Test
    fun aLineWithNoVoiceIsNotSplit() {
        assertNull(split("Plain line"))
        assertNull(split("(All of it)"))
        assertNull(split("Empty ( ) bracket"))
    }

    @Test
    fun theVerseAfterABracketInAWordGoesOnWithoutASpace() {
        assertEquals("on and on.", split("on and on (and on).")!!.verse.text)
    }

    @Test
    fun eachLetterIsLitAsTheSameLetterOfTheWholeLine() {
        val text = "Year of the swamp (ooh)"
        val spans = LyricTiming.sweep(text, 0.0, 10.0)
        val split = LyricVoices.split(text, spans)!!
        // "Year" "of" "the" "swamp" are letters 0..13; "(ooh)" is 14..18, its brackets 14 and 18.
        assertEquals(14, split.verse.letters.size)
        assertEquals(listOf(15, 16, 17), split.voice.letters.toList())
        assertEquals(spans.last().from, split.voice.spans.single().from, 0.0)
    }

    @Test
    fun aBackingLineLosesItsBrackets() {
        val text = "(Oh, fuck 'em)"
        val row = LyricVoices.unbracketed(text, LyricTiming.sweep(text, 0.0, 10.0))
        assertEquals("Oh, fuck 'em", row.text)
        assertEquals(3, row.spans.size)
    }

    @Test
    fun aBackingLineGoesWithTheLineBeforeIt() {
        val sheet = LyricSheet.parse("[00:10.00]To crown you the queen\n[00:12.00](I don't fuck with these)\n[00:13.00]Now with that crown")
        val score = LyricScore.of(sheet, asides = true)
        val words = score.parts.mapNotNull { it.piece as? LyricScore.Piece.Words }
        assertEquals(listOf("To crown you the queen", "Now with that crown"), words.map { it.line.text })
        assertEquals(LyricScore.Aside(sheet.lines[1], 13.0), words[0].asides.single())
        // The line before it is swept until the backing line starts.
        assertEquals(12.0, KaraokeLines.next(score.parts[0], score), 0.0)
        // Without asides, the score is the sheet's.
        assertEquals(3, LyricScore.of(sheet).parts.count { it.piece is LyricScore.Piece.Words })
    }

    @Test
    fun aBackingLineWithNoLineBeforeItKeepsItsRow() {
        val sheet = LyricSheet.parse("[00:01.00](Ooh)\n[00:03.00]First line")
        val score = LyricScore.of(sheet, asides = true)
        assertEquals(2, score.parts.count { it.piece is LyricScore.Piece.Words })
    }

    @Test
    fun aMeasuredBackingLineShowsAsItsWordsAreSung() {
        val sheet = LyricSheet.parse("[00:10.00]To crown you\n[00:12.00](Oh yeah)\n[00:14.00]Next")
        val aside = (LyricScore.of(sheet, asides = true).parts[0].piece as LyricScore.Piece.Words).asides.single()
        val measured = listOf(MeasuredLine(1, 12.0, "(Oh yeah)", listOf(MeasuredWord(11.6, 12.0), MeasuredWord(12.0, 12.8))))
        val voice = KaraokeLines.voice(aside, measured)
        assertEquals(11.6 - KaraokeLines.LEAD, voice.from, 1e-9)
        assertEquals(12.8, voice.until, 1e-9)
        // Unmeasured, it shows from its stamp.
        assertEquals(12.0, KaraokeLines.voice(aside, emptyList()).from, 0.0)
    }
}
