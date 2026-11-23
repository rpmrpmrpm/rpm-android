package com.rpm.player.core.downloads

import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.net.WireJson
import com.rpm.player.core.playback.LocalCopies
import kotlinx.serialization.Serializable
import java.io.File

/**
 * The albums downloaded to this phone, and the files that hold them. The iPhone's `DownloadBook`
 * (RPMLibrary/DownloadBook.swift).
 *
 * Two facts are kept together under one lock:
 *
 * - **which albums were asked for**, with their tracks as they were when the album was asked for. The downloads
 *   page can then be drawn offline, and a removal knows which files belong to the album.
 * - **which tracks have a file**, and at what rate.
 *
 * Everything the screens show is worked out from those two facts when it is asked for. An album is on the phone
 * when every one of its tracks has a file, and downloading while some do not. Nothing is stored that could
 * disagree with them.
 *
 * **One lock for the facts and the files.** A download finishing and a removal can arrive in either order, from two
 * threads. [keep] asks "is this track still wanted?" and moves the file inside the same lock that [drop] deletes
 * under, so whichever runs second sees what the first did: a download for an album just removed is thrown away, and
 * a removal after a download deletes the file it finds.
 *
 * The files live in the app's own storage ([root]; the phone puts it under `noBackupFilesDir`), which no other app can
 * read, the system does not clear as it clears caches, and Android's backup leaves out: they are copies of what is in
 * the cloud, and an album would pass the 25 MB that Auto Backup holds for an app and stop its backup altogether. The book reads its manifest from the disk the first time it is asked anything, so the first
 * question should come from a background thread ([AlbumDownloads.begin] does this).
 *
 * **A playlist is kept as an album is**, under a key of its own ([playlistKey]), with its tracks as they were when it
 * was asked for. The two can share tracks. A track has one file, and that file stays while any album or playlist that
 * holds the track is still asked for.
 */
class DownloadBook(private val root: File, private val clock: () -> Long = System::currentTimeMillis) : LocalCopies {
    @Serializable
    data class Album(
        val key: String,
        val album: String,
        val artist: String,
        val tracks: List<CloudTrack>,
        /** When the album was asked for, in milliseconds. The list is newest first. */
        val asked: Long,
    ) {
        val asAlbum: CloudAlbum get() = CloudAlbum(key, album, artist, tracks)

        /** Whether this is a playlist and not a record. See [playlistKey]. */
        val isPlaylist: Boolean get() = DownloadBook.isPlaylistKey(key)
    }

    @Serializable
    data class Copy(val kilobits: Int, val bytes: Long)

    /** A track's file, and the rate it was downloaded at. */
    data class Held(val file: File, val kilobits: Int)

    @Serializable
    private data class Manifest(
        val albums: Map<String, Album> = emptyMap(),
        val copies: Map<String, Copy> = emptyMap(),
        /** The account the files were downloaded for. Another account signing in on this phone must not find them. */
        val owner: String? = null,
    )

    private val lock = Any()
    private val tracksDir = File(root, "tracks")
    private val partialDir = File(root, "partial")
    private val manifestFile = File(root, "manifest.json")

    private var albums = HashMap<String, Album>()
    private var copies = HashMap<String, Copy>()
    private var account: String? = null

    /** For each wanted track, one of the albums or playlists that holds it. Only whether a track has an entry here
     * matters. Rebuilt whenever the albums change. */
    private var owners = HashMap<String, String>()

    @Volatile private var loaded = false

    /** Whether the manifest has been read. Before that, a question asked here reads it, from whatever thread asks. */
    val isLoaded: Boolean get() = loaded

    /** Reads the manifest and makes it agree with the disk. Safe to call more than once; only the first call reads. */
    fun load() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            tracksDir.mkdirs()
            // Whatever was being written when the process ended is not a whole track.
            partialDir.deleteRecursively()
            partialDir.mkdirs()
            val read = runCatching { WireJson.decodeFromString(Manifest.serializer(), manifestFile.readText()) }.getOrNull()
            if (read != null) {
                albums = HashMap(read.albums)
                copies = HashMap(read.copies)
                account = read.owner
            }
            reconcile()
            loaded = true
        }
    }

    // ── reading ──

    /** The file for a track, if this phone holds one. */
    fun held(rpmId: String): Held? = locked {
        copies[rpmId]?.let { Held(file(rpmId), it.kilobits) }
    }

    /** For the player, on a loader thread: the file, if it is still there. */
    override fun fileOf(rpmId: String): File? = held(rpmId)?.file?.takeIf { it.isFile }

    /** For the player's offline rule, on the main thread: what the manifest says, without the disk. A file removed
     * behind the book's back is found when it is opened. */
    override fun holds(rpmId: String): Boolean = locked { rpmId in copies }

    /** Every album that was asked for, newest first. */
    val all: List<Album> get() = locked { albums.values.sortedByDescending { it.asked } }

    /** How many of an album's tracks have a file, out of how many it has. Null for an album nobody asked for. */
    fun count(key: String): Pair<Int, Int>? = locked {
        albums[key]?.let { album -> album.tracks.count { it.rpmId in copies } to album.tracks.size }
    }

    /** How many bytes an album's files take up on the phone. */
    fun bytes(key: String): Long = locked {
        albums[key]?.tracks?.sumOf { copies[it.rpmId]?.bytes ?: 0L } ?: 0L
    }

    /** Everything the files take up on the phone. A track held by an album and a playlist has one file, and it is
     * counted once. */
    val totalBytes: Long get() = locked { copies.values.sumOf { it.bytes } }

    /**
     * Every track that is wanted and has no file yet, in the order the albums were asked for and each album's own
     * order. What the downloader works down.
     *
     * **Each track is listed once**, where it first appears. A track can be in an album and a playlist, or twice in
     * one playlist, and it has one file. Listed twice, it would be signed and transferred twice.
     */
    val missing: List<String>
        get() = locked {
            albums.values.sortedBy { it.asked }.flatMap { it.tracks }.map { it.rpmId }.filter { it !in copies }.distinct()
        }

    /** Whether a track belongs to an album that is still wanted. */
    fun isWanted(rpmId: String): Boolean = locked { rpmId in owners }

    /** The account the files were downloaded for, or null for a book that holds nothing yet. */
    val owner: String? get() = locked { account }

    /** Where a transfer writes a track while it arrives. [keep] moves it into place. */
    fun partial(rpmId: String): File = File(partialDir, name(rpmId) + "." + System.nanoTime())

    // ── writing ──

    /**
     * Asks for an album, with the tracks it has now. Asking again for an album already asked for replaces its tracks
     * and keeps its place: this is how an album that gained a track on the computer gets the new track.
     */
    fun want(album: CloudAlbum, at: Long = clock()) = locked {
        val asked = albums[album.key]?.asked ?: at
        albums[album.key] = Album(album.key, album.album, album.artist, album.tracks, asked)
        reconcile()
        save()
    }

    /**
     * Takes albums off the phone. Their files are deleted now. Anything still downloading for them is thrown away
     * when it arrives, because it is not wanted any more.
     *
     * **A track that another album or playlist still holds keeps its file**, and it is not in the answer.
     *
     * @return the tracks that stopped being wanted, so whoever is downloading them can stop.
     */
    fun drop(keys: Set<String>): Set<String> = locked {
        val gone = HashSet<String>()
        for (key in keys) albums.remove(key)?.let { album -> album.tracks.mapTo(gone) { it.rpmId } }
        // A track that another album still asks for stays wanted.
        gone.removeAll(albums.values.flatMap { album -> album.tracks.map { it.rpmId } }.toSet())
        reconcile()
        save()
        gone
    }

    /**
     * Puts a finished download in place, **if its track is still wanted**, and says whether it was kept. The file at
     * [from] is moved or deleted before this returns.
     */
    fun keep(rpmId: String, from: File, kilobits: Int): Boolean = locked {
        if (rpmId !in owners) {
            from.delete()
            return@locked false
        }
        val to = file(rpmId)
        to.delete()
        val moved = from.renameTo(to) || runCatching { from.copyTo(to, overwrite = true); true }.getOrDefault(false)
        from.delete()
        if (!moved) return@locked false
        copies[rpmId] = Copy(kilobits, to.length())
        save()
        true
    }

    /** Removes everything from the phone: signing out, or another account signing in. The files are that account's
     * music. */
    fun forgetAll() = locked {
        albums.clear()
        copies.clear()
        owners.clear()
        account = null
        tracksDir.deleteRecursively()
        tracksDir.mkdirs()
        save()
    }

    /** Notes the account the files are for. */
    fun claim(userId: String) = locked {
        if (account != userId) {
            account = userId
            save()
        }
    }

    // ── inside the lock ──

    private inline fun <T> locked(body: () -> T): T {
        load()
        return synchronized(lock, body)
    }

    /**
     * Makes the copies agree with the albums and with the disk. A copy nobody wants is deleted. A copy whose file
     * has gone is forgotten. A file no copy refers to is deleted. This runs on every change, so none of the three
     * can build up.
     */
    private fun reconcile() {
        owners = HashMap()
        for (album in albums.values) for (track in album.tracks) owners[track.rpmId] = album.key

        copies.keys.filter { it !in owners }.forEach { rpmId ->
            copies.remove(rpmId)
            file(rpmId).delete()
        }
        copies.keys.filter { !file(it).isFile }.forEach { copies.remove(it) }

        val named = copies.keys.map { name(it) }.toSet()
        tracksDir.listFiles()?.forEach { if (it.name !in named) it.deleteRecursively() }
    }

    /** Written to a second file and moved over the first, so a process ended mid-write leaves the last whole one. */
    private fun save() {
        val text = WireJson.encodeToString(Manifest.serializer(), Manifest(albums, copies, account))
        root.mkdirs()
        val next = File(root, "manifest.json.next")
        runCatching {
            next.writeText(text)
            if (!next.renameTo(manifestFile)) {
                manifestFile.delete()
                next.renameTo(manifestFile)
            }
        }
    }

    private fun file(rpmId: String) = File(tracksDir, name(rpmId))

    companion object {
        private const val PLAYLIST_PREFIX = "playlist:"

        /**
         * The key a playlist is kept under, from its id ([com.rpm.player.core.library.TrackList.id]).
         *
         * It cannot be the key of a record: a record's key ([com.rpm.player.core.library.AlbumWall.key]) starts with
         * `folder:` or `tags:`.
         */
        fun playlistKey(id: String): String = PLAYLIST_PREFIX + id

        /** Whether a key is one that [playlistKey] made. */
        fun isPlaylistKey(key: String): Boolean = key.startsWith(PLAYLIST_PREFIX)

        /** One file per track, named by its id. Escaped, because the id comes from the store and nothing promises it
         * has no slash. */
        fun name(rpmId: String): String = buildString {
            for (byte in rpmId.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt().toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.') append(c)
                else append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
    }
}
