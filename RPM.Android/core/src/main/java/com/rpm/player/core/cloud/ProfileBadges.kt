package com.rpm.player.core.cloud

import androidx.annotation.StringRes
import com.rpm.player.core.R

/**
 * The badges a person can show under their name on their profile page, and what unlocks each. It is the iPhone's
 * `ProfileBadges`: the same ids, the same order and the same rules; change both.
 *
 * Every badge is worked out from the person's own collection on their own phone ([unlocked]), sent to the server
 * (`PUT profile/badges`), and kept there, so a badge once unlocked stays. The server only checks the ids' shape.
 */
object ProfileBadges {
    /** One badge: its id on the wire, its name and what unlocks it. */
    class Badge(val id: String, @StringRes val name: Int, @StringRes val need: Int)

    /** Every badge, in the order the badges page lists them. */
    val all: List<Badge> = listOf(
        Badge("first-spin", R.string.Rpm_Badge_FirstSpin, R.string.Rpm_Badge_FirstSpin_Need),
        Badge("crate-digger", R.string.Rpm_Badge_CrateDigger, R.string.Rpm_Badge_CrateDigger_Need),
        Badge("record-collector", R.string.Rpm_Badge_RecordCollector, R.string.Rpm_Badge_RecordCollector_Need),
        Badge("music-connoisseur", R.string.Rpm_Badge_Connoisseur, R.string.Rpm_Badge_Connoisseur_Need),
        Badge("living-archive", R.string.Rpm_Badge_LivingArchive, R.string.Rpm_Badge_LivingArchive_Need),
        Badge("museum-curator", R.string.Rpm_Badge_MuseumCurator, R.string.Rpm_Badge_MuseumCurator_Need),
        Badge("ten-thousand", R.string.Rpm_Badge_TenThousand, R.string.Rpm_Badge_TenThousand_Need),
        Badge("rock", R.string.Rpm_Badge_Rock, R.string.Rpm_Badge_Rock_Need),
        Badge("jazz", R.string.Rpm_Badge_Jazz, R.string.Rpm_Badge_Jazz_Need),
        Badge("classical", R.string.Rpm_Badge_Classical, R.string.Rpm_Badge_Classical_Need),
        Badge("hiphop", R.string.Rpm_Badge_HipHop, R.string.Rpm_Badge_HipHop_Need),
        Badge("electronic", R.string.Rpm_Badge_Electronic, R.string.Rpm_Badge_Electronic_Need),
        Badge("metal", R.string.Rpm_Badge_Metal, R.string.Rpm_Badge_Metal_Need),
        Badge("pop", R.string.Rpm_Badge_Pop, R.string.Rpm_Badge_Pop_Need),
        Badge("soul", R.string.Rpm_Badge_Soul, R.string.Rpm_Badge_Soul_Need),
        Badge("folk", R.string.Rpm_Badge_Folk, R.string.Rpm_Badge_Folk_Need),
        Badge("blues", R.string.Rpm_Badge_Blues, R.string.Rpm_Badge_Blues_Need),
        Badge("reggae", R.string.Rpm_Badge_Reggae, R.string.Rpm_Badge_Reggae_Need),
        Badge("latin", R.string.Rpm_Badge_Latin, R.string.Rpm_Badge_Latin_Need),
        Badge("genre-hopper", R.string.Rpm_Badge_GenreHopper, R.string.Rpm_Badge_GenreHopper_Need),
        Badge("omnivore", R.string.Rpm_Badge_Omnivore, R.string.Rpm_Badge_Omnivore_Need),
    )

    fun byId(id: String): Badge? = all.firstOrNull { it.id == id }

    /** The badge colours, as 0xRRGGBB; the first is the default. The iPhone's `ProfileBadges.colours`; change both. */
    val colours: List<Long> = listOf(0x7A1FD1, 0x2563EB, 0x0E9F95, 0x2E9E4F, 0xD12A4E, 0xE0701B, 0xD63FA6, 0xB8901F)

    /** Albums needed for each size badge. */
    private val sizes = listOf("first-spin" to 1, "crate-digger" to 50, "record-collector" to 250,
        "music-connoisseur" to 1_000, "living-archive" to 2_500, "museum-curator" to 5_000)

    /** The genre badges, each with the shelves it counts (lower case; see `Genres`). */
    private val genres = listOf(
        "rock" to setOf("rock", "indie", "punk", "noise"),
        "jazz" to setOf("jazz"),
        "classical" to setOf("classical", "neo-classical", "opera"),
        "hiphop" to setOf("hip-hop / rap", "rap", "trap", "old school hip hop"),
        "electronic" to setOf("electronic / dance", "house", "techno", "club"),
        "metal" to setOf("metal"),
        "pop" to setOf("pop", "disco"),
        "soul" to setOf("r&b", "soul", "funk"),
        "folk" to setOf("folk", "country"),
        "blues" to setOf("blues"),
        "reggae" to setOf("reggae"),
        "latin" to setOf("latin"),
    )

    /** A genre badge needs this share of all albums ... */
    private const val GENRE_SHARE = 0.2

    /** ... and at least this many albums of the genre. */
    private const val GENRE_LEAST = 25

    /** A genre counts towards the range badges with this many albums. */
    private const val RANGE_ALBUMS = 5

    /** The shelf for music the app cannot file; it is not a genre. */
    private const val UNCLASSIFIED = "other / unknown"

    /**
     * The badges this collection has unlocked, in [all]'s order.
     *
     * @param genreAlbums how many albums each genre shelf holds, by the shelf's name.
     */
    fun unlocked(albums: Int, tracks: Int, genreAlbums: Map<String, Int>): List<String> {
        val held = HashSet<String>()
        for ((id, least) in sizes) if (albums >= least) held += id
        if (tracks >= 10_000) held += "ten-thousand"
        val byShelf = genreAlbums.mapKeys { it.key.trim().lowercase() }
        for ((id, shelves) in genres) {
            val count = byShelf.filterKeys { it in shelves }.values.sum()
            if (count >= GENRE_LEAST && albums > 0 && count >= albums * GENRE_SHARE) held += id
        }
        val wide = byShelf.count { (shelf, count) -> shelf != UNCLASSIFIED && count >= RANGE_ALBUMS }
        if (wide >= 10) held += "genre-hopper"
        if (wide >= 20) held += "omnivore"
        return all.map { it.id }.filter { it in held }
    }
}
