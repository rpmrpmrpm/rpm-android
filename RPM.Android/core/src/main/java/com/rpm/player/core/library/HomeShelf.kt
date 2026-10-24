package com.rpm.player.core.library

import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.radio.RadioStation
import kotlin.random.Random

/** What heads a banner card, by the row it was taken from. */
enum class Kicker { CONTINUE, ADDED, MADE_FOR_YOU, SUGGESTED, PLAYED, LISTENED }

sealed interface BannerCard {
    data class OfAlbum(val album: CloudAlbum, val kicker: Kicker) : BannerCard
    data class OfList(val list: TrackList) : BannerCard
    /** The station heard most; the desktop's radio card, which has no kicker. */
    data class OfStation(val station: RadioStation) : BannerCard
}

sealed interface QuickPick {
    data class Song(val track: CloudTrack, val album: CloudAlbum?) : QuickPick
    data class OfAlbum(val album: CloudAlbum) : QuickPick
    data class OfList(val list: TrackList) : QuickPick
    data class OfStation(val station: RadioStation) : QuickPick
    /** A song radio on one of the songs played most ([HomeShelf.radioSeeds]): pressing it starts the radio. */
    data class Radio(val track: CloudTrack, val album: CloudAlbum?) : QuickPick
}

/** Home, top to bottom. Every row may be empty, and an empty row is not drawn. */
data class Home(
    val banner: List<BannerCard>,
    val quick: List<QuickPick>,
    val madeForYou: List<TrackList>,
    val listened: List<CloudAlbum>,
    val suggested: List<CloudAlbum>,
    val taste: List<CloudAlbum>,
    val played: List<CloudAlbum>,
    val added: List<CloudAlbum>,
)

/**
 * Pegasus's home, from what the cloud holds. The desktop's `HomeShelf`, `QuickPicks` and the rows of
 * `HomeView`, without the three that need what only the computer has: "Recommended for you" and
 * "Based on your listening" (similar albums and the listening profile), and the radio card, which [withStation] adds.
 *
 * A collection another account shares has none of the person's plays (they are kept by the person's own track ids),
 * so every row worked out from listening is empty there. Its home is made from what the collection itself holds
 * instead (the `Shelf.Borrowed` branches of [build]): Recommended for you seeded from what was added last, as the
 * desktop seeds it on a borrowed shelf; Quick selection from the owner's favourite songs, the collection's records and
 * its lists, so that all its columns are filled; and Made for you from its mood lists, with a different draw at each
 * launch.
 */
object HomeShelf {
    const val BANNER_CARDS = 7

    /** Entries in each of Quick selection's columns, filled down, as the desktop's four. */
    const val QUICK_DOWN = 4

    /** Quick selection's columns on the TV: eight, three of them across the screen, scrolled together from
     * left to right. The desktop shows four columns and does not scroll. */
    const val QUICK_COLUMNS = 8

    const val QUICK_PICKS = QUICK_COLUMNS * QUICK_DOWN
    const val ROW_LENGTH = 20
    /** The album artists a compilation is filed under, in the spellings this library's tags use. */
    private val COMPILATION_ARTISTS = setOf("various artists", "various", "va")

    /** The records the suggestions are taken from: the ones listened to last. */
    private const val SEEDS = 5

    /** The banner's "Most played" card is drawn at random from this many (`BannerReach`). */
    private const val BANNER_REACH = 8

    /** The order generated lists stand in on home (`SmartPlaylists.OnTheHomeScreen`). */
    val HOME_ORDER = listOf(
        "RightNow", "OnRepeat", "FavoriteTracks", "FirstListens", "Rediscover", "LovedAndForgotten",
        "LateNight", "Calm", "Energetic", "Danceable", "Focus", "Sleep",
    )

    fun build(c: Collection, random: Random): Home {
        // Each album's totals are worked out once, by its key. A comparator that summed an album's tracks on
        // every comparison took most of a second per row on a TV box. An album itself as a map key hashes
        // every one of its tracks on each lookup.
        val lastPlayed = c.albums.associate { it.key to c.lastPlayedOf(it) }
        val plays = c.albums.associate { it.key to c.playsOf(it) }
        val addedAt = c.albums.associate { it.key to c.addedOf(it) }
        val listened = c.albums.filter { lastPlayed.getValue(it.key) > 0 }
            .sortedWith(compareByDescending<CloudAlbum> { lastPlayed.getValue(it.key) }.thenBy { it.key })
        val played = c.albums.filter { plays.getValue(it.key) > 0 }
            .sortedWith(compareByDescending<CloudAlbum> { plays.getValue(it.key) }.thenBy { it.key })
        val added = c.albums
            .sortedWith(compareByDescending<CloudAlbum> { addedAt.getValue(it.key) }.thenBy { it.key })
        // The launch's draw: one order of the worked-out rows per launch, as the desktop's launch number gives.
        val draw = random.nextInt()
        val madeForYou = when (c.shelf) {
            Shelf.Own -> c.lists
                .filter { it.generated && it.plainMood && it.kind in HOME_ORDER }
                .sortedBy { HOME_ORDER.indexOf(it.kind) }
            is Shelf.Borrowed -> moodsDrawn(c.lists, draw)
        }

        val traits = Taste.traitsOf(c.albums)
        val byKey = traits.associateBy { it.album.key }
        val listenedRow = listened.take(ROW_LENGTH)
        val listenedKeys = listenedRow.map { it.key }.toSet()
        // The records the suggestions are drawn from: the ones listened to last, or on a borrowed shelf, which has no
        // listening of the person's, the ones added last (the desktop's HomeShelf.Suggested does the same).
        val seeds = when (c.shelf) {
            Shelf.Own -> listened.take(SEEDS)
            is Shelf.Borrowed -> added.take(SEEDS)
        }
        val suggested = Taste.suggested(seeds.mapNotNull { byKey[it.key] }, traits, listenedKeys, ROW_LENGTH, draw)
        val taste = Taste.fromTaste(plays, traits, listenedKeys + suggested.map { it.key }, ROW_LENGTH, draw)

        val quick = when (c.shelf) {
            // The lists as the iPhone deals them: the first generated lists, in the order the collection holds them.
            Shelf.Own -> quick(c, played, c.lists.filter { it.generated }.take(QUICK_LISTS))
            is Shelf.Borrowed -> recommendedQuick(c, madeForYou, shown = (suggested + added.take(ROW_LENGTH)).mapTo(HashSet()) { it.key }, draw)
        }

        return Home(
            banner = banner(listened, added, played, suggested, madeForYou, random),
            quick = quick,
            madeForYou = madeForYou,
            listened = listenedRow,
            suggested = suggested,
            taste = taste,
            played = played.take(ROW_LENGTH),
            added = added.take(ROW_LENGTH),
        )
    }

    /** One card from each source in turn, never the same record twice, and never a compilation of
     * various artists, which has no face to put on a banner. */
    private fun banner(
        listened: List<CloudAlbum>,
        added: List<CloudAlbum>,
        played: List<CloudAlbum>,
        suggested: List<CloudAlbum>,
        lists: List<TrackList>,
        random: Random,
    ): List<BannerCard> {
        val used = HashSet<String>()
        fun fit(album: CloudAlbum) = album.key !in used && album.artist.trim().lowercase() !in COMPILATION_ARTISTS
        val sources: List<() -> BannerCard?> = listOf(
            { listened.firstOrNull(::fit)?.let { BannerCard.OfAlbum(it, Kicker.CONTINUE) } },
            { added.firstOrNull(::fit)?.let { BannerCard.OfAlbum(it, Kicker.ADDED) } },
            { lists.firstOrNull { "list:" + it.id !in used }?.let { BannerCard.OfList(it) } },
            { suggested.firstOrNull(::fit)?.let { BannerCard.OfAlbum(it, Kicker.SUGGESTED) } },
            { played.take(BANNER_REACH).filter(::fit).randomOrNull(random)?.let { BannerCard.OfAlbum(it, Kicker.PLAYED) } },
            { listened.firstOrNull(::fit)?.let { BannerCard.OfAlbum(it, Kicker.LISTENED) } },
        )
        val cards = mutableListOf<BannerCard>()
        var round = 0
        while (cards.size < BANNER_CARDS && round < BANNER_CARDS) {
            var any = false
            for (source in sources) {
                if (cards.size >= BANNER_CARDS) break
                val card = source() ?: continue
                used += when (card) {
                    is BannerCard.OfAlbum -> card.album.key
                    is BannerCard.OfList -> "list:" + card.list.id
                    is BannerCard.OfStation -> "station:" + card.station.uuid
                }
                cards += card
                any = true
            }
            if (!any) break
            round++
        }
        return cards
    }

    /** [QUICK_PICKS] picks: a radio on the songs played most ([radioSeeds]) first, then the most played song,
     * album and list dealt in turn. */
    private fun quick(c: Collection, played: List<CloudAlbum>, lists: List<TrackList>): List<QuickPick> {
        val most = MostPlayed.songs(c.tracksById.values, c.plays, Int.MAX_VALUE)
        val songs = most.map { QuickPick.Song(it, c.albumOf[it.rpmId]) }
        val radios = radioSeeds(most).map { QuickPick.Radio(it, c.albumOf[it.rpmId]) }
        return (radios + deal(songs, played.map { QuickPick.OfAlbum(it) }, lists.map { QuickPick.OfList(it) })).take(QUICK_PICKS)
    }

    /** How many generated lists Quick selection deals from on the person's own shelf (`HomeSongs` on iPhone). */
    private const val QUICK_LISTS = 8

    /** How many song radios Quick selection offers at most. The desktop's `QuickPicks.MostRadios`. */
    const val MOST_RADIOS = 2

    /**
     * The songs to offer a radio on, from [mostPlayed] (most played first): the first, and the next one by another
     * act, because two radios on one act are one radio. A song whose tags name no act is never taken for another such
     * song. The desktop's `QuickPicks.RadioSeeds`.
     */
    fun radioSeeds(mostPlayed: List<CloudTrack>): List<CloudTrack> {
        val seeds = ArrayList<CloudTrack>(MOST_RADIOS)
        val acts = HashSet<String>()
        for (track in mostPlayed) {
            if (seeds.size == MOST_RADIOS) break
            val act = (track.tags?.albumArtist?.takeIf { it.isNotBlank() } ?: track.artist).orEmpty().trim().lowercase()
            if (act.isNotEmpty() && !acts.add(act)) continue
            seeds += track
        }
        return seeds
    }

    /**
     * Quick selection on a borrowed shelf, where nothing has been played: recommendations, dealt song, record and list
     * in turn as the played ones are, and drawn again at each launch ([draw]).
     *
     * - Songs: the owner's favourite songs, then one song from each record of the records below.
     * - Records: the ones holding the owner's favourite songs, then the rest of the collection, the ones the rows under
     *   it already show ([shown]: Recommended for you and Recently added) last.
     * - Lists: [moods] (Made for you, in its own draw), then the owner's other lists, in another draw.
     */
    private fun recommendedQuick(c: Collection, moods: List<TrackList>, shown: Set<String>, draw: Int): List<QuickPick> {
        val loved = c.albums.filter { album -> album.tracks.any { it.tags?.favorite == true } }
        val lovedKeys = loved.mapTo(HashSet()) { it.key }
        val rest = c.albums.filter { it.key !in lovedKeys }
        val drawn = Taste.shuffled(loved.toMutableList(), draw, "quick-loved") + Taste.shuffled(rest.toMutableList(), draw, "quick-rest")
        val records = (drawn.filter { it.key !in shown } + drawn.filter { it.key in shown }).filter { it.album.isNotBlank() }

        val favourites = c.tracksById.values.filter { it.tags?.favorite == true }.sortedBy { it.rpmId }
        // Only as many records as Quick selection could take songs from: a collection has thousands.
        val fromRecords = records.take(QUICK_PICKS).mapNotNull { album -> Taste.shuffled(album.tracks.toMutableList(), draw, album.key).firstOrNull() }
        // One title by one artist once, so a song on a record and on its best-of is not offered twice.
        val heard = HashSet<String>()
        val songs = (Taste.shuffled(favourites.toMutableList(), draw, "quick-songs") + fromRecords)
            .filter { heard.add(Taste.fold(it.artist) + "|" + Taste.fold(it.title)) }
            .map { QuickPick.Song(it, c.albumOf[it.rpmId]) }

        val moodIds = moods.mapTo(HashSet()) { it.id }
        val otherLists = Taste.shuffled(c.lists.filter { it.id !in moodIds }.sortedBy { it.id }.toMutableList(), draw, "quick-lists")
        return deal(songs, records.map { QuickPick.OfAlbum(it) }, (moods + otherLists).map { QuickPick.OfList(it) })
    }

    /**
     * Made for you on a borrowed shelf: the mood lists the owner's computer sent (Calm, Energetic, Danceable, Focus and
     * Sleep, each also cut to genres), one of each mood in turn so the row opens on different moods, the moods in an
     * order of [draw] and each mood's lists in another; then the generated lists of other kinds (decades, classical).
     * At most [ROW_LENGTH]. Each launch draws again, so the row changes from one opening of the app to the next.
     */
    private fun moodsDrawn(lists: List<TrackList>, draw: Int): List<TrackList> {
        val generated = lists.filter { it.generated }
        val moodKinds = Mood.entries.map { it.kind }.toSet()
        val byMood = generated.filter { it.kind in moodKinds }.groupBy { it.kind.orEmpty() }
        val moods = Taste.shuffled(byMood.keys.sorted().toMutableList(), draw, "moods")
            .map { kind -> Taste.shuffled(byMood.getValue(kind).sortedBy { it.id }.toMutableList(), draw, kind) }
        val dealt = mutableListOf<TrackList>()
        var round = 0
        while (dealt.size < ROW_LENGTH && moods.any { round < it.size }) {
            for (kind in moods) if (dealt.size < ROW_LENGTH) kind.getOrNull(round)?.let { dealt += it }
            round++
        }
        val others = Taste.shuffled(generated.filter { it.kind !in moodKinds }.sortedBy { it.id }.toMutableList(), draw, "other-lists")
        return (dealt + others).take(ROW_LENGTH)
    }

    /** [QUICK_PICKS] picks dealt in turn from [songs], [albums] and [picks], a kind that runs out skipped. */
    private fun deal(songs: List<QuickPick>, albums: List<QuickPick>, picks: List<QuickPick>): List<QuickPick> {
        val dealt = mutableListOf<QuickPick>()
        var i = 0
        while (dealt.size < QUICK_PICKS && (i < songs.size || i < albums.size || i < picks.size)) {
            songs.getOrNull(i)?.let { if (dealt.size < QUICK_PICKS) dealt += it }
            albums.getOrNull(i)?.let { if (dealt.size < QUICK_PICKS) dealt += it }
            picks.getOrNull(i)?.let { if (dealt.size < QUICK_PICKS) dealt += it }
            i++
        }
        return dealt
    }

    /**
     * The banner with the station heard most added as its radio card: the desktop deals it last of seven
     * sources, so it lands after the first round, here after the first six cards, and the banner keeps
     * its seven.
     */
    fun withStation(banner: List<BannerCard>, station: RadioStation?): List<BannerCard> {
        if (station == null) return banner
        val at = minOf(6, banner.size)
        return (banner.take(at) + BannerCard.OfStation(station) + banner.drop(at)).take(BANNER_CARDS)
    }

    /** Quick selection dealt again with the stations heard most as its fourth kind (QuickPicks: song,
     * album, playlist, station in turn, a kind that runs out skipped). */
    fun withStations(quick: List<QuickPick>, stations: List<RadioStation>): List<QuickPick> {
        if (stations.isEmpty()) return quick
        // The radios stay first, ahead of the deal, as they were put.
        val radios = quick.filterIsInstance<QuickPick.Radio>()
        val kinds = listOf(
            quick.filterIsInstance<QuickPick.Song>(),
            quick.filterIsInstance<QuickPick.OfAlbum>(),
            quick.filterIsInstance<QuickPick.OfList>(),
            stations.map { QuickPick.OfStation(it) },
        )
        val dealt = radios.toMutableList<QuickPick>()
        var i = 0
        while (dealt.size < QUICK_PICKS && kinds.any { i < it.size }) {
            for (kind in kinds) if (dealt.size < QUICK_PICKS) kind.getOrNull(i)?.let { dealt += it }
            i++
        }
        return dealt
    }
}
