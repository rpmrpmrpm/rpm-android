package com.rpm.player.core.library

import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.cloud.CloudClient
import com.rpm.player.core.cloud.CloudPlaylist
import com.rpm.player.core.cloud.CloudTrackTags
import com.rpm.player.core.cloud.PlaylistPut
import com.rpm.player.core.cloud.PlaylistWrite
import com.rpm.player.core.cloud.Shelf
import kotlinx.coroutines.CancellationException

/**
 * What this TV has written to the person's own collection this session, laid over every library a load
 * holds ([LibraryRepository]), so the screens show a write as soon as the cloud took it.
 *
 * **Laid over, not written into.** A load's cloud read can have started before the write and land after
 * it; replacing the library with that read would take the write back off the screen. So each edit carries
 * the stamp the cloud gave the write, and is laid over a row only while that row is older than the stamp:
 * a row read after the write already holds it (or something newer from another device), and is left as
 * it is. That holds whichever order the read and the write arrive in.
 */
data class OwnEdits(
    /** Whose collection these were written to. Edits for another account are never laid over. */
    val user: String?,
    /** Favourite marks put on or taken off here, by track id, each with the stamp its row was given. */
    val marks: Map<String, Mark>,
    /** Playlists written here, by id, as they were written, each with the stamp the server gave it. */
    val playlists: Map<String, CloudPlaylist>,
    /** Playlists made here, by id, with when (this TV's clock, unix ms). One of these missing from a
     * library read before it was made is added to it; missing from one read after, it was deleted
     * elsewhere and stays missing. */
    val made: Map<String, Long>,
    /** Playlists removed here, by id, with when the removal ended (this device's clock, unix ms). One of these is
     * left out of a library read before then: the read was answered before the store dropped it. A read that began
     * after it and still lists it shows what the store holds, because another device wrote it again (the iPhone's
     * `HeldPlaylists.removed`). */
    val removed: Map<String, Long> = emptyMap(),
) {
    /** [library] with these edits laid over each row older than the edit. */
    fun over(library: CloudLibrary): CloudLibrary {
        if (marks.isEmpty() && playlists.isEmpty() && made.isEmpty() && removed.isEmpty()) return library
        val tracks = if (marks.isEmpty()) library.tracks else library.tracks.map { track ->
            val mark = marks[track.rpmId]
            if (mark == null || track.updatedAt >= mark.stamp || (track.tags?.favorite == true) == mark.favorite) track
            else track.copy(tags = (track.tags ?: CloudTrackTags()).copy(favorite = mark.favorite, favoritedAt = mark.at))
        }
        val read = if (removed.isEmpty()) library.playlists else library.playlists.filter { list ->
            // Removed here after this read began: its answer is older than the removal.
            removed[list.playlistId]?.let { gone -> gone > library.readAt } != true
        }
        val kept = if (playlists.isEmpty()) read else read.map { list ->
            val written = playlists[list.playlistId]
            if (written == null || list.updatedAt >= written.updatedAt) list else written
        }
        val present = kept.mapTo(HashSet()) { it.playlistId }
        val missed = made.filter { (id, at) -> id !in present && at > library.readAt }.keys.mapNotNull(playlists::get)
        return library.copy(tracks = tracks, playlists = kept + missed)
    }

    /**
     * These edits with [playlist] as written here, by a write that began at [began] (this device's clock). A write
     * that began before this device removed the playlist (an add sent just before the delete) is left out: it wrote
     * a row the store no longer holds. One that began after it wrote the playlist again, and the removal is past.
     */
    fun wrote(playlist: CloudPlaylist, began: Long): OwnEdits {
        val gone = removed[playlist.playlistId]
        if (gone != null && began < gone) return this
        return copy(playlists = playlists + (playlist.playlistId to playlist), removed = removed - playlist.playlistId)
    }

    /** These edits with the playlist [id] removed here, the removal having ended [at] (this device's clock). It goes
     * from the screen at once, and a read that began before [at] does not bring it back. */
    fun removedHere(id: String, at: Long): OwnEdits =
        copy(playlists = playlists - id, made = made - id, removed = removed + (id to at))

    /** These edits for [user]: the same, or none if they were made for someone else. */
    fun forUser(user: String): OwnEdits = if (this.user == user) this else OwnEdits(user, emptyMap(), emptyMap(), emptyMap())

    /** A favourite mark as written here: on or off, when it was put on (.NET ticks, 0 when taken off), and the
     * stamp the row was given. */
    data class Mark(val favorite: Boolean, val at: Long, val stamp: Long)

    companion object {
        val NONE = OwnEdits(null, emptyMap(), emptyMap(), emptyMap())
    }
}

/** What adding a track to a playlist came to. */
sealed interface Filed {
    data object Added : Filed
    /** The playlist already holds the track: picked when it did, or put there by another device just
     * before. Nothing is written; a second copy is never added unasked (the phone's `AlreadyThere`). */
    data object AlreadyIn : Filed
    data object Failed : Filed
}

/**
 * The writes a track's menu makes to the person's own collection: the favourite mark, on or off, and a
 * place in one of their playlists. It follows the phone's `setFavorite` and `PlaylistWriter.add`, for one track.
 * Each write that the cloud takes is recorded with the repository ([LibraryRepository.edited]), which lays
 * it over the library on screen.
 */
class OwnWriter(
    private val cloud: CloudClient,
    private val auth: AuthClient,
    private val library: LibraryRepository,
    private val clock: () -> Long,
) {
    /** Puts the favourite mark on [rpmId], or takes it off. False when it could not be saved: no session,
     * no network, no cloud row for the track (one only on a computer), or, taking it off, an API that does
     * not have `cloud/track-mark` yet, or a starter track, which the cloud does not hold ([StarterTracks]). */
    suspend fun mark(rpmId: String, favorite: Boolean): Boolean {
        if (StarterTracks.isStarter(rpmId)) return false
        val user = auth.session.value?.userId ?: return false
        val at = if (favorite) utcTicks(clock()) else 0L
        val stamp = orNull {
            if (favorite) cloud.markFavourite(rpmId, at) else cloud.unmarkFavourite(rpmId)
        } ?: return false
        library.edited(user) { it.copy(marks = it.marks + (rpmId to OwnEdits.Mark(favorite, at, stamp))) }
        return true
    }

    /**
     * Puts [rpmId] at the end of the person's playlist [playlistId]. The playlist is read again first
     * rather than written from the copy on screen, which can be several changes behind, and written only
     * if it is still that copy. If another device wrote in between, the write is made again on the copy
     * it stored, once (the phone's `writeAgainst`); a second refusal is a failure.
     *
     * @param again whether a second copy goes in when the playlist already holds the track: the phone's
     * `AlreadyThere.addAnother`, which only the person's own answer to the question asks for. Otherwise nothing is
     * written and the answer is [Filed.AlreadyIn].
     */
    suspend fun addToPlaylist(rpmId: String, playlistId: String, again: Boolean = false): Filed {
        // A playlist names cloud tracks, and a starter track is not one.
        if (StarterTracks.isStarter(rpmId)) return Filed.Failed
        val user = auth.session.value?.userId ?: return Filed.Failed
        val began = clock()
        var current = orNull { cloud.playlists(Shelf.Own) }?.firstOrNull { it.playlistId == playlistId } ?: return Filed.Failed
        repeat(2) {
            if (!again && rpmId in current.trackIds) {
                record(user, current, began)
                return Filed.AlreadyIn
            }
            val wanted = current.trackIds + rpmId
            // No precondition where the store never gave a stamp: one made up would refuse every write.
            val write = PlaylistWrite(current.name, wanted, current.folder, current.updatedAt.takeIf { it > 0 })
            when (val put = orNull { cloud.putPlaylist(current.playlistId, write) } ?: return Filed.Failed) {
                is PlaylistPut.Written -> {
                    record(user, current.copy(trackIds = wanted, updatedAt = put.updatedAt), began)
                    return Filed.Added
                }
                is PlaylistPut.Conflict -> current = put.stored ?: return Filed.Failed
            }
        }
        return Filed.Failed
    }

    /**
     * Makes a playlist called [name] holding [rpmId], at the top of the person's tree, as the phone's
     * `PlaylistWriter.create` does: under a new id, written only if nothing is stored under it
     * (`ifUnchangedSince` 0), so a clash is a failure rather than somebody's playlist overwritten.
     */
    suspend fun createPlaylist(rpmId: String, name: String): Boolean {
        if (StarterTracks.isStarter(rpmId)) return false
        val user = auth.session.value?.userId ?: return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val id = java.util.UUID.randomUUID().toString().lowercase()
        val made = clock()
        val put = orNull { cloud.putPlaylist(id, PlaylistWrite(trimmed, listOf(rpmId), emptyList(), ifUnchangedSince = 0)) }
        if (put !is PlaylistPut.Written) return false
        library.edited(user) {
            it.copy(
                playlists = it.playlists + (id to CloudPlaylist(id, trimmed, listOf(rpmId), emptyList(), put.updatedAt)),
                made = it.made + (id to made),
            )
        }
        return true
    }

    /** Records [playlist] as written by a write that began at [began]; see [OwnEdits.wrote]. */
    private fun record(user: String, playlist: CloudPlaylist, began: Long) {
        library.edited(user) { it.wrote(playlist, began) }
    }

    private suspend fun <T> orNull(call: suspend () -> T): T? = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        /** .NET ticks (100 ns since 0001-01-01, UTC) at [unixMillis]: how the desktop stamps a favourite. */
        fun utcTicks(unixMillis: Long): Long = unixMillis * 10_000 + 621_355_968_000_000_000L
    }
}
