package com.rpm.player.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.rpm.player.core.R
import com.rpm.player.core.library.GenreMarks
import com.rpm.player.core.library.GenreWallItem
import com.rpm.player.core.library.GenreWordmark
import com.rpm.player.core.library.Walls
import com.rpm.player.core.library.WordmarkPaths
import com.rpm.player.core.room.RoomPalette
import com.rpm.player.core.room.Rgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Where the artists and genres screens were left. The app holds this state, not the screens, because
 * the screens leave composition when a page opens over them. Back from an artist's or a genre's page
 * then finds the card that opened it and puts the focus on it. It starts again whenever a screen is
 * picked from the side menu ([reset]).
 */
@Stable
class BrowseState {
    /** The artists list's order. */
    var artistOrder by mutableStateOf(com.rpm.player.core.library.ArtistOrdering.Name)

    /** The artist last opened from the artists list, by name. */
    var openedArtist by mutableStateOf<String?>(null)

    /** The genre last opened from the genres list, by name. */
    var openedGenre by mutableStateOf<String?>(null)

    /** The record last opened from an artist's or a genre's page, by key. */
    var openedAlbum by mutableStateOf<String?>(null)

    fun reset() {
        artistOrder = com.rpm.player.core.library.ArtistOrdering.Name
        openedArtist = null
        openedGenre = null
        openedAlbum = null
    }
}

/**
 * The genres list (the desktop Pegasus's PegasusChrome.Genres): every genre the collection holds a
 * record of, [GENRE_COLUMNS] to a row. Each genre is a card in the genre's colour with its wordmark
 * across it ([GenreCard]). The genres are in the shelves' own order, with "Other" last
 * ([com.rpm.player.core.library.GenreWall]). OK opens the genre's page. There is no back key: the
 * remote's Back goes home, as on the wall.
 */
@Composable
fun GenresScreen(walls: Walls, images: ImageLoader, state: BrowseState, mini: MiniPlayerState, onOpenGenre: (GenreWallItem) -> Unit) {
    val genres = walls.genres
    val grid = rememberLazyGridState()
    val atTop = remember { derivedStateOf { grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0 } }
    // Only the screen in front gives the mini player its scroll state, which the mini player reads
    // live. A screen kept under it does not (LocalOnTop).
    val onTop = LocalOnTop.current
    SideEffect { if (onTop) mini.follow(atTop) }
    val first = remember { FocusRequester() }
    val returnTo = remember { FocusRequester() }
    val opened = state.openedGenre

    Box(Modifier.fillMaxSize()) {
        if (genres.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Message(stringResource(R.string.Rpm_Library_Empty)) }
        } else {
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Fixed(GENRE_COLUMNS),
                contentPadding = PaddingValues(start = 34.px, end = 50.px, top = 64.px, bottom = 28.px),
                horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
                verticalArrangement = Arrangement.spacedBy(CARD_GAP),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(genres, key = { it.name }) { genre ->
                    GenreCard(
                        genre = genre,
                        images = images,
                        onClick = {
                            state.openedGenre = genre.name
                            onOpenGenre(genre)
                        },
                        modifier = when {
                            genre.name == opened -> Modifier.focusRequester(returnTo)
                            genre === genres.first() -> Modifier.focusRequester(first)
                            else -> Modifier
                        },
                    )
                }
            }
        }
    }

    // Back from a genre's page: focus that genre's card, scrolled into view. Otherwise focus the first card.
    LaunchedEffect(Unit) {
        val at = genres.indexOfFirst { it.name == opened }
        if (at >= 0) {
            grid.scrollToItem(at)
            withFrameNanos { }
            runCatching { returnTo.requestFocus() }
        } else {
            runCatching { first.requestFocus() }
        }
    }
}

/** The space between artist and genre cards, across and down. Rows of cards on the artist and genre
 * pages keep half of it on each side of every card. */
internal val CARD_GAP = 44.px

/** How much of the genre's colour is laid over its photograph on a card. */
private const val PHOTO_SHADE = 0.55f

/** How many genres stand in a row. */
const val GENRE_COLUMNS = 4

/** "12 albums", or "1 album". */
@Composable
fun albumCount(albums: Int): String =
    if (albums == 1) stringResource(R.string.Stats_AlbumsOne) else stringResource(R.string.Stats_Albums, albums.toString())

/**
 * One genre as a card, 16 by 9 with corner 10. It shows the desktop's photograph for the genre
 * ([genrePhoto]) under the genre's colour, or, when there is no photograph, the colour as a still
 * room ([genreRoom]). The wordmark ([GenreLettering]) is across the middle. There is no name under
 * the card, because the wordmark already spells it out, and no count.
 *
 * The colour over the photograph is the genre's darkest tone at [PHOTO_SHADE], so a white wordmark
 * can be read on any part of the picture. A pale genre, whose wordmark is dark ([genreInk]), is
 * covered with its lightest tone instead.
 */
@Composable
fun GenreCard(genre: GenreWallItem, images: ImageLoader, onClick: () -> Unit, modifier: Modifier) {
    val mark = remember(genre.name) { GenreMarks.of(genre.name) }
    val photo = genrePhoto(genre.name)
    val context = LocalContext.current
    CardButton(onClick = onClick, modifier = modifier.semantics { contentDescription = genre.name }) {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.px))) {
                if (photo == null) {
                    CardRoomView(genreRoom(mark), apart = 0, drift = false, fewest = 32, soften = 0f, modifier = Modifier.fillMaxSize())
                } else {
                    val room = remember(mark) { genreRoom(mark) }
                    val pale = remember(mark) { mark != null && genreInk(mark) != Color.White }
                    val shade = remember(room, pale) { (if (pale) room.tint else room.ground).let { Color(it.r.coerceIn(0f, 1f), it.g.coerceIn(0f, 1f), it.b.coerceIn(0f, 1f)) } }
                    Box(Modifier.fillMaxSize().background(shade))
                    val edge = with(LocalDensity.current) { maxWidth.roundToPx() }
                    AsyncImage(
                        model = remember(photo, edge) { ImageRequest.Builder(context).data(photo).size(edge, edge * 9 / 16).crossfade(PICTURE_FADE_MS).build() },
                        imageLoader = images,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.fillMaxSize().background(shade.copy(alpha = PHOTO_SHADE)))
                }
                if (mark != null) {
                    val letters = maxWidth * (0.8f / GenreMarks.widestName.toFloat())
                    GenreLettering(mark, letters, Modifier.align(Alignment.Center))
                } else {
                    Text(genre.name, style = ui(28.ts, FontWeight.Bold), color = Ink.Words, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.px))
                }
            }
        }
    }
}

/**
 * A genre's name as its wordmark: the outline from
 * [GenreWordmarkTable][com.rpm.player.core.library.GenreWordmarkTable], stretched to fill a box whose
 * letters are [letters] tall from their top to the baseline (so a name of two lines is taller than
 * a name of one line, as on the desktop). It is white, or near-black on a pale genre ([genreInk]).
 * The outline is parsed once per genre, off the main thread ([WordmarkPaths]).
 */
@Composable
fun GenreLettering(mark: GenreWordmark, letters: Dp, modifier: Modifier) {
    // Kept per genre, not per call site. A genre's page opened from another genre's page (a subgenre)
    // is the same call site. A state remembered without the genre would keep the first genre's
    // lettering, for example Metal opened from Rock would read ROCK. The first value of produceState is
    // read only once, so it cannot be used here.
    val held = remember(mark.genre) { mutableStateOf(WordmarkPaths.cached(mark)) }
    LaunchedEffect(mark.genre) { if (held.value == null) held.value = WordmarkPaths.of(mark) }
    val path by held
    val high = letters / mark.base.toFloat()
    val wide = high * mark.aspect.toFloat()
    val ink = remember(mark.genre) { genreInk(mark) }
    val drawn = path ?: return
    val bounds = remember(drawn) { drawn.getBounds() }
    Canvas(modifier.size(wide, high)) {
        if (bounds.width <= 0f || bounds.height <= 0f) return@Canvas
        scale(size.width / bounds.width, size.height / bounds.height, pivot = Offset.Zero) {
            translate(-bounds.left, -bounds.top) { drawPath(drawn, ink) }
        }
    }
}

/**
 * The genre's colour as a room (the desktop's GenreWordmarks.Room): the ground is the darkest tone,
 * the tint is the lightest, the glow is the bare colour, and the shade is the middle tone. A genre
 * that the table has no colour for gets the app's blue ([RoomPalette.FALLBACK]).
 */
fun genreRoom(mark: GenreWordmark?): RoomPalette {
    mark ?: return RoomPalette.FALLBACK
    val (r, g, b) = GenreMarks.channels(mark)
    val colour = Rgb(r, g, b)
    return RoomPalette(ground = colour * GenreMarks.DEEP, tint = colour * GenreMarks.LIT, glow = colour, shade = colour * GenreMarks.MID)
}

/**
 * The ink that a genre's name is written in (the desktop's GenreWordmarks.InkOn). It is white, unless
 * white against the genre's lightest tone has less contrast than large lettering needs (3 to 1), as
 * on the pale genres.
 */
fun genreInk(mark: GenreWordmark): Color {
    val (r, g, b) = GenreMarks.channels(mark)
    val lit = luminance(r * GenreMarks.LIT, g * GenreMarks.LIT, b * GenreMarks.LIT)
    val contrast = (max(1.0, lit) + 0.05) / (min(1.0, lit) + 0.05)
    return if (contrast >= 3.0) Color.White else Color(0xFF141418)
}

/** Relative luminance, the sRGB way. */
private fun luminance(r: Float, g: Float, b: Float): Double {
    fun straighten(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    return 0.2126 * straighten(r) + 0.7152 * straighten(g) + 0.0722 * straighten(b)
}
