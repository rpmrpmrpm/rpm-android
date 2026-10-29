package com.rpm.player.tv.ui

import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.lerp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Stable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import com.rpm.player.core.cloud.Shelf
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.rpm.player.core.library.RecordFacts
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import com.rpm.player.core.R
import com.rpm.player.core.library.AlbumFavourites
import com.rpm.player.core.library.AlbumRatings
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.LibraryRepository
import com.rpm.player.core.library.LibraryState
import java.text.Normalizer

/** Draws [content] once the library is ready, and the loading and failure messages until then. */
@Composable
fun LibraryGate(library: LibraryRepository, content: @Composable (LibraryState.Ready) -> Unit) {
    val state by library.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is LibraryState.Ready -> content(s)
        is LibraryState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val retry = remember { FocusRequester() }
                Message(stringResource(R.string.Rpm_Library_Error))
                Spacer(Modifier.height(24.px))
                val shape = RoundedCornerShape(7.s)
                DeskButton(
                    onClick = library::retry,
                    corner = 7.s,
                    modifier = Modifier.focusRequester(retry).height(60.s).background(Color(0x14FFFFFF), shape).border(1.px, Color(0xB3FFFFFF), shape).padding(horizontal = 24.s),
                ) {
                    Text(stringResource(R.string.Rpm_Library_Retry), style = ui(22.ts, FontWeight.SemiBold), color = Ink.Words)
                }
                LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
            }
        }
        LibraryState.SignedOut -> Waiting(stringResource(R.string.Rpm_Library_Loading))
        // A collection another account shares is being opened: the desktop's switching screen says so.
        is LibraryState.Loading ->
            Waiting(stringResource(if (s.shelf is Shelf.Borrowed) R.string.Viz_SwitchingCollection else R.string.Rpm_Library_Loading))
    }
}

/** The wall's orders (the desktop's LibraryTools sort menu); by artist first, as on the desktop. */
enum class WallOrder(val label: Int, val glyph: Char) {
    Album(R.string.AlbumSort_Album, Fa.RECORD),
    Artist(R.string.AlbumSort_Artist, Fa.USER),
    Added(R.string.AlbumSort_Added, ''),
    /** By the year the record came out, oldest first; records with no year at the end. */
    Date(R.string.AlbumSort_Date, ''),
    Plays(R.string.AlbumSort_Plays, ''),
}

/**
 * The search on the album wall as it stands: whether it is open, what is typed, and the album last opened
 * from its results. Held by the app rather than the wall, which leaves composition when an album page
 * opens over it, so Back from that page comes back to the results; started again whenever a screen is
 * picked from the side menu.
 */
@Stable
class WallSearchState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")

    /** The album last opened from the results, given the focus again on the way back. */
    var opened by mutableStateOf<String?>(null)

    /** Set when the field is to take the focus as it appears, and [showKeyboard] the TV's keyboard. */
    var focusField by mutableStateOf(false)
    var showKeyboard by mutableStateOf(false)

    fun close() {
        open = false
        query = ""
        opened = null
        focusField = false
        showKeyboard = false
    }
}

/**
 * The album wall (PegasusChrome.Library.cs): every album in [WALL_COLUMNS] columns, 22 apart, 34 in from
 * the sides; in the top right, the search key, the order key and the favourites key. The wall starts
 * under them and dissolves over `96·s` at the bottom while there is more below. There is no back key in
 * the top left as on the desktop: the remote's Back does the same.
 *
 * The search is a key until it is pressed, then a field ([WallSearch]). While it is open the wall is
 * blurred under `#33000000`, the mini player is covered, and the albums matching what is typed are listed
 * in a smoked glass panel under the tools ([SearchResults]), as the radio's station search covers its
 * screen. The side menu's Search opens [SearchScreen], not this. The order key opens
 * the four orders; the favourites key shows only albums holding a favourite track. The remote centres the row it moves to and reveals a card within a row.
 *
 * Back while the search is open: from the field with something typed it goes to the first result, and
 * with nothing typed (or nothing found) it closes the search; from the results it closes the search.
 * The keyboard takes the first Back itself, so its closing is treated as that Back.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumsScreen(
    collection: Collection,
    images: ImageLoader,
    carousel: Boolean,
    order: WallOrder,
    onOrder: (WallOrder) -> Unit,
    searchState: WallSearchState,
    backdrop: Backdrop,
    mini: MiniPlayerState,
    favouriteAlbums: AlbumFavourites,
    ratings: AlbumRatings,
    actions: TrackActions,
    onOpenAlbum: (CloudAlbum) -> Unit,
) {
    var favouritesOn by rememberSaveable { mutableStateOf(false) }
    // On a collection another account shares, the hearts are the owner's, so there is no favourites key.
    val ownShelf = collection.shelf == Shelf.Own
    val favourites = favouritesOn && ownShelf
    // The wall in the order asked for. The first is worked out at once, so the screen opens on it. A new order, or
    // the favourites key, is worked out off the main thread while the wall keeps the last one; one that takes longer
    // than [SORT_QUIET_MS] puts the turning record over a blur of the wall until it is in, then the blur fades,
    // so a slow sort does not look like a frozen screen. Keyed on the collection, so a
    // new one is laid out at once like a first.
    var shown by remember(collection) { mutableStateOf(wallOf(collection, "", order, favourites)) }
    var shownFor by remember(collection) { mutableStateOf(order to favourites) }
    var sorting by remember { mutableStateOf(false) }
    val sortVeil by animateFloatAsState(if (sorting) 1f else 0f, tween(if (sorting) SORT_IN_MS else SORT_OUT_MS), label = "sort")
    LaunchedEffect(collection, order, favourites) {
        // A change undone before its wall was in: what is shown is already right.
        if (shownFor == (order to favourites)) {
            sorting = false
            return@LaunchedEffect
        }
        val began = android.os.SystemClock.uptimeMillis()
        fun since() = android.os.SystemClock.uptimeMillis() - began
        val work = async(Dispatchers.Default) { wallOf(collection, "", order, favourites).also { com.rpm.player.core.diag.Diag.d(SORT_LOG, "$order sorted in ${since()} ms") } }
        val quick = withTimeoutOrNull(SORT_QUIET_MS) { work.await() }
        if (quick == null && !sorting) {
            backdrop.capture()
            com.rpm.player.core.diag.Diag.d(SORT_LOG, "blur taken at ${since()} ms")
            sorting = true
        }
        shown = quick ?: work.await()
        shownFor = order to favourites
        // The new wall is laid out under the blur before the blur fades.
        withFrameNanos { }
        com.rpm.player.core.diag.Diag.d(SORT_LOG, "first frame of the new wall at ${since()} ms")
        withFrameNanos { }
        sorting = false
    }
    val query = searchState.query
    val open = searchState.open
    val results = remember(collection, query, order, favourites) {
        if (query.isBlank()) emptyList() else wallOf(collection, query, order, favourites)
    }
    val field = remember { FocusRequester() }
    val firstCard = remember { FocusRequester() }
    val orderKey = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val returnRow = remember { FocusRequester() }
    val menu = rememberRowMenu()
    val grid = rememberLazyGridState()
    val underKeys = rememberUnderKeys(grid, WALL_COLUMNS)
    val carouselState = remember { CarouselState() }
    val atTop = remember { derivedStateOf { grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0 } }
    // The screen in front hands the mini player its scroll answer, which it reads live; one kept under it does not
    // (LocalOnTop).
    val onTop = LocalOnTop.current
    SideEffect { if (onTop) mini.follow(atTop) }
    val scope = rememberCoroutineScope()
    // The open field takes the place of the order and favourites keys, which a search does not use, and
    // the panel under it is as wide.
    val searchWide = SEARCH_WIDE + (TOOL_WIDE + 2.px + 8.px) * (if (ownShelf) 2 else 1)
    // The blur is drawn only once it has been taken of the wall as it stands, so it never shows a
    // picture of another screen, or of itself.
    var blurred by remember { mutableStateOf(false) }
    var inResults by remember { mutableStateOf(false) }

    // The search covers the wall, the mini player with it.
    DisposableEffect(open) {
        if (open) mini.cover()
        onDispose { if (open) mini.uncover() }
    }

    // Opened from the wall's search key, with the keyboard up.
    fun openSearch() {
        scope.launch {
            // The wall as it is drawn now, before anything covers it.
            withFrameNanos { }
            backdrop.capture()
            searchState.focusField = true
            searchState.showKeyboard = true
            searchState.open = true
            blurred = true
        }
    }

    // The focus goes to the first album BEFORE the search closes. Closed first, the focused field or result
    // left the screen, Compose cleared the focus and searched afresh for somewhere to enter, and it chose an
    // album two rows down, scrolling the wall to it (seen on the Amiko, read from the focus's stack). With
    // the first album off screen (the wall scrolled before the search), the wall goes back to its top first.
    fun closeSearch(focusWall: Boolean) {
        if (focusWall) {
            val now = runCatching { firstCard.requestFocus() }.getOrDefault(false)
            scope.launch {
                if (!now) grid.scrollToItem(0)
                withFrameNanos { }
                // Asked again (a no-op when it already holds): see [focusTwice].
                runCatching { firstCard.requestFocus() }
            }
        }
        searchState.close()
        blurred = false
        inResults = false
    }

    // A request for the focus made as the keyboard closes is spent waking the view's own focus, which the
    // keyboard took; Compose then enters wherever it can (seen on the Amiko: the album under the field).
    // Asked again a frame later, once the view has its focus, it lands. A no-op when the first one did.
    fun focusTwice(target: FocusRequester, landed: () -> Boolean) {
        runCatching { target.requestFocus() }
        scope.launch {
            withFrameNanos { }
            if (!landed()) runCatching { target.requestFocus() }
        }
    }

    fun toResults(): Boolean {
        if (results.isEmpty()) return false
        focusTwice(firstResult) { inResults }
        return true
    }

    // The keyboard going away while the search is open, read off the window's insets every 150 ms: the
    // keyboard takes the remote's first Back and the app hears nothing of it. (The window's focus does not
    // change: the TV's keyboard leaves it with the app.) Typed and found: the first result. Typed, nothing
    // found: the field. Nothing typed: the search closes.
    // The activity's window, which the keyboard opens over, even where the screens are on a virtual display.
    val view = LocalKeyboardView.current
    val keyboardClosed by rememberUpdatedState {
        when {
            searchState.query.isBlank() -> closeSearch(focusWall = true)
            !toResults() -> focusTwice(field) { false }
        }
    }
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        var seen = false
        while (true) {
            val up = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (seen && !up) keyboardClosed()
            seen = up
            delay(KEYBOARD_POLL_MS)
        }
    }

    BackHandler(enabled = open && LocalOnTop.current) {
        when {
            inResults -> closeSearch(focusWall = true)
            query.isNotBlank() && toResults() -> Unit
            else -> closeSearch(focusWall = true)
        }
    }

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = WALL_COLUMNS
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Message(stringResource(if (collection.albums.isEmpty()) R.string.Tv_Library_Empty else R.string.Viz_LibraryNoMatch))
                }
            } else if (carousel) {
                val unknownAlbum = stringResource(R.string.Queue_UnknownAlbum)
                AlbumCarousel(
                    shown, order, collection, images, backdrop, mini, carouselState, focus = firstCard, up = orderKey, onOpen = onOpenAlbum,
                    onMenu = { album -> menu.open(album.album.ifBlank { unknownAlbum }, albumChoices(album, collection, favouriteAlbums, ratings, actions), returnTo = firstCard) },
                )
            } else {
                // The row past the last one on screen is decoded into memory as the wall scrolls, at the size a card
                // asks for (a cell is as wide as its cover), so the covers are there when the row comes into view.
                // Nothing is composed for it: a row composed ahead is what costs on the Amiko.
                val context = LocalContext.current
                DecodeAhead(
                    images = images,
                    key = shown,
                    lastVisible = { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 },
                    wanted = { last ->
                        val edge = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.size?.width ?: 0
                        if (edge <= 0) emptyList()
                        else (last + 1..minOf(shown.lastIndex, last + columns)).mapNotNull { i ->
                            collection.cover(shown[i])?.let { aheadRequest(context, it, edge, edge) }
                        }
                    },
                )
                CompositionLocalProvider(LocalBringIntoViewSpec provides CentreWallRows) {
                    LazyVerticalGrid(
                        state = grid,
                        columns = GridCells.Fixed(columns),
                        contentPadding = PaddingValues(start = 34.px, end = 34.px + 16.px, top = WALL_TOP, bottom = 28.px),
                        horizontalArrangement = Arrangement.spacedBy(22.px),
                        verticalArrangement = Arrangement.spacedBy(22.px),
                        modifier = Modifier.fillMaxSize().footFade(grid),
                    ) {
                        itemsIndexed(shown, key = { _, album -> album.key }) { index, album ->
                            val cover = collection.cover(album)
                            WallCard(
                                art = {
                                    if (cover != null) {
                                        CoverImage(cover, images, ContentScale.Crop, Modifier.fillMaxSize(), onShown = {})
                                    }
                                },
                                title = albumTitle(album),
                                line = albumArtist(album),
                                explicit = album.tracks.any { it.tags?.advisory == EXPLICIT },
                                onClick = { onOpenAlbum(album) },
                                // Focusable while the search covers the wall, so the focus can be put here
                                // before the search closes; the search's own keys keep the remote in it.
                                modifier = (if (album === shown.first()) Modifier.focusRequester(firstCard) else Modifier)
                                    .then(underKeys.mark(index))
                                    // Up from any card of the first row reaches the tools in the top right corner.
                                    // The search by layout finds them only from the columns under them.
                                    .focusProperties { if (index < columns) up = orderKey },
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = open && blurred,
            enter = fadeIn(tween(SEARCH_SLIDE_MS)),
            exit = fadeOut(tween(SEARCH_SLIDE_MS)),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                GlassBackdrop(backdrop, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Color(0x33000000)))
            }
        }
        // The results it last showed, so it folds up with them rather than with "nothing found" once the
        // search is closed and the text cleared.
        var shownResults by remember { mutableStateOf<List<CloudAlbum>>(emptyList()) }
        SideEffect { if (query.isNotBlank()) shownResults = results }
        AnimatedVisibility(
            visible = open && blurred && query.isNotBlank(),
            enter = expandVertically(tween(SEARCH_SLIDE_MS, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(SEARCH_SLIDE_MS)),
            exit = shrinkVertically(tween(SEARCH_SLIDE_MS, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(SEARCH_SLIDE_MS)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 14.px + TOOL_HIGH + 12.px, end = 50.px, bottom = 34.px)
                .width(searchWide),
        ) {
            SearchResults(
                results = shownResults,
                collection = collection,
                images = images,
                backdrop = backdrop,
                opened = searchState.opened,
                firstResult = firstResult,
                returnRow = returnRow,
                field = field,
                onOpen = { album ->
                    searchState.opened = album.key
                    onOpenAlbum(album)
                },
                onFocus = { inResults = it },
                modifier = Modifier.fillMaxSize(),
            )
        }

        val keysAlpha by animateFloatAsState(if (open) 0f else 1f, tween(SEARCH_SLIDE_MS), label = "keys")
        // The orders' names, read here because the menu is built in the key's click.
        val orderNames = WallOrder.entries.associateWith { stringResource(it.label) }
        Box(Modifier.align(Alignment.TopEnd).padding(top = 14.px, end = 50.px)) {
            Row(
                Modifier.align(Alignment.CenterEnd).graphicsLayer { alpha = keysAlpha },
                horizontalArrangement = Arrangement.spacedBy(8.px),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolBox {
                    ToolKey(
                        onClick = {
                            menu.open(
                                title = "",
                                // The order in force wears the tick, as on the desktop (LibrarySort_Click).
                                choices = WallOrder.entries.map { o -> MenuTick(orderNames.getValue(o), ticked = o == order) { onOrder(o) } },
                                returnTo = orderKey,
                            )
                        },
                        modifier = Modifier.focusRequester(orderKey).focusProperties { canFocus = !open; down = if (carousel) firstCard else if (underKeys.index >= 0) underKeys.requester else FocusRequester.Default },
                    ) { Glyph(Fa.SORT, TOOL_GLYPH, Ink.Key) }
                }
                if (ownShelf) {
                    ToolBox {
                        ToolKey(onClick = { favouritesOn = !favouritesOn }, modifier = Modifier.focusProperties { canFocus = !open; down = if (carousel) firstCard else if (underKeys.index >= 0) underKeys.requester else FocusRequester.Default }) {
                            Glyph(Fa.HEART, TOOL_GLYPH, if (favourites) Ink.Loved else Ink.Unloved, regular = !favourites)
                        }
                    }
                }
            }
            WallSearch(
                state = searchState,
                wide = searchWide,
                keysWide = searchWide - SEARCH_WIDE,
                modifier = Modifier.align(Alignment.CenterEnd),
                onOpen = { openSearch() },
                keysDown = { if (carousel) firstCard else if (underKeys.index >= 0) underKeys.requester else FocusRequester.Default },
                onLeave = { toResults() },
                onEmptyBlur = { closeSearch(focusWall = false) },
                field = field,
            )
        }

        // A new order being worked out: the wall blurred under the turning record.
        if (sortVeil > 0f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = sortVeil }) {
                GlassBackdrop(backdrop, Modifier.fillMaxSize().dithered())
                Box(Modifier.fillMaxSize().background(Color(0x33000000)))
                RecordSpinner(Modifier.align(Alignment.Center).size(64.s))
            }
        }
    }

    RowMenu(menu)

    // Nothing is focused when a screen opens, and the first arrow press would be spent finding something.
    // Back from an album opened from the results finds them again, with the album it opened in focus.
    LaunchedEffect(Unit) {
        when {
            searchState.open -> {
                withFrameNanos { }
                backdrop.capture()
                blurred = true
                withFrameNanos { }
                val target = when {
                    results.any { it.key == searchState.opened } -> returnRow
                    results.isNotEmpty() -> firstResult
                    else -> field
                }
                runCatching { target.requestFocus() }
            }
            else -> runCatching { firstCard.requestFocus() }
        }
    }
}

/**
 * The albums matching the search, in a panel of the smoked glass under the tools, as wide as the field
 * and the keys beside it: each a row of the cover (88·s, corner 6) and the name and artist at the wall's
 * sizes, lit while the remote stands on it. OK opens the album's page. Up from the first goes back to
 * the field. With nothing found it says so.
 */
@Composable
private fun SearchResults(
    results: List<CloudAlbum>,
    collection: Collection,
    images: ImageLoader,
    backdrop: Backdrop,
    opened: String?,
    firstResult: FocusRequester,
    returnRow: FocusRequester,
    field: FocusRequester,
    onOpen: (CloudAlbum) -> Unit,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier,
) {
    GlassPanel(backdrop, 12.s, modifier.onFocusChanged { onFocus(it.hasFocus) }) {
        Box(Modifier.matchParentSize().background(GLASS_DIM))
        if (results.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.s), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.Viz_LibraryNoMatch), style = ui(18.ts), color = Color(0xCCFFFFFF), textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.s),
                verticalArrangement = Arrangement.spacedBy(4.s),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(results, key = { _, album -> album.key }) { i, album ->
                    var lit by remember { mutableStateOf(false) }
                    val shape = RoundedCornerShape(8.px)
                    DeskButton(
                        onClick = { onOpen(album) },
                        corner = 8.px,
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .then(if (i == 0) Modifier.focusRequester(firstResult) else Modifier)
                            .then(if (album.key == opened) Modifier.focusRequester(returnRow) else Modifier)
                            // The albums under the blur can take the focus, so the remote is kept in the panel.
                            .focusProperties {
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                                if (i == 0) up = field
                                if (i == results.lastIndex) down = FocusRequester.Cancel
                            }
                            .onFocusChanged { lit = it.isFocused }
                            .fillMaxWidth()
                            .background(if (lit) Color(0x33FFFFFF) else Color.Transparent, shape)
                            .padding(10.s),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(88.s).clip(RoundedCornerShape(6.px)).background(Ink.Well)) {
                                val cover = collection.cover(album)
                                if (cover != null) {
                                    CoverImage(cover, images, ContentScale.Crop, Modifier.fillMaxSize(), onShown = {})
                                }
                            }
                            Spacer(Modifier.width(16.s))
                            Column(verticalArrangement = Arrangement.spacedBy(4.s)) {
                                Text(albumTitle(album), style = ui(CARD_TITLE_TEXT, FontWeight.SemiBold), color = Ink.Card, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(albumArtist(album), style = ui(CARD_LINE_TEXT), color = Ink.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Albums in a row on the wall and lists on the playlists screen: 6, where the desktop's `round(8 / s)` is
 * 7 at 1080, so each cover is larger from a couch. */
const val WALL_COLUMNS = 6

/** Where the wall starts: under the tools, `14 + 44·s + 16`. */
internal val WALL_TOP: Dp get() = 14.px + TOOL_HIGH + 16.px

@OptIn(ExperimentalFoundationApi::class)
private val CentreWallRows = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset + size / 2 - containerSize / 2
}

/** The wall's foot dissolves over `96·s` while there is more below; there is no dissolve at the top. */
private fun Modifier.footFade(grid: LazyGridState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (!grid.canScrollForward) return@drawWithContent
            val depth = 96.s.toPx()
            drawRect(
                Brush.verticalGradient(0f to Color.Black, ((size.height - depth) / size.height).coerceIn(0f, 1f) to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn,
            )
        }

/** The size of a card's name on the wall, and of an entry's title in Quick selection: 17·s (the desktop's
 * 12), read from a couch. */
val CARD_TITLE_TEXT = 17.ts

/** The size of a card's second line on the wall (the artist), and of an entry's second line in Quick
 * selection: 15·s (the desktop's 11). */
val CARD_LINE_TEXT = 15.ts

/**
 * One card on the wall (PegasusCardTemplate): the picture square on `#22FFFFFF` with corner 6, then 7
 * under it the name ([CARD_TITLE_TEXT] SemiBold, centred, with the E 4 after it) and 7 under that the
 * second line ([CARD_LINE_TEXT], `#99FFFFFF`). The playlists screen draws its lists with it too.
 */
@Composable
fun WallCard(art: @Composable () -> Unit, title: String, line: String?, explicit: Boolean, onClick: () -> Unit, modifier: Modifier) {
    CardButton(onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.px)).background(Ink.Well)) {
                art()
            }
            Row(Modifier.padding(top = 7.px).heightIn(min = 22.s), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = ui(CARD_TITLE_TEXT, FontWeight.SemiBold), color = Ink.Card, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
                if (explicit) {
                    Spacer(Modifier.width(5.px))
                    ExplicitBadge(16.s)
                }
            }
            Text(line ?: " ", style = ui(CARD_LINE_TEXT), color = Ink.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 7.px).heightIn(min = 20.s))
        }
    }
}

/**
 * The wall's search (LibraryTools): a tool key with the magnifier until it is pressed, then the field,
 * which slides out to the left from the key's place to [wide] over [SEARCH_SLIDE_MS], over the order and
 * favourites keys ([keysWide]), which fade out under it, and folds back the same way when the search
 * closes, drawn whole until it is the key again. The field is corner 6, `#1FFFFFFF` with a 1 px
 * `#26FFFFFF` edge, the magnifier and the text 19·s.
 *
 * [onOpen] is the key pressed; the field then takes the focus (and the keyboard, with
 * [WallSearchState.showKeyboard]) as it appears. Down on the field and the keyboard's search key call
 * [onLeave]. Losing the focus with nothing typed calls [onEmptyBlur]. The keyboard closing is watched by
 * the wall ([AlbumsScreen]), not here: the field does not keep the focus when the keyboard hides.
 */
@Composable
private fun WallSearch(
    state: WallSearchState,
    wide: Dp,
    keysWide: Dp,
    modifier: Modifier,
    onOpen: () -> Unit,
    /** Where Down from the search key goes: the carousel, or the card under the keys. */
    keysDown: () -> FocusRequester,
    onLeave: () -> Unit,
    onEmptyBlur: () -> Unit,
    field: FocusRequester,
) {
    val shape = RoundedCornerShape(6.px)
    val open = state.open
    val query = state.query
    // 0 folded into the key beside the order key, 1 open over the keys to [wide]; both ways eased.
    val unfolded by animateFloatAsState(if (open) 1f else 0f, tween(SEARCH_SLIDE_MS, easing = FastOutSlowInEasing), label = "search")
    val keyboard = LocalSoftwareKeyboardController.current
    // What was typed, drawn in the field while it folds after the search has closed and cleared it.
    var lastQuery by remember { mutableStateOf("") }
    SideEffect { if (open) lastQuery = query }
    Box(
        modifier
            .padding(end = lerp(keysWide, 0.px, unfolded))
            .width(lerp(TOOL_WIDE + 2.px, wide, unfolded))
            .height(TOOL_HIGH),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (!open && unfolded > 0.001f) {
            // Folding: the field as it looked, with nothing in it to take the focus.
            Row(
                Modifier.fillMaxSize().background(TOOL_FILL, shape).border(1.px, TOOL_RIM, shape).padding(horizontal = 13.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(Fa.SEARCH, TOOL_GLYPH, Ink.Dim)
                Spacer(Modifier.width(12.px))
                Text(
                    lastQuery.ifEmpty { stringResource(R.string.Viz_BrowseSearch) },
                    style = ui(SEARCH_TEXT),
                    color = if (lastQuery.isEmpty()) Color(0x8CFFFFFF) else Ink.Card,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        } else if (!open) {
            ToolBox {
                ToolKey(onClick = onOpen, modifier = Modifier.focusProperties { down = keysDown() }) { Glyph(Fa.SEARCH, TOOL_GLYPH, Ink.Key) }
            }
        } else {
            var focused by remember { mutableStateOf(false) }
            // Closed only after the field has held the focus: its first focus report says unfocused.
            var held by remember { mutableStateOf(false) }
            BasicTextField(
                value = query,
                onValueChange = { state.query = it },
                singleLine = true,
                textStyle = ui(SEARCH_TEXT).copy(color = Ink.Card),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onLeave() }),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(field)
                    .deskFocus(6.px)
                    // Down leaves the field for the results. Left to the field, it moves the cursor and the
                    // remote could never leave it.
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            onLeave()
                            true
                        } else {
                            false
                        }
                    }
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) held = true else if (held && query.isEmpty()) onEmptyBlur()
                    },
                decorationBox = { inner ->
                    Row(
                        Modifier.fillMaxSize().background(TOOL_FILL, shape).border(1.px, TOOL_RIM, shape).padding(horizontal = 13.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph(Fa.SEARCH, TOOL_GLYPH, Ink.Dim)
                        Spacer(Modifier.width(12.px))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text(stringResource(R.string.Viz_BrowseSearch), style = ui(SEARCH_TEXT), color = if (focused) Color(0xA6FFFFFF) else Color(0x8CFFFFFF), maxLines = 1)
                            }
                            inner()
                        }
                    }
                },
            )
            LaunchedEffect(Unit) {
                if (state.focusField) {
                    val withKeyboard = state.showKeyboard
                    state.focusField = false
                    state.showKeyboard = false
                    runCatching { field.requestFocus() }
                    if (withKeyboard) keyboard?.show()
                }
            }
        }
    }
}

/** A box styled as the search field, holding one tool key. */
@Composable
internal fun ToolBox(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(6.px)
    Box(Modifier.background(TOOL_FILL, shape).border(1.px, TOOL_RIM, shape).padding(1.px)) { content() }
}

/** A tool key, [TOOL_WIDE] by `42·s`. */
@Composable
internal fun ToolKey(onClick: () -> Unit, modifier: Modifier, glyph: @Composable () -> Unit) {
    DeskButton(onClick = onClick, corner = 6.px, modifier = modifier.size(TOOL_WIDE, 42.s)) { glyph() }
}

/** The tools at 1.5 times the desktop's (keys 34 by 28, glyphs 12): beside the wall's larger text the
 * desktop's were a size and a half too small. */
private val TOOL_WIDE = 51.s
internal val TOOL_HIGH = 44.s
internal val TOOL_GLYPH = 18.ts
private val SEARCH_TEXT = 19.ts

/** The open field's width before it takes the place of the order and favourites keys. */
private val SEARCH_WIDE = 360.s
private const val SEARCH_SLIDE_MS = 240
private const val KEYBOARD_POLL_MS = 150L

private val TOOL_FILL = Color(0x1FFFFFFF)
private val TOOL_RIM = Color(0x26FFFFFF)

/** The wall as it is drawn: narrowed by [query] (every word in the title or artist, ignoring case and
 * accents) and to favourites, in [order]. */
internal fun wallOf(collection: Collection, query: String, order: WallOrder, favourites: Boolean): List<CloudAlbum> {
    val words = fold(query).split(' ').filter { it.isNotBlank() }
    val folded = FoldedNames.of(collection.albums)
    // Each record's sort key is worked out once and the keys are sorted, never the records: a record is a data class
    // whose hash runs over all its tracks, and a lookup by record in every comparison held a sort of a few thousand
    // for over a second on a low-end TV box.
    val keyed = collection.albums.mapNotNull { album ->
        if (favourites && album.tracks.none { it.tags?.favorite == true }) return@mapNotNull null
        val names = folded[album] ?: FoldedNames.Names(fold(album.artist), fold(album.album))
        if (words.isNotEmpty() && words.any { it !in names.title + " " + names.artist }) return@mapNotNull null
        val number = when (order) {
            WallOrder.Added -> collection.addedOf(album)
            WallOrder.Plays -> collection.playsOf(album).toLong()
            WallOrder.Date -> (RecordFacts.year(album.tracks) ?: Int.MAX_VALUE).toLong()
            WallOrder.Artist, WallOrder.Album -> 0L
        }
        WallKey(album, names.artist, names.title, number)
    }
    val by = when (order) {
        WallOrder.Artist -> compareBy<WallKey>({ it.artist }, { it.title })
        WallOrder.Album -> compareBy<WallKey>({ it.title }, { it.artist })
        WallOrder.Added, WallOrder.Plays -> compareByDescending<WallKey> { it.number }.thenBy { it.album.key }
        WallOrder.Date -> compareBy<WallKey>({ it.number }, { it.artist }, { it.title })
    }
    return keyed.sortedWith(by).map { it.album }
}

/** A record and what the wall sorts it by: its folded artist and title, and a number (added, plays, year). */
private class WallKey(val album: CloudAlbum, val artist: String, val title: String, val number: Long)

/**
 * Every record's artist and title folded ([fold]), for the last collection's records, so a new order or search
 * does not fold them again. Kept by the records list's identity and looked up by each record's identity, never by
 * its hash. Worked out by whichever thread asks first; another asking meanwhile waits for it.
 */
private object FoldedNames {
    class Names(val artist: String, val title: String)

    private var of: List<CloudAlbum>? = null
    private var names = java.util.IdentityHashMap<CloudAlbum, Names>()

    @Synchronized
    fun of(albums: List<CloudAlbum>): java.util.IdentityHashMap<CloudAlbum, Names> {
        if (of !== albums) {
            names = java.util.IdentityHashMap<CloudAlbum, Names>(albums.size).apply {
                for (album in albums) put(album, Names(fold(album.artist), fold(album.album)))
            }
            of = albums
        }
        return names
    }
}

internal fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase()

private val MARKS = Regex("\\p{M}+")

/** How long a new order may take before the wall is blurred under the turning record, and how long the blur
 * takes to come and to go. */
private const val SORT_QUIET_MS = 250L

/** For `adb logcat -s rpm.sort`: how long a new order took, and where. */
private const val SORT_LOG = "rpm.sort"
private const val SORT_IN_MS = 150
private const val SORT_OUT_MS = 350

/** A screen's message ("Loading your library…", an empty wall): 22·s `#99FFFFFF`, centred, 40 in from
 * the sides. The desktop has 14; this is read from a couch. */
@Composable
fun Message(text: String) {
    Text(text, style = ui(22.ts), color = Ink.Dim, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 40.px))
}

/** A whole screen waiting: the turning record ([RecordSpinner]) at the size the desktop's main screens draw
 * their wait at (ShelfLoader, 64), and what is being waited for under it. */
@Composable
private fun Waiting(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RecordSpinner(Modifier.size(64.s))
            Spacer(Modifier.height(24.px))
            Message(text)
        }
    }
}
