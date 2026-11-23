package com.rpm.player.phone.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rpm.player.core.R
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.downloads.DownloadBook
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.TrackList
import com.rpm.player.core.library.listName

/**
 * How many albums and playlists are downloaded to this phone, and the space they take: the downloads card on the
 * profile tab, and the line at the top of the downloads page.
 */
@Immutable
data class Downloaded(val albums: Int, val playlists: Int, val bytes: Long) {
    companion object {
        val None = Downloaded(0, 0, 0L)
    }
}

/**
 * How many albums and playlists are downloaded, and the space they use, as one line (the iPhone's
 * `DownloadsPage.summary`). Read on the downloads page and on the profile tab's downloads card. With no downloads at
 * all it is the card's "none" line.
 *
 * Only albums: the album count. One playlist alone: that it is one playlist. Any other mix: how many downloads there
 * are.
 *
 * @param size the space, worded as the caller's page words sizes.
 */
@Composable
fun downloadsSummary(downloaded: Downloaded, size: (Long) -> String): String {
    val (albums, playlists, bytes) = downloaded
    return when {
        albums + playlists == 0 -> stringResource(R.string.Rpm_Downloads_None)
        playlists == 0 && albums == 1 -> stringResource(R.string.Rpm_Downloads_SummaryOne, size(bytes))
        playlists == 0 -> stringResource(R.string.Rpm_Downloads_Summary, albums.toString(), size(bytes))
        albums == 0 && playlists == 1 -> stringResource(R.string.Rpm_Downloads_SummaryOnePlaylist, size(bytes))
        else -> stringResource(R.string.Rpm_Downloads_SummaryMixed, (albums + playlists).toString(), size(bytes))
    }
}

/**
 * The downloaded playlists' rows on the downloads page, as the lists on the screen show them (the iPhone's
 * `RootView.downloadRows`). A row is given its list, for its picture and its frame, when the list is among the lists
 * of the person's own shelf on the screen.
 */
@Stable
class DownloadedLists(private val collection: Collection?) {
    private val byKey: Map<String, TrackList> =
        if (collection?.shelf == Shelf.Own) collection.lists.associateBy { DownloadBook.playlistKey(it.id) } else emptyMap()

    /** The list a row is, or null: a record, or a downloaded playlist that is not among the lists on the screen. */
    fun list(album: CloudAlbum): TrackList? = byKey[album.key]

    /**
     * The row's picture: the list's own, as on the playlists tab, or else the cover of its first song's record, which
     * is also what a list on another shelf or no longer on the screen shows.
     */
    fun picture(album: CloudAlbum): Any? {
        val collection = collection ?: return null
        list(album)?.let { return collection.cover(it) }
        return album.tracks.firstOrNull()?.let { collection.albumOf[it.rpmId] }?.let(collection::cover)
    }

    companion object {
        val None = DownloadedLists(null)
    }
}

/** [DownloadedLists] for [collection], made again only when the collection changes. */
@Composable
fun rememberDownloadedLists(collection: Collection): DownloadedLists = remember(collection) { DownloadedLists(collection) }

/**
 * A downloaded playlist follows its list (the iPhone's `.onChange(of: model.playlists)` in `RootView`): a song added on
 * the computer or on this phone is downloaded, and a song taken out leaves the phone. Run each time the collection is
 * read again. See [com.rpm.player.core.downloads.AlbumDownloads.follow].
 */
@Composable
fun FollowDownloadedLists(downloads: DownloadsHere, collection: Collection) {
    val resources = LocalContext.current.resources
    LaunchedEffect(collection, resources) {
        downloads.engine.follow(collection.shelf, collection.lists) { listName(it.name, resources) }
    }
}
