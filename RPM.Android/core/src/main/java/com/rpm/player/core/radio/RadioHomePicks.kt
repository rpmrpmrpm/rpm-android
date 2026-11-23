package com.rpm.player.core.radio

/** Why a station stands on the radio home: the person pinned it, saved it, heard it often, it is local, or it is suggested. */
enum class StationWhy { Pinned, Saved, Heard, Local, ForYou }

/** One station on the radio home, with the reason it is there. */
data class StationPick(val station: RadioStation, val why: StationWhy)

/**
 * What the phone's radio home shows, worked out from the lists it already has. There is no network here: the lists come
 * from [RadioRepository] (the stations heard most, the local stations, the saved stations, the directory's answers) and
 * the pinned stations from the phone's settings.
 *
 * A station is listed once on each part of the page. Two entries count as the same station when one's uuid is the
 * other's, or is one of the entries folded into it ([RadioStation.alsoFiledAs]).
 */
object RadioHomePicks {
    /** The most cards on the banner. */
    const val BANNER_MOST = 6

    /** The most lines on the quick selection: four panels of four, as on home. */
    const val QUICK_MOST = 16

    /** The most suggested stations on the quick selection. They come after the pinned and the saved stations. */
    const val SUGGESTED_MOST = 8

    /** How many of the stations heard most are read for the person's taste. */
    const val TASTE_FROM = 20

    /** How many genres the suggestions are asked for. */
    const val TASTE_GENRES = 2

    /**
     * The banner: the stations heard most and the local stations, one of each in turn, heard first. When one list runs
     * out, the other fills the banner.
     */
    fun banner(heard: List<RadioStation>, local: List<RadioStation>, most: Int = BANNER_MOST): List<StationPick> {
        val picks = Picks(most)
        val max = maxOf(heard.size, local.size)
        for (at in 0 until max) {
            heard.getOrNull(at)?.let { picks.add(it, StationWhy.Heard) }
            local.getOrNull(at)?.let { picks.add(it, StationWhy.Local) }
            if (picks.full) break
        }
        return picks.list
    }

    /** The quick selection: what the person pinned, in the order they pinned it, then their saved stations, then the suggestions. */
    fun quick(pinned: List<RadioStation>, saved: List<RadioStation>, suggested: List<RadioStation>, most: Int = QUICK_MOST): List<StationPick> {
        val picks = Picks(most)
        pinned.forEach { picks.add(it, StationWhy.Pinned) }
        saved.forEach { picks.add(it, StationWhy.Saved) }
        suggested.forEach { picks.add(it, StationWhy.ForYou) }
        return picks.list
    }

    /**
     * The genres that [stations] play most, most first, at most [most]. Each station counts once for each genre its tags
     * name ([RadioGenres.names]). Two genres with the same count keep the order in which they were first met, so the
     * stations given first decide: pass the pinned and saved stations before the ones heard.
     */
    fun taste(stations: List<RadioStation>, most: Int = TASTE_GENRES): List<String> {
        val counts = LinkedHashMap<String, Int>()
        for (station in stations) {
            for (genre in station.shownTags.flatMap(RadioGenres::names).distinct()) counts[genre] = (counts[genre] ?: 0) + 1
        }
        return counts.entries.sortedByDescending { it.value }.take(most).map { it.key }
    }

    /**
     * Suggestions from [answers], one list for each genre of the person's taste: one station from each list in turn,
     * leaving out the stations in [known] (those already pinned, saved or heard), at most [most].
     */
    fun suggested(answers: List<List<RadioStation>>, known: List<RadioStation>, most: Int = SUGGESTED_MOST): List<RadioStation> {
        val seen = Seen()
        known.forEach(seen::add)
        val out = ArrayList<RadioStation>()
        val max = answers.maxOfOrNull { it.size } ?: 0
        for (at in 0 until max) {
            for (answer in answers) {
                val station = answer.getOrNull(at) ?: continue
                if (seen.add(station)) out += station
                if (out.size >= most) return out
            }
        }
        return out
    }

    /** The stations of [stations] that are not in [without]. */
    fun without(stations: List<RadioStation>, without: List<RadioStation>): List<RadioStation> {
        val seen = Seen()
        without.forEach(seen::add)
        return stations.filter(seen::add)
    }

    /** The ids already taken, each station by its uuid and the uuids folded into it, all in lower case. */
    private class Seen {
        private val ids = HashSet<String>()

        /** Takes [station] and returns true, or returns false when it is a station already taken. */
        fun add(station: RadioStation): Boolean {
            val all = (listOf(station.uuid) + station.alsoFiledAs).map { it.lowercase() }
            if (all.any { it in ids }) return false
            ids += all
            return true
        }
    }

    /** A list of picks with no station twice, at most [most] long. */
    private class Picks(private val most: Int) {
        private val seen = Seen()
        val list = ArrayList<StationPick>()
        val full get() = list.size >= most

        fun add(station: RadioStation, why: StationWhy) {
            if (full || !seen.add(station)) return
            list += StationPick(station, why)
        }
    }
}
