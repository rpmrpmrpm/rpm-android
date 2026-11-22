package com.rpm.player.core.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.library.StarterTracks
import com.rpm.player.core.net.Reach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What the player does without a network, as the iPhone's `Player` does it (RPMAudio: `startPlaying`, `playable`,
 * `theLineWentAway`, `theLineCameBack`). Made by the playback service only on a device that keeps downloads (the phone,
 * through [com.rpm.player.core.Services.line]); the TV streams everything and never makes one.
 *
 * - **Offline, only what is on the phone can play.** A start goes to the first track at or after the one asked for
 *   that has a copy here ([startAt], [settle]), and the queue steps over the rest as it moves on. The queue itself is
 *   kept whole, so the records that were stepped over are still there when the network comes back. With nothing on
 *   the phone from there on, nothing starts and the reason is said ([PlaybackFailure.Offline]).
 * - **The network going stops a streamed record at once** ([theLineWentAway]), with its queue and position kept and the
 *   reason on the screen. Left alone it would play the seconds it has read ahead and stop in the middle of a phrase,
 *   which is worse. A record read from the phone plays on, and so does a station: a station is not stepped over or
 *   stopped here, it stops by itself when its stream does and says so ([StationReconnect]).
 * - **The network coming back carries on from where it stopped** ([theLineCameBack]), if that was recent enough that
 *   somebody is still waiting for it.
 *
 * Everything here runs on the main thread, where the player's listeners are called.
 *
 * @param line the settled network reading: the one the offline bar shows, so the screen and the player agree.
 * @param said called whenever [failure] changes, so the service can publish it.
 */
@OptIn(UnstableApi::class)
class OfflinePlayback(
    private val player: Player,
    private val line: StateFlow<Reach>,
    private val copies: LocalCopies,
    private val scope: CoroutineScope,
    private val said: () -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : Player.Listener {

    /** Why the player stands still, or [PlaybackFailure.None]. Cleared by the next thing that plays. */
    var failure: PlaybackFailure = PlaybackFailure.None
        private set

    /** When [failure] began, while it is one the network could undo. */
    private var failedAt: Long? = null

    val isOffline: Boolean get() = !line.value.isOnline

    private val handler = Handler(Looper.getMainLooper())

    /** The queue position whose end is being watched for ([watchTheEdge]). */
    private var edgeOf = C.INDEX_UNSET
    private val edge = Runnable { atTheEdge() }

    fun attach() {
        player.addListener(this)
        scope.launch {
            var was = line.value.isOnline
            line.collect { now ->
                if (was && !now.isOnline) theLineWentAway()
                if (!was && now.isOnline) theLineCameBack()
                was = now.isOnline
                settle()
            }
        }
    }

    /**
     * Where a list handed over to play from [asked] really starts: the iPhone's `playable(in:from:)`. Online, where it
     * was asked; offline, the first track from there that is on the phone. With none, where it was asked, and the
     * start is stopped with the reason as it arrives ([settle]).
     */
    fun startAt(items: List<MediaItem>, asked: Int): Int {
        if (!isOffline) return asked
        return (asked until items.size).firstOrNull { holds(items[it]) } ?: asked
    }

    /** A new list was handed over: what was said about the last one is over, as every start clears it on iPhone. */
    fun started() = say(PlaybackFailure.None)

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && player.isPlaying) say(PlaybackFailure.None)
        if (events.containsAny(
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_IS_PLAYING_CHANGED,
            )
        ) {
            settle()
        }
    }

    /**
     * Makes what the player is about to do something it can do offline. It is asked again at every change of the queue,
     * the place in it, the state and the network, so whichever way the player arrived at a record (a tap, Next, the
     * queue moving on, a key on the lock screen) the same rule decides.
     */
    private fun settle() {
        handler.removeCallbacks(edge)
        if (!isOffline || !player.playWhenReady) return
        val at = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        if (!holds(item)) return step(at)
        // The record plays from the phone. The one after it may not, and the player must not be left to open it: it
        // would wait at the end of this one for a network that is not there. The edge is watched instead.
        val next = at + 1
        if (player.repeatMode == Player.REPEAT_MODE_ONE || next >= player.mediaItemCount || holds(player.getMediaItemAt(next))) return
        edgeOf = at
        watchTheEdge()
    }

    /** Looks again when the record playing should have ended, or in a second when its length is not known yet. */
    private fun watchTheEdge() {
        val duration = player.duration
        val left = if (duration == C.TIME_UNSET) EDGE_POLL_MS else (duration - player.currentPosition).coerceIn(0, EDGE_POLL_MS * 10)
        handler.postDelayed(edge, left + EDGE_LATE_MS)
    }

    /**
     * The record playing has reached its end with the next one not on the phone: the queue moves to the next record that
     * is, a gap and not a stop, as the iPhone's look-ahead leaves a record it cannot open for the queue to step over.
     */
    private fun atTheEdge() {
        if (!isOffline || !player.playWhenReady || player.currentMediaItemIndex != edgeOf) return settle()
        val duration = player.duration
        val position = player.currentPosition
        val ended = player.playbackState == Player.STATE_ENDED ||
            (duration != C.TIME_UNSET && position >= duration - EDGE_MS) ||
            // The audio stopped a little short of the length the file declares, and the player waits for the next one.
            (duration != C.TIME_UNSET && player.playbackState == Player.STATE_BUFFERING && duration - position < EDGE_SHORT_MS)
        if (ended) step(edgeOf + 1) else watchTheEdge()
    }

    /**
     * Moves to the first record from [from] that is on the phone. With none, the player stands on [from] with the
     * reason, so the record that could not play is the one shown and the one that plays when the network comes back.
     */
    private fun step(from: Int) {
        handler.removeCallbacks(edge)
        val count = player.mediaItemCount
        val to = (from until count).firstOrNull { holds(player.getMediaItemAt(it)) }
        if (to != null) {
            player.seekTo(to, 0)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            return
        }
        if (from < count && from != player.currentMediaItemIndex) player.seekTo(from, 0)
        halt()
    }

    /**
     * Stops what is playing and says why. The source is let go, as the iPhone's engine is emptied, so nothing goes on
     * asking a network that is not there; the queue and the place in the record are kept for Play, or for the network
     * coming back. **The reason goes first**, so a controller that hears the pause reads it with the pause.
     */
    private fun halt() {
        say(PlaybackFailure.Offline)
        player.pause()
        player.stop()
    }

    /** The network went away: a streamed record that is playing, or on its way, stops now. */
    private fun theLineWentAway() {
        val item = player.currentMediaItem ?: return
        if (holds(item) || !player.playWhenReady) return
        halt()
    }

    /**
     * The network came back: a record that stopped for want of it plays on from where it stood, as long as that was
     * recent ([STILL_WAITING_MS]). A train out of a tunnel picks up its music; a phone that has been in a pocket since
     * the morning does not start playing in a meeting because the Wi-Fi came back. The reason stays on the screen
     * otherwise, until the next thing plays.
     */
    private fun theLineCameBack() {
        handler.removeCallbacks(edge)
        if (player.isPlaying || !failure.blamesTheNetwork || player.currentMediaItem == null) return
        val stopped = failedAt ?: return
        if (clock() - stopped >= STILL_WAITING_MS) return
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    private fun say(now: PlaybackFailure) {
        if (failure == now) return
        failure = now
        failedAt = if (now.blamesTheNetwork) clock() else null
        said()
    }

    /** Whether [item] can play without a network: a track on the phone, or anything that is not a track, which this
     * rule leaves to itself. */
    private fun holds(item: MediaItem): Boolean = TrackRef.parse(item.mediaId)?.let { holds(copies, it) } ?: true

    companion object {
        /** How long after a record stopped for want of a network it is still the one somebody is waiting for: the
         * iPhone's `stillWaiting`. */
        const val STILL_WAITING_MS = 120_000L

        /** How close to its declared length a record counts as ended. */
        private const val EDGE_MS = 120L

        /** How far short of its declared length a record that has stopped producing sound counts as ended. */
        private const val EDGE_SHORT_MS = 1_500L

        /** How long after the expected end the edge is looked at, so the last of the sound has been heard. */
        private const val EDGE_LATE_MS = 60L

        private const val EDGE_POLL_MS = 1_000L

        /** Whether the track [ref] is on this device. Only the person's own records are ever downloaded, and a starter
         * track is always there, inside the app. */
        fun holds(copies: LocalCopies, ref: TrackRef): Boolean =
            ref.shelf == Shelf.Own && (copies.holds(ref.rpmId) || StarterTracks.isStarter(ref.rpmId))
    }
}

/**
 * The player's retry rule, with the network gone. A track that is not on the phone is not given up on: its load is
 * tried again every [WAIT_MS] for as long as the phone is offline, so the player never turns a missing network into
 * an error ("Playback failed") about a record it has only read ahead to. [OfflinePlayback] decides what plays instead,
 * and when the network comes back the next try simply works. Everything else fails as it does online.
 */
@OptIn(UnstableApi::class)
class OfflineRetries(
    private val online: LoadErrorHandlingPolicy,
    private val offline: () -> Boolean,
    private val copies: LocalCopies,
) : LoadErrorHandlingPolicy by online {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (!offline()) return online.getRetryDelayMsFor(loadErrorInfo)
        val ref = CloudTrackUri.ref(loadErrorInfo.loadEventInfo.dataSpec.uri)
        if (ref != null && !OfflinePlayback.holds(copies, ref)) return WAIT_MS
        // Offline, the count below is not consulted (see getMinimumLoadableRetryCount), so the limit is kept here.
        if (loadErrorInfo.errorCount > online.getMinimumLoadableRetryCount(loadErrorInfo.mediaLoadData.dataType)) return C.TIME_UNSET
        return online.getRetryDelayMsFor(loadErrorInfo)
    }

    /** Offline, an error that is being tried again is not reported, whatever its count: the limit for everything that is
     * not a missing track is applied in [getRetryDelayMsFor] instead, which can tell them apart. */
    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        if (offline()) Int.MAX_VALUE else online.getMinimumLoadableRetryCount(dataType)

    private companion object {
        const val WAIT_MS = 1_000L
    }
}
