package com.rpm.player.core.library

import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.cloud.CloudMoodList
import com.rpm.player.core.cloud.CloudMoodLists
import com.rpm.player.core.cloud.CloudPlaylist
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.TrackPlaysRow

/** How often a track has been played, on every device, and when last. */
data class Plays(val count: Int, val lastPlayedAt: Long)

/** What a list is called. The words for moods and decades are the app's own, so the screen resolves
 * them; this layer only says which. */
sealed interface ListName {
    /** A generated list of a kind the app names (`Playlists_<kind>`); [fallback] where it does not. */
    data class Mood(val kind: String, val fallback: String) : ListName
    data class Decade(val decade: Int) : ListName
    data class Text(val text: String) : ListName
}

/** A list of tracks to show and play: one the computer generated, or one the person made. */
data class TrackList(
    /** Unique among all lists: "mood/…" or "own/…". */
    val id: String,
    /** The id the cloud stores its picture under (`cloud/playlist-art/{id}`). */
    val storeId: String,
    val name: ListName,
    val tracks: List<CloudTrack>,
    /** How many of the songs the list names this library cannot find: a playlist can be pushed before its audio
     * has finished uploading, so a list can be ahead of the store it plays from. The count is shown instead of
     * quietly listing fewer songs. */
    val missing: Int,
    val folder: List<String>,
    val generated: Boolean,
    /** The generated kind ("Calm", "RightNow"…), null for a made list. */
    val kind: String?,
    /** A plain mood rather than a genre cut or a decade. */
    val plainMood: Boolean,
    /** The genre a generated list is cut to, or null; its decade, or 0. Its picture's colours are seeded from them. */
    val genre: String?,
    val decade: Int,
    /** The sentence under a mood list's name on home, or null: every other list, and a mood whose songs
     * name no genre. Worked out once here, off the main thread, rather than on every drawing of a card. */
    val blurb: MoodLine?,
    /** The stamp the store gave a made list when it was last written (milliseconds, the server's clock), which a
     * write names as the copy it replaces; 0 for a generated list, or a row the store never stamped. */
    val stamp: Long = 0,
    /** How many lists' worth of songs the computer sent for a generated list: more than one means another
     * selection can be shown ([Lists.window]). 1 for a made list. */
    val slices: Int = 1,
)

/**
 * Everything one session's library holds, as the screens read it. Built once per load, off the main
 * thread, by [of]: every cover model is worked out here, because working one out hashes the album's
 * names, and a card that did it on every draw would make home stutter on a TV box.
 *
 * Equal only to itself, not a data class: each load is its own collection. Compose compares a parameter
 * that is a new instance with `equals`, and a data class would compare every album, track and play of two
 * libraries. When the cloud's copy replaces the cache's, every card holding the collection would do that,
 * and the main thread would stop for over 5 seconds on a TV box (an ANR).
 */
class Collection(
    /** Whose collection this is: the person's own, or one another account shares. */
    val shelf: Shelf,
    val albums: List<CloudAlbum>,
    val tracksById: Map<String, CloudTrack>,
    val plays: Map<String, Plays>,
    val lists: List<TrackList>,
    /** The album a track is on, by the wall's own grouping. */
    val albumOf: Map<String, CloudAlbum>,
    private val albumCovers: Map<String, Cover?>,
    private val listCovers: Map<String, ListArt>,
    /** The cloud's art index for [shelf], which [disc] reads. */
    private val index: CoverIndex,
    /** The moods each track is in ("Calm", "Danceable"…), by rpmId, read from every window of the computer's mood
     * lists and not only the one each list shows ([Lists.moods]). A song radio counts two songs in one mood as
     * closer. Empty where the computer sent no lists. */
    val moodsById: Map<String, Set<String>> = emptyMap(),
) {
    private val albumByKey: Map<String, CloudAlbum> by lazy { albums.associateBy { it.key } }
    private val listById: Map<String, TrackList> by lazy { lists.associateBy { it.id } }

    fun album(key: String): CloudAlbum? = albumByKey[key]
    fun list(id: String): TrackList? = listById[id]

    fun cover(album: CloudAlbum): Cover? = albumCovers[album.key]
    fun cover(list: TrackList): ListArt? = listCovers[list.id]

    /** The album's disc picture, or null when the index lists none. Worked out when first asked rather than for
     * every album in [of], because it is asked for only when a record is queued or its page opens, and then kept:
     * a list's queue asks once per track. */
    fun disc(album: CloudAlbum): DiscArt? = synchronized(discs) {
        if (discs.containsKey(album.key)) discs[album.key] else (StarterTracks.discOf(album) ?: discFor(album, index, shelf)).also { discs[album.key] = it }
    }

    private val discs = HashMap<String, DiscArt?>()

    fun playsOf(album: CloudAlbum): Int = album.tracks.sumOf { plays[it.rpmId]?.count ?: 0 }
    fun lastPlayedOf(album: CloudAlbum): Long = album.tracks.maxOfOrNull { plays[it.rpmId]?.lastPlayedAt ?: 0 } ?: 0
    fun addedOf(album: CloudAlbum): Long = album.tracks.maxOfOrNull { it.addedAt } ?: 0

    companion object {
        fun of(
            shelf: Shelf,
            albums: List<CloudAlbum>,
            tracksById: Map<String, CloudTrack>,
            plays: Map<String, Plays>,
            lists: List<TrackList>,
            index: CoverIndex,
            moods: Map<String, Set<String>> = emptyMap(),
        ): Collection {
            val albumOf = buildMap { for (album in albums) for (track in album.tracks) put(track.rpmId, album) }
            // A starter record's cover ships with the app; every other is asked of the cloud.
            val albumCovers = albums.associate { it.key to (StarterTracks.coverOf(it) ?: coverFor(it, index, shelf)) }
            // A generated list uses the photograph shipped with the app, as on the desktop (GeneratedCover's
            // order). The cloud also keeps a drawn picture for that list, but it is not asked for.
            val listCovers = lists.associate { list ->
                list.id to (
                    (if (list.generated) PlaylistPhotos.of(list.kind, list.genre, list.decade) else null)
                        ?: ListCover(shelf, list.storeId, list.tracks.firstNotNullOfOrNull { albumOf[it.rpmId] }?.let { albumCovers[it.key] })
                    )
            }
            return Collection(shelf, albums, tracksById, plays, lists, albumOf, albumCovers, listCovers, index, moods)
        }
    }
}

/** Turns the cloud's rows into the collection's plays and lists, with the same rules as the iOS app
 * (`PlayLog.everythingPlayed`, `GeneratedLists.sets`, `PlaylistSet.resolve`). */
object Lists {

    /** Every device's totals added together; the latest play wins. */
    fun plays(rows: List<TrackPlaysRow>): Map<String, Plays> {
        val merged = HashMap<String, Plays>()
        for (row in rows) {
            val before = merged[row.rpmId]
            merged[row.rpmId] = Plays(
                count = (before?.count ?: 0) + row.plays,
                lastPlayedAt = maxOf(before?.lastPlayedAt ?: 0, row.lastPlayedAt),
            )
        }
        return merged
    }

    /**
     * [plays] with this device's own listens laid over them, so home shows what was played since the cloud was read.
     * Each listen is a track's rpmId and when it was counted (Unix seconds). A listen makes the track's last play at
     * least that late, and the track counted as played at least once. A listen counted after [readAt] (Unix
     * seconds), when the plays were read, is not in them yet and adds one to the count. An earlier one is taken to be in
     * them already, because this device sends its totals soon after each play ([com.rpm.player.core.playback.TrackPlaysSync]).
     */
    fun withListens(plays: Map<String, Plays>, listens: List<Pair<String, Long>>, readAt: Long): Map<String, Plays> {
        if (listens.isEmpty()) return plays
        val laid = HashMap(plays)
        for ((rpmId, at) in listens) {
            val before = laid[rpmId]
            val count = (before?.count ?: 0) + (if (at > readAt) 1 else 0)
            laid[rpmId] = Plays(count = maxOf(count, 1), lastPlayedAt = maxOf(before?.lastPlayedAt ?: 0, at))
        }
        return laid
    }

    /** The generated lists, each showing its first window of tracks. Empty lists are left out: the
     * computer does not push one, and an empty list would promise music and open on nothing. No other
     * floor: the computer only sends a list long enough to show (`SmartPlaylists.LeastToShow`, one song
     * for Favourite tracks, ten for a whole mood). On a borrowed [shelf] Your mix and the listening lists
     * ([OWNER_ONLY]) are left out too: they are about the owner's own listening. The API leaves them out
     * of a borrowed cut, and the iOS app drops them as well. */
    fun generated(
        cut: CloudMoodLists?,
        byId: Map<String, CloudTrack>,
        shelf: Shelf,
        readings: Map<String, Int> = emptyMap(),
        /** The store's answers to the current press of each list, by store id. A list with one shows it instead of its
         * slice of the run. */
        fresh: Map<String, List<String>> = emptyMap(),
    ): List<TrackList> =
        cut?.lists.orEmpty().filter { it.trackIds.isNotEmpty() && !(shelf != Shelf.Own && it.kind in OWNER_ONLY) }.map { list ->
            val storeId = storeIdOf(list)
            val shown = fresh[storeId] ?: window(list.trackIds, list.shows, readings[storeId] ?: 0)
            val tracks = shown.mapNotNull(byId::get)
            TrackList(
                id = "mood/$storeId",
                storeId = storeId,
                name = name(list.kind, list.genre, list.decade, list.title),
                tracks = tracks,
                missing = shown.size - tracks.size,
                folder = list.folder,
                generated = true,
                kind = list.kind,
                plainMood = list.genre == null && list.decade == 0,
                genre = list.genre,
                decade = list.decade,
                blurb = MoodBlurb.of(list.kind, tracks.map { it.tags?.genre }),
                slices = slices(list.trackIds.size, list.shows),
            )
        }.filter { it.tracks.isNotEmpty() }

    /** The id a generated list is kept under: the one the computer sent, or one made from what it is. */
    fun storeIdOf(list: CloudMoodList): String = list.id ?: "${list.kind}|${list.genre.orEmpty()}|${list.decade}"

    /**
     * Which of the plain moods each track is in, by rpmId: every window of every list of those kinds, a genre's cut
     * of a mood included. The desktop's `MoodShelf.MoodsByPath`, which its song radio reads. Decades and the
     * listening lists are not moods and are left out.
     */
    fun moods(cut: CloudMoodLists?): Map<String, Set<String>> {
        val byId = HashMap<String, MutableSet<String>>()
        for (list in cut?.lists.orEmpty()) {
            if (list.kind !in MOOD_KINDS || list.decade > 0) continue
            for (id in list.trackIds) byId.getOrPut(id) { HashSet() }.add(list.kind)
        }
        return byId
    }

    /** The kinds that are a mood, as the desktop's `MoodShelf.MoodsByPath` counts them. */
    private val MOOD_KINDS = setOf(
        "Calm", "Energetic", "Danceable", "Focus", "Sleep",
        "Melancholy", "Happy", "Angry", "Romantic", "Zen", "NewYearsEve", "Christmas",
    )

    /** The person's own playlists, sorted by name, each track found in the catalogue or left out. */
    fun own(playlists: List<CloudPlaylist>, byId: Map<String, CloudTrack>): List<TrackList> =
        playlists.map { list ->
            val tracks = list.trackIds.mapNotNull(byId::get)
            TrackList(
                id = "own/${list.playlistId}",
                storeId = list.playlistId,
                name = ListName.Text(list.name),
                tracks = tracks,
                missing = list.trackIds.size - tracks.size,
                folder = list.folder,
                generated = false,
                kind = null,
                plainMood = false,
                genre = null,
                decade = 0,
                // A playlist somebody made and named says what it is in that name (HomeView's stored cards).
                blurb = null,
                stamp = list.updatedAt,
            )
        }.sortedBy { (it.name as ListName.Text).text.lowercase() }

    /**
     * The desktop's `SmartPlaylists.OwnerOnly`: the kinds it sends for its owner alone. They are also every list the
     * desktop files in its Smart playlists folder, because the desktop sends no other kind that is not a mood
     * (`SmartPlaylists.SentOut`). [PlaylistBrowse] reads them to take these lists out of that folder, and the phone's
     * playlists view reads them to tell these lists from the moods in the line under a list's name.
     */
    val OWNER_ONLY = setOf("RightNow", "OnRepeat", "FavoriteTracks", "FirstListens", "Rediscover", "LovedAndForgotten", "LateNight")

    /**
     * The slice of a run that is one list, as the iPhone's `GeneratedLists.window`: the [reading]th, counting the
     * presses of "another selection", wrapping round. Slices do not overlap, except that the last one reaches back
     * to be a full list: a run of 130 in lists of 50 gives 0..<50, 50..<100 and 80..<130. A run no longer than one
     * list, or a cut that does not say how long a list is ([shows] 0), is the list itself.
     */
    fun window(ids: List<String>, shows: Int, reading: Int = 0): List<String> {
        if (shows <= 0 || ids.size <= shows) return ids
        val count = slices(ids.size, shows)
        val which = ((reading % count) + count) % count
        val start = minOf(which * shows, ids.size - shows)
        return ids.subList(start, start + shows)
    }

    /** How many slices a run of [size] in lists of [shows] gives: 1 when it is one list or less. */
    fun slices(size: Int, shows: Int): Int = if (shows <= 0 || size <= shows) 1 else (size + shows - 1) / shows

    /** A decade by its number, a mood by the app's word for it, a genre cut by the title the computer
     * gave it or its genre. */
    fun name(kind: String, genre: String?, decade: Int, title: String?): ListName {
        if (decade > 0) return ListName.Decade(decade)
        val said = title?.trim().orEmpty()
        if (genre == null) return ListName.Mood(kind, fallback = said.ifEmpty { kind })
        if (said.isNotEmpty()) return ListName.Text(said)
        return if (genre.isNotBlank()) ListName.Text(genre) else ListName.Mood(kind, fallback = kind)
    }
}
