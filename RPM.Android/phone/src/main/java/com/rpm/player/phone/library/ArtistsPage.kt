package com.rpm.player.phone.library

import com.rpm.player.phone.design.rememberPicture
import com.rpm.player.phone.design.UnderHeader
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.rpm.player.core.R
import com.rpm.player.core.library.ArtistCredit
import com.rpm.player.core.library.ArtistOrdering
import com.rpm.player.core.library.ArtistSort
import com.rpm.player.core.library.ArtistWall
import com.rpm.player.core.library.ArtistWallItem
import com.rpm.player.core.library.Artwork
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.LibraryState
import com.rpm.player.phone.PhoneSettings
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.BlurredPicture
import com.rpm.player.phone.design.GlassKey
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.PullToRefresh
import com.rpm.player.phone.design.pinch
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.PageHeading
import com.rpm.player.phone.design.SEARCH_HEIGHT
import com.rpm.player.phone.design.SearchBox
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.design.dissolvesAtTop
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.radiusFor
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.design.scrolled
import com.rpm.player.phone.ui.ArtistMark
import com.rpm.player.phone.ui.MarkView
import com.rpm.player.phone.ui.VinylGround
import com.rpm.player.phone.design.rememberHaptics
import com.rpm.player.phone.ui.color
import com.rpm.player.phone.ui.rememberAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The orders the artists' menu offers, in the iPhone's order. */
private val ARTIST_ORDERS = listOf(
    ArtistOrdering.Name to R.string.ArtistSort_Name,
    ArtistOrdering.Albums to R.string.ArtistSort_Albums,
    ArtistOrdering.Tracks to R.string.ArtistSort_Tracks,
    ArtistOrdering.Added to R.string.ArtistSort_Added,
)

/**
 * The space between the boxes, the most boxes across, and the smallest a pinch makes them (`ArtistsPage` on iPhone). The
 * width they aim for is the person's own, 190 at first ([PhoneSettings.artistBox]).
 */
private const val BOX_SMALLEST = 140.0
private const val BOX_GUTTER = 14.0
private const val BOX_COLUMNS = 3

/** The height of a box as a share of its width. It matches the shape of the backdrop pictures, which is 16:9. */
private const val BOX_SHAPE = 9f / 16f

/**
 * The library's artists view. It matches the iPhone's `ArtistsPage` (RPMUI/ArtistsPage.swift). It shows a wall
 * of boxes, one for each artist. Each box shows the artist's backdrop photograph. If there is none, it shows
 * the artist's first record, blurred. The name is drawn across the box. The header has the search key and the
 * order menu. The search row opens under the header, and it narrows the wall as the name is typed.
 *
 * Two fingers pinch the boxes bigger or smaller, and the size is kept. A pull down from the top reads the collection
 * again. On a borrowed collection the counts line starts with whose it is.
 *
 * Not yet implemented: the accent tint over a blurred cover.
 */
@Composable
fun ArtistsPage(
    ready: LibraryState.Ready,
    images: ImageLoader,
    artwork: Artwork,
    settings: PhoneSettings,
    bottomRoom: Dp,
    onOpen: (ArtistWallItem) -> Unit,
    reading: WallReading,
) {
    val ground = rememberBackdrop()
    val page = rememberBackdrop()
    val collection = ready.collection
    var pinching by remember { mutableStateOf<Double?>(null) }
    val order = settings.artistOrder
    var sorting by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    // The search key sets this. The search box clears it once it has the keyboard. Without that, the box would
    // raise the keyboard again when it is drawn again, for example on the way back from a page opened from the results.
    var raise by remember { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    fun closeSearch() {
        asked = ""
        focusManager.clearFocus()
        searching = false
    }
    BackHandler(enabled = searching) { closeSearch() }

    // Sort off the main thread. The Added order reads every track of every artist's records.
    val wall by produceState<List<ArtistWallItem>?>(null, ready.walls, order) {
        value = withContext(Dispatchers.Default) {
            ArtistSort.by(ready.walls.artists, order, collection.albums.associateBy { it.key })
        }
    }
    val shown = remember(wall, asked) {
        val wanted = ArtistWall.searchKey(asked)
        if (wanted.isEmpty()) wall else wall?.filter { ArtistWall.searchKey(it.name).contains(wanted) }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(page)) {
            UnderHeader(ground, ground = { VinylGround() }, header = { Column(Modifier.statusBarsPadding()) {
                Box(Modifier.padding(start = 20.dp, end = 16.dp, bottom = 10.dp)) {
                    PageHeading(stringResource(R.string.Artists_Title), lent(reading.lender, counts(ready.walls.artists.size))) {
                        KeyGroup(ground) {
                            HeaderKey(Glyph.search) { if (searching) closeSearch() else { searching = true; raise = true } }
                            HeaderKey(Glyph.sort) { sorting = true }
                        }
                    }
                }
                AnimatedVisibility(searching, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
                    SearchRow(asked, { asked = it }, stringResource(R.string.Artists_SearchPlaceholder), focus, raise, onRaised = { raise = false }, ground, onClose = ::closeSearch)
                }
                ModeTabs(LibraryMode.Artists, onMode = settings::changeLibraryMode, backdrop = ground)
            } }) { top ->
                // Kept above the keyboard while it is open, so the search results, or the no-match message, stay in
                // view. The wall runs up under the header and fades out over it; the messages start below it.
                Box(Modifier.fillMaxSize().imePadding()) {
                    val below = Modifier.fillMaxSize().padding(top = top)
                    when {
                        ready.walls.artists.isEmpty() -> Box(below) { Empty(reading.lender != null) }
                        shown == null -> Unit
                        shown.isEmpty() -> Box(below) { NoMatch() }
                        else -> PullToRefresh(reading.refreshing, reading.onRefresh, top = top) {
                            Boxes(
                                shown,
                                collection,
                                images,
                                artwork,
                                bottomRoom,
                                onOpen,
                                stride = pinching ?: settings.artistBox,
                                modifier = Modifier.pinch(
                                    onZoom = { pinching = pinchedStride(settings.artistBox, it, BOX_SMALLEST) },
                                    onEnd = { pinching?.let(settings::changeArtistBox); pinching = null },
                                ),
                                top = top,
                            )
                        }
                    }
                }
            }
        }
        if (sorting) {
            SortMenu(page, ARTIST_ORDERS, order, onPick = { settings.changeArtistOrder(it); sorting = false }, onClose = { sorting = false })
        }
    }
}

/** Example: "1,203 artists". */
@Composable
private fun counts(count: Int): String =
    if (count == 1) stringResource(R.string.Artists_CountOne) else stringResource(R.string.Artists_Count, count)

/**
 * The search box, and the key beside it that closes the search, under the header. The cross inside the box
 * clears the text. The key beside it closes the search, as on iPhone.
 */
@Composable
internal fun SearchRow(
    asked: String,
    onAsked: (String) -> Unit,
    prompt: String,
    focus: FocusRequester,
    raise: Boolean,
    onRaised: () -> Unit,
    backdrop: Backdrop,
    onClose: () -> Unit,
) {
    val haptics = rememberHaptics()
    // The keyboard comes up when the search is opened, not each time the box is drawn.
    LaunchedEffect(raise) {
        if (raise) {
            focus.requestFocus()
            onRaised()
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchBox(asked, onAsked, prompt, focus, Modifier.weight(1f))
        GlassKey(backdrop, { haptics.pick(); onClose() }, edge = SEARCH_HEIGHT) {
            BasicText(Glyph.close, style = icon(15f).copy(color = Ink.white(0xB8)))
        }
    }
}

@Composable
private fun Boxes(
    artists: List<ArtistWallItem>,
    collection: Collection,
    images: ImageLoader,
    artwork: Artwork,
    bottomRoom: Dp,
    onOpen: (ArtistWallItem) -> Unit,
    stride: Double,
    modifier: Modifier,
    top: Dp,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val (columns, width) = WallLayout.wall(maxWidth.value.toDouble(), stride, EDGE, BOX_GUTTER, BOX_COLUMNS)
        val grid = rememberLazyGridState()
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
            modifier = Modifier.fillMaxSize().dissolvesAtTop(top) { grid.scrolled() },
            contentPadding = PaddingValues(start = EDGE.dp, end = EDGE.dp, top = top + 4.dp, bottom = FOOT + bottomRoom),
            horizontalArrangement = Arrangement.spacedBy(BOX_GUTTER.dp),
            verticalArrangement = Arrangement.spacedBy(BOX_GUTTER.dp),
        ) {
            items(artists, key = { it.name }, contentType = { "artist" }) { artist ->
                ArtistBox(artist, width.dp, collection, images, artwork) { onOpen(artist) }
            }
        }
    }
}

/**
 * One artist: the backdrop photograph filling a wide box. If there is none, the artist's first record is shown,
 * blurred. A veil darkens down the box, and the name is drawn across the middle. This matches the iPhone's
 * `ArtistBox`.
 */
@Composable
internal fun ArtistBox(artist: ArtistWallItem, width: Dp, collection: Collection, images: ImageLoader, artwork: Artwork, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val density = LocalDensity.current
    val photo = artistBackdrop(artist, collection, artwork)
    val sleeve = remember(artist, collection) { artist.own.firstNotNullOfOrNull { collection.album(it) }?.let { collection.cover(it) } }
    Box(
        Modifier
            .width(width)
            .height(width * BOX_SHAPE)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0x22 / 255f))
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            photo != null -> AsyncImage(
                model = rememberPicture(photo, width, width * BOX_SHAPE),
                contentDescription = null,
                imageLoader = images,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            sleeve != null -> {
                BlurredPicture(sleeve, images, sigma = 24.dp, modifier = Modifier.fillMaxSize())
                // The sleeve's accent over it at a little over a fifth, as on iPhone (`sleeve.accent` at 0.22), so a box
                // with no photograph has the colour of the artist's own music. Read only while there is no photograph.
                rememberAccent(sleeve, images)?.let { accent ->
                    Box(Modifier.fillMaxSize().background(accent.color().copy(alpha = 0.22f)))
                }
            }
        }
        // The shade gets darker towards the bottom of the box. The name is across the middle.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0x26 / 255f),
                    0.55f to Color.Black.copy(alpha = 0x8C / 255f),
                    1f to Color.Black.copy(alpha = 0xB3 / 255f),
                ),
            ),
        )
        BasicText(
            artist.name,
            Modifier.padding(horizontal = 10.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = displayBold(15f).copy(color = Color.White, textAlign = TextAlign.Center),
        )
        Box(Modifier.fillMaxSize().border(1.dp, Ink.cardBorder, shape))
    }
}

/**
 * An artist's backdrop photograph: the first wide picture that the rpm API has for them. The iPhone's boxes and
 * artist page ask for the same picture (`ArtistPortraits`, the backdrops). It is requested through [Artwork],
 * once per artist, for the life of the app. A picture that [Artwork] already holds is the first value, so a box
 * that is drawn again shows the photograph from its first frame. The result is null until the answer arrives.
 * It is also null for compilations, and where there is no picture.
 */
@Composable
fun artistBackdrop(artist: ArtistWallItem, collection: Collection, artwork: Artwork): String? {
    val known = remember(artist.name) { artwork.peek(artist.name)?.backgrounds?.firstOrNull() }
    val photo by produceState(known, artist.name) {
        if (ArtistCredit.isVariousArtists(artist.name)) return@produceState
        // One of the artist's own records is sent with the request. It tells two artists with the same name apart.
        val album = artist.own.firstNotNullOfOrNull { key -> collection.album(key)?.album?.takeIf { it.isNotBlank() } }.orEmpty()
        value = artwork.of(artist.name, album)?.backgrounds?.firstOrNull()
    }
    return photo
}

/** A collection with no artists, which is a collection with no records. */
@Composable
private fun Empty(lent: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        MarkView(ArtistMark, Ink.white(0x3A), Modifier.size(46.dp))
        BasicText(
            stringResource(if (lent) R.string.Rpm_Share_EmptyShelf else R.string.Rpm_Library_Empty),
            Modifier.widthIn(max = 300.dp),
            style = display(14f).copy(color = Ink.white(0x88), textAlign = TextAlign.Center),
        )
    }
}

/** A search that matches no artist. */
@Composable
private fun NoMatch() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(Glyph.search, style = icon(34f).copy(color = Ink.white(0x3A)))
        BasicText(
            stringResource(R.string.Artists_NoMatch),
            Modifier.widthIn(max = 300.dp),
            style = display(14f).copy(color = Ink.white(0x88), textAlign = TextAlign.Center),
        )
    }
}
