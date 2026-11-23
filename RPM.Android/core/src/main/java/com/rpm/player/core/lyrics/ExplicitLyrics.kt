package com.rpm.player.core.lyrics

/**
 * Picks the uncensored sheet for an explicit record from the sheets LRCLIB offers.
 *
 * LRCLIB often holds two sheets for one song: the explicit one and the clean (edited) one. They have the same names
 * and nearly the same length, so the exact lookup can return either. For a record marked explicit, the explicit sheet
 * is the right one. This gives each candidate a score and keeps the best.
 *
 * **The score.** Each masked word (a word with `*` in it, a `#` after a letter or another `#`, or a `[censored]`
 * marker) costs [MASK_PENALTY] points. Each word from [WORDS] adds one point. A clean sheet with masks scores below
 * zero, and the explicit sheet scores above it. A clean sheet that drops the words without masking them scores zero,
 * and the explicit sheet still scores more.
 *
 * **The same rules on every device.** The desktop (`RPM.Core/Lyrics/ExplicitLyrics.cs`) and the iPhone app (RPMKit
 * `ExplicitLyrics.swift`) use the same word list, the same split into words and the same score. Change all three
 * together.
 */
object ExplicitLyrics {
    /** What one masked word costs. It is large, so that one mask outweighs several explicit words. */
    const val MASK_PENALTY = 10

    /**
     * Common explicit words, in lower case and letters only. They come from the swearing list of the desktop's
     * `LyricCensor`, without the words that also have an innocent meaning (such as "hell" or "cock"). A word is
     * counted when the letters of a word in the sheet are exactly one of these.
     */
    val WORDS: Set<String> = setOf(
        "arse", "arsehole", "ass", "asshole", "assholes",
        "bastard", "bastards", "bitch", "bitches", "bitchin", "bitching", "bollocks", "bullshit",
        "cunt", "cunts", "damn", "dammit",
        "fuck", "fucked", "fucker", "fuckers", "fuckin", "fucking", "fucks", "goddamn",
        "motherfucker", "motherfuckers", "motherfuckin", "motherfucking",
        "piss", "pissed", "pussies", "pussy",
        "shit", "shits", "shittin", "shitting", "shitty", "slut", "sluts",
        "tits", "titties", "twat", "wanker", "whore", "whores",
    )

    private val SPACE = Regex("""\s+""")

    /** The sheet's score: one point for each explicit word, minus [MASK_PENALTY] for each masked word. */
    fun score(sheet: LyricSheet): Int {
        var score = 0
        for (line in sheet.lines) {
            for (raw in line.text.split(SPACE)) {
                if (raw.isEmpty()) continue
                val token = raw.lowercase()
                if (isMasked(token)) score -= MASK_PENALTY
                else if (token.filter { it.isLetter() } in WORDS) score++
            }
        }
        return score
    }

    /**
     * The best sheet for an explicit record, or [LyricSheet.EMPTY] when there is no candidate. The highest [score]
     * wins. On a tie, [exact] wins if it is one of the best. Otherwise the first of the best in [found] wins, so
     * LRCLIB's own order decides.
     *
     * @param exact The exact lookup's answer, or [LyricSheet.EMPTY] when it had none.
     * @param found The search's candidates, in LRCLIB's order. Sheets without timings are ignored.
     */
    fun pick(exact: LyricSheet, found: List<LyricSheet>): LyricSheet {
        var best = LyricSheet.EMPTY
        var bestScore = Int.MIN_VALUE
        // The exact answer is looked at first, and a later candidate replaces it only with a higher score. That is
        // what makes a tie keep the exact answer.
        if (exact.isSynced) {
            best = exact
            bestScore = score(exact)
        }
        for (sheet in found) {
            if (!sheet.isSynced) continue
            val score = score(sheet)
            if (score > bestScore) {
                best = sheet
                bestScore = score
            }
        }
        return best
    }

    /**
     * Whether a word (already in lower case) is masked: it has a `*`, a `#` after a letter or after another `#`
     * ("f#ck", "###"), or it is a "[censored]" or "(censored)" marker. A `#` at the start of a word, as in "#1", is
     * not a mask.
     */
    private fun isMasked(token: String): Boolean {
        if ('*' in token || "[censored" in token || "(censored" in token) return true
        for (i in 1 until token.length) {
            if (token[i] == '#' && (token[i - 1].isLetter() || token[i - 1] == '#')) return true
        }
        return false
    }
}
