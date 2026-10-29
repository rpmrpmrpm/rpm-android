package com.rpm.player.tv.ui

import com.rpm.player.core.diag.Diag
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import com.rpm.player.core.playback.SeekSteps

/**
 * The player's seek bar under the remote (the desktop's SeekBar.StepBy and CommitStep, driven by
 * PegasusChrome.Adjust). Left and Right on the focused bar move a preview of the playhead, which the bar draws
 * in place of the position. The seek is made once, when the key comes up. A seek on every repeat would
 * open the stream again twenty times a second (Android repeats a held key every 50 ms).
 *
 * This is the only way the bar seeks. A preview belongs to the item that was playing when it started: a step
 * after the item changed starts again from the new item's position, and a commit after the item changed is
 * dropped. Each case is decided by asking the player which item is current at the moment of the step or the
 * commit, not by listening for the change.
 */
@Stable
class SeekKeys(private val controller: Player?) {
    /** Where the keys have put the playhead, and on which item; null while no preview is up. */
    var preview by mutableStateOf<SeekPreview?>(null)
        private set

    /** When the last Left or Right reached the bar, on the key's own clock (`KeyEvent.eventTime`). Used for
     * the rate of the held step. */
    private var steppedAt = 0L

    /**
     * One Left or Right going down on the bar. [way] is -1 for Left and 1 for Right. [repeat] is true while the
     * key is held. Returns false where nothing moved: nothing is playing, the item cannot seek, or the preview
     * is already at that end.
     */
    fun step(way: Int, repeat: Boolean, eventTimeMs: Long): Boolean {
        val since = eventTimeMs - steppedAt
        steppedAt = eventTimeMs
        val player = controller ?: return false.also { Diag.d(TAG, "no controller") }
        val item = SeekItem.of(player)
        // A preview from another item is dropped, not carried onto this one.
        val held = preview?.takeIf { it.item == item }
        if (held == null) preview = null
        val duration = player.duration
        if (item == null || !player.isCurrentMediaItemSeekable || duration == C.TIME_UNSET || duration <= 0) {
            Diag.d(TAG, "refused: item=${item?.mediaId} seekable=${player.isCurrentMediaItemSeekable} duration=$duration state=${player.playbackState}")
            return false
        }
        val from = held?.toMs ?: player.currentPosition
        val to = SeekSteps.next(from, way, repeat, since, duration)
        if (to == from) return false
        preview = SeekPreview(to, item)
        return true
    }

    /**
     * Left or Right came up on the bar, or the bar lost the focus. Seeks to the preview, if there is one and
     * the item it was made on is still the one playing. Otherwise the preview is dropped.
     */
    fun commit() {
        val held = preview ?: return
        preview = null
        val player = controller ?: return
        if (SeekItem.of(player) == held.item) player.seekTo(held.toMs) else Diag.d(TAG, "dropped: the item changed")
    }

    private companion object {
        /** Why a step did nothing, for `adb logcat -s rpm.seek`. */
        const val TAG = "rpm.seek"
    }
}

/** Where the keys have put the playhead, in milliseconds, and the item they did it on. */
data class SeekPreview(val toMs: Long, val item: SeekItem)

/** An item in the queue as a preview names it: its place and its media id. One track queued twice is two
 * items, and a queue that changes under the preview does not take the seek onto another track. */
data class SeekItem(val index: Int, val mediaId: String) {
    companion object {
        fun of(player: Player): SeekItem? =
            player.currentMediaItem?.let { SeekItem(player.currentMediaItemIndex, it.mediaId) }
    }
}
