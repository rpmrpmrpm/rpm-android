package com.rpm.player.core.library

/**
 * Which generated list the home banner shows: the mood the hour asks for ([MoodClock]), cut to the kind of music this
 * listener plays at that time of day. The desktop's `MoodPick`.
 *
 * The clock is a general guess about people. What this device has played in this part of the day is a fact about the
 * listener ([com.rpm.player.core.playback.DayPartPlays]). A listener who plays jazz late gets the quiet jazz at
 * midnight, and a listener who plays techno gets the quiet techno.
 *
 * On one day in [OTHER_DAY_IN] the pick steps down its own ranking, to another cut of the same mood, and each such day
 * steps one further. A banner that gives the same answer every day stops being looked at. The day is chosen by the
 * date only, so the list does not change while somebody looks at it.
 */
object MoodPick {
    /** How often the banner shows something other than the usual answer: one day in seven. */
    const val OTHER_DAY_IN = 7

    /** The fewest songs a mood list over the whole collection may hold and still be shown. The desktop's
     * `SmartPlaylists.LeastForWholeShelf` for the five moods the clock names. */
    const val LEAST_FOR_WHOLE_SHELF = 10

    /** The same for a mood cut to one genre: a smaller question, so a smaller floor (`LeastForNarrowedRow`). */
    const val LEAST_FOR_GENRE_CUT = 6

    /**
     * The list to show, or null when [offered] holds none of the [moods].
     *
     * @param moods the moods that suit the hour, best first: [MoodClock.order].
     * @param genres what this listener plays in this part of the day, most played first. Empty is normal: with no
     *   listening yet, the whole collection's cut of the mood is shown.
     * @param offered the mood lists this collection has: [offered].
     * @param dayNumber a number that goes up by one each day (the day of the year). It decides which days are the
     *   one in seven, and which other list is shown on them.
     */
    fun pick(moods: List<Mood>, genres: List<String>, offered: List<TrackList>, dayNumber: Int): TrackList? {
        for (mood in moods) {
            val group = group(mood, genres, offered)
            if (group.isEmpty()) continue
            return group[which(group.size, dayNumber)]
        }
        return null
    }

    /**
     * Every list [offered] holds for [mood], best first: the cuts of the mood to the genres this listener plays, in
     * order of plays, then the whole collection's list of the mood.
     *
     * The whole collection's list is last: a listener who plays jazz at night is better served by the quiet jazz than
     * by every quiet record. It stays in the group, so a listener with no genres still gets a list, and the one day
     * in seven has somewhere to step to when a mood has one cut.
     */
    fun group(mood: Mood, genres: List<String>, offered: List<TrackList>): List<TrackList> {
        val ofMood = offered.filter { it.kind == mood.kind }
        val group = ArrayList<TrackList>()
        for (genre in genres) {
            if (genre.isEmpty()) continue
            ofMood.firstOrNull { !it.plainMood && it.genre == genre }?.let { group += it }
        }
        ofMood.firstOrNull { it.plainMood }?.let { group += it }
        return group
    }

    /**
     * Which of a group of [groupSize] to show on [dayNumber]: 0 on six days in seven. On the seventh it steps down the
     * ranking, and on each such day after that one further, wrapping round. A group of one has nowhere to step.
     */
    fun which(groupSize: Int, dayNumber: Int): Int {
        if (groupSize <= 1) return 0
        // Floored, so a day number below nought still steps forwards.
        if (Math.floorMod(dayNumber, OTHER_DAY_IN) != 0) return 0
        return 1 + Math.floorMod(Math.floorDiv(dayNumber, OTHER_DAY_IN), groupSize - 1)
    }

    /**
     * The list the card of [whole]'s mood on the Made for you row opens: the cut of the mood to the genre this
     * listener plays most at this time of day, or the whole collection's list where no cut fits ([group]). The
     * desktop's `HomeView.Cut`. Only the moods that are cut to genres ([BROKEN_OUT_BY_GENRE]); any other mood keeps
     * [whole].
     *
     * Never the list the banner shows ([taken]) where the group has another: the banner takes the same group's first on
     * six days in seven, and the same list twice, one above the other, says nothing new. A group of one keeps its only
     * list.
     */
    fun cut(whole: TrackList, genres: List<String>, offered: List<TrackList>, taken: TrackList?): TrackList {
        val mood = Mood.entries.firstOrNull { it.kind == whole.kind }?.takeIf { it in BROKEN_OUT_BY_GENRE } ?: return whole
        val group = group(mood, genres, offered)
        if (group.isEmpty()) return whole
        if (group[0].id != taken?.id) return group[0]
        return if (group.size > 1) group[1] else group[0]
    }

    /** The moods the computer also cuts to each genre: the desktop's `SmartPlaylists.BrokenOutByGenre`. Sleep is cut
     * over the whole collection only; Zen, New Year's Eve and Merry Christmas have no genre reading. */
    val BROKEN_OUT_BY_GENRE = setOf(Mood.Calm, Mood.Energetic, Mood.Danceable, Mood.Focus, Mood.Melancholy, Mood.Happy, Mood.Angry, Mood.Romantic)

    /**
     * The mood lists among [lists] that a pick or a cut may take: the generated lists of a mood ([Mood]), over the whole
     * collection or cut to a genre (never to a decade, which is not a mood), and long enough to show. The desktop's
     * `OfferedGeneratedLists(SmartPlaylists.Moods, withDecades: false)` with its floor.
     */
    fun offered(lists: List<TrackList>): List<TrackList> = lists.filter { list ->
        list.generated && list.decade == 0 && list.kind in MOOD_KINDS &&
            list.tracks.size >= (if (list.plainMood) LEAST_FOR_WHOLE_SHELF else LEAST_FOR_GENRE_CUT)
    }

    private val MOOD_KINDS = Mood.entries.mapTo(HashSet()) { it.kind }
}
