package com.rpm.player.phone.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import coil3.ImageLoader
import com.rpm.player.core.R
import com.rpm.player.core.playback.QueueLook
import com.rpm.player.core.playback.QueueRow
import com.rpm.player.core.playback.QueueRows
import com.rpm.player.core.playback.Sitting
import com.rpm.player.phone.design.BackKey
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.SwipeKey
import com.rpm.player.phone.design.SwipeRow
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.dissolvesAtTop
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.design.scrolled
import com.rpm.player.phone.player.QueueEntry
import com.rpm.player.phone.player.QueueRead
import com.rpm.player.core.room.RoomPalette
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import com.rpm.player.phone.design.ScrollsToTop

/** The red of a key that takes something away. */
private val REMOVE = Color(0xFFE5484D)

/**
 * How close to the top or bottom edge of the list a carried row must be before the list scrolls. The list scrolls by
 * EDGE_STEP pixels per frame there.
 */
private val EDGE_ROOM = 48.dp
private const val EDGE_STEP = 18f

/** How long a let-go row waits for the queue to show its move before it is put down anyway. */
private const val LANDING_MS = 600L

/**
 * The queue. It matches the iPhone's `PlaylistPage` when it shows the running order (RPMUI/PlaylistPage.swift,
 * `isQueue`). The layout is a list's page, but there is no Play key. The queue is either already playing, or
 * there is nothing to play. The list starts at the point where the sitting began ([Sitting]). So a record
 * started at track ten does not list the nine tracks above it as played. A tap plays from that song. A row
 * pulled left is removed. A row pulled right is played next, unless it is the song playing or the song that is
 * already next.
 *
 * Rearranging is a mode, as on the iPhone. Its key shows a grip on every song still to come. A song carried by
 * its grip goes where it is let go, never above the song playing ([QueueRows.drop]). Songs already played, and
 * the song playing, are not moved.
 *
 * Every action is given the row's place in the queue. At the moment it acts, it checks that the queue still
 * holds that song at that place. The queue can change under the page, for example through another controller,
 * or when the player moves on.
 *
 * A song's long press opens the song menu ([LocalSongMenu]), which the app gives this page with no play next
 * ([UpNext.ItIsTheQueue]): the swipe moves a song up instead. Rearranging has no menu.
 *
 * @param sittingFrom where the session says the sitting begins.
 */
@Composable
fun QueuePage(queue: QueueRead, sittingFrom: Int, controller: Player?, images: ImageLoader, bottomRoom: Dp, onBack: () -> Unit) {
    var asked by rememberSaveable { mutableStateOf(false) }
    val page = rememberBackdrop()
    val list = rememberLazyListState()
    ScrollsToTop(list)
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.asPaddingValues(density).calculateTopPadding() + BACK_ROW
    val look = QueueLook.sitting(Sitting.shownFrom(sittingFrom, queue.entries.size, queue.playing), queue.playing)
    val rows = remember(queue, look.from) { QueueRows.of(queue.entries, look) { it.mediaId } }
    val carry = remember { Carry() }
    // Rearranging needs two songs or more. With fewer, there is nothing to rearrange.
    val rearranging = asked && rows.size > 1
    BackHandler(onBack = { if (rearranging) asked = false else onBack() })

    // When the queue is read again after the carried row was let go, the move has landed. The row stops being
    // carried in the same frame as it takes its new place. So it does not jump back to the old place first.
    LaunchedEffect(queue) { if (carry.landed) carry.clear() }
    // If the queue never shows the move (another edit took the row first), the row is still released after a short
    // wait.
    LaunchedEffect(carry.landed) {
        if (carry.landed) {
            delay(LANDING_MS)
            carry.clear()
        }
    }

    // The drop as it is now. It is read here because the gesture began on an earlier pass.
    val drop by rememberUpdatedState {
        val from = rows.indexOfFirst { it.id == carry.id }
        val over = hoverOf(list, rows, carry)
        val move = if (from >= 0 && over != null) QueueRows.drop(rows, look, from, if (over > from) over + 1 else over, queue.entries.size) else null
        val mediaId = rows.getOrNull(from)?.item?.mediaId
        if (move != null && mediaId != null && controller?.holds(move.first, mediaId) == true) {
            controller.moveMediaItem(move.first, move.second)
            carry.landed = true
        } else {
            carry.clear()
        }
    }

    // When a carried row is near the top or bottom of the list, the list scrolls. The row moves with the scroll,
    // so it stays under the finger.
    val edge = with(density) { EDGE_ROOM.toPx() }
    val foot = with(density) { bottomRoom.toPx() }
    LaunchedEffect(carry.id) {
        while (carry.id != null && !carry.landed) {
            val info = list.layoutInfo
            val mine = info.visibleItemsInfo.firstOrNull { it.key == carry.id }
            if (mine != null) {
                val centre = mine.offset + carry.dy + mine.size / 2f
                val step = when {
                    centre < info.viewportStartOffset + edge -> -EDGE_STEP
                    centre > info.viewportEndOffset - foot - edge -> EDGE_STEP
                    else -> 0f
                }
                if (step != 0f) carry.dy += list.scrollBy(step)
            }
            delay(16)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sleeve = (maxWidth.value * COVER_FRACTION).coerceIn(160f, 360f).roundToInt().dp
        Box(Modifier.fillMaxSize().backdropSource(page)) {
            // The queue is a list's page on iPhone, and stands on the list's room; nobody made a picture for it, so the
            // room is in the app's own blue.
            RoomGround(RoomPalette.FALLBACK, seed = stringResource(R.string.AlbumMenu_PlaylistNowPlaying))
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().dissolvesAtTop(top) { list.scrolled() }) {
                item(key = "header") {
                    val seconds = rows.sumOf { it.item.track.tags?.durationSeconds ?: 0.0 }.toInt()
                    ListHead(
                        stringResource(R.string.AlbumMenu_PlaylistNowPlaying),
                        rows.firstOrNull()?.item?.cover,
                        holding(rows.size, seconds, 0),
                        about = null,
                        aboutLines = 0,
                        images = images,
                        edge = sleeve,
                        top = top,
                    ) {
                        // The round keys of a list's page, without Play: what is in the queue is already playing.
                        SideKey(Glyph.shuffle, stringResource(R.string.AlbumMenu_Shuffle), { controller?.shuffleQueue() }, enabled = rows.isNotEmpty())
                        // Shown only when there is something to rearrange. The tick leaves rearranging mode. It is not
                        // a play mark, because a play mark here would look like "play this".
                        if (rows.size > 1) {
                            SideKey(
                                if (rearranging) Glyph.check else Glyph.grip,
                                stringResource(if (rearranging) R.string.CloudChip_StageDone else R.string.Rpm_Queue_Rearrange),
                                { asked = !rearranging },
                            )
                        }
                    }
                }
                if (rows.isEmpty()) {
                    item(key = "nothing") {
                        BasicText(
                            stringResource(R.string.Rpm_Queue_Empty),
                            Modifier.fillMaxWidth().padding(40.dp),
                            style = display(15f).copy(color = Ink.white(0xCC), textAlign = TextAlign.Center),
                        )
                    }
                }
                items(rows, key = { it.id }) { row ->
                    val shift by animateFloatAsState(shiftOf(row, rows, list, carry), label = "make room")
                    val carried = row.id == carry.id
                    Box(
                        Modifier
                            .zIndex(if (carried) 1f else 0f)
                            .graphicsLayer { translationY = if (carried) carry.dy else shift }
                            .background(if (carried) Ink.white(0x14) else Color.Transparent),
                    ) {
                        if (rearranging) {
                            CompositionLocalProvider(LocalSongMenu provides null) { Rearranged(row, images, carry, list, onDrop = { drop() }) }
                        } else {
                            QueueLine(row, queue.playing, controller, images)
                        }
                    }
                }
                item(key = "foot") { Spacer(Modifier.height(40.dp + bottomRoom)) }
            }
        }
        Box(Modifier.padding(top = top - BACK_ROW).height(BACK_ROW).padding(start = 20.dp), contentAlignment = Alignment.Center) {
            BackKey(page, onBack = { if (rearranging) asked = false else onBack() })
        }
    }
}

/**
 * One song of the queue. Remove is under its right edge. Where the song can move up, Play next is under its left edge.
 */
@Composable
private fun QueueLine(row: QueueRow<QueueEntry>, playing: Int?, controller: Player?, images: ImageLoader) {
    val at = row.at
    val id = row.item.mediaId
    val canMoveUp = at != playing && at != (playing ?: -1) + 1
    SwipeRow(
        leading = if (canMoveUp) SwipeKey(Glyph.next, stringResource(R.string.AlbumMenu_PlayNext), Ink.white(0x33)) { controller?.playNext(at, id) } else null,
        trailing = SwipeKey(Glyph.remove, stringResource(R.string.Details_RemoveArt), REMOVE) { controller?.remove(at, id) },
    ) {
        ListSongRow(row.item.track, row.item.cover, images, playing = row.state == QueueRow.State.Playing) { controller?.jump(at, id) }
    }
}

/**
 * One song while rearranging. It cannot be pulled or tapped. A grip is shown at its end if the song is still to come.
 */
@Composable
private fun Rearranged(row: QueueRow<QueueEntry>, images: ImageLoader, carry: Carry, list: LazyListState, onDrop: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            ListSongRow(row.item.track, row.item.cover, images, playing = row.state == QueueRow.State.Playing) {}
        }
        if (row.state == QueueRow.State.Coming) {
            Box(
                Modifier
                    .width(48.dp)
                    .height(56.dp)
                    .pointerInput(row.id) {
                        detectDragGestures(
                            onDragStart = {
                                carry.id = row.id
                                carry.dy = 0f
                                carry.landed = false
                                carry.size = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == row.id }?.size ?: 0
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                carry.dy += amount.y
                            },
                            onDragEnd = onDrop,
                            onDragCancel = carry::clear,
                        )
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicText(Glyph.grip, style = icon(16f).copy(color = Ink.white(0x88)))
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

/**
 * The row being carried while rearranging. It stores which row it is, how far the finger has moved it, how
 * tall it is, and whether it has been let go with its move sent. A let-go row waits for the queue to show the move.
 */
private class Carry {
    var id by mutableStateOf<String?>(null)
    var dy by mutableFloatStateOf(0f)
    var size by mutableIntStateOf(0)
    var landed by mutableStateOf(false)

    fun clear() {
        id = null
        dy = 0f
        size = 0
        landed = false
    }
}

/**
 * The row that the carried row is over, as an index in the queue. Null when nothing is carried. It is read
 * from where the rows are laid out on screen, not from where they have been moved aside to. It is never above
 * the first song still to come, because that is the highest a song can land.
 */
private fun hoverOf(list: LazyListState, rows: List<QueueRow<QueueEntry>>, carry: Carry): Int? {
    val id = carry.id ?: return null
    val seen = list.layoutInfo.visibleItemsInfo
    val mine = seen.firstOrNull { it.key == id } ?: return null
    val centre = mine.offset + carry.dy + mine.size / 2f
    val places = rows.withIndex().associate { it.value.id to it.index }
    val shown = seen.filter { it.key in places }
    if (shown.isEmpty()) return null
    val under = shown.firstOrNull { centre >= it.offset && centre < it.offset + it.size }
        ?: if (centre < shown.first().offset) shown.first() else shown.last()
    val first = rows.indexOfFirst { it.state == QueueRow.State.Coming }.coerceAtLeast(0)
    return maxOf(places.getValue(under.key as String), first)
}

/**
 * How far a row moves aside to make room for the carried row. It moves up by the carried row's height when the
 * carried row passes it going down. It moves down when the carried row passes it going up.
 */
private fun shiftOf(row: QueueRow<QueueEntry>, rows: List<QueueRow<QueueEntry>>, list: LazyListState, carry: Carry): Float {
    val id = carry.id ?: return 0f
    if (row.id == id) return 0f
    val from = rows.indexOfFirst { it.id == id }
    val over = hoverOf(list, rows, carry) ?: return 0f
    val at = rows.indexOf(row)
    return when {
        over > from && at in (from + 1)..over -> -carry.size.toFloat()
        over < from && at in over until from -> carry.size.toFloat()
        else -> 0f
    }
}

/** Whether the queue still holds [mediaId] at [at]: every edit asks this at the moment it acts. */
private fun Player.holds(at: Int, mediaId: String): Boolean = at in 0 until mediaItemCount && getMediaItemAt(at).mediaId == mediaId

/** Plays from the song at [at]. */
private fun Player.jump(at: Int, mediaId: String) {
    if (!holds(at, mediaId)) return
    seekToDefaultPosition(at)
    if (playbackState == Player.STATE_IDLE) prepare()
    play()
}

/** Takes the song at [at] out of the queue. */
private fun Player.remove(at: Int, mediaId: String) {
    if (holds(at, mediaId)) removeMediaItem(at)
}

/**
 * Moves the song at [at] to just after the song playing. A row above the playing song moves the playing song up one
 * place when it leaves. So it lands at the old place of the playing song.
 */
private fun Player.playNext(at: Int, mediaId: String) {
    val current = currentMediaItemIndex
    if (!holds(at, mediaId) || at == current || at == current + 1) return
    moveMediaItem(at, if (at < current) current else current + 1)
}

/** Plays the whole queue again in another order, from the top: a new sitting (`Player.shuffleTheQueue`). */
private fun Player.shuffleQueue() {
    if (mediaItemCount == 0) return
    setMediaItems((0 until mediaItemCount).map { getMediaItemAt(it) }.shuffled(), 0, 0)
    prepare()
    play()
}
