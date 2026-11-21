package com.rpm.player.core.lyrics

/**
 * A lyric sheet cut into what is sung and what is waited through. It has the same logic as the iOS
 * app's `LyricScore` (RPMKit/Lyrics/LyricScore.swift); keep the two the same.
 *
 * An LRC file says when each line starts but not how long it lasts. A break in the singing is
 * either a line with no words or a wait that no singing could fill ([REST] seconds or more). Both
 * become a rest between two stretches of words. A screen asks the score which piece the playhead
 * is in, which stretch that is, and how far through a rest it is.
 */
class LyricScore private constructor(val parts: List<Part>) {

    sealed interface Piece {
        /** A line of words, and the backing lines sung after it that [of] put under it ([Aside]). */
        data class Words(val line: LyricLine, val asides: List<Aside> = emptyList()) : Piece

        /** Nothing is sung between these two moments; [until] is infinite after the last line. */
        data class Rest(val from: Double, val until: Double) : Piece
    }

    /** One piece and where it stands. [section] is the stretch of singing it belongs to. A rest
     * carries the number of the stretch it closes, so the words before it and the rest are shown
     * together and the words after it are not. */
    data class Part(val id: Int, val section: Int, val piece: Piece) {
        val seconds: Double
            get() = when (piece) {
                is Piece.Words -> piece.line.seconds
                is Piece.Rest -> piece.from
            }
        val isRest: Boolean get() = piece is Piece.Rest
    }

    /** A line bracketed from end to end ([LyricVoices.isAside]), a backing vocal: its [line], and [until], when the
     * line after it in the sheet starts (infinite after the last). */
    data class Aside(val line: LyricLine, val until: Double)

    /** Which piece the playhead is in, or null before the first line (an intro is part of the song).
     * Binary search, because this is asked once per frame. */
    fun index(seconds: Double): Int? {
        var low = 0
        var high = parts.size - 1
        var found: Int? = null
        while (low <= high) {
            val mid = (low + high) / 2
            if (parts[mid].seconds <= seconds) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    companion object {
        /** How long a wait between two lines must be to count as a break and not a breath. Same
         * value as the desktop's `LyricHush.AfterBreak`. */
        const val REST = 20.0

        /** How long the last line before a rest stays up, counted from its own time. It is half of
         * [REST], so it can never fill a whole rest. */
        const val SUNG = 8.0

        /**
         * The score of [sheet]. With [asides], a line bracketed from end to end ([LyricVoices.isAside]) is not a piece of
         * its own: it goes with the line of words before it ([Piece.Words.asides]), and the score moves on as if it
         * were not there. A backing line with no line of words before it in its stretch stays a piece of its own.
         */
        fun of(sheet: LyricSheet, asides: Boolean = false): LyricScore {
            val parts = mutableListOf<Part>()
            var section = 0
            var lastSang: Double? = null
            sheet.lines.forEachIndexed { at, line ->
                val next = sheet.lines.getOrNull(at + 1)?.seconds ?: Double.POSITIVE_INFINITY
                if (isWordless(line.text)) {
                    parts += Part(parts.size, section, Piece.Rest(line.seconds, next))
                    section += 1
                    lastSang = null
                    return@forEachIndexed
                }
                val held = (parts.lastOrNull()?.piece as? Piece.Words)?.takeIf { lastSang != null }
                if (asides && held != null && LyricVoices.isAside(line.text) && line.seconds - lastSang!! < REST) {
                    parts[parts.lastIndex] = parts.last().copy(piece = held.copy(asides = held.asides + Aside(line, next)))
                    lastSang = line.seconds
                    return@forEachIndexed
                }
                val sang = lastSang
                // A wait that no line marks: the rest closes the stretch before it, and this line opens the next.
                if (sang != null && line.seconds - sang >= REST) {
                    parts += Part(parts.size, section, Piece.Rest(sang + SUNG, line.seconds))
                    section += 1
                }
                parts += Part(parts.size, section, Piece.Words(line))
                lastSang = line.seconds
            }
            // The outro: a rest that never ends after the last line.
            lastSang?.let { parts += Part(parts.size, section, Piece.Rest(it + SUNG, Double.POSITIVE_INFINITY)) }
            return LyricScore(parts)
        }

        /** A line with nothing to sing: blank, or only musical notes (the four from Miscellaneous
         * Symbols and the two emoji). Sharps and flats do not count as notes, because a lyric can be about one. */
        fun isWordless(text: String?): Boolean {
            if (text == null) return true
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                if (Character.isWhitespace(cp)) continue
                if (cp in NOTES) continue
                return false
            }
            return true
        }

        private val NOTES = setOf(0x2669, 0x266A, 0x266B, 0x266C, 0x1F3B5, 0x1F3B6)
    }
}

/** One word of a line: where it sits in the text and the stretch of the record it is sung over. */
data class WordSpan(val start: Int, val length: Int, val from: Double, val to: Double)

/**
 * Which word of a line is being sung, for karaoke. It is the Android side of the desktop's
 * `LyricTiming.Sweep` (RPM.Core/Lyrics). LRCLIB sheets have no word timestamps, so the line is
 * spread across its words by their length, from the line's own time to the next line's time. The
 * speed is capped at [SLOWEST_CHARS] characters a second: past that the sweep finishes and the
 * line stays lit, instead of crawling through an instrumental. The line's last word then takes the time left
 * before the next line, up to [LAST_HELD] and ending [BEFORE_NEXT] short of it: a line's last word is often the
 * held note ("...and sends it on"), and lit at singing speed it sat lit while the voice still carried it.
 */
object LyricTiming {
    const val SLOWEST_CHARS = 7.0

    /** The longest a line's last word is held for, in seconds, past the singing-speed sweep. */
    const val LAST_HELD = 3.0

    /** How far short of the next line the held last word ends, in seconds. */
    const val BEFORE_NEXT = 0.25

    fun sweep(text: String?, from: Double, until: Double): List<WordSpan> {
        val words = split(text)
        if (words.isEmpty()) return emptyList()
        val chars = words.sumOf { it.second }
        val capped = from + chars / SLOWEST_CHARS
        val end = if (until.isFinite()) minOf(maxOf(from, until), capped) else capped
        val run = maxOf(0.0, end - from)
        var soFar = 0
        val spans = words.map { (start, length) ->
            // Measured from the start of the line, so rounding cannot move the last word off the end.
            val opens = from + run * soFar / maxOf(1, chars)
            soFar += length
            val closes = from + run * soFar / maxOf(1, chars)
            WordSpan(start, length, opens, closes)
        }
        // The last word holds on into the time the sweep left over, when the next line is known.
        if (!until.isFinite()) return spans
        val last = spans.last()
        val held = minOf(until - BEFORE_NEXT, last.to + LAST_HELD)
        return if (held > last.to) spans.dropLast(1) + last.copy(to = held) else spans
    }

    /** Runs of anything that is not whitespace; punctuation stays with its word. */
    private fun split(text: String?): List<Pair<Int, Int>> {
        if (text.isNullOrBlank()) return emptyList()
        val words = mutableListOf<Pair<Int, Int>>()
        var at = 0
        while (at < text.length) {
            while (at < text.length && text[at].isWhitespace()) at++
            val start = at
            while (at < text.length && !text[at].isWhitespace()) at++
            if (at > start) words += start to (at - start)
        }
        return words
    }
}
