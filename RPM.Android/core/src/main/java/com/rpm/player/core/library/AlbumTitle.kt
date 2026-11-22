package com.rpm.player.core.library

/**
 * A record's name with the bracketed editions taken off the end (the desktop's `AlbumTitle`). "Short N' Sweet (Deluxe)"
 * is a record called Short N' Sweet, in its Deluxe edition. The record page shows the two parts on separate lines.
 *
 * - **Only from the end.** A bracketed group in the middle or at the start is part of the name: "(What's the Story)
 *   Morning Glory?" keeps it. Groups are removed from the end again and again, so "... (Deluxe) [Remastered]" loses
 *   both.
 * - **Only when the brackets hold an edition** ([isAside]). "The Guide (Wommat)" is really called that, so it stays.
 * - A title that is only brackets, such as "( )", keeps them, so the heading is never empty.
 *
 * @property full the title exactly as it was tagged. Use it wherever the record is named, not rebuilt from the parts.
 * @property name the title without the trailing editions.
 * @property editions what was inside those brackets, in reading order, without the brackets. Empty for most records.
 */
class AlbumTitle private constructor(val full: String, val name: String, val editions: List<String>) {
    companion object {
        fun split(title: String?): AlbumTitle {
            val full = title.orEmpty()
            var name = full.trimEnd()
            val editions = ArrayList<String>()
            while (true) {
                val (before, inside) = peel(name) ?: break
                if (before.isEmpty()) break
                editions.add(0, inside)
                name = before
            }
            return AlbumTitle(full, name, editions)
        }

        /**
         * Whether the text between a pair of brackets is a note about which recording or pressing this is, and not
         * part of the name (the desktop's `TitleAsides.IsAside`). The test is a list of words, not the shape: a phrase
         * this does not know is read as part of the name, which is the safe way to be wrong. A credit ("feat. …",
         * "with …") is never an aside, whatever words it holds.
         */
        fun isAside(inside: String): Boolean {
            val phrase = inside.trim()
            if (phrase.isEmpty() || isCredit(phrase)) return false
            return words(phrase).any { it in QUALIFIERS }
        }

        /**
         * One trailing bracketed group: what is left of the title, and what was inside. Null when the title does not
         * end in one, or the group is not an edition. The closing bracket is counted back to its own opener, so
         * "(Deluxe (2019))" comes off whole.
         */
        private fun peel(title: String): Pair<String, String>? {
            if (title.isEmpty()) return null
            val (open, close) = when (title.last()) {
                ')' -> '(' to ')'
                ']' -> '[' to ']'
                else -> return null
            }
            var depth = 0
            for (i in title.indices.reversed()) {
                val c = title[i]
                if (c == close) {
                    depth++
                } else if (c == open && --depth == 0) {
                    val inside = title.substring(i + 1, title.length - 1).trim()
                    return if (isAside(inside)) title.substring(0, i).trimEnd() to inside else null
                }
            }
            // A bracket that is never closed, such as "Album (Deluxe", is part of the title.
            return null
        }

        /** The phrase's words, lower case, split on every character that is not a letter or a digit. */
        private fun words(phrase: String): List<String> =
            phrase.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.map { it.lowercase() }

        /** Whether the phrase opens with a credit mark that ends where its next word begins. */
        private fun isCredit(phrase: String): Boolean = CREDIT_MARKS.any { mark ->
            if (!phrase.startsWith(mark, ignoreCase = true)) return@any false
            if (!mark.last().isLetter()) return@any true
            val after = phrase.getOrNull(mark.length)
            after == null || after == '.' || after == ':' || after.isWhitespace()
        }

        private val CREDIT_MARKS = listOf("feat.", "feat", "featuring", "ft.", "ft", "with", "w/")

        /** The words a version or a pressing is described with (the desktop's `TitleAsides.Qualifiers`). English only. */
        private val QUALIFIERS = setOf(
            // what was done to the recording
            "mix", "mixes", "mixed", "remix", "remixes", "remixed", "edit", "edits", "edited",
            "version", "versions", "remaster", "remastered", "remasters", "rerecorded", "rerecording",
            "dub", "instrumental", "acoustic", "unplugged", "acappella", "cappella", "karaoke", "reprise",
            // where and how it was taken down
            "live", "demo", "rehearsal", "session", "sessions", "take", "outtake", "bootleg", "concert",
            // which cut of it this is
            "radio", "single", "album", "extended", "original", "alternate", "clean", "explicit",
            "censored", "mono", "stereo", "club", "dance", "vocal",
            // which pressing carries it
            "deluxe", "edition", "expanded", "anniversary", "reissue", "bonus", "unreleased",
        )
    }
}
