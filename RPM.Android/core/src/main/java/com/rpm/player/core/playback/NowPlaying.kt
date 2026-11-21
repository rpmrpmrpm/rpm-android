package com.rpm.player.core.playback

import com.rpm.player.core.library.isExplicit
import com.rpm.player.core.cloud.Shelf
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.Cover
import com.rpm.player.core.library.DiscArt
import com.rpm.player.core.radio.Icy
import com.rpm.player.core.radio.RadioStation
import com.rpm.player.core.radio.StationLogo
import kotlinx.coroutines.delay
import kotlin.math.abs

/** What the player is doing, read whole from the controller every time it changes. */
data class NowPlaying(
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String,
    val cover: Cover?,
    /** The record's own disc picture, where the cloud keeps one; drawn in place of the drawn record's label. */
    val disc: DiscArt?,
    val playing: Boolean,
    /** Whether the position is moving now: playing and not waiting for data (`Player.isPlaying`). [playing] is what
     * the play button shows and stays true while the player buffers; the position is carried forward only on this. */
    val advancing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val failed: Boolean,
    /** Set when the item is a radio station; [title] is then the station's name and [album] its detail
     * line, and what it announces is in [StationPlaying.said]. */
    val station: StationPlaying?,
    /** When [positionMs] was read, on the `SystemClock.uptimeMillis` clock, so a reader can carry the
     * position forward between reads while playing. */
    val readAtMs: Long,
    /** Why the player stands still, as the playback service says it ([PlaybackFailure.EXTRA]): on the phone, that it
     * is offline. Always [PlaybackFailure.None] on the TV. */
    val failure: PlaybackFailure = PlaybackFailure.None,
) {
    /** The position now, carried forward from the last read while it advances. */
    fun positionAt(uptimeMs: Long): Long =
        if (advancing) positionMs + (uptimeMs - readAtMs).coerceAtLeast(0) else positionMs
}

/** A station playing: its name, its logo, its country and quality, and what it announces now. */
data class StationPlaying(val name: String, val logo: StationLogo, val detail: String?, val said: Icy.Said?, val reconnecting: Boolean)

/**
 * The controller's state as Compose state. Every player event re-reads the whole state rather than
 * applying a change, so the order events arrive in cannot leave the screen wrong. The position is re-read
 * twice a second while playing, because a playing position changes without an event, but a read is published only when something other than the
 * position changed, or the position is more than [POSITION_DRIFT_MS] from where it was carried to
 * ([NowPlaying.positionAt]). Publishing every read recomposed every screen that reads this state twice a
 * second, about 40 ms of the main thread each time on a low-end TV box. What shows the position reads
 * [rememberPosition], which moves on its own.
 */
@Composable
fun rememberNowPlaying(controller: Player?): NowPlaying? {
    var state by remember(controller) { mutableStateOf(controller?.let(::read)) }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                state = read(player)
            }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }

    LaunchedEffect(controller, state?.playing) {
        while (controller != null && state?.playing == true) {
            delay(500)
            val fresh = read(controller)
            val held = state
            if (fresh != null && held != null && !fresh.isNewsAfter(held)) continue
            state = fresh
        }
    }

    return state
}

/** Whether this read says something [held] does not: anything besides the position, or a position more
 * than [POSITION_DRIFT_MS] from where [held] carried it to by the time of this read. */
internal fun NowPlaying.isNewsAfter(held: NowPlaying): Boolean =
    copy(positionMs = 0, readAtMs = 0) != held.copy(positionMs = 0, readAtMs = 0) ||
        abs(positionMs - held.positionAt(readAtMs)) >= POSITION_DRIFT_MS

/** How far a read position may be from the carried one before it is published. The karaoke line and the lit lyric
 * are drawn from the carried position, so an error under the limit stays for as long as nothing else
 * changes. A larger limit let a 170 ms error show for seconds at a time on a low-end TV box. A carried
 * position that keeps up publishes nothing, so this costs a recomposition only where the position really
 * moved off. */
internal const val POSITION_DRIFT_MS = 60L

/**
 * The position of [nowPlaying], carried forward from its last read ([NowPlaying.positionAt]) and moved
 * each time the time left, shown in whole seconds ([remainingOf]), would change while it plays: once a
 * second. Read it in the composable that shows the position, so only that is recomposed each second.
 */
@Composable
fun rememberPosition(nowPlaying: NowPlaying?): State<Long> {
    val position = remember(nowPlaying) { mutableLongStateOf(nowPlaying?.positionAt(SystemClock.uptimeMillis()) ?: 0L) }
    LaunchedEffect(position) {
        val shown = nowPlaying ?: return@LaunchedEffect
        while (shown.playing) {
            val left = shown.durationMs - position.longValue
            delay(if (left > 0) left % 1_000 + 1 else 1_000)
            position.longValue = shown.positionAt(SystemClock.uptimeMillis())
        }
    }
    return position
}

private fun read(player: Player): NowPlaying? {
    val item = player.currentMediaItem ?: return null
    val metadata = item.mediaMetadata
    val station = StationItems.uuidOf(item.mediaId)?.let { uuid ->
        val name = metadata.station?.toString().orEmpty()
        val extras = metadata.extras
        // The player's title is what the stream announces; the station's item carries none to hide it.
        // Media3 starts the player's metadata again from the new item when the item changes, so an old
        // station's announcement is not shown under a new one ("Y × Z").
        // The session gives the song's own title and the whole announcement in an extra; an older session only the title.
        val announced = player.mediaMetadata.extras?.getString(StationItems.EXTRA_ANNOUNCED) ?: player.mediaMetadata.title?.toString()
        StationPlaying(
            name = name,
            logo = StationLogo(
                uuid,
                extras?.getString(StationItems.EXTRA_LOGO_STAMP).orEmpty(),
                extras?.getStringArray(StationItems.EXTRA_LOGOS)?.toList().orEmpty(),
            ),
            detail = extras?.getString(StationItems.EXTRA_DETAIL),
            said = Icy.said(announced, name),
            reconnecting = StationReconnect.reconnecting(
                player,
                attempting = (player as? MediaController)?.sessionExtras?.getBoolean(StationReconnect.EXTRA_ATTEMPTING) == true,
            ),
        )
    }
    return NowPlaying(
        mediaId = item.mediaId,
        title = station?.name ?: metadata.title?.toString().orEmpty(),
        artist = if (station != null) "" else metadata.artist?.toString().orEmpty(),
        album = station?.detail ?: metadata.albumTitle?.toString().orEmpty(),
        cover = TrackRef.parse(item.mediaId)?.let { ref ->
            metadata.extras?.getStringArray(EXTRA_COVER_KEYS)?.takeIf { it.isNotEmpty() }?.let { Cover(ref.shelf, it.toList(), stamp = 0) }
        },
        disc = TrackRef.parse(item.mediaId)?.let { ref ->
            metadata.extras?.getStringArray(EXTRA_DISC_KEYS)?.takeIf { it.isNotEmpty() }?.let {
                DiscArt(ref.shelf, it.toList(), metadata.extras?.getLong(EXTRA_DISC_STAMP) ?: 0L)
            }
        },
        playing = player.meansToPlay,
        advancing = player.isPlaying,
        positionMs = player.currentPosition.coerceAtLeast(0),
        // A live transcode reports no length, so the length the library lists stands in for it.
        durationMs = player.duration.takeIf { it > 0 } ?: metadata.durationMs?.takeIf { it > 0 } ?: 0,
        failed = player.playerError != null,
        station = station,
        readAtMs = android.os.SystemClock.uptimeMillis(),
        failure = PlaybackFailure.of((player as? MediaController)?.sessionExtras?.getString(PlaybackFailure.EXTRA)),
    )
}


/** One track to queue, from [shelf], with the names, cover and disc it is shown under. A list mixes albums,
 * so each track carries its own. [radio] names the song radio that queued it, or is null (see [SongRadioRuns]).
 * [upNext] says the person added it to a queue already playing ([EXTRA_UP_NEXT]). */
data class Queued(
    val shelf: Shelf,
    val track: CloudTrack,
    val albumName: String,
    val albumArtist: String,
    val cover: Cover?,
    val disc: DiscArt?,
    val radio: String? = null,
    val upNext: Boolean = false,
)

/** One song of [collection] to queue, with the names, cover and disc of the record it is on. */
fun queuedOf(collection: Collection, track: CloudTrack): Queued {
    val album = collection.albumOf[track.rpmId]
    return Queued(
        collection.shelf,
        track,
        album?.album ?: track.album.orEmpty(),
        album?.artist ?: track.artist.orEmpty(),
        album?.let { collection.cover(it) },
        album?.let { collection.disc(it) },
    )
}

/**
 * "Start radio" on one song: the queue becomes that song, which plays now, and the playback service keeps
 * [SongRadio.AHEAD][com.rpm.player.core.library.SongRadio.AHEAD] songs like whichever one is playing queued after it
 * ([SongRadioRuns]). Whatever was queued before is replaced, as with any other play.
 */
fun Player.startRadio(song: Queued) {
    playQueue(listOf(song.copy(radio = SongRadioRuns.newRun(song.track.rpmId))), 0)
}

/** The tracks of [album] on [shelf] as a queue. */
fun queueOf(shelf: Shelf, album: CloudAlbum, cover: Cover?, disc: DiscArt?): List<Queued> =
    album.tracks.map { Queued(shelf, it, album.album, album.artist, cover, disc) }

/** Replaces the queue with [queue] and plays it from [startIndex]. The list is what the queue was started with
 * ([QueueStarted]), which a later tap that would replace the queue compares it against. */
fun Player.playQueue(queue: List<Queued>, startIndex: Int) {
    if (queue.isEmpty()) return
    val items = queue.map(::mediaItemOf)
    QueueStarted.ids = items.map { it.mediaId }
    setMediaItems(items, startIndex.coerceIn(0, queue.size - 1), /* startPositionMs = */ 0)
    prepare()
    play()
}

/** Replaces the queue with [station] and plays it. */
fun Player.tune(station: RadioStation, logoStamp: String, detail: String?) {
    setMediaItem(StationItems.itemOf(station, logoStamp, detail))
    prepare()
    play()
}

/** Puts [tracks], in order, straight after the track playing, or plays them when nothing is queued. Added to a
 * queue that plays, they are marked as the person's ([EXTRA_UP_NEXT]); played into an empty one, they are the list. */
fun Player.playNext(tracks: List<Queued>) {
    if (tracks.isEmpty()) return
    if (mediaItemCount == 0) return playQueue(tracks, 0)
    addMediaItems(currentMediaItemIndex + 1, tracks.map { mediaItemOf(it.copy(upNext = true)) })
}

/**
 * Puts [tracks], in order, straight after the track playing, and **starts nothing**: the iPhone's
 * `Player.insertNext`, which its song menu's "Play next" calls. A song sent up next is a song asked for later. Into an
 * empty queue they go as the queue, paused at its first song and not prepared, so nothing is read from the network
 * until Play; Media3 has no queue without a current item, so that song is what the mini player shows. The TV keeps
 * [playNext], which plays into an empty queue.
 */
fun Player.insertNext(tracks: List<Queued>) {
    if (tracks.isEmpty()) return
    val items = tracks.map { mediaItemOf(it.copy(upNext = true)) }
    if (mediaItemCount == 0) {
        setMediaItems(items, 0, /* startPositionMs = */ 0)
        return
    }
    addMediaItems(currentMediaItemIndex + 1, items)
}

/** Puts [tracks] at the end of the queue, or plays them when nothing is queued. Marked as [playNext] marks them. */
fun Player.addToQueue(tracks: List<Queued>) {
    if (tracks.isEmpty()) return
    if (mediaItemCount == 0) return playQueue(tracks, 0)
    addMediaItems(tracks.map { mediaItemOf(it.copy(upNext = true)) })
}

/** Set on a queued song whose tags say its lyrics are explicit. The phone's media session marks its title with
 * [SessionArtwork.EXPLICIT_MARK] for the lock screen and the notification. */
const val EXTRA_EXPLICIT = "rpm.explicit"

/** The song radio a queued item belongs to, in its metadata extras. Not kept with the saved queue: a radio ends
 * when the service does, as it does on the computer. */
const val EXTRA_RADIO = "rpm.radio"

internal fun mediaItemOf(q: Queued): MediaItem =
    MediaItem.Builder()
        .setMediaId(TrackRef(q.shelf, q.track.rpmId).mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(q.track.title)
                .setArtist(q.track.artist)
                .setAlbumTitle(q.albumName)
                .setAlbumArtist(q.albumArtist)
                .setTrackNumber(q.track.tags?.trackNumber?.takeIf { it > 0 })
                .setDurationMs(q.track.tags?.durationSeconds?.takeIf { it > 0 }?.let { (it * 1000).toLong() })
                .setExtras(
                    Bundle().apply {
                        putStringArray(EXTRA_COVER_KEYS, q.cover?.keys?.toTypedArray() ?: emptyArray())
                        putStringArray(EXTRA_DISC_KEYS, q.disc?.keys?.toTypedArray() ?: emptyArray())
                        putLong(EXTRA_DISC_STAMP, q.disc?.stamp ?: 0L)
                        // What the playback service levels the track from (LevelSink).
                        putLevel(Level.of(q.track.tags))
                        q.radio?.let { putString(EXTRA_RADIO, it) }
                        if (q.upNext) putBoolean(EXTRA_UP_NEXT, true)
                        if (q.track.isExplicit) putBoolean(EXTRA_EXPLICIT, true)
                    },
                )
                .build(),
        )
        .build()
