package com.rpm.player.core.lyrics

/**
 * The second voice in a lyric line: the words a sheet puts in round brackets. These are the rules of the desktop's
 * Pegasus lyrics (`AsideGroups`, `IsAside`, `AsideText` and `Verse` in RPM.Desktop/Presentation/Pegasus/
 * PegasusChrome.xaml.cs).
 *
 * Sheets write two singers as one line, with the second singer in brackets: "Milan, darling, Milan (fuck, fuck)".
 * Left in the line, two people's words run together and wrap into the next line. So the bracketed words are shown on
 * a smaller row of their own under the line ([split]), and the brackets themselves are not shown.
 *
 * A line bracketed from end to end is a backing vocal on its own ([isAside]). It has no verse to sit under, so the
 * lyric view shows it under the line before it while it is sung ([LyricScore.Aside]).
 */
object LyricVoices {
    /** How much smaller the second voice is drawn than the verse, as on the desktop. */
    const val SCALE = 0.76f

    /** Whether [text] is bracketed from end to end, with one bracket pair around the whole line. A line that only
     * contains brackets is not: "I wish I understood (yeah)" is the verse, with a shout in it. */
    fun isAside(text: String?): Boolean {
        val line = text?.trim() ?: return false
        return line.length > 2 && line[0] == '(' && line[line.length - 1] == ')' && line.indexOf(')', 1) == line.length - 1
    }

    /**
     * Every bracketed group of [text], as half-open ranges from the opening bracket to after the closing one, in the
     * order they were written. Only the outermost brackets count: a group inside a group belongs to it. A group must
     * have words inside it, and the line must have words outside its groups; otherwise there is no second voice. Every
     * group counts, however short: "(uh)" is a second voice as much as a sung answer is.
     */
    fun groups(text: String?): List<IntRange> {
        if (text == null || text.length <= 2 || isAside(text)) return emptyList()
        val found = ArrayList<IntRange>()
        var depth = 0
        var open = -1
        for (i in text.indices) {
            when {
                text[i] == '(' -> if (depth++ == 0) open = i
                text[i] == ')' && depth > 0 -> if (--depth == 0 && open >= 0) {
                    if (text.substring(open + 1, i).isNotBlank()) found += open until i + 1
                    open = -1
                }
            }
        }
        return if (found.isNotEmpty() && text.length > found.sumOf { it.last - it.first + 1 }) found else emptyList()
    }

    /** One row of a line taken apart: its [text], its words as spans of that text, and for each of its letters the
     * place of that letter among the letters of the whole line's spans, which is what [KaraokeLight.light] fills. */
    class Row(val text: String, val spans: List<WordSpan>, val letters: IntArray)

    /** A line taken apart into its [verse] and its second voice ([voice]). */
    class Split(val verse: Row, val voice: Row)

    /**
     * The line [text] taken apart, or null when it has no second voice ([groups]). [spans] are the line's words, as
     * [LyricTiming.sweep] cuts them, with their times. Each row keeps the times of the words it takes.
     *
     * In the second voice, words of one group are joined by a space and two groups by a comma, so two answers written
     * apart do not read as one. The brackets are left out. A word cut by a bracket is shared between the two rows, and
     * what follows the bracket in the verse is joined to the verse without a space.
     */
    fun split(text: String, spans: List<WordSpan>): Split? {
        val groups = groups(text)
        if (groups.isEmpty()) return null
        fun groupAt(at: Int): Int = groups.indexOfFirst { at in it }

        val verse = Builder()
        val voice = Builder()
        var letter = 0
        for ((k, word) in spans.withIndex()) {
            for (c in 0 until word.length) {
                val at = word.start + c
                val group = groupAt(at)
                val ch = text.getOrNull(at)
                if (ch != null && (group < 0 || (ch != '(' && ch != ')'))) {
                    // The verse after a bracket inside one word ("on (and on).") goes on without a space.
                    if (group >= 0) voice.add(k, group, ch, word, letter, apart = true)
                    else verse.add(k, group, ch, word, letter, apart = c == 0)
                }
                letter++
            }
        }
        return Split(verse.row(), voice.row())
    }

    /** A backing line ([isAside]) as its row shows it: [text] without its brackets, with [spans], its words as
     * [LyricTiming.sweep] cuts them, made to fit. */
    fun unbracketed(text: String, spans: List<WordSpan>): Row {
        val row = Builder()
        var letter = 0
        for ((k, word) in spans.withIndex()) {
            for (c in 0 until word.length) {
                val ch = text.getOrNull(word.start + c)
                if (ch != null && ch != '(' && ch != ')') row.add(k, -1, ch, word, letter, apart = c == 0 || row.isEmpty)
                letter++
            }
        }
        return row.row()
    }

    /** Writes one row a letter at a time. */
    private class Builder {
        private val text = StringBuilder()
        private val spans = ArrayList<WordSpan>()
        private val letters = ArrayList<Int>()
        private var word = -1
        private var group = -1

        fun add(k: Int, group: Int, ch: Char, from: WordSpan, letter: Int, apart: Boolean) {
            if (k != word || spans.isEmpty()) {
                if (text.isNotEmpty() && apart) text.append(if (group >= 0 && this.group >= 0 && group != this.group) ", " else " ")
                spans += WordSpan(text.length, 0, from.from, from.to)
                word = k
                this.group = group
            }
            text.append(ch)
            val last = spans.last()
            spans[spans.lastIndex] = last.copy(length = last.length + 1)
            letters += letter
        }

        val isEmpty: Boolean get() = text.isEmpty()

        fun row(): Row = Row(text.toString(), spans.toList(), letters.toIntArray())
    }
}
