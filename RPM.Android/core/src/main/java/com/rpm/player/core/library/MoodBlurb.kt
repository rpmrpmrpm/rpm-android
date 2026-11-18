package com.rpm.player.core.library

/** The moods that carry a sentence under their name, by the kind the computer sends. On screen Happy is
 * "Feel Good", Angry "Rage", Romantic "Date Night" and NewYearsEve "New Year's Eve". */
enum class Mood(val kind: String) {
    Calm("Calm"),
    Energetic("Energetic"),
    Danceable("Danceable"),
    Focus("Focus"),
    Sleep("Sleep"),
    Melancholy("Melancholy"),
    Happy("Happy"),
    Angry("Angry"),
    Romantic("Romantic"),
    Zen("Zen"),
    NewYearsEve("NewYearsEve");

    /** Whether the sentence names no genres: Sleep is cut across every genre, Zen is ambient and new age
     * records only, and New Year's Eve is a night, not a cut by genre. The desktop's `MoodBlurbText`. */
    val namesNoGenres: Boolean get() = this == Sleep || this == Zen || this == NewYearsEve
}

/** The sentence under a mood list's name, as parts: which mood, and the kinds of music it names, as they
 * are said in running text, commonest first. Empty for a mood that names none ([Mood.namesNoGenres]). The
 * screen puts the words round them (`Playlists_Blurb…`), as the desktop's `MoodBlurbText` does. */
data class MoodLine(val mood: Mood, val genres: List<String>)

/**
 * Which kinds of music a mood list's sentence names. It follows the desktop's `MoodBlurb` (RPM.Core) and the
 * phone's `MoodBlurb`, so one list says the same thing in every app.
 *
 * Only the moods get one. "Most played" says what it is in its own title. A mood is different: "Chill"
 * is a word, and which part of the collection it drew from cannot be seen from the name.
 */
object MoodBlurb {
    /** How many kinds of music a sentence names. The desktop's `MostGenres`. */
    const val MOST_GENRES = 3

    /** The fewest songs of a genre before it is named: a twentieth of the list. The desktop's `LeastShare`. */
    const val LEAST_SHARE = 0.05

    /**
     * The sentence's parts for a generated list, or null where it has none: every kind but the moods, and a
     * mood whose songs name no genre worth naming. Sleep, Zen and New Year's Eve name none and always have
     * their sentence.
     *
     * [genres] is the raw genre tag of each song the list shows, in list order: the songs printed under
     * the sentence, so the two cannot disagree.
     */
    fun of(kind: String?, genres: List<String?>): MoodLine? {
        val mood = Mood.entries.firstOrNull { it.kind == kind } ?: return null
        if (mood.namesNoGenres) return MoodLine(mood, emptyList())
        val named = spoken(genres)
        return if (named.isEmpty()) null else MoodLine(mood, named)
    }

    /** The kinds of music worth naming, commonest first, as they are said in running text. The tags go
     * through [Genres.match] first, so a list holding "Deep House" and "Acid House" is one house list
     * rather than two rare ones. */
    fun spoken(genres: List<String?>): List<String> {
        if (genres.isEmpty()) return emptyList()
        val counted = HashMap<String, Int>()
        for (tag in genres) {
            val canonical = Genres.match(tag) ?: continue
            if (canonical == Genres.UNKNOWN) continue
            counted[canonical] = (counted[canonical] ?: 0) + 1
        }
        val least = genres.size * LEAST_SHARE
        return counted.entries
            .filter { it.value >= least }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MOST_GENRES)
            .map { Genres.spoken(it.key) }
    }
}
