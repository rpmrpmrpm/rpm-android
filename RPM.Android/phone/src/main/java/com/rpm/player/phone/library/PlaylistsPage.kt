package com.rpm.player.phone.library

import com.rpm.player.phone.design.holdPress
import com.rpm.player.phone.design.MenuItem
import androidx.compose.foundation.layout.height
import com.rpm.player.phone.design.rememberPicture
import com.rpm.player.phone.design.UnderHeader
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.graphicsLayer
import com.rpm.player.core.library.PlaylistPhotos
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
import com.rpm.player.phone.ui.CoverCorner
import com.rpm.player.phone.ui.LiveRoom
import com.rpm.player.phone.ui.coverSquare
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.library.Genres
import com.rpm.player.core.library.LibraryState
import com.rpm.player.core.library.Lists
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
import com.rpm.player.phone.ui.VinylGround
import com.rpm.player.phone.design.ScrollsToTop

/** The gap between the back key and the title beside it (`RPMBackKey.besideTitle` on iPhone). */
private val BESIDE_TITLE = 12.dp

/**
 * The library's playlists view. It matches the iPhone's `PlaylistsPage` (RPMUI/PlaylistsPage.swift). It
 * shows the lists that the computer generated and the lists that the person made, in the folders they were
 * filed in. The folders come first, then the lists. Each row has its picture, its name and a line that says what kind
 * of row it is ([byline]).
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
 * Unlike the iPhone, there is no row for the queue or for the marked songs. The computer's lists about listening
 * stand at the top of the tree, and its decades are inside its moods ([PlaylistBrowse.of]).
 *
 * **Above the top of the tree stand the songs played lately and the pinned lists** ([Heads]): the history first, then
 * the lists the person pinned, in the order they were pinned. They are rows in both layouts, so they read as a shelf of
 * their own and not as more covers. A pinned list is also still in its own folder. A long press on a list offers to pin
 * it, or to let it go, and to put it on home's Quick selection ([com.rpm.player.phone.home.Holds]).
 *
 * @param onMake makes a playlist from the panel: its name, its folder and its songs. It returns a sentence when it
 * failed, and null when it is done.
 * @param history the songs played lately, newest first ([PlayedLately]); empty hides the history's row.
 * @param onOpenHistory opens the page of those songs.
 * @param owner the name of the person whose collection this is: the account's own name on its own collection, the
 * lender's on a borrowed one, null when it is not known. It is named under each list somebody made ([byline]).
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
    onOpen: (TrackList) -> Unit,
    onMake: suspend (name: String, folder: List<String>, tracks: List<CloudTrack>) -> String?,
    onReread: suspend () -> Unit = {},
    history: List<CloudTrack> = emptyList(),
    onOpenHistory: () -> Unit = {},
    owner: String? = null,
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
    val browse = remember(at, collection, resources) {
        PlaylistBrowse.of(at, collection.lists, { listName(it.name, resources) })
    }
    val back = browse.back
    BackHandler(enabled = back != null) { if (back != null) at = back }
    val own = collection.shelf == Shelf.Own

    // A press on a folder opens it and a press on anything else opens that, from either layout, so a tile and a row
    // always agree about what a tap does.
    val haptics = rememberHaptics()
    fun press(row: PlaylistRow) = when (row) {
        is PlaylistRow.Folder -> {
            haptics.pick()
            at = row.folder.path
        }
        is PlaylistRow.Playlist -> onOpen(row.list)
    }
    val pictureOf = { row: PlaylistRow -> collection.coverModel(row) }
    val holds = com.rpm.player.phone.home.LocalHolds.current
    val menuOf = { row: PlaylistRow -> (row as? PlaylistRow.Playlist)?.let { list -> holds?.let { { it.list(list.list) } } } }
    // The shelf above the tree: only at its top, where nothing else is open.
    val pinned = remember(settings.pinnedLists, collection, resources) {
        settings.pinnedLists.mapNotNull { id -> collection.list(id)?.let { PlaylistRow.Playlist(it, listName(it.name, resources), emptyList()) } }
    }
    val heads: (@Composable () -> Unit)? = if (history.isNotEmpty() || pinned.isNotEmpty()) {
        { Heads(history, pinned, collection, images, onOpenHistory, ::press, menuOf) }
    } else {
        null
    }

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
                ) { FolderFade(browse.at) { shownAt -> key(settings.playlistLayout) {
                    // The folder this child shows: while a folder changes, the one that leaves is still drawn
                    // from its own rows as it fades out.
                    val shown = if (shownAt == browse.at) browse else remember(shownAt, collection, resources) {
                        PlaylistBrowse.of(shownAt, collection.lists, { listName(it.name, resources) })
                    }
                    val shelf = heads.takeIf { shown.at.isEmpty() }
                    when (settings.playlistLayout) {
                        PlaylistLayout.List -> Column(Modifier.fillMaxSize()) {
                            Rows(shown, owner, pictureOf, images, bottomRoom, top, ::press, menuOf, shelf) {
                                Footer(shown, empty = collection.lists.isEmpty(), own = own)
                            }
                        }
                        PlaylistLayout.Grid -> Box(Modifier.fillMaxSize()) {
                            PlaylistWall(
                                shown.rows,
                                titleOf = { title(it) },
                                lineOf = { byline(it, owner) },
                                pictureOf = pictureOf,
                                framedOf = { framedOf(it) },
                                images = images,
                                targetStride = pinching ?: boundedStride(settings.playlistStride),
                                bottomRoom = bottomRoom,
                                topRoom = top,
                                onPress = ::press,
                                menuOf = menuOf,
                                header = shelf,
                                onPinch = { factor ->
                                    if (factor == null) {
                                        pinching?.let(settings::changePlaylistStride)
                                        pinching = null
                                    } else {
                                        pinching = boundedStride(settings.playlistStride * factor)
                                    }
                                },
                            ) { Footer(shown, empty = collection.lists.isEmpty(), own = own) }
                        }
                    }
                } } }
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

/**
 * How a folder leaves and comes in (`folderFade` on the iPhone): the folder shown fades out first, and the new one
 * fades in after it. One after the other and not together, so the two lists are never seen through each other.
 * Each folder is a child of its own, with its own scroll, so a new folder starts at the top: without that, going
 * from the middle of a long folder into a short one would leave the scroll past the end of the short one.
 */
@Composable
private fun FolderFade(at: List<String>, content: @Composable (List<String>) -> Unit) {
    AnimatedContent(
        targetState = at,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            fadeIn(tween(FADE_IN_MS, delayMillis = FADE_OUT_MS, easing = EaseOut)) togetherWith
                fadeOut(tween(FADE_OUT_MS, easing = EaseIn)) using null
        },
        label = "folder",
    ) { content(it) }
}

/** How long the folder shown takes to fade out, and the new one to fade in after it (0.14 s and 0.22 s on iPhone). */
private const val FADE_OUT_MS = 140
private const val FADE_IN_MS = 220

/** The rows layout: one row per folder or list, the footer after them. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Rows(
    browse: PlaylistBrowse,
    owner: String?,
    pictureOf: (PlaylistRow) -> Any?,
    images: ImageLoader,
    bottomRoom: Dp,
    top: Dp,
    onPress: (PlaylistRow) -> Unit,
    menuOf: (PlaylistRow) -> (() -> List<MenuItem>)?,
    heads: (@Composable () -> Unit)?,
    footer: @Composable () -> Unit,
) {
    val list = rememberLazyListState()
    ScrollsToTop(list)
    LazyColumn(
        state = list,
        modifier = Modifier.weight(1f).fillMaxWidth().dissolvesAtTop(top) { list.scrolled() },
        contentPadding = PaddingValues(top = top, bottom = FOOT + bottomRoom),
    ) {
        if (heads != null) item(key = "heads", contentType = "heads") { heads() }
        items(browse.rows, key = { it.id }, contentType = { "row" }) { row ->
            Line(row, byline(row, owner), pictureOf(row), framedOf(row), images, menu = menuOf(row)) { onPress(row) }
        }
        item(key = "footer", contentType = "footer") { footer() }
    }
}

/**
 * The picture a row shows. A list uses its own picture, or else the cover of the record it opens on. One of the app's
 * own folders shows its photograph, behind its frame ([folderFrame]); a folder somebody made has no picture, and
 * shows a room behind its frame instead.
 */
private fun com.rpm.player.core.library.Collection.coverModel(row: PlaylistRow): Any? = when (row) {
    is PlaylistRow.Folder -> PlaylistPhotos.folder(row.face)
    is PlaylistRow.Playlist -> cover(row.list)
}

/** The frame a row's square wears: a generated list's, or none. */
@Composable
private fun framedOf(row: PlaylistRow): FramedList? = (row as? PlaylistRow.Playlist)?.let { FramedList.of(it.list, it.name) }

/** The name a row shows. */
private fun title(row: PlaylistRow): String = when (row) {
    is PlaylistRow.Folder -> row.folder.name
    is PlaylistRow.Playlist -> row.name
}

/**
 * The line under a row's name, where a record shows its artist: what kind of row it is (`PlaylistRow.byline` on
 * iPhone).
 *
 * - A folder says that it is a folder.
 * - A list somebody made names the person whose collection is shown ([owner]). Only the owner of a collection can
 *   make lists in it. With no name known, it says only that it is a playlist.
 * - A mood list names the genre it is cut to. A list cut across all genres (a whole mood, or a decade) says that it
 *   is a mood list.
 * - The lists about listening ([Lists.OWNER_ONLY]) are neither. They say how much they hold.
 *
 * The genre is the genres wall's name for it ([Genres.match]), so a list and the wall call the same music by the same
 * name. Genre names are not translated anywhere.
 */
@Composable
private fun byline(row: PlaylistRow, owner: String?): String = when (row) {
    is PlaylistRow.Folder -> stringResource(R.string.Playlists_RowFolder)
    is PlaylistRow.Playlist -> {
        val list = row.list
        val genre = list.genre?.trim()?.takeIf { it.isNotEmpty() }
        when {
            !list.generated ->
                if (owner.isNullOrBlank()) stringResource(R.string.Home_QuickPlaylist) else stringResource(R.string.Playlists_RowBy, owner)
            list.kind in Lists.OWNER_ONLY -> holding(list.tracks.size, seconds(list.tracks), list.missing)
            genre != null -> Genres.match(genre) ?: genre
            else -> stringResource(R.string.Playlists_RowMood)
        }
    }
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

/**
 * The shelf above the top of the tree: the songs played lately, then the pinned lists. Rows like the tree's own, in
 * either layout, with a thin line under them that sets them apart from the tree.
 */
@Composable
private fun Heads(
    history: List<CloudTrack>,
    pinned: List<PlaylistRow.Playlist>,
    collection: com.rpm.player.core.library.Collection,
    images: ImageLoader,
    onHistory: () -> Unit,
    onPress: (PlaylistRow) -> Unit,
    menuOf: (PlaylistRow) -> (() -> List<MenuItem>)?,
) {
    Column(Modifier.fillMaxWidth()) {
        if (history.isNotEmpty()) {
            // The cover of the record played last, so the row shows what was just heard.
            val cover = collection.albumOf[history.first().rpmId]?.let { collection.cover(it) }
            HeadLine(
                stringResource(R.string.Playlists_RecentlyPlayed),
                holding(history.size, seconds(history), 0),
                end = Glyph.history,
                onClick = onHistory,
                menu = null,
            ) { PlaylistSleeve(cover, Glyph.history, edge = 44.dp, mark = 16f, images) }
        }
        for (row in pinned) {
            val framed = framedOf(row)
            HeadLine(row.name, holding(row.list.tracks.size, seconds(row.list.tracks), row.list.missing), end = Glyph.pin, onClick = { onPress(row) }, menu = menuOf(row)) {
                PlaylistSleeve(collection.cover(row.list), row.mark, edge = 44.dp, mark = 16f, images, framed, markPicture = row.markPicture)
            }
        }
        Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().height(1.dp).background(Ink.white(0x1A)))
    }
}

/** One row of [Heads]: a [Line] with its own picture and a quiet mark at its end that says why it stands up here. */
@Composable
private fun HeadLine(title: String, detail: String, end: String, onClick: () -> Unit, menu: (() -> List<MenuItem>)?, picture: @Composable () -> Unit) {
    val presses = remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .background(if (pressed) Ink.white(0x24) else Color.Transparent)
            .holdPress(presses, onClick, menu)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        picture()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(15f).copy(color = Color.White))
            BasicText(detail, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(11f).copy(color = Ink.white(0x88)))
        }
        BasicText(end, style = icon(12f).copy(color = Ink.white(0x55)))
    }
}

/** One row: the picture, the name over [line] ([byline]), and the chevron on a folder. The row is lit while pressed. */
@Composable
private fun Line(row: PlaylistRow, line: String, picture: Any?, framed: FramedList?, images: ImageLoader, menu: (() -> List<MenuItem>)? = null, onClick: () -> Unit) {
    val presses = remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .background(if (pressed) Ink.white(0x24) else Color.Transparent)
            .holdPress(presses, onClick, menu)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistSleeve(picture, row.mark, edge = 44.dp, mark = 16f, images, framed, markPicture = row.markPicture, folderFrame = row.folderFrame)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title(row), maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(15f).copy(color = Color.White))
            BasicText(line, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(11f).copy(color = Ink.white(0x88)))
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
 * Its corners are the rounding every cover has, from its size ([CoverCorner]), and the frame is clipped with them.
 *
 * @param picture a list's picture ([com.rpm.player.core.library.ListArt]) or a record's cover. Null leaves only the
 * icon.
 * @param drifts whether the room behind a roomed list's window moves (`roomDrifts` on iPhone). Every caller says so:
 * it moves only on the list's own page, because each moving square is a room drawn 30 times a second.
 * @param markPicture a drawable shown in place of [glyph] on a square with no picture, at the glyph's size. It is as
 * faint as the desktop draws it on its tiles (40%), and it is a grey picture, so it reads as faint as the glyphs.
 * @param folderFrame the name and inks of a folder ([FramedList.folder]) for the folder frame ([FolderFrameOverlay]).
 * One of the app's own folders has its photograph ([picture]) behind it, and the two come in together, once the
 * photograph is ready, so the frame never shows over an empty window. A folder somebody made has no photograph and
 * shows a still room in its colours ([ListRoom]) behind the frame.
 */
@Composable
fun PlaylistSleeve(
    picture: Any?,
    glyph: String,
    edge: Dp,
    mark: Float,
    images: ImageLoader,
    framed: FramedList? = null,
    drifts: Boolean = false,
    @DrawableRes markPicture: Int? = null,
    folderFrame: FramedList? = null,
) {
    val shape = coverSquare(edge)
    Box(
        Modifier.size(edge).clip(shape).background(Color.Black.copy(alpha = 0x22 / 255f)).border(1.dp, Ink.cardBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            framed != null && framed.look.roomed && drifts -> LiveRoom(framed.look.room, framed.name, running = true, Modifier.fillMaxSize())
            framed != null && framed.look.roomed -> ListRoom(framed.look, framed.name, Modifier.fillMaxSize())
            framed == null && folderFrame != null && picture != null -> FolderCover(picture, folderFrame, edge, images)
            framed == null && folderFrame != null -> {
                // A folder somebody made has no photograph: a still room in its colours shows through the window.
                ListRoom(folderFrame.look, folderFrame.name, Modifier.fillMaxSize())
                FolderFrameOverlay(folderFrame, Modifier.fillMaxSize())
            }
            picture != null -> {
                if (framed == null) BasicText(glyph, style = icon(mark).copy(color = Ink.white(0x3A)))
                AsyncImage(
                    model = rememberPicture(picture, edge),
                    contentDescription = null,
                    imageLoader = images,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            framed == null && markPicture != null ->
                Image(painterResource(markPicture), contentDescription = null, modifier = Modifier.size(mark.dp), alpha = 0.4f)
            framed == null -> BasicText(glyph, style = icon(mark).copy(color = Ink.white(0x3A)))
        }
        if (framed != null) ListFrameOverlay(framed, Modifier.fillMaxSize())
    }
}

/**
 * One of the app's own folders: its photograph with the folder frame over it, the name in the bar under the window
 * ([FolderFrameOverlay], the folder branch of the iPhone's `PlaylistSleeve`). The photograph is washed and cached off
 * the main thread by the image loader
 * ([com.rpm.player.core.library.PlaylistPhotoFetcher]). Both fade in at once (0.2 s, ease out) when it is ready. A
 * photograph the loader already holds is answered before the next frame, and is shown at once rather than faded.
 */
@Composable
private fun FolderCover(picture: Any, frame: FramedList, edge: Dp, images: ImageLoader) {
    val request = rememberPicture(picture, edge)
    var ready by remember(request) { mutableStateOf(false) }
    // Whether a frame has passed since the request was made: an answer before that came from the loader's memory.
    var waited by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request) { withFrameNanos { }; waited = true }
    val shown by animateFloatAsState(
        if (ready) 1f else 0f,
        if (waited) tween(200, easing = EaseOut) else snap(),
        label = "folder cover",
    )
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown }) {
        AsyncImage(
            model = request,
            contentDescription = null,
            imageLoader = images,
            contentScale = ContentScale.Crop,
            onSuccess = { ready = true },
            modifier = Modifier.fillMaxSize(),
        )
        if (ready) FolderFrameOverlay(frame, Modifier.fillMaxSize())
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

/** Example: "2 songs can't be found". These are songs that the list names but the library cannot find: not uploaded
 *  yet, or named by an id the collection no longer has. */
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
