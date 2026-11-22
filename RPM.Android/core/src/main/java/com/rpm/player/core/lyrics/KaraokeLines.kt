package com.rpm.player.core.lyrics

/**
 * Which words of the score are lit when, for karaoke: the measured words a line takes, and the line that is shown while
 * the last one is still being sung. The iPhone has the same rules as `KaraokeLines` (RPMKit/Lyrics/KaraokeLight.swift);
 * the phone's lyric view and the TV's `LyricColumn` use them from here. Keep the two the same, except that here a line
 * also takes over before its stamp when its measured words start earlier ([held]); the iPhone does not do that.
 */
object KaraokeLines {
    /** How far before its stamp a line's last measured word may end and still be taken, in seconds. */
    const val STRAY = 1.0

    /** How long before its first measured word a line takes over, in seconds: about when the light starts to arrive. */
    const val LEAD = 0.35

    /** The longest a line waits past its stamp for its first measured word, in seconds. */
    const val WAIT_LONGEST = 4.0

    /** The earliest before its stamp a line takes over for its first measured word, in seconds. */
    const val EARLIEST = 1.0

    /** The shortest time a line of words is the sung line, in seconds, unless the playhead is moved past it. */
    const val SHORTEST = 1.0

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

    /** When the piece after [part] starts, or its first backing line ([LyricScore.Aside]) where that is sooner; infinity
     * after the last piece. */
    fun next(part: LyricScore.Part, score: LyricScore): Double {
        val after = score.parts.getOrNull(part.id + 1)?.seconds ?: Double.POSITIVE_INFINITY
        val aside = (part.piece as? LyricScore.Piece.Words)?.asides?.firstOrNull()?.line?.seconds ?: return after
        return minOf(after, aside)
    }

    /** When a backing line is shown and how its words are lit: [spans] over its text, shown from [from] until [until]. */
    class Voice(val spans: List<WordSpan>, val from: Double, val until: Double)

    /**
     * When the backing line [aside] is shown under its line, and its words. With measured words that fit it, it comes
     * in [LEAD] before its first word, as a line does ([held]), but no earlier than [EARLIEST] before its stamp; else at
     * its stamp, with its time shared between its words by length. It goes when its last word has been sung.
     */
    fun voice(aside: LyricScore.Aside, measured: List<MeasuredLine>): Voice {
        val line = aside.line
        heard(measured, line.text, line.seconds, aside.until)?.let { words ->
            return Voice(words, maxOf(words.first().from - LEAD, line.seconds - EARLIEST), words.last().to)
        }
        val swept = LyricTiming.sweep(line.text, line.seconds, aside.until)
        return Voice(swept, line.seconds, swept.lastOrNull()?.to ?: line.seconds)
    }

    /**
     * The piece to show at [seconds]: the piece the time stamps say, moved by the rules below; null before the first.
     *
     * It is the previous piece while that piece's measured words are still being sung and the words of [piece] have not
     * started. A stamp often comes before the last word of the line before it, and moving on at the stamp would leave
     * that word unlit.
     *
     * It is also the previous piece while the measured words of [piece] are still well ahead. A line measured to start
     * well after its stamp would stand there unlit until the voice reaches it. That wait is never longer than
     * [WAIT_LONGEST] past the stamp, so a measurement that went wrong cannot hold the lyrics back.
     *
     * And it is the next piece once its first measured word is [LEAD] away, even before its stamp. A sheet's stamp is
     * often a little after the voice starts the line, by up to half a second in quick singing. Waiting for the stamp would
     * show the line late, with its first words already sung and lit all at once. It moves on no earlier than [EARLIEST]
     * before the stamp, and not while the line it leaves is still on its last measured word, unless the next line's first
     * word has already started.
     *
     * Whatever the rules say, a line of words is the sung line for at least [SHORTEST] ([Handovers]). A sheet can stamp
     * a line a quarter of a second before the next, and the measured words of the line before can run up to the next
     * one's: the rules would then pass over it, and its words would never be lit.
     */
    fun held(score: LyricScore, seconds: Double, heard: List<List<WordSpan>?>): Int? = handovers(score, heard).piece(seconds)

    /**
     * When each piece of a score takes over as the sung piece, worked out once for a score and its measured words, so
     * that a line can be given a moment of its own ([SHORTEST]) after the rules have been applied.
     */
    class Handovers internal constructor(private val at: DoubleArray) {
        /** The piece to show at [seconds], or null before the first. */
        fun piece(seconds: Double): Int? {
            var low = 0
            var high = at.size - 1
            var found: Int? = null
            while (low <= high) {
                val mid = (low + high) / 2
                if (at[mid] <= seconds) {
                    found = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return found
        }

        /** When the next piece after [seconds] takes over, or null when none does. */
        fun next(seconds: Double): Double? = at.firstOrNull { it > seconds && it.isFinite() }
    }

    /** The [Handovers] of [score] with [heard], made once and kept while the two are in use. */
    fun handovers(score: LyricScore, heard: List<List<WordSpan>?>): Handovers = synchronized(made) {
        made[score]?.takeIf { it.first === heard }?.second ?: handoversOf(score, heard).also { made[score] = heard to it }
    }

    private val made = java.util.WeakHashMap<LyricScore, Pair<List<List<WordSpan>?>, Handovers>>()

    /** How finely the rules are followed through the song when the hand-overs are worked out, in seconds. */
    private const val STEP = 0.01

    private fun handoversOf(score: LyricScore, heard: List<List<WordSpan>?>): Handovers {
        val parts = score.parts
        val at = DoubleArray(parts.size) { Double.POSITIVE_INFINITY }
        if (parts.isEmpty()) return Handovers(at)
        fun rule(seconds: Double): Int = rule(score, score.index(seconds), seconds, heard) ?: -1

        // The rules are followed through the song in small steps, and each hand-over is then found exactly between the
        // last two steps. A piece the rules pass over takes over with the one after it.
        val sung = heard.maxOfOrNull { it?.lastOrNull()?.to ?: 0.0 } ?: 0.0
        val end = maxOf(parts.last().seconds, sung) + WAIT_LONGEST + 1
        var reached = -1
        var before = -1.0
        var seconds = -1.0
        while (seconds <= end && reached < parts.lastIndex) {
            val piece = rule(seconds)
            if (piece > reached) {
                var low = before
                var high = seconds
                repeat(40) {
                    val mid = (low + high) / 2
                    if (rule(mid) > reached) high = mid else low = mid
                }
                // Stamps and measured words are given to the hundredth, so the moment is put back on the microsecond
                // it came from: a line takes over at its moment, not a hair after it.
                val round = kotlin.math.round(high * 1e6) / 1e6
                if (rule(round) > reached) high = round
                for (q in reached + 1..piece) at[q] = high
                reached = piece
            }
            before = seconds
            seconds += STEP
        }

        // Every line of words is sung for a moment: the pieces after a line that would be passed over move on later.
        for (p in 0 until parts.lastIndex) {
            if (parts[p].piece is LyricScore.Piece.Words && at[p].isFinite()) at[p + 1] = maxOf(at[p + 1], at[p] + SHORTEST)
        }
        return Handovers(at)
    }

    /** The rules of [held], before a line is given its moment. */
    private fun rule(score: LyricScore, piece: Int?, seconds: Double, heard: List<List<WordSpan>?>): Int? {
        val stays = heldBack(score, piece, seconds, heard)
        if (stays != piece) return stays
        val next = (piece ?: -1) + 1
        if (next !in heard.indices || next !in score.parts.indices) return piece
        val starts = heard[next]?.firstOrNull()?.from ?: return piece
        if (seconds < starts - LEAD || seconds < score.parts[next].seconds - EARLIEST) return piece
        val singing = piece?.let { heard.getOrNull(it)?.lastOrNull() }
        if (singing != null && singing.to > seconds && starts > seconds) return piece
        return next
    }

    /** [held] without moving on early: the previous piece while it is still sung or the words of [piece] are well ahead. */
    private fun heldBack(score: LyricScore, piece: Int?, seconds: Double, heard: List<List<WordSpan>?>): Int? {
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
