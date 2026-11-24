package com.rpm.player.phone.library

import com.rpm.player.core.frames.FramedList
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.rpm.player.core.playback.QueueLook
import com.rpm.player.core.playback.QueueRows
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.rpm.player.phone.design.isSquare
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.library.Cover
import com.rpm.player.core.library.ListArt
import com.rpm.player.core.library.MoodLine
import com.rpm.player.core.library.PlaylistFeatures
import com.rpm.player.core.library.TrackList
import com.rpm.player.core.library.isExplicit
import com.rpm.player.phone.design.ExplicitMark
import com.rpm.player.core.library.moodSentence
import com.rpm.player.phone.design.BackKey
import com.rpm.player.phone.design.HoldsPageColour
import com.rpm.player.phone.design.PageColour
import com.rpm.player.phone.design.rememberColourPage
import com.rpm.player.phone.design.rememberGlassColour
import com.rpm.player.phone.ui.color
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.Weight
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.design.dissolvesAtTop
import com.rpm.player.phone.design.rememberHaptics
import com.rpm.player.phone.design.OverChrome
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.pt
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.design.scrolled
import com.rpm.player.phone.design.system
import com.rpm.player.phone.ui.LiveRoom
import com.rpm.player.phone.downloads.DownloadButton
import com.rpm.player.phone.downloads.DownloadKey
import com.rpm.player.phone.downloads.RowDownloadMark
import com.rpm.player.phone.ui.rememberRoomColours
import com.rpm.player.core.room.RoomPalette
import kotlin.math.roundToInt
import com.rpm.player.phone.design.ScrollsToTop

/**
 * One list whose order this phone does not own. That covers a stored or generated list, and the marked songs.
 * It matches the iPhone's `PlaylistPage` (RPMUI/PlaylistPage.swift). The layout is the same as the album
 * page's. The list's picture is large and centred. Below it are the name, how much is in the list, who is on
 * it, centred, and the round keys: Shuffle, then Play in the middle in a glass circle, as a record has them. All of
 * these scroll away with the songs. The back key floats over the page at the top left, and the list's menu
 * ([PlaylistMenu], [PlaylistCorner]) at the top right, where a record has its heart. The queue has its own page
 * ([QueuePage]).
 *
 * The page's background is the list's room, moving ([RoomGround]): in a generated list's own colours, in the colours
 * of a list's own picture, or in the app's blue where there is no picture (the marked songs). If the cloud has no
 * picture for a stored list, the square shows the cover of the record the list opens on. The iPhone app uses that
 * cover for the square only, not for the room's colours.
 *
 * A generated list wears its frame on the square ([framed]). The five that stand on the room show it moving in the
 * square too, as on iPhone. Where the computer sent more than one list's worth of songs, its menu shows another
 * selection ([PlaylistMenu.Generated]).
 *
 * A stored playlist of the person's own has, in its menu, Add songs, which adds songs picked from the albums
 * ([adding]), and, when this phone can see all its songs ([canReorder]), Rearrange, which turns rearranging on: each
 * row then has a grip, a row carried by it goes where it is let go, and Done takes the menu key's place. A generated
 * list has both greyed: the computer replaces it on its next run.
 *
 * A song's long press opens the song menu ([LocalSongMenu]).
 *
 * @param name the name shown for the list.
 * @param missing how many songs the list names that the library cannot find.
 * @param blurb a mood list's sentence, or null.
 * @param picture the list's own picture, or null if no picture exists for it. The square then shows the cover of the
 * record the list opens on.
 * @param coverOf the cover of the record that a song is on. It is used for the song's row.
 * @param playing the id of the track playing, which the mark in the margin follows.
 * @param onPlay plays the list from the song at that index: a tap on a row.
 * @param onPlayAll the Play key: the whole list from the top. See [AlbumPage] for why a key and a row differ.
 * @param menu what the menu at the top right offers. None, the default, is no menu: the marked songs have none.
 * @param onMove writes the songs in a new order, after a row was carried. It answers whether the store took it; the
 * rows go back where they were when it did not.
 * @param download the download key beside Play, or null where there is none to offer: the favourite songs, the songs
 * played lately, and another account's playlist. See [DownloadKey].
 */
@Composable
fun PlaylistPage(
    name: String,
    tracks: List<CloudTrack>,
    missing: Int,
    blurb: MoodLine?,
    picture: ListArt?,
    coverOf: (CloudTrack) -> Cover?,
    images: ImageLoader,
    playing: String?,
    bottomRoom: Dp,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onBack: () -> Unit,
    onPlayAll: () -> Unit = { onPlay(0) },
    framed: FramedList? = null,
    menu: PlaylistMenu = PlaylistMenu.None,
    adding: ListAdding? = null,
    canReorder: Boolean = false,
    onMove: suspend (List<CloudTrack>) -> Boolean = { false },
    download: DownloadKey? = null,
) {
    var picking by remember { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(false) }
    // The order on screen: the list's own, or a move made here that the store has not answered yet.
    var order by remember(tracks) { mutableStateOf(tracks) }
    val rowsOf = remember(order) { QueueRows.of(order, QueueLook.Whole) { it.rpmId } }
    // Rearranging needs two songs or more, and a list this phone may write.
    val rearranging = asked && canReorder && rowsOf.size > 1
    val haptics = rememberHaptics()
    // The glass takes the list's colour while it is on screen ([PageColour]): a generated list's own middle colour, as
    // its frame and room have, else the main colour of its own picture, never a record's in it. Back lets go of it
    // first, so the glass changes as the page slides away and not after it has gone.
    val colourPage = rememberColourPage()
    val pictureColour = rememberGlassColour(if (framed == null) picture else null, images)
    HoldsPageColour(framed?.look?.trio?.mid?.color() ?: pictureColour, colourPage)
    val goBack = {
        PageColour.release(colourPage)
        onBack()
    }
    BackHandler(onBack = { if (rearranging) asked = false else goBack() })
    val page = rememberBackdrop()
    val rows = rememberLazyListState()
    ScrollsToTop(rows)
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.asPaddingValues(density).calculateTopPadding() + BACK_ROW
    val carry = remember { ListCarry() }
    val scope = rememberCoroutineScope()

    // Once the order on screen shows the move, the carried row is put down in the same frame it takes its place.
    LaunchedEffect(order) { if (carry.landed) carry.clear() }
    val drop by rememberUpdatedState {
        val from = rowsOf.indexOfFirst { it.id == carry.id }
        val over = hoverOf(rows, rowsOf, carry)
        val move = if (from >= 0 && over != null) QueueRows.drop(rowsOf, QueueLook.Whole, from, if (over > from) over + 1 else over, order.size) else null
        if (move == null) {
            carry.clear()
        } else {
            val (source, target) = move
            val was = order
            val moved = order.toMutableList().apply { add(target, removeAt(source)) }
            order = moved
            carry.landed = true
            haptics.tick()
            scope.launch { if (!onMove(moved)) order = was }
        }
    }
    EdgeScroll(carry, rows, bottomRoom)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth
        val high = maxHeight
        val square = isSquare(wide, high)
        // The list's picture, its name, how much is in it, the line about it and the keys. On a square screen the line
        // about the list comes after the keys, as on the record page, so the keys are on screen before it is scrolled.
        val header: @Composable (Dp) -> Unit = { edge ->
                    val empty = order.isEmpty()
                    val sleeve = picture ?: order.firstOrNull()?.let(coverOf)
                    ListHead(name, sleeve, holding(order.size, seconds(order), missing), about(order, blurb), if (blurb == null) 2 else 3, images, edge, top, framed, drifts = true, keysFirst = square) {
                        // Shuffle, then Play in the middle in a glass circle, then the download key, as on the album page.
                        // A list with no download key keeps its place empty, so Play stays in the middle of the page.
                        // Adding and rearranging are in the menu.
                        SideKey(Glyph.shuffle, stringResource(R.string.AlbumMenu_Shuffle), onShuffle, enabled = !empty)
                        val play = stringResource(R.string.Rpm_Playlist_PlayList)
                        GlassButton(
                            onClick = onPlayAll,
                            modifier = Modifier.size(KEY_HEIGHT).alpha(if (empty) 0.4f else 1f).semantics { contentDescription = play },
                            weight = Weight.Primary,
                            enabled = !empty,
                            stretches = false,
                            height = KEY_HEIGHT,
                            radius = KEY_HEIGHT / 2,
                            inset = 0.dp,
                        ) { style ->
                            // The triangle's weight is left of its middle, so it is moved right a little to look centred.
                            BasicText(Glyph.play, Modifier.offset(x = 2.dp), style = icon(18f).copy(color = style.color))
                        }
                        if (download != null) DownloadButton(download, bare = true) else Spacer(Modifier.size(SIDE_KEY_WIDTH, KEY_HEIGHT))
                    }
        }
        // The songs, or the line for an empty list, and room for the tab bar: the same rows on a tall page and on a
        // square one.
        val songs: LazyListScope.() -> Unit = {
                if (order.isEmpty()) {
                    item(key = "nothing") { Nothing(missing, adding != null) }
                } else {
                    // A list can hold the same song twice, so each row is told apart by its copy as well as its song,
                    // which keeps its identity through a move.
                    items(rowsOf, key = { it.id }, contentType = { "song" }) { row ->
                        val shift by animateFloatAsState(shiftOf(row, rowsOf, rows, carry), label = "make room")
                        val carried = row.id == carry.id
                        Box(
                            Modifier
                                .zIndex(if (carried) 1f else 0f)
                                .graphicsLayer { translationY = if (carried) carry.dy else shift }
                                .background(if (carried) Ink.white(0x14) else Color.Transparent),
                        ) {
                            val track = row.item
                            if (rearranging) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) { ListSongRow(track, coverOf(track), images, playing = track.rpmId == playing, download = download) {} }
                                    Grip(row.id, carry, rows, onDrop = { drop() })
                                }
                            } else {
                                SongSwipe(track) { ListSongRow(track, coverOf(track), images, playing = track.rpmId == playing, download = download) { onPlay(row.at) } }
                            }
                        }
                    }
                }
                item(key = "foot") { Spacer(Modifier.height(40.dp + bottomRoom)) }
        }
        Box(Modifier.fillMaxSize().backdropSource(page)) {
            // The list's own colours for a generated list; the colours of its own picture for any other, never a
            // record's in it; the app's blue for a list with neither (`PlaylistPage.dress` on iPhone).
            val read = rememberRoomColours(if (framed == null) picture else null, images)
            RoomGround(framed?.look?.room ?: read ?: RoomPalette.FALLBACK, seed = name)
            if (square) {
                // **A square screen shows the list beside its songs**, as the record page does there ([isSquare]): the
                // picture, the name, the facts, the keys and the line about the list on the left, the songs on the
                // right, each side scrolling on its own. Rearranging works on the right-hand list as on a tall page.
                val side = rememberScrollState()
                ScrollsToTop(side)
                val left = wide * SQUARE_LEFT
                val edge = (high - top - bottomRoom - SQUARE_UNDER_LIST_PICTURE).coerceIn(SQUARE_SLEEVE_LEAST, left - 40.dp)
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .width(left)
                            .fillMaxHeight()
                            .dissolvesAtTop(top) { side.value.toFloat() }
                            .verticalScroll(side),
                    ) {
                        header(edge)
                        Spacer(Modifier.height(bottomRoom))
                    }
                    LazyColumn(state = rows, modifier = Modifier.weight(1f).fillMaxHeight().dissolvesAtTop(top) { rows.scrolled() }) {
                        item(key = "top") { Spacer(Modifier.height(top)) }
                        songs()
                    }
                }
            } else {
                LazyColumn(state = rows, modifier = Modifier.fillMaxSize().dissolvesAtTop(top) { rows.scrolled() }) {
                    item(key = "header") { header((wide.value * COVER_FRACTION).coerceIn(160f, 360f).roundToInt().dp) }
                    songs()
                }
            }
        }
        Box(Modifier.padding(top = top - BACK_ROW).height(BACK_ROW).padding(start = 20.dp), contentAlignment = Alignment.Center) {
            BackKey(page, onBack = { if (rearranging) asked = false else goBack() })
        }
        PlaylistCorner(
            menu,
            name,
            page,
            top,
            bottomRoom,
            rearranging = rearranging,
            canAdd = adding != null,
            canRearrange = canReorder && rowsOf.size > 1,
            onAdd = { picking = true },
            onRearrange = { asked = it },
            onBack = onBack,
        )
        // Over the tab bar and the mini player, as the iPhone's sheet is.
        OverChrome {
        AnimatedVisibility(picking && adding != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
            val into = adding ?: return@AnimatedVisibility
            NewPlaylistSheet(
                SheetErrand.Add(name, into.folder),
                into.collection,
                images,
                page,
                bottomRoom,
                commit = { _, songs -> into.onAdd(songs) },
                onClose = { picking = false },
            )
        }
        }
    }
}

/**
 * What the plus key on a list's page needs: the library to pick songs from, the folder the list is in, and the
 * write. [onAdd] puts the songs at the end of the list, leaving the ones already there as they are, and answers a
 * sentence when it failed, or null when it is done.
 */
class ListAdding(
    val collection: com.rpm.player.core.library.Collection,
    val folder: List<String>,
    val onAdd: suspend (List<CloudTrack>) -> String?,
)

/**
 * The page's background: the list's room, moving, filling the page as the album page's blurred cover does, under the
 * iPhone's dark gradient, lighter at the top where the square and the name are, darker down where the rows are
 * (`PlaylistPage.backdrop` on iPhone). It runs while the page is the one in front ([LiveRoom]).
 */
@Composable
internal fun RoomGround(palette: RoomPalette, seed: String) {
    Box(Modifier.fillMaxSize()) {
        LiveRoom(palette, seed, running = true, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0x22 / 255f),
                    0.45f to Color.Black.copy(alpha = 0x77 / 255f),
                    1f to Color.Black.copy(alpha = 0x99 / 255f),
                ),
            ),
        )
    }
}

/**
 * The top part of a list's page. It has the same layout as the album page: the picture large and centred,
 * the name, how much is in the list as a chip, a line about the list, and the round keys, centred.
 *
 * @param about the line under the chip, or null for none. [aboutLines] is the most lines it may take.
 */
@Composable
internal fun ListHead(
    name: String,
    picture: Any?,
    holding: String,
    about: String?,
    aboutLines: Int,
    images: ImageLoader,
    edge: Dp,
    top: Dp,
    framed: FramedList? = null,
    drifts: Boolean = false,
    keysFirst: Boolean = false,
    keys: @Composable RowScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(top))
        PlaylistSleeve(picture, Glyph.record, edge, mark = 52f, images, framed, drifts = drifts)
        BasicText(name, Modifier.padding(top = 10.dp), maxLines = 3, style = displayBold(24f).copy(color = Color.White, textAlign = TextAlign.Center))
        BasicText(
            holding,
            Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(Ink.white(0x1F))
                .padding(horizontal = 10.dp, vertical = 3.dp),
            // Centred, so a chip that wraps in a narrow column (a square screen's) keeps its lines in the middle.
            style = display(12f).copy(color = Ink.white(0xE6), textAlign = TextAlign.Center),
        )
        // What the list is and who is on it. The desktop shows the same line under a list's name. Above the keys, or
        // under them where [keysFirst] asks, as on a square screen.
        val line: @Composable () -> Unit = {
            about?.let { about ->
                BasicText(
                    about,
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    maxLines = aboutLines,
                    overflow = TextOverflow.Ellipsis,
                    // Centred, as the album page centres the text about a record.
                    style = display(14f).copy(color = Ink.white(0xB3), lineHeight = pt(14f * 1.2f + 7f), textAlign = TextAlign.Center),
                )
            }
        }
        if (!keysFirst) line()
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(KEYS_APART, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = keys,
        )
        if (keysFirst) line()
    }
}

/** On a square screen, the height a list's column needs besides its picture: the space above it, a one-line name, the
 *  chip, the keys and a margin under them. Measured on the Titan. A longer name pushes the keys down. */
private val SQUARE_UNDER_LIST_PICTURE = 196.dp

/**
 * A mood's sentence, then the artists that most of the songs are by, for example "Featuring Blur, Pulp and
 * 4 more". Null if there is neither.
 */
@Composable
private fun about(tracks: List<CloudTrack>, mood: MoodLine?): String? {
    val blurb = mood?.let { moodSentence(it) }
    val featured = remember(tracks) { PlaylistFeatures.of(tracks.map { it.artist }, PlaylistFeatures.NAMED) }
    if (featured.named.isEmpty()) return blurb
    val names = featured.named.joinToString(", ")
    val featuring = if (featured.more > 0) {
        stringResource(R.string.Playlists_FeaturingMore, names, featured.more)
    } else {
        stringResource(R.string.Playlists_Featuring, names)
    }
    return if (blurb == null) featuring else "$blurb $featuring"
}

/**
 * One song: the playing mark in the margin, the cover of its record, the title (with an E after it for
 * explicit lyrics), the artist and record below it, and the length. The row is lit while a finger is on it.
 *
 * On a playlist's page, what its download key cannot say about this one song is a small mark in the trailing margin,
 * where it moves nothing ([RowDownloadMark]). The queue passes no [download], and its rows have no mark.
 */
@Composable
internal fun ListSongRow(track: CloudTrack, cover: Cover?, images: ImageLoader, playing: Boolean, download: DownloadKey? = null, onClick: () -> Unit) {
    val presses = remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .background(if (pressed) Ink.white(0x24) else Color.Transparent)
            .songPress(track, presses, onClick),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(28.dp).clip(RoundedCornerShape(percent = 50)).background(if (playing) Color.White else Color.Transparent))
            PlaylistSleeve(cover, Glyph.record, edge = 40.dp, mark = 16f, images)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(track.title.orEmpty(), Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(16f).copy(color = Color.White))
                    if (track.isExplicit) ExplicitMark()
                }
                said(track).takeIf { it.isNotEmpty() }?.let {
                    BasicText(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(13f).copy(color = Ink.white(0x77)))
                }
            }
            BasicText(length(track.tags?.durationSeconds ?: 0.0), style = display(13f).copy(color = Ink.white(0x66)))
        }
        RowDownloadMark(download, track.rpmId)
    }
}

/** The artist and the record, for example "Talk Talk · Spirit of Eden". Each part is shown only if the song has it. */
private fun said(track: CloudTrack): String =
    listOfNotNull(track.artist?.takeIf { it.isNotEmpty() }, track.album?.takeIf { it.isNotEmpty() }).joinToString(" · ")

/** An empty list. Either it names songs that have not arrived yet, or it has nothing in it. */
@Composable
private fun Nothing(missing: Int, adding: Boolean) {
    BasicText(
        when {
            missing > 0 -> waitingOn(missing)
            // A list songs can be added to says how.
            adding -> stringResource(R.string.Rpm_Playlist_Empty)
            else -> stringResource(R.string.Rpm_Playlist_EmptyPlain)
        },
        Modifier.fillMaxWidth().padding(40.dp),
        style = display(15f).copy(color = Ink.white(0xCC), textAlign = TextAlign.Center),
    )
}
