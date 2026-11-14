package com.rpm.player.core.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which line is shown and which measured words it takes: the iPhone's `KaraokeLightTests`, from "which line is shown". */
class KaraokeLinesTest {

    private val sheet = LyricSheet.parse("[00:10.00]first line here\n[00:14.00]second line")

    @Test
    fun measuredWordsOutsideTheLinesOwnTimeAreNotTaken() {
        val measured = listOf(
            MeasuredLine(0, 10.0, "first line here", listOf(MeasuredWord(3.0, 3.5), MeasuredWord(3.5, 4.0), MeasuredWord(4.0, 4.5))),
        )
        // All three words end more than a second before the line starts.
        assertNull(KaraokeLines.heard(measured, "first line here", 10.0, 14.0))
    }

    @Test
    fun theLineBeforeStaysUpWhileItsLastMeasuredWordIsSung() {
        val measured = listOf(
            MeasuredLine(0, 10.0, "first line here", listOf(MeasuredWord(10.0, 11.0), MeasuredWord(11.0, 12.0), MeasuredWord(12.0, 14.5))),
            MeasuredLine(1, 14.0, "second line", listOf(MeasuredWord(14.8, 15.2), MeasuredWord(15.2, 16.0))),
        )
        val score = LyricScore.of(sheet)
        val heard = KaraokeLines.heard(score, measured)
        val second = score.parts.indexOfFirst { (it.piece as? LyricScore.Piece.Words)?.line?.text == "second line" }

        // At 14.2 the stamp says the second line, but the first is still on its last word.
        assertEquals(second - 1, KaraokeLines.held(score, second, 14.2, heard))
        // Once the second line's first word starts, it is shown.
        assertEquals(second, KaraokeLines.held(score, second, 14.9, heard))
    }

    @Test
    fun withNothingMeasuredTheStampsDecide() {
        val score = LyricScore.of(sheet)
        val heard = KaraokeLines.heard(score, emptyList())
        assertEquals(1, KaraokeLines.held(score, 1, 14.2, heard))
        assertNull(KaraokeLines.held(score, null, 1.0, heard))
    }

    @Test
    fun anUnmeasuredLineIsSweptByLength() {
        val score = LyricScore.of(sheet)
        val heard = KaraokeLines.heard(score, emptyList())
        val first = score.parts.first { it.piece is LyricScore.Piece.Words }
        val spans = KaraokeLines.spans(first, score, heard)
        assertEquals(3, spans.size)
        assertEquals(10.0, spans.first().from, 0.0)
    }
}
