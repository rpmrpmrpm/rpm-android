package com.rpm.player.core.lyrics

/**
 * Which words of the score are lit when, for karaoke: the measured words a line takes, and the line that is shown while
 * the last one is still being sung. These are the rules of the TV app's lyric column, which the iPhone ported as
 * `KaraokeLines` (RPMKit/Lyrics/KaraokeLight.swift); the phone uses them from here. Keep the three the same.
 */
object KaraokeLines {
    /** How far before its stamp a line's last measured word may end and still be taken, in seconds. */
    const val STRAY = 1.0

    /** How long before its first measured word a line takes over, in seconds: about when the light starts to arrive. */
    const val LEAD = 0.35

    /** The longest a line waits past its stamp for its first measured word, in seconds. */
    const val WAIT_LONGEST = 4.0

    /**
     * The measured words of the line [text] at [seconds], where they fall in the line's own time: none may end before the
     * line starts (with [STRAY] of slack), and the first may not start after the next piece does ([next]). Words placed
     * outside that are the aligner's mistake, and the line is then swept by length instead.
     */
    fun heard(measured: List<MeasuredLine>, text: String, seconds: Double, next: Double): List<WordSpan>? {
        val words = MeasuredLyrics.forLine(measured, text, seconds) ?: return null
        val first = words.firstOrNull() ?: return null
        val last = words.last()
        if (last.to < seconds - STRAY) return null
        if (next.isFinite() && first.from > next) return null
        return words
    }

    /** Each piece's measured words, by the piece's place in the score. Null for a rest and for a line with no measured
     * words that fit. */
    fun heard(score: LyricScore, measured: List<MeasuredLine>): List<List<WordSpan>?> =
        score.parts.map { part ->
            val piece = part.piece as? LyricScore.Piece.Words ?: return@map null
            heard(measured, piece.line.text, piece.line.seconds, next(part, score))
        }

    /** The spans the sung line is lit by: its measured words where they fit, else the line's time shared between its
     * words by length ([LyricTiming.sweep]). */
    fun spans(part: LyricScore.Part, score: LyricScore, heard: List<List<WordSpan>?>): List<WordSpan> {
        val piece = part.piece as? LyricScore.Piece.Words ?: return emptyList()
        heard.getOrNull(part.id)?.let { return it }
        return LyricTiming.sweep(piece.line.text, piece.line.seconds, next(part, score))
    }

    /** When the piece after [part] starts, or infinity after the last piece. */
    fun next(part: LyricScore.Part, score: LyricScore): Double =
        score.parts.getOrNull(part.id + 1)?.seconds ?: Double.POSITIVE_INFINITY

    /**
     * The piece to show at [seconds], given the piece that the time stamps say ([piece]; null before the first).
     *
     * It is the previous piece while that piece's measured words are still being sung and the words of [piece] have not
     * started. A stamp often comes before the last word of the line before it, and moving on at the stamp would leave
     * that word unlit.
     *
     * It is also the previous piece while the measured words of [piece] are still well ahead. A line measured to start
     * well after its stamp would stand there unlit until the voice reaches it. That wait is never longer than
     * [WAIT_LONGEST] past the stamp, so a measurement that went wrong cannot hold the lyrics back.
     */
    fun held(score: LyricScore, piece: Int?, seconds: Double, heard: List<List<WordSpan>?>): Int? {
        if (piece == null || piece !in heard.indices || piece !in score.parts.indices) return piece

        // Before the first piece is null: the intro is shown.
        val before = if (piece >= 1) piece - 1 else null
        val starts = heard[piece]?.firstOrNull()?.from

        val last = before?.let { heard[it]?.lastOrNull() }
        if (last != null && last.to > seconds) {
            if (starts != null && starts <= seconds) return piece
            return before
        }

        val stamp = score.parts[piece].seconds
        if (starts != null && seconds < starts - LEAD && seconds < stamp + WAIT_LONGEST) return before
        return piece
    }
}
