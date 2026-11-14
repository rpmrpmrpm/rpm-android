package com.rpm.player.core.library

import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.cloud.CloudClient
import com.rpm.player.core.cloud.CloudPlaylist
import com.rpm.player.core.cloud.PlaylistPut
import com.rpm.player.core.cloud.PlaylistWrite
import com.rpm.player.core.cloud.Shelf
import kotlinx.coroutines.CancellationException

/** Why a playlist was not written. Each has the sentence the iPhone shows for it (`PlaylistWriteError.userMessage`). */
enum class PlaylistRefusal {
    /** The list belongs to another account, which this one may not write to. */
    NotYours,

    /** The list was on screen, but the store no longer holds it. */
    Gone,

    /** A list the computer cut. Its id names nothing in the store, and the computer replaces it on its next run. */
    Generated,

    /** Refused twice in a row: another device keeps writing the same list. */
    Busy,

    /** The list names songs this phone cannot see. A write sends the whole order, so it would drop them. */
    Unseen,

    /** A rename to a name with nothing in it. The name card does not offer Save then; this covers any case that gets
     * past it. */
    Nameless,

    /** No session, no network, or the store said no: the general sentence for the write that failed. */
    Failed,
}

/** What adding songs to a playlist came to. */
sealed interface SongsAdded {
    /** [count] songs were put at the end of the list. */
    data class Added(val count: Int) : SongsAdded

    /** Every song was already in the list, so nothing was written. A second copy is never added unasked. */
    data object AlreadyIn : SongsAdded

    data class Refused(val why: PlaylistRefusal) : SongsAdded
}

/**
 * Where the person's own playlists are kept: the cloud, through [CloudClient]. The writer reads and writes through
 * this and not the client itself, so its rules can be tested against a store held in memory, as the iPhone's
 * `PlaylistStore` is.
 */
interface PlaylistStore {
    /** The playlists on the person's own shelf, as the store holds them now. */
    suspend fun playlists(): List<CloudPlaylist>

    /** Writes one playlist as a whole, if the stored copy still has [PlaylistWrite.ifUnchangedSince]. */
    suspend fun putPlaylist(playlistId: String, write: PlaylistWrite): PlaylistPut

    /** Removes a playlist. One that is already gone is not a failure. */
    suspend fun deletePlaylist(playlistId: String)
}

/** The cloud as a [PlaylistStore]. */
private class CloudPlaylistStore(private val cloud: CloudClient) : PlaylistStore {
    override suspend fun playlists() = cloud.playlists(Shelf.Own)
    override suspend fun putPlaylist(playlistId: String, write: PlaylistWrite) = cloud.putPlaylist(playlistId, write)
    override suspend fun deletePlaylist(playlistId: String) = cloud.deletePlaylist(playlistId)
}

/**
 * Makes a playlist, adds songs to one, changes the order of one, renames one and removes one, on the person's own
 * shelf. It matches the iPhone's `PlaylistWriter` (RPMLibrary/PlaylistWriter.swift) and its callers in
 * `LibrarySession`.
 *
 * **Every write names the copy it replaces** (`ifUnchangedSince`). When another device wrote first, the store
 * refuses and hands back what it holds, and the two are merged ([PlaylistMerge]) and written once more. A second
 * refusal is [PlaylistRefusal.Busy], so the phone never keeps writing against somebody else's edit.
 *
 * Each write the store takes is recorded with the repository ([LibraryRepository.edited]), which lays it over the
 * collection on screen, so the page shows it at once and a load that started before it does not take it back. A
 * removal is recorded the same way ([OwnEdits.removedHere]), so a read answered before it does not bring the list
 * back.
 */
class PlaylistWriter(
    private val store: PlaylistStore,
    /** The signed-in account's id, or null when there is no session. */
    private val user: () -> String?,
    /** Records a write the store took for that account ([LibraryRepository.edited]). */
    private val edited: (String, (OwnEdits) -> OwnEdits) -> Unit,
    private val clock: () -> Long,
    private val mint: () -> String = { java.util.UUID.randomUUID().toString().lowercase() },
) {
    constructor(
        cloud: CloudClient,
        auth: AuthClient,
        library: LibraryRepository,
        clock: () -> Long,
        mint: () -> String = { java.util.UUID.randomUUID().toString().lowercase() },
    ) : this(CloudPlaylistStore(cloud), { auth.session.value?.userId }, library::edited, clock, mint)

    /**
     * Makes a playlist called [name] in [folder] (outermost first; empty is the top of the tree) holding [rpmIds]
     * in that order. It may hold none, to be filled later. It goes under a new id and is written only if nothing is
     * stored there (`ifUnchangedSince` 0), so a clash is a failure and never somebody's playlist overwritten.
     *
     * @return null when it was made.
     */
    suspend fun make(name: String, folder: List<String>, rpmIds: List<String>): PlaylistRefusal? {
        val user = user() ?: return PlaylistRefusal.Failed
        // Trimmed, because a trailing space makes the name look like another playlist's.
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return PlaylistRefusal.Failed
        val id = mint()
        val made = clock()
        val put = orNull { store.putPlaylist(id, PlaylistWrite(trimmed, rpmIds, folder, ifUnchangedSince = 0)) }
        if (put !is PlaylistPut.Written) return PlaylistRefusal.Failed
        edited(user) {
            it.copy(
                playlists = it.playlists + (id to CloudPlaylist(id, trimmed, rpmIds, folder, put.updatedAt)),
                made = it.made + (id to made),
            )
        }
        return null
    }

    /**
     * Puts [rpmIds] at the end of [list], leaving any song the list already holds where it is: somebody who picks
     * twelve songs does not want a second copy of the three already there. The list is read from the store first,
     * not taken from the page, which can be several changes behind.
     */
    suspend fun add(list: TrackList, shelf: Shelf, rpmIds: List<String>): SongsAdded {
        if (shelf != Shelf.Own) return SongsAdded.Refused(PlaylistRefusal.NotYours)
        if (list.generated) return SongsAdded.Refused(PlaylistRefusal.Generated)
        val user = user() ?: return SongsAdded.Refused(PlaylistRefusal.Failed)
        val began = clock()
        val stored = orNull { store.playlists() } ?: return SongsAdded.Refused(PlaylistRefusal.Failed)
        var current = stored.firstOrNull { it.playlistId == list.storeId } ?: return SongsAdded.Refused(PlaylistRefusal.Gone)
        repeat(2) {
            val have = current.trackIds.toHashSet()
            // Each song once, even when it was picked twice.
            val added = rpmIds.filter { have.add(it) }
            if (added.isEmpty()) return SongsAdded.AlreadyIn
            val wanted = current.trackIds + added
            // No precondition where the store never gave a stamp: one made up would refuse every write.
            val write = PlaylistWrite(current.name, wanted, current.folder, current.updatedAt.takeIf { it > 0 })
            when (val put = orNull { store.putPlaylist(current.playlistId, write) } ?: return SongsAdded.Refused(PlaylistRefusal.Failed)) {
                is PlaylistPut.Written -> {
                    record(user, current.copy(trackIds = wanted, updatedAt = put.updatedAt), began)
                    return SongsAdded.Added(added.size)
                }
                // Another device wrote first. Adding to the end merges with anything, so the songs are added again to
                // the copy it stored: the same answer as a three-way merge, without one.
                is PlaylistPut.Conflict -> current = put.stored ?: return SongsAdded.Refused(PlaylistRefusal.Busy)
            }
        }
        return SongsAdded.Refused(PlaylistRefusal.Busy)
    }

    /**
     * Writes [order] (every song of [list], by id) as [list]'s running order. The whole list is sent, because that
     * is the only form the store takes, so a list with songs this phone cannot see is refused rather than shortened.
     * The name and the folder come from the store when another device wrote first ([writeAgainst]).
     *
     * @return null when it was written.
     */
    suspend fun reorder(list: TrackList, shelf: Shelf, order: List<String>): PlaylistRefusal? {
        if (shelf != Shelf.Own) return PlaylistRefusal.NotYours
        if (list.generated) return PlaylistRefusal.Generated
        if (list.missing > 0) return PlaylistRefusal.Unseen
        val user = user() ?: return PlaylistRefusal.Failed
        val name = (list.name as? ListName.Text)?.text ?: return PlaylistRefusal.Failed
        return writeAgainst(
            user,
            CloudPlaylist(list.storeId, name, order, list.folder, 0),
            ancestor = list.tracks.map { it.rpmId },
            stamp = list.stamp,
            name = NameFrom.TheStore,
            began = clock(),
        )
    }

    /**
     * Gives [list] the name [name], trimmed (the iPhone's `PlaylistWriter.rename`).
     *
     * The songs and the folder are taken from the store, read again here, and not from the page. A page can stay open
     * for a long time, and a rename must not write an old running order back. A name that is already the list's
     * writes nothing: a write would only give the row a new stamp, and every other device would read that as a
     * change.
     *
     * @return null when the list has the name.
     */
    suspend fun rename(list: TrackList, shelf: Shelf, name: String): PlaylistRefusal? {
        if (shelf != Shelf.Own) return PlaylistRefusal.NotYours
        if (list.generated) return PlaylistRefusal.Generated
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return PlaylistRefusal.Nameless
        val user = user() ?: return PlaylistRefusal.Failed
        val began = clock()
        val stored = orNull { store.playlists() } ?: return PlaylistRefusal.Failed
        val current = stored.firstOrNull { it.playlistId == list.storeId } ?: return PlaylistRefusal.Gone
        if (current.name == trimmed) {
            record(user, current, began)
            return null
        }
        return writeAgainst(
            user,
            current.copy(name = trimmed, updatedAt = 0),
            ancestor = current.trackIds,
            stamp = current.updatedAt,
            name = NameFrom.Renamed(trimmed),
            began = began,
        )
    }

    /**
     * Removes [list] from the cloud (the iPhone's `PlaylistWriter.delete`). The songs it names stay in the collection:
     * they belong to records, and a song can be on other playlists too. The list goes from the screen once the store
     * has dropped it, not before: a removal that failed must not look as if it worked.
     *
     * @return null when it is gone.
     */
    suspend fun delete(list: TrackList, shelf: Shelf): PlaylistRefusal? {
        if (shelf != Shelf.Own) return PlaylistRefusal.NotYours
        if (list.generated) return PlaylistRefusal.Generated
        val user = user() ?: return PlaylistRefusal.Failed
        orNull { store.deletePlaylist(list.storeId) } ?: return PlaylistRefusal.Failed
        val at = clock()
        edited(user) { it.removedHere(list.storeId, at) }
        return null
    }

    /** Which name a merged write keeps. See [writeAgainst]. */
    private sealed interface NameFrom {
        /** The store's name: every write but a rename. A different name in the store is a rename made on another
         * device, and it is kept. */
        data object TheStore : NameFrom

        /** The name this write gives the list. */
        data class Renamed(val name: String) : NameFrom
    }

    /**
     * Writes [wanted], naming the copy it replaces ([stamp]). If another device changed the list first, the two
     * versions of its songs are merged against [ancestor] ([PlaylistMerge]) and the write is made once more; a second
     * refusal is [PlaylistRefusal.Busy].
     *
     * **The folder always comes from the store, and so does the name, except in a rename.** The phone does not move
     * playlists between folders, so a different folder is a change made on a computer, and it is left alone. [name]
     * says which name the merge keeps. There is no default, because a rename that took the store's name would undo
     * itself.
     *
     * @param began when the write began (this device's clock), for [OwnEdits.wrote].
     */
    private suspend fun writeAgainst(
        user: String,
        wanted: CloudPlaylist,
        ancestor: List<String>,
        stamp: Long,
        name: NameFrom,
        began: Long,
    ): PlaylistRefusal? {
        var sending = wanted
        var base = ancestor
        var replacing = stamp
        repeat(2) {
            // No precondition where the store never gave a stamp: one made up would refuse every write.
            val write = PlaylistWrite(sending.name, sending.trackIds, sending.folder, replacing.takeIf { it > 0 })
            when (val put = orNull { store.putPlaylist(sending.playlistId, write) } ?: return PlaylistRefusal.Failed) {
                is PlaylistPut.Written -> {
                    record(user, sending.copy(updatedAt = put.updatedAt), began)
                    return null
                }
                is PlaylistPut.Conflict -> {
                    // Refused without saying what it holds: there is nothing to merge against, and writing anyway
                    // would undo the other device's change.
                    val stored = put.stored ?: return PlaylistRefusal.Busy
                    val kept = when (name) {
                        NameFrom.TheStore -> stored.name
                        is NameFrom.Renamed -> name.name
                    }
                    sending = CloudPlaylist(stored.playlistId, kept, PlaylistMerge.songs(base, sending.trackIds, stored.trackIds), stored.folder, 0)
                    base = stored.trackIds
                    replacing = stored.updatedAt
                }
            }
        }
        return PlaylistRefusal.Busy
    }

    /** Records [playlist] as written by a write that began at [began]; see [OwnEdits.wrote]. */
    private fun record(user: String, playlist: CloudPlaylist, began: Long) {
        edited(user) { it.wrote(playlist, began) }
    }

    private suspend fun <T> orNull(call: suspend () -> T): T? = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
