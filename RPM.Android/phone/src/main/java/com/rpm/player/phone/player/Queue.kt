package com.rpm.player.phone.player

import com.rpm.player.core.playback.EXTRA_RADIO
import com.rpm.player.core.playback.EXTRA_UP_NEXT
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.CloudTrackTags
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.Cover
import com.rpm.player.core.playback.EXTRA_COVER_KEYS
import com.rpm.player.core.playback.TrackRef

/**
 * One entry of the queue as a page shows it: the song as the library knows it, or as the queue entry
 * describes it when the library does not have the song. It also carries the cover art of the entry, and the
 * [part] of the queue it belongs to.
 */
class QueueEntry(val mediaId: String, val track: CloudTrack, val cover: Cover?, val part: QueuePart = QueuePart.List)

/**
 * Which part of the queue an entry is in, as its queue item says: the record or list that was played, the songs the
 * person added to it ([EXTRA_UP_NEXT]), or the songs a mixtape added ([EXTRA_RADIO]). The up next drawer puts a heading
 * where each of the last two begins.
 */
enum class QueuePart { List, Yours, Mixtape }

/**
 * The queue as the controller holds it, and the index of the entry that is playing. The index is null if no entry is
 * playing.
 */
class QueueRead(val entries: List<QueueEntry>, val playing: Int?) {
    val seconds: Int get() = entries.sumOf { it.track.tags?.durationSeconds ?: 0.0 }.toInt()

    companion object {
        val Empty = QueueRead(emptyList(), null)
    }
}

/**
 * The queue, read whole from [controller] when it connects. It is read again on every event that changes
 * the queue or the playing position. The TV app's queue drawer reads it the same way. A MediaController
 * applies an edit to its own copy before the call returns, so a read after an edit made here already shows
 * the edit.
 */
@Composable
fun rememberQueue(controller: Player?, collection: Collection): QueueRead {
    var read by remember(controller, collection) { mutableStateOf(controller?.let { readQueue(it, collection) } ?: QueueRead.Empty) }
    DisposableEffect(controller, collection) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) read = readQueue(player, collection)
            }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }
    return read
}

private fun readQueue(player: Player, collection: Collection): QueueRead {
    val entries = (0 until player.mediaItemCount).map { entryOf(player.getMediaItemAt(it), collection) }
    val playing = player.currentMediaItemIndex.takeIf { it in entries.indices }
    return QueueRead(entries, playing)
}

private fun entryOf(item: MediaItem, collection: Collection): QueueEntry {
    val ref = TrackRef.parse(item.mediaId)
    val metadata = item.mediaMetadata
    val track = ref?.let { collection.tracksById[it.rpmId] } ?: CloudTrack(
        rpmId = ref?.rpmId ?: item.mediaId,
        title = (metadata.title ?: metadata.station)?.toString(),
        artist = metadata.artist?.toString(),
        album = metadata.albumTitle?.toString(),
        tags = CloudTrackTags(durationSeconds = (metadata.durationMs ?: 0L) / 1000.0),
    )
    val cover = ref?.let { r -> metadata.extras?.getStringArray(EXTRA_COVER_KEYS)?.takeIf { it.isNotEmpty() }?.let { Cover(r.shelf, it.toList(), stamp = 0) } }
    val part = when {
        metadata.extras?.getString(EXTRA_RADIO) != null -> QueuePart.Mixtape
        metadata.extras?.getBoolean(EXTRA_UP_NEXT) == true -> QueuePart.Yours
        else -> QueuePart.List
    }
    return QueueEntry(item.mediaId, track, cover, part)
}
