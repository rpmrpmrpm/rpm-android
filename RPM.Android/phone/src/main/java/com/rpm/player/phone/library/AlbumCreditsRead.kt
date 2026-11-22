package com.rpm.player.phone.library

import com.rpm.player.core.cloud.CloudClient
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.library.AlbumCredits
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.StarterTracks
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads the copyright line and the label under a record's songs, from its first song's sidecar: the iPhone's
 * `AppModel.credits(of:)`.
 *
 * What each record's sidecar said is kept, by the record's first song, for as long as this collection is shown: the
 * tags change only when a computer edits them, and a page opened again shows them at once. A read that failed is not
 * kept, so the page asks again the next time it opens.
 *
 * @param shelf the collection shown. On a borrowed one nothing is read, because the sidecar route takes no shelf and
 * would answer about this account's own files.
 */
class AlbumCreditsRead(private val cloud: CloudClient, private val shelf: Shelf) {
    private val read = ConcurrentHashMap<String, AlbumCredits>()

    /** The record's credits, or null where they cannot be read: a borrowed shelf, or a failed read. */
    suspend fun of(album: CloudAlbum): AlbumCredits? {
        // A starter record is not in the cloud, which keeps the sidecars.
        if (shelf != Shelf.Own || StarterTracks.isStarter(album)) return null
        val first = album.tracks.firstOrNull() ?: return null
        read[first.rpmId]?.let { return it }

        val sidecar = try {
            cloud.sidecar(first.rpmId)
        } catch (e: IOException) {
            null
        } ?: return null
        return AlbumCredits(sidecar.copyright, sidecar.publisher).also { read[first.rpmId] = it }
    }
}
