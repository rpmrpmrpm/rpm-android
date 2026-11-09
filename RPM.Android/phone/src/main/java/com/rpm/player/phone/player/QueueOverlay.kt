package com.rpm.player.phone.player

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import com.rpm.player.core.playback.SongRadioRuns
import com.rpm.player.phone.profile.Switch
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import com.rpm.player.core.R
import com.rpm.player.core.playback.QueueLook
import com.rpm.player.core.playback.QueueRow
import com.rpm.player.core.playback.QueueRows
import com.rpm.player.core.playback.Sitting
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.CHROME_BLUR
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.rememberHaptics
import com.rpm.player.phone.design.SwipeKey
import com.rpm.player.phone.design.SwipeRow
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.design.frost
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.kicker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How tall the panel is at most, and so what a pull is measured against: the frame and the scrim use one number. */
private val PANEL = 520.dp

/** Every row is one line of title over one of artist, so every row is this tall, which is what a drag counts in. */
private val ROW = 54.dp

/** `spring(duration: 0.32)`, the drawer's own spring. */
private const val DRAWER_STIFFNESS = 385f

/** The red of a key that takes something away. */
private val REMOVE = Color(0xFFE5484D)

/**
 * The queue, drawn over the player instead of pushed as a page: the iPhone's `QueueOverlay` (RPMUI/QueueOverlay.swift).
 * The queue belongs to what is already on the screen, so the panel rises from the foot over the sheet, on the bar's
 * glass, and stops below the top: the record above it shows this is still the player, and a tap there closes it.
 *
 * The list starts where the sitting began, so a record started at track ten does not list nine tracks as played. A tap
 * plays from that song; a row pulled left is removed, one pulled right is played next. Rearranging is a mode with a grip
 * on each song still to come, and a drop never lands above the song playing ([QueueRows.drop]). Every action is given the
 * row's place in the queue, and checks the queue still holds that song there when it acts.
 */
@Composable
internal fun QueueOverlay(queue: QueueRead, sittingFrom: Int, player: Player, backdrop: Backdrop, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val panelPx = with(density) { PANEL.toPx() }
    val travel = remember { Animatable(panelPx) }
    var up by remember { mutableStateOf(true) }
    val drawerSpring = spring<Float>(dampingRatio = 1f, stiffness = DRAWER_STIFFNESS)
    LaunchedEffect(Unit) { travel.animateTo(0f, drawerSpring) }
    fun putAway() {
        if (!up) return
        up = false
        scope.launch {
            travel.animateTo(panelPx, drawerSpring)
            onClose()
        }
    }
    BackHandler { putAway() }

    Box(Modifier.fillMaxSize()) {
        // The dark fades with the list: a scrim that stayed black behind a panel half off the bottom would look like two
        // things moving apart.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - (travel.value / panelPx).coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0x59 / 255f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { putAway() },
        )
        val plate = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = PANEL)
                .fillMaxHeight()
                .offset { IntOffset(0, travel.value.roundToInt()) }
                .frost(backdrop, plate, CHROME_BLUR)
                .border(1.dp, Ink.glassEdge, plate)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding(),
        ) {
            Panel(
                queue,
                sittingFrom,
                player,
                onPull = { delta -> scope.launch { travel.snapTo((travel.value + delta).coerceAtLeast(0f)) } },
                onLetGo = { velocity ->
                    val far = with(density) { 120.dp.toPx() }
                    val thrown = with(density) { 260.dp.toPx() }
                    if (travel.value > far || travel.value + velocity * 0.5f > thrown) putAway()
                    else scope.launch { travel.animateTo(0f, spring(dampingRatio = 1f, stiffness = 439f)) }
                },
                onTap = { putAway() },
            )
        }
    }
}

/** The drawer's grip, heading and list. Rearranging is off each time the list comes up. */
@Composable
private fun Panel(queue: QueueRead, sittingFrom: Int, player: Player, onPull: (Float) -> Unit, onLetGo: (Float) -> Unit, onTap: () -> Unit) {
    var rearranging by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val look = QueueLook.sitting(Sitting.shownFrom(sittingFrom, queue.entries.size, queue.playing), queue.playing)
    val rows = remember(queue, look.from) { QueueRows.of(queue.entries, look) { it.mediaId } }
    val coming = rows.count { it.state == QueueRow.State.Coming }

    Column(
        Modifier
            .fillMaxWidth()
            .draggable(rememberDraggableState(onPull), Orientation.Vertical, onDragStopped = { onLetGo(it) })
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(36.dp, 5.dp).clip(RoundedCornerShape(percent = 50)).background(Color(0x4DEBEBF5)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                BasicText(stringResource(R.string.Viz_Queue), style = displayBold(18f).copy(color = Color.White))
                val left = (queue.entries.size - ((queue.playing ?: -1) + 1)).coerceAtLeast(0)
                BasicText(if (left == 1) stringResource(R.string.Rpm_Queue_ToComeOne) else stringResource(R.string.Rpm_Queue_ToCome, left), style = kicker())
            }
            // Another order for what has not been played: one press, not a mode. Hidden where it could change nothing.
            if (coming >= 2 && !rearranging) {
                HeadKey(Glyph.shuffle, stringResource(R.string.Rpm_Queue_Shuffle)) {
                    haptics.commit()
                    player.shuffleWhatIsComing()
                }
            }
            if (queue.entries.size > 1) {
                // A tick to leave the mode, not a play mark, which would read as "play this".
                HeadKey(
                    if (rearranging) Glyph.check else Glyph.grip,
                    stringResource(if (rearranging) R.string.CloudChip_StageDone else R.string.Rpm_Queue_Rearrange),
                    Modifier.offset(x = 6.dp),
                ) {
                    haptics.pick()
                    rearranging = !rearranging
                }
            }
        }
        AutoMixtape()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.white(0x1A)))
    if (rows.isEmpty()) {
        BasicText(
            stringResource(R.string.Rpm_Queue_Empty),
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
            style = display(13f).copy(color = Ink.white(0x88), textAlign = TextAlign.Center),
        )
    } else {
        Rows(rows, look, queue, player, rearranging && queue.entries.size > 1)
    }
}

@Composable
private fun HeadKey(glyph: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(38.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(glyph, style = icon(15f).copy(color = Ink.white(0xB8)))
    }
}

/**
 * The auto mixtape switch: on, an album, a playlist or a single that ends goes on as a mixtape of songs like it
 * ([SongRadioRuns.MIXTAPE_AFTER], which the playback service reads when a last song starts). The TV switches the same
 * setting in its player settings. Off until the person turns it on.
 */
@Composable
private fun AutoMixtape() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SongRadioRuns.SETTINGS_FILE, Context.MODE_PRIVATE) }
    var on by remember { mutableStateOf(prefs.getBoolean(SongRadioRuns.MIXTAPE_AFTER, SongRadioRuns.MIXTAPE_AFTER_PHONE)) }
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Switch) {
                haptics.pick()
                on = !on
                prefs.edit().putBoolean(SongRadioRuns.MIXTAPE_AFTER, on).apply()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(stringResource(R.string.Rpm_Queue_AutoMixtape), Modifier.weight(1f), style = display(14f).copy(color = Color.White))
        Spacer(Modifier.width(12.dp))
        Switch(on)
    }
}

/**
 * The heading over a part of the queue: the songs the person added, a mixtape's, or what was played where it carries on
 * after one of those, named by its record. The songs of that part follow it, up to where another part begins.
 */
@Composable
private fun PartHeading(part: QueuePart, record: String?) {
    val said = when (part) {
        QueuePart.Yours -> stringResource(R.string.Rpm_Queue_YourQueue)
        QueuePart.Mixtape -> stringResource(R.string.Rpm_Queue_Mixtape)
        // What was played, where it carries on after the songs added before it: named by its record.
        QueuePart.List -> record?.takeIf { it.isNotBlank() } ?: return
    }
    BasicText(said.uppercase(), Modifier.fillMaxWidth().padding(start = 22.dp, top = 14.dp, bottom = 4.dp), style = kicker())
}

/**
 * The list. A carried row moves with the finger, and the rows it passes step aside by one row.
 *
 * A heading goes before the first song of each part that was added: the songs the person added, and a mixtape's
 * ([QueuePart], [PartHeading]). Not while rearranging: a carried song is placed by how many rows it passes, and a heading
 * among them would put it one row off.
 */
@Composable
private fun Rows(rows: List<QueueRow<QueueEntry>>, look: QueueLook, queue: QueueRead, player: Player, rearranging: Boolean) {
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val rowPx = with(density) { ROW.toPx() }
    var carried by remember { mutableStateOf<String?>(null) }
    var dy by remember { mutableFloatStateOf(0f) }
    val haptics = rememberHaptics()
    val from = rows.indexOfFirst { it.id == carried }
    // Where the carried row would land, as a place in the list, never above the first song still to come.
    val first = rows.indexOfFirst { it.state == QueueRow.State.Coming }.coerceAtLeast(0)
    val over = if (from < 0) -1 else (from + (dy / rowPx).roundToInt()).coerceIn(first, rows.lastIndex)

    // A carried row near the top or the foot of the list scrolls it, and moves with the scroll so it stays under the finger.
    LaunchedEffect(carried) {
        while (carried != null) {
            val info = list.layoutInfo
            val mine = info.visibleItemsInfo.firstOrNull { it.key == carried }
            if (mine != null) {
                val centre = mine.offset + dy + mine.size / 2f
                val edge = rowPx
                val step = when {
                    centre < info.viewportStartOffset + edge -> -12f
                    centre > info.viewportEndOffset - edge -> 12f
                    else -> 0f
                }
                if (step != 0f) dy += list.scrollBy(step)
            }
            delay(16)
        }
    }

    LazyColumn(state = list, modifier = Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, part ->
            // A heading where a part begins: an added part anywhere, and what was played where it carries on after one.
            val begins = if (index == 0) part.item.part != QueuePart.List else rows[index - 1].item.part != part.item.part
            if (begins && !rearranging) item(key = "part:" + part.id, contentType = "heading") { PartHeading(part.item.part, part.item.track.album) }
            item(key = part.id, contentType = "song") {
                val row = part
                val at = index
                val aside = when {
                    from < 0 || row.id == carried -> 0f
                    over > from && at in (from + 1)..over -> -rowPx
                    over < from && at in over until from -> rowPx
                    else -> 0f
                }
                val shift by animateFloatAsState(aside, label = "make room")
                val isCarried = row.id == carried
                Box(
                    Modifier
                        .zIndex(if (isCarried) 1f else 0f)
                        .graphicsLayer { translationY = if (isCarried) dy else shift }
                        .background(if (isCarried) Ink.white(0x14) else Color.Transparent),
                ) {
                    if (rearranging) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { Song(row, trailingRoom = false) {} }
                            if (row.state == QueueRow.State.Coming) {
                                Box(
                                    Modifier
                                        .width(48.dp)
                                        .height(ROW)
                                        .pointerInput(row.id) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    carried = row.id
                                                    dy = 0f
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    dy += amount.y
                                                },
                                                onDragEnd = {
                                                    val source = rows.indexOfFirst { it.id == carried }
                                                    val target = if (source < 0) -1 else (source + (dy / rowPx).roundToInt()).coerceIn(first, rows.lastIndex)
                                                    val move = if (source >= 0 && target >= 0) {
                                                        QueueRows.drop(rows, look, source, if (target > source) target + 1 else target, queue.entries.size)
                                                    } else null
                                                    val id = rows.getOrNull(source)?.item?.mediaId
                                                    if (move != null && id != null && player.holds(move.first, id)) {
                                                        haptics.tick()
                                                        player.moveMediaItem(move.first, move.second)
                                                    }
                                                    carried = null
                                                    dy = 0f
                                                },
                                                onDragCancel = {
                                                    carried = null
                                                    dy = 0f
                                                },
                                            )
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    BasicText(Glyph.grip, style = icon(15f).copy(color = Ink.white(0x88)))
                                }
                            } else {
                                Spacer(Modifier.width(48.dp))
                            }
                        }
                    } else {
                        val playing = queue.playing
                        val canMoveUp = row.at != playing && row.at != (playing ?: -1) + 1
                        val id = row.item.mediaId
                        SwipeRow(
                            leading = if (canMoveUp) SwipeKey(Glyph.next, stringResource(R.string.AlbumMenu_PlayNext), Ink.white(0x33)) { haptics.commit(); player.playNext(row.at, id) } else null,
                            trailing = SwipeKey(Glyph.remove, stringResource(R.string.Details_RemoveArt), REMOVE) { haptics.commit(); player.remove(row.at, id) },
                        ) {
                            Song(row, trailingRoom = true) { player.jump(row.at, id) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One song: the playing mark in the list's own left margin, so every title starts at the same place, the title over the
 * artist, and its length. Songs already played are dimmer: the list reads from the needle down.
 */
@Composable
private fun Song(row: QueueRow<QueueEntry>, trailingRoom: Boolean, onClick: () -> Unit) {
    val track = row.item.track
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(end = if (trailingRoom) 18.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(start = 7.dp)
                .size(3.dp, 26.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(if (row.state == QueueRow.State.Playing) Color.White else Color.Transparent),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BasicText(track.title.orEmpty(), maxLines = 1, style = display(15f).copy(color = if (row.state == QueueRow.State.Played) Ink.white(0x77) else Color.White))
            BasicText(track.artist.orEmpty(), maxLines = 1, style = display(12f).copy(color = Ink.white(0x88)))
        }
        val seconds = (track.tags?.durationSeconds ?: 0.0).toInt()
        if (seconds > 0) BasicText("%d:%02d".format(seconds / 60, seconds % 60), style = display(12f).copy(color = Ink.white(0x66)))
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

private fun Player.remove(at: Int, mediaId: String) {
    if (holds(at, mediaId)) removeMediaItem(at)
}

/** Moves the song at [at] to just after the song playing. */
private fun Player.playNext(at: Int, mediaId: String) {
    val current = currentMediaItemIndex
    if (!holds(at, mediaId) || at == current || at == current + 1) return
    moveMediaItem(at, if (at < current) current else current + 1)
}

/** Another order for the songs after the one playing, the iPhone's `shuffleWhatIsComing`. What has played, and the song
 * playing, stay where they are. */
private fun Player.shuffleWhatIsComing() {
    val start = currentMediaItemIndex + 1
    if (mediaItemCount - start < 2) return
    val coming = (start until mediaItemCount).map { getMediaItemAt(it) }.shuffled()
    removeMediaItems(start, mediaItemCount)
    addMediaItems(start, coming)
}
