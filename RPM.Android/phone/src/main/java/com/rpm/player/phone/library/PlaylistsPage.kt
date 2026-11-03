package com.rpm.player.phone.library

import com.rpm.player.phone.design.UnderHeader
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.rpm.player.phone.ui.LiveRoom
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.library.LibraryState
import com.rpm.player.core.library.PlaylistBrowse
import com.rpm.player.core.library.PlaylistRow
import com.rpm.player.core.library.TrackList
import com.rpm.player.core.library.listName
import com.rpm.player.phone.PhoneSettings
import com.rpm.player.phone.design.BackKey
import com.rpm.player.phone.design.rememberHaptics
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.PageHeading
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.dissolvesAtTop
import com.rpm.player.phone.design.OverChrome
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.design.scrolled
import com.rpm.player.phone.player.QueueRead
import com.rpm.player.phone.ui.VinylGround

/** The gap between the back key and the title beside it (`RPMBackKey.besideTitle` on iPhone). */
private val BESIDE_TITLE = 12.dp

/**
 * The library's playlists view. It matches the iPhone's `PlaylistsPage` (RPMUI/PlaylistsPage.swift). It
 * shows the lists that the computer generated and the lists that the person made, in the folders they were
 * filed in. The folders come first, then the lists. Each row has its picture, its name and how much is in it.
 * Opening a folder shows its contents in place, with a back key beside its name. Opening a list opens its page.
 *
 * The same folder can be shown as covers instead of rows ([PlaylistWall]), chosen from the layout key in the header,
 * as on the iPhone. The choice and the covers' size, which a pinch changes, are kept between launches. Both layouts
 * draw the rows of one [PlaylistBrowse], so they always agree about what is in a folder, and switching keeps the
 * folder open.
 *
 * The plus key beside it makes a playlist in the folder that is open ([NewPlaylistSheet]): which plus was tapped
 * says where the playlist goes. It is not offered on a borrowed shelf, where a playlist would belong to this account
 * but name records it does not have.
 *
 * The current folder is checked against the lists on every pass. If a folder is emptied while it is open, the
 * reader is moved to the folder that holds it.
 *
 * The queue's row is at the top of the tree and opens the queue. On the person's own shelf, the marked songs'
 * row is under it.
 *
 * @param marked the marked songs, or null on a borrowed shelf, which has no row for them.
 * @param onMake makes a playlist from the panel: its name, its folder and its songs. It returns a sentence when it
 * failed, and null when it is done.
 *
 * Not yet implemented: pull to refresh (the lists are read again when the app comes back to the front instead), and
 * the line that says whose collection a borrowed one is.
 */
@Composable
fun PlaylistsPage(
    ready: LibraryState.Ready,
    images: ImageLoader,
    settings: PhoneSettings,
    onMode: (LibraryMode) -> Unit,
    bottomRoom: Dp,
    queue: QueueRead,
    onOpenQueue: () -> Unit,
    marked: List<CloudTrack>?,
    onOpenMarked: () -> Unit,
    onOpen: (TrackList) -> Unit,
    onMake: suspend (name: String, folder: List<String>, tracks: List<CloudTrack>) -> String?,
    onReread: suspend () -> Unit = {},
) {
    val ground = rememberBackdrop()
    // A pull down from the top reads the playlists again (`onReread` on iPhone); the spinner stays while it runs.
    val pulls = androidx.compose.runtime.rememberCoroutineScope()
    var rereading by remember { mutableStateOf(false) }
    val page = rememberBackdrop()
    val collection = ready.collection
    val resources = LocalContext.current.resources
    var at by rememberSaveable { mutableStateOf(listOf<String>()) }
    // The folder the panel makes its playlist in, or null while it is closed: the plus key that was tapped already
    // says which folder.
    var making by remember { mutableStateOf<List<String>?>(null) }
    var choosingLayout by remember { mutableStateOf(false) }
    // The size the fingers ask for while they are still down. It is saved only when they lift.
    var pinching by remember { mutableStateOf<Double?>(null) }
    val browse = remember(at, collection, resources, marked != null) {
        PlaylistBrowse.of(at, collection.lists, { listName(it.name, resources) }, marked = marked != null)
    }
    val back = browse.back
    BackHandler(enabled = back != null) { if (back != null) at = back }
    val own = collection.shelf == Shelf.Own

    // A press on a folder opens it and a press on anything else opens that, from either layout, so a tile and a row
    // always agree about what a tap does.
    val haptics = rememberHaptics()
    fun press(row: PlaylistRow) = when (row) {
        PlaylistRow.Queue -> onOpenQueue()
        PlaylistRow.Marked -> onOpenMarked()
        is PlaylistRow.Folder -> {
            haptics.pick()
            at = row.folder.path
        }
        is PlaylistRow.Playlist -> onOpen(row.list)
    }
    val pictureOf = { row: PlaylistRow -> collection.coverModel(row, queue, marked.orEmpty()) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(page)) {
            // The lists run up under the header and fade out over it, as on iPhone.
            UnderHeader(ground, ground = { VinylGround() }, header = { Column(Modifier.statusBarsPadding()) {
                Row(Modifier.padding(start = 20.dp, end = 16.dp, bottom = 10.dp)) {
                    if (back != null) {
                        BackKey(ground, onBack = { haptics.pick(); at = back }, modifier = Modifier.padding(end = BESIDE_TITLE))
                    }
                    PageHeading(browse.at.lastOrNull() ?: stringResource(R.string.Playlists_Title), counts(browse)) {
                        // The folder's keys on one glass capsule, as in the records wall's header. The layout key sits
                        // with them, not in the menu that leaves the lists, because it does neither of those things.
                        KeyGroup(ground) {
                            HeaderKey(settings.playlistLayout.mark) { choosingLayout = true }
                            if (own) HeaderKey(Glyph.plus) { making = browse.at }
                        }
                    }
                }
                ModeTabs(LibraryMode.Playlists, onMode = onMode, backdrop = ground)
            } }) { top ->
                // A folder is a new place, and a new place starts at the top.
                com.rpm.player.phone.design.PullToRefresh(
                    rereading,
                    onRefresh = {
                        rereading = true
                        pulls.launch { try { onReread() } finally { rereading = false } }
                    },
                    modifier = Modifier.fillMaxSize(),
                    top = top,
                ) { key(browse.at, settings.playlistLayout) {
                    when (settings.playlistLayout) {
                        PlaylistLayout.List -> Column(Modifier.fillMaxSize()) {
                            Rows(browse, queue, marked.orEmpty(), pictureOf, images, bottomRoom, top, ::press) {
                                Footer(browse, empty = collection.lists.isEmpty(), own = own)
                            }
                        }
                        PlaylistLayout.Grid -> Box(Modifier.fillMaxSize()) {
                            PlaylistWall(
                                browse.rows,
                                titleOf = { title(it) },
                                pictureOf = pictureOf,
                                framedOf = { framedOf(it) },
                                images = images,
                                targetStride = pinching ?: boundedStride(settings.playlistStride),
                                bottomRoom = bottomRoom,
                                topRoom = top,
                                onPress = ::press,
                                onPinch = { factor ->
                                    if (factor == null) {
                                        pinching?.let(settings::changePlaylistStride)
                                        pinching = null
                                    } else {
                                        pinching = boundedStride(settings.playlistStride * factor)
                                    }
                                },
                            ) { Footer(browse, empty = collection.lists.isEmpty(), own = own) }
                        }
                    }
                } }
            }
        }
        if (choosingLayout) {
            SortMenu(
                page,
                PlaylistLayout.entries.map { it to it.label },
                settings.playlistLayout,
                onPick = { settings.changePlaylistLayout(it); choosingLayout = false },
                onClose = { choosingLayout = false },
            )
        }
        // Over the tab bar and the mini player, as the iPhone's sheet is.
        OverChrome {
        AnimatedVisibility(making != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
            // Held while the panel fades out, after [making] has been cleared.
            val folder = remember { making.orEmpty() }
            NewPlaylistSheet(
                SheetErrand.Make(folder),
                collection,
                images,
                page,
                bottomRoom,
                commit = { name, tracks -> onMake(name, folder, tracks) },
                onClose = { making = null },
            )
        }
        }
    }
}

/** The rows layout: one row per folder or list, the footer after them. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Rows(
    browse: PlaylistBrowse,
    queue: QueueRead,
    marked: List<CloudTrack>,
    pictureOf: (PlaylistRow) -> Any?,
    images: ImageLoader,
    bottomRoom: Dp,
    top: Dp,
    onPress: (PlaylistRow) -> Unit,
    footer: @Composable () -> Unit,
) {
    val list = rememberLazyListState()
    LazyColumn(
        state = list,
        modifier = Modifier.weight(1f).fillMaxWidth().dissolvesAtTop(top) { list.scrolled() },
        contentPadding = PaddingValues(top = top, bottom = FOOT + bottomRoom),
    ) {
        items(browse.rows, key = { it.id }, contentType = { "row" }) { row ->
            Line(row, queue, marked, pictureOf(row), framedOf(row), images) { onPress(row) }
        }
        item(key = "footer", contentType = "footer") { footer() }
    }
}

/**
 * The picture a row shows. A list uses its own picture, or else the cover of the record it opens on. The
 * queue and the marked songs use the cover of their first song. A folder has no picture.
 */
private fun com.rpm.player.core.library.Collection.coverModel(row: PlaylistRow, queue: QueueRead, marked: List<CloudTrack>): Any? = when (row) {
    PlaylistRow.Queue -> queue.entries.firstOrNull()?.cover
    PlaylistRow.Marked -> marked.firstOrNull()?.let { albumOf[it.rpmId] }?.let { cover(it) }
    is PlaylistRow.Folder -> null
    is PlaylistRow.Playlist -> cover(row.list)
}

/** The frame a row's square wears: a generated list's, or none. */
@Composable
private fun framedOf(row: PlaylistRow): FramedList? = (row as? PlaylistRow.Playlist)?.let { FramedList.of(it.list, it.name) }

/** The name a row shows. The queue and the marked songs are named here: no name is stored for either. */
@Composable
private fun title(row: PlaylistRow): String = when (row) {
    PlaylistRow.Queue -> stringResource(R.string.AlbumMenu_PlaylistNowPlaying)
    PlaylistRow.Marked -> stringResource(R.string.Playlists_FavoriteTracks)
    is PlaylistRow.Folder -> row.folder.name
    is PlaylistRow.Playlist -> row.name
}

/**
 * Example: "Mixes  ·  4 playlists". The folders above this one come first, then how many rows it shows.
 */
@Composable
private fun counts(browse: PlaylistBrowse): String {
    val parts = ArrayList<String>()
    if (browse.path.isNotEmpty()) parts += browse.path.joinToString(" / ")
    parts += playlistsCount(browse.rows.size)
    return parts.joinToString("  ·  ")
}

@Composable
private fun playlistsCount(count: Int): String =
    if (count == 1) stringResource(R.string.Stats_PlaylistsOne) else stringResource(R.string.Stats_Playlists, count)

/** One row: the picture, the name over how much is in it, and the chevron on a folder. The row is lit while pressed. */
@Composable
private fun Line(row: PlaylistRow, queue: QueueRead, marked: List<CloudTrack>, picture: Any?, framed: FramedList?, images: ImageLoader, onClick: () -> Unit) {
    val presses = remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .background(if (pressed) Ink.white(0x24) else Color.Transparent)
            .clickable(presses, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistSleeve(picture, row.mark, edge = 44.dp, corner = 6.dp, mark = 16f, images, framed)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val detail = when (row) {
                PlaylistRow.Queue -> holding(queue.entries.size, queue.seconds, 0)
                PlaylistRow.Marked -> holding(marked.size, seconds(marked), 0)
                is PlaylistRow.Folder -> playlistsCount(row.folder.playlists)
                is PlaylistRow.Playlist -> holding(row.list.tracks.size, seconds(row.list.tracks), row.list.missing)
            }
            BasicText(title(row), maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(15f).copy(color = Color.White))
            BasicText(detail, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(11f).copy(color = Ink.white(0x88)))
        }
        if (row is PlaylistRow.Folder) BasicText(Glyph.into, style = icon(12f).copy(color = Ink.white(0x55)))
    }
}

/**
 * The message to show where there are no rows. It is shown inside an empty folder, or when the account has no lists.
 */
@Composable
private fun Footer(browse: PlaylistBrowse, empty: Boolean, own: Boolean) {
    when {
        browse.at.isNotEmpty() && browse.rows.isEmpty() -> BasicText(
            stringResource(R.string.Playlists_FolderEmpty),
            Modifier.fillMaxWidth().padding(40.dp),
            style = display(13f).copy(color = Ink.white(0x88), textAlign = TextAlign.Center),
        )
        browse.at.isEmpty() && empty && own -> BasicText(
            stringResource(R.string.Rpm_Playlists_EmptyHere),
            Modifier.padding(horizontal = 20.dp).padding(top = 18.dp),
            style = display(12f).copy(color = Ink.white(0x77)),
        )
    }
}

/**
 * The square that a list or a song shows. It matches the iPhone's `PlaylistSleeve`. It is a dark well with a
 * thin edge and a faint icon. The picture is drawn over it once it has loaded. The picture fills the square
 * and is cropped from the bottom, as a cover is.
 *
 * A generated list's square wears its frame ([framed]), lettered with its name, over its photograph, or over its
 * room for the five lists that have no photograph. A framed square shows no icon: the frame is the mark.
 *
 * @param picture a list's picture ([com.rpm.player.core.library.ListArt]) or a record's cover. Null leaves only the
 * icon.
 * @param drifts whether the room behind a roomed list's window moves (`roomDrifts` on iPhone). Every caller says so:
 * it moves only on the list's own page, because each moving square is a room drawn 30 times a second.
 */
@Composable
fun PlaylistSleeve(picture: Any?, glyph: String, edge: Dp, corner: Dp, mark: Float, images: ImageLoader, framed: FramedList? = null, drifts: Boolean = false) {
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier.size(edge).clip(shape).background(Color.Black.copy(alpha = 0x22 / 255f)).border(1.dp, Ink.cardBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            framed != null && framed.look.roomed && drifts -> LiveRoom(framed.look.room, framed.name, running = true, Modifier.fillMaxSize())
            framed != null && framed.look.roomed -> ListRoom(framed.look, framed.name, Modifier.fillMaxSize())
            picture != null -> {
                if (framed == null) BasicText(glyph, style = icon(mark).copy(color = Ink.white(0x3A)))
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(picture).build(),
                    contentDescription = null,
                    imageLoader = images,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            framed == null -> BasicText(glyph, style = icon(mark).copy(color = Ink.white(0x3A)))
        }
        if (framed != null) ListFrameOverlay(framed, Modifier.fillMaxSize())
    }
}

/** The songs' total length, in seconds. */
fun seconds(tracks: List<CloudTrack>): Int = tracks.sumOf { it.tags?.durationSeconds ?: 0.0 }.toInt()

/**
 * Example: "24 songs  ·  1 hour 32 min  ·  2 songs not uploaded yet". This matches the iPhone's
 * `PlaylistWords.holding`. Missing songs are said, so a short list does not look complete.
 */
@Composable
fun holding(songs: Int, seconds: Int, missing: Int): String {
    var said = if (songs == 1) stringResource(R.string.Stats_SongsOne) else stringResource(R.string.Stats_Songs, songs)
    if (seconds > 0) said += "  ·  " + runtime(seconds)
    if (missing > 0) said += "  ·  " + waitingOn(missing)
    return said
}

/** Example: "2 songs not uploaded yet". These are songs that the list names but the library cannot find. */
@Composable
fun waitingOn(missing: Int): String =
    if (missing == 1) stringResource(R.string.Rpm_Playlist_MissingOne) else stringResource(R.string.Rpm_Playlist_Missing, missing)

/** A list's length as one localised sentence: hours and minutes, or minutes only. */
@Composable
private fun runtime(seconds: Int): String {
    val hours = seconds / 3600
    if (hours < 1) {
        val minutes = seconds / 60
        return if (minutes == 1) stringResource(R.string.Rpm_Playlists_LenMinuteOne) else stringResource(R.string.Playlists_LenMinutes, minutes)
    }
    val rest = (seconds % 3600) / 60
    return if (hours == 1) stringResource(R.string.Rpm_Playlists_LenHourOne, rest) else stringResource(R.string.Playlists_LenHours, hours, rest)
}
