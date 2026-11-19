package com.rpm.player.phone.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.PlaylistBrowse
import com.rpm.player.core.library.PlaylistRow
import com.rpm.player.core.library.TrackList
import com.rpm.player.core.library.listName
import com.rpm.player.phone.design.Alert
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.DialogKey
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.GlassCard
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.KeyRole
import com.rpm.player.phone.design.Weight
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.ui.RecordSpinner
import kotlinx.coroutines.launch

/**
 * Where a song goes: the playlist tree, one folder at a time, with an Add key at the foot. It matches the iPhone's
 * `PlaylistPickSheet` (RPMUI/PlaylistPickSheet.swift), shown over everything from the root, as the iPhone's `filing`
 * overlay.
 *
 * **A tap on a playlist chooses it, and the Add key adds the song.** A tap does not add the song at once: a folder and a
 * playlist look alike in a list of places, and a tap that cannot be undone is wrong for somebody walking through a
 * tree.
 *
 * **Only this account's own stored lists are offered, and the filtering is done here**, not by the caller. On another
 * account's shelf the lists are that account's, which the store would refuse to change, and the desktop's generated
 * lists are written by the desktop alone. So a caller that filters and one that forgets get the same screen. A shelf
 * whose lists all belong to someone else says so, which is a third kind of empty, apart from no playlists at all and
 * an empty folder.
 *
 * **A playlist can hold the same song twice, but nobody does it by accident.** When the chosen list already holds the
 * song, as the copy on this phone shows it, the sheet asks before adding anything, and Add again is the only way to put
 * in a second copy. The write reads the store again, so a list that gained the song on another device a moment ago
 * answers "already in" rather than taking a second copy unasked.
 *
 * The iPhone's sheet has no entry for a new playlist, so neither has this one.
 *
 * @param onAdd adds the song to the list, a second copy only when `again` says so, and answers with what went wrong,
 * or null when it went in.
 */
@Composable
fun PlaylistPickSheet(
    track: CloudTrack,
    collection: Collection,
    backdrop: Backdrop,
    onAdd: suspend (TrackList, CloudTrack, again: Boolean) -> String?,
    onClose: () -> Unit,
) {
    val own = collection.shelf == Shelf.Own
    val playlists = remember(collection) { if (own) collection.lists.filter { !it.generated } else emptyList() }
    val borrowed = !own && collection.lists.any { !it.generated }
    val resources = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var at by remember { mutableStateOf(emptyList<String>()) }
    var picked by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    // The list Add was pressed for, while the question about it is on screen. The list itself, not a flag: the person
    // confirms a second copy in *this* list, the one the question names, whatever is chosen when the key is pressed.
    var asking by remember { mutableStateOf<TrackList?>(null) }

    val browse = remember(at, playlists) {
        PlaylistBrowse.of(at, playlists, { listName(it.name, resources) })
    }
    val chosen = picked?.let { id -> playlists.firstOrNull { it.id == id } }

    fun file(into: TrackList, again: Boolean) {
        if (busy) return
        busy = true
        said = null
        scope.launch {
            val trouble = onAdd(into, track, again)
            busy = false
            if (trouble == null) onClose() else said = trouble
        }
    }

    fun add() {
        val list = chosen ?: return
        if (busy) return
        // Already in the list, as this phone holds it: ask, and add nothing until the person answers.
        if (list.tracks.any { it.rpmId == track.rpmId }) asking = list else file(list, again = false)
    }

    BackHandler(enabled = asking == null) { onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0x99 / 255f))
            // A tap anywhere outside the panel closes it without adding anything.
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(
            backdrop,
            Modifier
                .width(320.dp)
                // A tap on the panel itself stays on the panel.
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
            radius = 18.dp,
        ) {
            Column {
                Header(browse, track, onBack = { at = it; picked = null }, onClose = onClose)
                Line()
                val rows = browse.rows
                if (rows.isEmpty()) {
                    BasicText(
                        stringResource(
                            when {
                                borrowed -> R.string.Rpm_PlaylistPick_Borrowed
                                browse.at.isEmpty() -> R.string.Rpm_PlaylistPick_Empty
                                else -> R.string.Rpm_PlaylistPick_EmptyFolder
                            },
                        ),
                        Modifier.fillMaxWidth().padding(18.dp),
                        style = kicker().copy(textAlign = TextAlign.Center),
                    )
                } else {
                    // As tall as its rows, up to 300 dp, then it scrolls: the iPhone's `ShortScroll`.
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp + 12.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(rows, key = { it.id }, contentType = { if (it is PlaylistRow.Folder) 0 else 1 }) { row ->
                            Place(row, picked = row.id == picked) {
                                said = null
                                when (row) {
                                    is PlaylistRow.Folder -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        at = row.folder.path
                                        picked = null
                                    }
                                    else -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                        picked = if (picked == row.id) null else row.id
                                    }
                                }
                            }
                        }
                    }
                }
                Line()
                Deed(said, busy, ready = chosen != null && !busy, onCancel = onClose, onAdd = ::add)
            }
        }
    }

    asking?.let { list ->
        Alert(
            backdrop,
            stringResource(R.string.Rpm_PlaylistPick_AlreadyIn, listName(list.name)),
            stringResource(R.string.Rpm_PlaylistPick_AlreadyInBody),
            listOf(
                DialogKey(stringResource(R.string.Cloud_Cancel), KeyRole.Cancel) { asking = null },
                DialogKey(stringResource(R.string.Rpm_PlaylistPick_AddAgain)) {
                    asking = null
                    file(list, again = true)
                },
            ),
        )
    }
}

/**
 * The song being added, under the name of where the person stands. The row it came from is no longer on screen while
 * this shows, and a picker that does not name its song makes the person remember it.
 */
@Composable
private fun Header(browse: PlaylistBrowse, track: CloudTrack, onBack: (List<String>) -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            browse.back?.let { back -> FlatKey(Glyph.back) { onBack(back) } }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                BasicText(
                    browse.at.lastOrNull() ?: stringResource(R.string.Playlists_AddToPlaylist),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = displayBold(19f).copy(color = Color.White),
                )
                BasicText(track.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = kicker())
            }
        }
        FlatKey(Glyph.close, onClose)
    }
}

/**
 * A round key on the glass, 40 dp: the iPhone's `RPMGlassKey` as it is drawn on a card, a flat fill and the glass edge,
 * never a second frost.
 */
@Composable
private fun FlatKey(glyph: String, onClick: () -> Unit) {
    val presses = remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Ink.ghostFace)
            .border(1.dp, Ink.glassEdge, CircleShape)
            .clickable(presses, indication = null, role = Role.Button, onClick = onClick)
            .alpha(if (pressed) 0.8f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(glyph, style = icon(15f).copy(color = Ink.white(0xB8)))
    }
}

/**
 * One place: a folder, which opens, or a playlist, which is chosen. A chevron on a folder, a tick on the chosen
 * playlist, never both.
 */
@Composable
private fun Place(row: PlaylistRow, picked: Boolean, onClick: () -> Unit) {
    val presses = remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    val folder = row is PlaylistRow.Folder
    val (name, detail) = when (row) {
        is PlaylistRow.Folder -> row.folder.name to
            if (row.folder.playlists == 1) stringResource(R.string.Stats_PlaylistsOne) else stringResource(R.string.Stats_Playlists, row.folder.playlists)
        is PlaylistRow.Playlist -> row.name to holding(row.list.tracks.size, seconds(row.list.tracks), row.list.missing)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .background(if (pressed) Ink.white(0x24) else Color.Transparent)
            .clickable(presses, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            BasicText(if (folder) Glyph.folder else Glyph.record, style = icon(15f).copy(color = Ink.white(0xB3)))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BasicText(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(15f).copy(color = Color.White))
            BasicText(detail, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(12f).copy(color = Ink.white(0x88)))
        }
        if (folder) {
            BasicText(Glyph.into, style = icon(12f).copy(color = Ink.white(0x77)))
        } else if (picked) {
            BasicText(Glyph.check, style = icon(14f).copy(color = Color.White))
        }
    }
}

/** The foot: what went wrong, if anything, over Cancel and Add. Add is dimmed until a playlist is chosen. */
@Composable
private fun Deed(said: String?, busy: Boolean, ready: Boolean, onCancel: () -> Unit, onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        said?.let { BasicText(it, Modifier.fillMaxWidth(), style = kicker()) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(onClick = onCancel, modifier = Modifier.weight(1f), height = 42.dp, size = 14f) { style ->
                BasicText(stringResource(R.string.Cloud_Cancel), style = style)
            }
            GlassButton(
                onClick = onAdd,
                modifier = Modifier.weight(1f).alpha(if (ready) 1f else 0.45f),
                weight = Weight.Primary,
                enabled = ready,
                height = 42.dp,
                size = 14f,
            ) { style ->
                if (busy) RecordSpinner(18.dp) else BasicText(stringResource(R.string.Rpm_PlaylistPick_Add), style = style)
            }
        }
    }
}

/** The hairline between the parts of the panel. */
@Composable
private fun Line() = Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.white(0x1A)))
