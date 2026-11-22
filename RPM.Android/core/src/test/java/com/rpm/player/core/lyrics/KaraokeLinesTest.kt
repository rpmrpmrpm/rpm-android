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
        assertEquals(second - 1, KaraokeLines.held(score, 14.2, heard))
        // Once the second line's first word starts, it is shown.
        assertEquals(second, KaraokeLines.held(score, 14.9, heard))
    }

    @Test
    fun aLineSungBeforeItsStampIsShownAsTheLightArrives() {
        val measured = listOf(
            MeasuredLine(0, 10.0, "first line here", listOf(MeasuredWord(10.0, 11.0), MeasuredWord(11.0, 12.0), MeasuredWord(12.0, 13.0))),
            MeasuredLine(1, 14.0, "second line", listOf(MeasuredWord(13.5, 13.8), MeasuredWord(13.8, 14.5))),
        )
        val score = LyricScore.of(sheet)
        val heard = KaraokeLines.heard(score, measured)
        val second = score.parts.indexOfFirst { (it.piece as? LyricScore.Piece.Words)?.line?.text == "second line" }

        // The voice starts the second line at 13.5, half a second before its stamp.
        assertEquals(second - 1, KaraokeLines.held(score, 13.0, heard))
        assertEquals(second, KaraokeLines.held(score, 13.2, heard))
        // Once the stamp is reached, it stays.
        assertEquals(second, KaraokeLines.held(score, 14.1, heard))
    }

    @Test
    fun aLineIsNotShownEarlyWhileTheOneBeforeIsStillSung() {
        val measured = listOf(
            MeasuredLine(0, 10.0, "first line here", listOf(MeasuredWord(10.0, 11.0), MeasuredWord(11.0, 12.0), MeasuredWord(12.0, 13.7))),
            MeasuredLine(1, 14.0, "second line", listOf(MeasuredWord(13.5, 13.8), MeasuredWord(13.8, 14.5))),
        )
        val score = LyricScore.of(sheet)
        val heard = KaraokeLines.heard(score, measured)
        val second = score.parts.indexOfFirst { (it.piece as? LyricScore.Piece.Words)?.line?.text == "second line" }

        // The first line's last word runs to 13.7; the second line waits for its own first word at 13.5.
        assertEquals(second - 1, KaraokeLines.held(score, 13.3, heard))
        assertEquals(second, KaraokeLines.held(score, 13.5, heard))
    }

    @Test
    fun aLineIsNeverShownMoreThanASecondBeforeItsStamp() {
        val measured = listOf(
            MeasuredLine(0, 10.0, "first line here", listOf(MeasuredWord(10.0, 10.5), MeasuredWord(10.5, 11.0), MeasuredWord(11.0, 11.5))),
            MeasuredLine(1, 14.0, "second line", listOf(MeasuredWord(12.0, 13.8), MeasuredWord(13.8, 14.5))),
        )
        val score = LyricScore.of(sheet)
        val heard = KaraokeLines.heard(score, measured)
        val second = score.parts.indexOfFirst { (it.piece as? LyricScore.Piece.Words)?.line?.text == "second line" }

        assertEquals(second - 1, KaraokeLines.held(score, 12.5, heard))
        assertEquals(second, KaraokeLines.held(score, 13.0, heard))
    }

    @Test
    fun theFirstLineTakesOverFromTheIntroBeforeItsStamp() {
        val measured = listOf(
            MeasuredLine(0, 10.0, "first line here", listOf(MeasuredWord(9.6, 10.5), MeasuredWord(10.5, 11.0), MeasuredWord(11.0, 11.5))),
        )
        val score = LyricScore.of(sheet)
        val heard = KaraokeLines.heard(score, measured)
        val first = score.parts.indexOfFirst { it.piece is LyricScore.Piece.Words }

        assertNull(KaraokeLines.held(score, 9.0, heard))
        assertEquals(first, KaraokeLines.held(score, 9.3, heard))
    }

    @Test
    fun withNothingMeasuredTheStampsDecide() {
        val score = LyricScore.of(sheet)
        val heard = KaraokeLines.heard(score, emptyList())
        assertEquals(1, KaraokeLines.held(score, 14.2, heard))
        assertNull(KaraokeLines.held(score, 1.0, heard))
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

    /**
     * JT's "Oh", as LRCLIB has it and as the cloud measured it. The sheet stamps "And they used to..." at 17.28 and the
     * next line at 17.52, and the line was not measured; the measured last word of the line before runs to 17.51, and
     * the next line's first word is measured at 17.40. The rules alone went from "You get these..." straight to "If they
     * get out of line" at 17.51.
     */
    @Test
    fun aLineSqueezedBetweenItsNeighboursIsStillSung() {
        val sheet = LyricSheet.parse(
            listOf(
                "[00:08.55]To crown you the queen of bad bitches worldwide",
                "[00:11.98](I don't fuck with these niggas, I don't fuck with these hoes)",
                "[00:12.74]Now with that crown",
                "[00:14.31]You get these beautiful crystal slippers, baby",
                "[00:17.28]And they used to, not wear, but to crack a ho in they head",
                "[00:17.52]If they get out of line",
                "[00:22.82](I don't fuck with these niggas, I don't fuck with these hoes)",
                "[00:25.74]Pull up double-R, this that Rih and Rocky",
            ).joinToString("\n"),
        )
        val measured = listOf(
            MeasuredLine(2, 8.55, "To crown you the queen of bad bitches worldwide", listOf(MeasuredWord(8.07, 8.13), MeasuredWord(8.25, 8.51), MeasuredWord(8.59, 8.71), MeasuredWord(8.79, 8.85), MeasuredWord(8.91, 9.45), MeasuredWord(9.61, 9.65), MeasuredWord(9.81, 10.07), MeasuredWord(10.19, 10.55), MeasuredWord(10.61, 11.57))),
            MeasuredLine(3, 11.98, "(I don't fuck with these niggas, I don't fuck with these hoes)", listOf(MeasuredWord(11.42, 11.44), MeasuredWord(11.46, 11.56), MeasuredWord(11.58, 11.66), MeasuredWord(11.68, 11.76), MeasuredWord(11.82, 11.94), MeasuredWord(11.96, 12.10), MeasuredWord(12.12, 12.14), MeasuredWord(12.16, 12.40), MeasuredWord(12.48, 12.59), MeasuredWord(12.59, 12.70), MeasuredWord(12.70, 12.84), MeasuredWord(12.84, 12.98))),
            MeasuredLine(4, 12.74, "Now with that crown", listOf(MeasuredWord(12.52, 12.64), MeasuredWord(12.74, 12.90), MeasuredWord(13.02, 13.18), MeasuredWord(13.40, 13.92))),
            MeasuredLine(5, 14.31, "You get these beautiful crystal slippers, baby", listOf(MeasuredWord(14.01, 14.09), MeasuredWord(14.19, 14.31), MeasuredWord(14.37, 14.61), MeasuredWord(14.81, 15.31), MeasuredWord(15.47, 15.85), MeasuredWord(15.99, 16.43), MeasuredWord(16.51, 17.51))),
            MeasuredLine(7, 17.52, "If they get out of line", listOf(MeasuredWord(17.40, 17.90), MeasuredWord(18.66, 18.90), MeasuredWord(19.20, 19.32), MeasuredWord(19.42, 19.72), MeasuredWord(20.02, 20.24), MeasuredWord(21.10, 21.56))),
        )
        for (asides in listOf(true, false)) {
            val score = LyricScore.of(sheet, asides)
            val heard = KaraokeLines.heard(score, measured)
            fun line(text: String) = score.parts.indexOfFirst { (it.piece as? LyricScore.Piece.Words)?.line?.text?.startsWith(text) == true }
            val slippers = line("You get these")
            val squeezed = line("And they used to")
            val outOfLine = line("If they get out")

            // The line before is held through its last measured word.
            assertEquals(slippers, KaraokeLines.held(score, 17.4, heard))
            // Then the squeezed line is sung for a moment of its own, and the next line follows.
            assertEquals(squeezed, KaraokeLines.held(score, 17.6, heard))
            assertEquals(squeezed, KaraokeLines.held(score, 17.51 + KaraokeLines.SHORTEST - 0.05, heard))
            assertEquals(outOfLine, KaraokeLines.held(score, 17.51 + KaraokeLines.SHORTEST + 0.05, heard))
        }
    }

    @Test
    fun theHandOversAreWhenThePiecesChange() {
        val handovers = KaraokeLines.handovers(LyricScore.of(sheet), emptyList())
        assertEquals(10.0, handovers.next(1.0)!!, 1e-3)
        assertEquals(14.0, handovers.next(10.5)!!, 1e-3)
    }
}
