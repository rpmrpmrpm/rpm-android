package com.rpm.player.phone.library

import com.rpm.player.core.library.isExplicit
import com.rpm.player.phone.design.MarkedTitle
import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.PlaylistRefusal
import com.rpm.player.core.library.SongSearch
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.GlassCard
import com.rpm.player.phone.design.GlassField
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.system
import com.rpm.player.phone.ui.RecordSpinner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the panel is for. The title and the key on the right say which. */
sealed interface SheetErrand {
    /** A new playlist, made in [folder] (outermost first; empty is the top of the tree). */
    data class Make(val folder: List<String>) : SheetErrand

    /** Songs added to a playlist that already exists: [name] as the list shows it, filed in [folder]. */
    data class Add(val name: String, val folder: List<String>) : SheetErrand
}

/** How long typing must pause before the library is searched. The iPhone app and the library's search use 140 ms. */
private const val PAUSE_MS = 140L

/** The colour of the line that says why the panel could not save. The iPhone's (255, 0xB4, 0xA0). */
private val TROUBLE = Color(0xFFFFB4A0)

/**
 * Makes a playlist, or adds songs to an existing one: the name and the songs on one panel. It matches the iPhone's
 * `NewPlaylistSheet` (RPMUI/NewPlaylistSheet.swift). One panel serves both, because adding songs is the same panel
 * without the name row.
 *
 * **Cancel and the action key are in the header**, not at the foot: both fields raise the keyboard, which covers the
 * foot of the panel, and a key there could not be reached without putting the keyboard away first.
 *
 * There is no folder field. The folder is decided by which plus key was tapped, so it is not asked again; the line
 * under the name says where the playlist goes.
 *
 * The songs come from the albums: a search through the library, paused on typing and run off the main thread, as the
 * library's own search is. They are kept **in the order they were picked**, which is the playlist's order. Once a
 * song has been picked, a switch shows the picked songs instead of the results, to look them over and take some out.
 *
 * Another screen that offers "New playlist" (the song menu's pick sheet) opens this panel with [SheetErrand.Make]
 * and its own [commit].
 *
 * @param backdrop what the panel's glass blurs: the page under it.
 * @param bottomRoom how much of the foot of the screen the chrome covers. The panel stops above it, as the iPhone's
 * stops at the page's foot room, or above the keyboard while it is up, whichever is higher.
 * @param commit does the saving, with the name (trimmed) and the songs picked. It returns a sentence when it failed,
 * shown on the panel, and null when it is done, which closes the panel.
 */
@Composable
fun NewPlaylistSheet(
    errand: SheetErrand,
    collection: Collection,
    images: ImageLoader,
    backdrop: Backdrop,
    bottomRoom: Dp,
    commit: suspend (name: String, tracks: List<CloudTrack>) -> String?,
    onClose: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf("") }
    // The picked songs, in the order picked, and their ids, so each drawn row can tell quickly whether it is picked.
    val chosen = remember { mutableStateListOf<CloudTrack>() }
    val chosenIds by remember { derivedStateOf { chosen.mapTo(HashSet()) { it.rpmId } } }
    var showingAdded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var trouble by remember { mutableStateOf<String?>(null) }
    // The search's answer is kept here, not worked out while drawing: the panel redraws for many reasons that have
    // nothing to do with the words, and each would search the whole library again.
    var settled by remember { mutableStateOf("") }
    var found by remember { mutableStateOf(emptyList<SongSearch.SongHit>()) }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    LaunchedEffect(asked, collection) {
        val asking = asked
        if (asking.isBlank()) {
            found = emptyList()
            settled = asking
            return@LaunchedEffect
        }
        delay(PAUSE_MS)
        found = withContext(Dispatchers.Default) { SongSearch.through(collection.albums, asking) }
        settled = asking
    }

    val ready = when (errand) {
        is SheetErrand.Make -> name.isNotBlank()
        is SheetErrand.Add -> chosen.isNotEmpty()
    }
    fun done() {
        if (!ready || busy) return
        busy = true
        trouble = null
        focus.clearFocus()
        scope.launch {
            val said = commit(name.trim(), chosen.toList())
            busy = false
            if (said == null) onClose() else trouble = said
        }
    }
    fun pick(track: CloudTrack) {
        if (track.rpmId in chosenIds) chosen.removeAll { it.rpmId == track.rpmId } else chosen += track
    }

    Box(Modifier.fillMaxSize()) {
        // A tap outside the panel cancels.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0xB3 / 255f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
        // The same space under the panel as at its sides, so its foot stays clear of the keyboard or the screen's edge.
        GlassCard(
            backdrop,
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).union(WindowInsets(bottom = bottomRoom)))
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                // The panel takes its own taps, so a tap on it does not reach the shade and cancel.
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.fillMaxSize()) {
                Header(errand, ready, busy, onCancel = onClose, onDone = ::done)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.white(0x1A)))
                if (errand is SheetErrand.Make) {
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        GlassField(
                            name,
                            { name = it },
                            stringResource(R.string.Rpm_NewPlaylist_Name),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                        )
                        // Where the playlist goes, quietly. At the top of the tree there is nothing to say.
                        if (errand.folder.isNotEmpty()) {
                            BasicText(stringResource(R.string.Rpm_NewPlaylist_In, errand.folder.joinToString(" / ")), style = kicker())
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassField(
                        asked,
                        {
                            asked = it
                            // Typing goes back to the results: a search box that shows the picked list would look broken.
                            showingAdded = false
                        },
                        stringResource(R.string.Rpm_NewPlaylist_Search),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    )
                    // Which list shows below. Shown only once a song is picked; before that there is no second list.
                    if (chosen.isNotEmpty()) {
                        Segments(
                            listOf(stringResource(R.string.Rpm_NewPlaylist_Results), stringResource(R.string.Rpm_NewPlaylist_Added, chosen.size.toString())),
                            chosen = if (showingAdded) 1 else 0,
                            onChoose = { showingAdded = it == 1 },
                        )
                    }
                }
                Songs(
                    rows = if (showingAdded) chosen.mapNotNull { track -> collection.albumOf[track.rpmId]?.let { SongSearch.SongHit(track, it) } } else found,
                    nothing = when {
                        showingAdded -> stringResource(R.string.Rpm_NewPlaylist_NoneAdded)
                        settled.isBlank() -> stringResource(R.string.Rpm_NewPlaylist_Start)
                        else -> stringResource(R.string.Rpm_NewPlaylist_NoResults)
                    },
                    isChosen = { it.rpmId in chosenIds },
                    collection = collection,
                    images = images,
                    onPick = ::pick,
                )
                trouble?.let {
                    BasicText(it, Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp), style = display(12f).copy(color = TROUBLE))
                }
            }
        }
    }
}

/** Cancel, the panel's title, and the key that saves, which is lit only when there is something to save. */
@Composable
private fun Header(errand: SheetErrand, ready: Boolean, busy: Boolean, onCancel: () -> Unit, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassButton(onClick = onCancel, stretches = false, height = 38.dp, radius = 10.dp, inset = 14.dp, size = 14f) { style ->
            BasicText(stringResource(R.string.Cloud_Cancel), style = style)
        }
        val title = when (errand) {
            is SheetErrand.Make -> stringResource(R.string.Playlists_NewPlaylist)
            is SheetErrand.Add -> errand.name
        }
        BasicText(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = displayBold(17f).copy(color = Color.White, textAlign = TextAlign.Center))
        GlassButton(onClick = onDone, enabled = ready && !busy, stretches = false, height = 38.dp, radius = 10.dp, inset = 14.dp, size = 14f) { style ->
            if (busy) {
                RecordSpinner(18.dp)
            } else {
                BasicText(stringResource(if (errand is SheetErrand.Make) R.string.Rpm_NewPlaylist_Create else R.string.Rpm_PlaylistPick_Add), style = style)
            }
        }
    }
}

/**
 * A two-way switch, as the iPhone's segmented picker draws it on a dark screen: a rounded track with the chosen half
 * lit. A switch and not a key, because the reader goes back and forth between the two lists.
 */
@Composable
private fun Segments(labels: List<String>, chosen: Int, onChoose: (Int) -> Unit) {
    val track = RoundedCornerShape(9.dp)
    val face = RoundedCornerShape(7.dp)
    Row(
        Modifier.fillMaxWidth().height(32.dp).clip(track).background(Ink.white(0x1F)).padding(2.dp),
    ) {
        labels.forEachIndexed { at, label ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(face)
                    .background(if (at == chosen) Ink.white(0x40) else Color.Transparent)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onChoose(at) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = system(13f, FontWeight.SemiBold).copy(color = Color.White))
            }
        }
    }
}

/** The results, or the picked songs, or why there are none. */
@Composable
private fun ColumnScope.Songs(
    rows: List<SongSearch.SongHit>,
    nothing: String,
    isChosen: (CloudTrack) -> Boolean,
    collection: Collection,
    images: ImageLoader,
    onPick: (CloudTrack) -> Unit,
) {
    if (rows.isEmpty()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
            BasicText(nothing, style = display(13f).copy(color = Ink.white(0x88), textAlign = TextAlign.Center))
        }
        return
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        items(rows, key = { it.track.rpmId }, contentType = { "song" }) { hit ->
            PickRow(hit, isChosen(hit.track), collection, images) { onPick(hit.track) }
        }
        item(key = "foot") { Box(Modifier.height(10.dp)) }
    }
}

/**
 * One song to pick: its record's cover, its title over its artist and record, and a plus for what a tap does, or a
 * tick once it is picked. The song's own artist is shown, and the record's only when the file has none: on a
 * compilation the two differ, and the song's is the one being looked for.
 */
@Composable
private fun PickRow(hit: SongSearch.SongHit, chosen: Boolean, collection: Collection, images: ImageLoader, onClick: () -> Unit) {
    val presses = remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .background(if (pressed) Ink.white(0x24) else Color.Transparent)
            .clickable(presses, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistSleeve(collection.cover(hit.album), Glyph.record, edge = 40.dp, mark = 16f, images)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            MarkedTitle(hit.track.title?.takeIf { it.isNotEmpty() } ?: hit.album.album, hit.track.isExplicit, display(14f).copy(color = Color.White))
            val artist = hit.track.artist?.takeIf { it.isNotEmpty() } ?: hit.album.artist
            val said = listOf(artist, hit.album.album).filter { it.isNotEmpty() }.joinToString(" · ")
            BasicText(said, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(11f).copy(color = Ink.white(0x88)))
        }
        BasicText(if (chosen) Glyph.check else Glyph.plus, style = icon(15f).copy(color = if (chosen) Ink.white(0xE6) else Ink.white(0x66)))
    }
}

/**
 * The sentence for a write that did not happen: the iPhone's `PlaylistWriteError.userMessage`, or [failed], the
 * general sentence for what was being done.
 */
fun refusalText(resources: Resources, refusal: PlaylistRefusal, failed: Int): String = resources.getString(
    when (refusal) {
        PlaylistRefusal.NotYours -> R.string.Rpm_PlaylistWrite_NotYours
        PlaylistRefusal.Gone -> R.string.Rpm_PlaylistWrite_Gone
        PlaylistRefusal.Generated -> R.string.Rpm_PlaylistWrite_Generated
        PlaylistRefusal.Busy -> R.string.Rpm_PlaylistWrite_Busy
        PlaylistRefusal.Unseen -> R.string.Rpm_PlaylistWrite_Unseen
        PlaylistRefusal.Nameless -> R.string.Rpm_PlaylistWrite_NoName
        PlaylistRefusal.Failed -> failed
    },
)
