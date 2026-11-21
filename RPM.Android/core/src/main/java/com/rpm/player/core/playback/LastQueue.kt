package com.rpm.player.core.playback

import com.rpm.player.core.cloud.Shelf
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.rpm.player.core.net.WireJson
import com.rpm.player.core.radio.RadioStation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File

/** One queued item as it is kept between runs: a track by its id and the names it is shown under, or a
 * station whole. */
@Serializable
data class SavedItem(
    val mediaId: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val durationMs: Long? = null,
    val coverKeys: List<String> = emptyList(),
    /** The album's disc keys and the first one's stamp. Empty and 0 in a queue saved by an older version. */
    val discKeys: List<String> = emptyList(),
    val discStamp: Long = 0,
    /** The track's reading for volume levelling ([Level]); null when nothing measured it. */
    val lufs: Double? = null,
    val peak: Double? = null,
    val station: RadioStation? = null,
    val logoStamp: String? = null,
    val detail: String? = null,
    /** Whether the person added it with Play next or Add to queue ([EXTRA_UP_NEXT]). False in an older queue. */
    val upNext: Boolean = false,
    /** Whether the song's lyrics are explicit ([EXTRA_EXPLICIT]). False in an older queue. */
    val explicit: Boolean = false,
)

/** The queue, which item was playing and where. */
@Serializable
data class SavedQueue(val items: List<SavedItem>, val index: Int, val positionMs: Long)

/**
 * The last queue that the service played, kept in the app's files so the remote's Play key can resume
 * it after the app has gone. It is read once, when the service starts, so a Play that started the
 * service finds it at once. It is written from snapshots that the service takes on the main thread.
 * One writer takes them in order and drops all but the newest waiting one. It writes through a
 * temporary file and a rename, so a reader sees the whole old queue or the whole new one.
 */
class LastQueue(private val file: File, scope: CoroutineScope) {
    private val pending = Channel<SavedQueue?>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            for (queue in pending) write(queue)
        }
    }

    /** The saved queue, or null when there is none or it cannot be read. Runs on the calling thread: it
     * is a small file and the service needs it before it handles the key that started it. */
    fun read(): SavedQueue? = try {
        if (file.exists()) WireJson.decodeFromString(SavedQueue.serializer(), file.readText()).takeIf { it.items.isNotEmpty() } else null
    } catch (_: Exception) {
        null
    }

    /** Keeps [queue]; null forgets the saved queue (an emptied queue, a signed-out account). */
    fun save(queue: SavedQueue?) {
        pending.trySend(queue)
    }

    private fun write(queue: SavedQueue?) {
        try {
            if (queue == null || queue.items.isEmpty()) {
                file.delete()
                return
            }
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(WireJson.encodeToString(SavedQueue.serializer(), queue))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (_: Exception) {
            // A queue that cannot be kept is not resumed; playback goes on.
        }
    }

    companion object {
        /** An item as it is kept. A station keeps the whole station; a track its id, names and cover keys. */
        fun savedOf(item: MediaItem): SavedItem {
            StationItems.stationOf(item)?.let { station ->
                val extras = item.mediaMetadata.extras
                return SavedItem(
                    mediaId = item.mediaId,
                    station = station,
                    logoStamp = extras?.getString(StationItems.EXTRA_LOGO_STAMP),
                    detail = extras?.getString(StationItems.EXTRA_DETAIL),
                )
            }
            val m = item.mediaMetadata
            val level = m.extras.level()
            return SavedItem(
                mediaId = item.mediaId,
                title = m.title?.toString(),
                artist = m.artist?.toString(),
                album = m.albumTitle?.toString(),
                albumArtist = m.albumArtist?.toString(),
                trackNumber = m.trackNumber,
                durationMs = m.durationMs,
                coverKeys = m.extras?.getStringArray(EXTRA_COVER_KEYS)?.toList().orEmpty(),
                discKeys = m.extras?.getStringArray(EXTRA_DISC_KEYS)?.toList().orEmpty(),
                discStamp = m.extras?.getLong(EXTRA_DISC_STAMP) ?: 0L,
                lufs = level.lufs,
                peak = level.truePeak,
                upNext = item.isUpNext,
                explicit = m.extras?.getBoolean(EXTRA_EXPLICIT) == true,
            )
        }

        /** The playable item again, with its address put back, as `onAddMediaItems` does for a controller's item. */
        fun itemOf(saved: SavedItem): MediaItem {
            saved.station?.let { station ->
                return StationItems.restore(StationItems.itemOf(station, saved.logoStamp.orEmpty(), saved.detail))
            }
            // Older saved queues hold bare ids, all of them the person's own tracks.
            val ref = TrackRef.parse(saved.mediaId) ?: TrackRef(Shelf.Own, saved.mediaId)
            return MediaItem.Builder()
                .setMediaId(ref.mediaId)
                .setUri(CloudTrackUri.of(ref))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(saved.title)
                        .setArtist(saved.artist)
                        .setAlbumTitle(saved.album)
                        .setAlbumArtist(saved.albumArtist)
                        .setTrackNumber(saved.trackNumber)
                        .setDurationMs(saved.durationMs)
                        .setExtras(
                            Bundle().apply {
                                putStringArray(EXTRA_COVER_KEYS, saved.coverKeys.toTypedArray())
                                putStringArray(EXTRA_DISC_KEYS, saved.discKeys.toTypedArray())
                                putLong(EXTRA_DISC_STAMP, saved.discStamp)
                                putLevel(Level(saved.lufs, saved.peak))
                                if (saved.upNext) putBoolean(EXTRA_UP_NEXT, true)
                                if (saved.explicit) putBoolean(EXTRA_EXPLICIT, true)
                            },
                        )
                        .build(),
                )
                .build()
        }

    }
}

/** Where a queued track carries its album's cover keys, so the player screen and the queue drawer can
 * draw the cover from the controller's own item, and a resumed queue keeps it. */
const val EXTRA_COVER_KEYS = "rpm.coverKeys"

/** Where a queued track carries its album's disc keys, and the stamp of the first, for the player's record. */
const val EXTRA_DISC_KEYS = "rpm.discKeys"
const val EXTRA_DISC_STAMP = "rpm.discStamp"
