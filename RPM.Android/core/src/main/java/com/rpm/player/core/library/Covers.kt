package com.rpm.player.core.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import android.os.SystemClock
import com.rpm.player.core.diag.Diag
import coil3.disk.DiskCache
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.rpm.player.core.art.SharpScaled
import com.rpm.player.core.cloud.CloudClient
import com.rpm.player.core.cloud.CoverSize
import com.rpm.player.core.cloud.Shelf
import okio.Buffer
import java.io.IOException

/**
 * The size covers are fetched at for screens drawn [drawnHeight] pixels high: a copy the server cuts, not the
 * original the desktop uploaded. On 1080-high screens the largest cover is about 410 pixels (the player's sleeve;
 * the record page's is 390, a home card 312, a wall tile about 290), so up to 1080 the size is 512. Originals
 * are 18 to 600 KB. Above 1080 (for example 2160, where a home card is 624 pixels) the size is 1024, the
 * server's largest copy. A cover sent to a Cast device is drawn from the same copy.
 */
fun coverEdgeFor(drawnHeight: Int): CoverSize.Edge = if (drawnHeight > 1080) CoverSize.Edge(1024) else CoverSize.Edge(512)

/**
 * The cover copy this device asks for, read at each fetch. Set by the TV once it has decided the height its screens
 * are drawn at, before it draws them; a change of that height recreates the screens, which then ask again.
 */
class CoverEdge(initial: CoverSize.Edge) {
    @Volatile
    var current: CoverSize.Edge = initial
}

/** A cover to draw: the shelf it is asked of and the album keys to try, in order. The image loader
 * caches it by the shelf and the keys, not by the signed URL, which changes every time it is signed; the
 * same album in two collections is two covers. */
data class Cover(
    val shelf: Shelf,
    val keys: List<String>,
    /** When the cover under [keys]' first key was written, from the cover index; 0 where that is not known,
     * which keeps it out of the disk cache ([CoverFetcher]). */
    val stamp: Long,
)

/** The cover model for an album, or null when the index says it has none, in which case nothing is
 * asked for and the placeholder is drawn. */
fun coverFor(album: CloudAlbum, index: CoverIndex, shelf: Shelf): Cover? {
    val spellings = AlbumArtKey.spellings(RecordPicture.COVER, album.tracks)
    val keys = when (index) {
        is CoverIndex.Known -> spellings.filter { it in index.keys }
        CoverIndex.Unknown -> spellings
    }
    if (keys.isEmpty()) return null
    val stamp = (index as? CoverIndex.Known)?.stamps?.get(keys.first()) ?: 0L
    return Cover(shelf, keys, stamp)
}

/** Fetches a [Cover]: asks the API for each key's signed URL until one answers, then downloads it.
 * Runs on the image loader's dispatcher and is cancelled with the grid cell that asked. */
class CoverFetcher(
    private val cover: Cover,
    private val options: Options,
    private val cloud: CloudClient,
    private val disk: DiskCache?,
    private val size: CoverEdge,
) : Fetcher {
    override suspend fun fetch(): FetchResult =
        StoredPictures.fetch(StoredPictures.COVER, cover.shelf, cover.keys, cover.stamp, size.current, options, cloud, disk)

    /** [size]: the copy to ask for ([coverEdgeFor]). */
    class Factory(private val cloud: CloudClient, private val size: CoverEdge) : Fetcher.Factory<Cover> {
        override fun create(data: Cover, options: Options, imageLoader: ImageLoader): Fetcher =
            CoverFetcher(data, options, cloud, imageLoader.diskCache, size)
    }
}

class CoverKeyer : Keyer<Cover> {
    override fun key(data: Cover, options: Options): String = keyOf(data)

    companion object {
        fun keyOf(data: Cover): String = StoredPictures.memoryKey(StoredPictures.COVER, data.shelf, data.keys)
    }
}

/**
 * The fetch shared by the pictures the cloud keeps under album keys: a record's cover ([CoverFetcher]) and its
 * disc ([DiscFetcher]). [kind] names the picture in its cache keys and its log line, so the cover and the disc
 * of one record are kept apart.
 */
internal object StoredPictures {
    const val COVER = "cover"
    const val DISC = "disc"

    private const val TAG = "rpm.covers"

    /** The key a picture is kept under in the loader's memory, and shared under while it downloads. */
    fun memoryKey(kind: String, shelf: Shelf, keys: List<String>) = kind + ":" + shelf.key + ":" + keys.joinToString(",")

    /**
     * The picture under the first of [keys] the API has, at [size]. One with a known [stamp] is kept on disk
     * under it, so a launch draws the pictures it drew last time without two requests each (the API for a
     * signed URL, then the picture), and a picture replaced from the desktop has a new stamp and is fetched
     * again. Without a stamp it always comes from the cloud.
     */
    suspend fun fetch(
        kind: String,
        shelf: Shelf,
        keys: List<String>,
        stamp: Long,
        size: CoverSize,
        options: Options,
        cloud: CloudClient,
        disk: DiskCache?,
    ): FetchResult {
        // The size is part of the key: a box that starts asking for bigger copies fetches them, not the ones it kept.
        val sized = when (size) {
            CoverSize.Original -> ""
            is CoverSize.Edge -> "/" + size.px
        }
        val diskKey = if (stamp > 0) memoryKey(kind, shelf, keys) + "@" + stamp + sized else null
        if (diskKey != null) {
            disk?.openSnapshot(diskKey)?.let { snapshot ->
                return SourceFetchResult(
                    source = ImageSource(snapshot.data, options.fileSystem, diskKey, snapshot, SharpScaled),
                    mimeType = null,
                    dataSource = DataSource.DISK,
                )
            }
        }
        // Shared by every request for this picture that is on its way at once: a card coming into view while the
        // row's look-ahead fetches the same cover waits for that download instead of starting a second one.
        val bytes = CoverFlights.shared(diskKey ?: memoryKey(kind, shelf, keys)) { download(kind, shelf, keys, size, cloud) }
        if (diskKey != null) keep(diskKey, bytes, options, disk)
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), options.fileSystem, SharpScaled),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    /** The picture from the cloud: the first key the API has a signed address for, then the picture. */
    private suspend fun download(kind: String, shelf: Shelf, keys: List<String>, size: CoverSize, cloud: CloudClient): ByteArray {
        for (key in keys) {
            val asked = SystemClock.elapsedRealtime()
            val url = cloud.coverUrl(shelf, key, size) ?: continue
            val signed = SystemClock.elapsedRealtime()
            val bytes = cloud.signedBytes(url)
            // What a picture not kept on the TV costs, for `adb logcat -s rpm.covers`: the API's answer, then the picture.
            Diag.d(TAG, "$kind ${bytes.size / 1024} KB: address in ${signed - asked} ms, picture in ${SystemClock.elapsedRealtime() - signed} ms")
            return bytes
        }
        throw IOException("no stored $kind under $keys")
    }

    /** Writes [bytes] to the disk cache. A picture that could not be written is fetched again next time. */
    private fun keep(key: String, bytes: ByteArray, options: Options, disk: DiskCache?) {
        val editor = disk?.openEditor(key) ?: return
        try {
            options.fileSystem.write(editor.data) { write(bytes) }
            editor.commit()
        } catch (_: Exception) {
            runCatching { editor.abort() }
        }
    }
}

/**
 * Downloads that every request for the same cover on its way at the same time waits for. A download goes on while
 * anyone waits for it and is cancelled when the last of them is (a card scrolled away, a look-ahead superseded),
 * so sharing it never keeps a request alive that nobody wants. Once it has ended it is forgotten: the next request
 * finds the cover in the disk or memory cache.
 */
internal object CoverFlights {
    private class Flight(val download: Deferred<ByteArray>) {
        var waiting = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flights = HashMap<String, Flight>()

    suspend fun shared(key: String, download: suspend () -> ByteArray): ByteArray {
        val flight = synchronized(flights) {
            flights.getOrPut(key) { Flight(scope.async(start = CoroutineStart.LAZY) { download() }) }.also { it.waiting++ }
        }
        try {
            return flight.download.await()
        } finally {
            synchronized(flights) {
                flight.waiting--
                if (flight.waiting == 0) {
                    flights.remove(key, flight)
                    if (!flight.download.isCompleted) flight.download.cancel()
                }
            }
        }
    }
}

/** A list's picture, as the image loader draws it: the photograph shipped for a generated list
 * ([PlaylistPhoto]), or the picture the cloud keeps ([ListCover]). [Collection.cover] picks which. */
sealed interface ListArt

/** A list's picture: the one the cloud stores for it under [storeId] on [shelf], or, when it has none,
 * the [fallback] cover (its first track's sleeve). Cached by the shelf and store id, not by the signed URL. */
data class ListCover(val shelf: Shelf, val storeId: String, val fallback: Cover?) : ListArt

class ListCoverFetcher(
    private val cover: ListCover,
    private val options: Options,
    private val cloud: CloudClient,
    private val size: CoverEdge,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bytes = cloud.playlistArtUrl(cover.shelf, cover.storeId)?.let { cloud.signedBytes(it) }
            ?: cover.fallback?.let { fallback -> fallback.keys.firstNotNullOfOrNull { key -> cloud.coverUrl(fallback.shelf, key, size.current)?.let { cloud.signedBytes(it) } } }
            ?: throw IOException("no picture for list ${cover.storeId}")
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), options.fileSystem, SharpScaled),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    /** [size]: the copy to ask for when the list has no picture of its own ([coverEdgeFor]). */
    class Factory(private val cloud: CloudClient, private val size: CoverEdge) : Fetcher.Factory<ListCover> {
        override fun create(data: ListCover, options: Options, imageLoader: ImageLoader): Fetcher =
            ListCoverFetcher(data, options, cloud, size)
    }
}

class ListCoverKeyer : Keyer<ListCover> {
    override fun key(data: ListCover, options: Options): String = "list:" + data.shelf.key + ":" + data.storeId
}
