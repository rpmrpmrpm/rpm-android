package com.rpm.player.phone.library

import com.rpm.player.phone.design.rememberPicture
import com.rpm.player.phone.design.UnderHeader
import android.graphics.Paint
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import com.rpm.player.core.room.GroundLead
import com.rpm.player.core.room.Rgb
import com.rpm.player.phone.design.LocalTextFeather
import com.rpm.player.phone.design.TopPull
import com.rpm.player.phone.design.rememberTopPull
import com.rpm.player.phone.ui.ArtGround
import com.rpm.player.phone.ui.RunOff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.library.ArtistWallItem
import com.rpm.player.core.library.Artwork
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.GenreMarks
import com.rpm.player.core.library.GenreShelves
import com.rpm.player.core.library.GenreWall
import com.rpm.player.core.library.GenreWallItem
import com.rpm.player.core.library.LibraryState
import com.rpm.player.core.library.MostPlayed
import com.rpm.player.core.library.QuickPick
import com.rpm.player.core.library.TrackList
import com.rpm.player.core.library.WordmarkPaths
import com.rpm.player.phone.PhoneSettings
import com.rpm.player.phone.design.BackKey
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.PullToRefresh
import com.rpm.player.phone.design.PageHeading
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.design.dissolvesAtTop
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.design.scrolled
import com.rpm.player.phone.design.system
import com.rpm.player.phone.home.AlbumRow
import com.rpm.player.phone.home.HomeActions
import com.rpm.player.phone.home.ListsRow
import com.rpm.player.phone.home.PicksRow
import com.rpm.player.phone.home.SongLine
import com.rpm.player.phone.home.SongTap
import com.rpm.player.core.playback.Tapped
import com.rpm.player.phone.ui.VinylGround
import com.rpm.player.phone.design.ScrollsToTop
import com.rpm.player.phone.ui.rememberCoverShape

/** The space between the cards on the genres wall (`GenresPage.gutter` on iPhone). */
private const val CARD_GUTTER = 14.0

/** How many of the genre's most-played songs its page shows. */
private const val MOST_PLAYED = 10

/**
 * The head's height, for each point of the page's width, below the status bar (`GenrePage.headShape` on
 * iPhone). HEAD_MOST is the largest share of the page's height that the head may take. The iPhone app has no
 * such cap. On its tall screen the head is under half the height anyway. On a square screen, 0.85 of the width
 * would be the whole screen height, and the name would sit under the chrome.
 */
private const val HEAD_SHAPE = 0.85f
private const val HEAD_MOST = 0.5f

/** The space between the sections of the genre page, and between its decades. */
private val SECTIONS_APART = 26.dp
private val ERAS_APART = 18.dp

/**
 * The library's genres view. It matches the iPhone's `GenresPage` (RPMUI/GenresPage.swift). It shows one
 * portrait card per genre, two across. Each card has the genre's photograph, the genre's name in its own
 * lettering at the foot, and the number of records in that genre. Which genre a record is in comes from
 * [GenreWall]. That is built with the other walls when the library is read.
 *
 * A pull down from the top reads the collection again.
 */
@Composable
fun GenresPage(
    ready: LibraryState.Ready,
    images: ImageLoader,
    settings: PhoneSettings,
    bottomRoom: Dp,
    onOpen: (GenreWallItem) -> Unit,
    reading: WallReading,
) {
    val ground = rememberBackdrop()
    val genres = ready.walls.genres
    Box(Modifier.fillMaxSize()) {
        // The wall runs up under the header and fades out over it, as on iPhone; the empty message starts below it.
        UnderHeader(ground, ground = { VinylGround() }, header = { Column(Modifier.statusBarsPadding()) {
            Box(Modifier.padding(start = 20.dp, end = 16.dp, bottom = 10.dp)) {
                PageHeading(stringResource(R.string.Genres_Title), counts(genres.size)) {}
            }
            ModeTabs(LibraryMode.Genres, onMode = settings::changeLibraryMode, backdrop = ground)
        } }) { top ->
            Box(Modifier.fillMaxSize()) {
                if (genres.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(top = top)) { Empty(lent = ready.collection.shelf != Shelf.Own) }
                } else PullToRefresh(reading.refreshing, reading.onRefresh, top = top) {
                    val grid = rememberLazyGridState()
                    ScrollsToTop(grid)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = grid,
                        modifier = Modifier.fillMaxSize().dissolvesAtTop(top) { grid.scrolled() },
                        contentPadding = PaddingValues(start = EDGE.dp, end = EDGE.dp, top = top + 4.dp, bottom = FOOT + bottomRoom),
                        horizontalArrangement = Arrangement.spacedBy(CARD_GUTTER.dp),
                        verticalArrangement = Arrangement.spacedBy(CARD_GUTTER.dp),
                    ) {
                        items(genres, key = { it.name }) { genre ->
                            GenreCard(genre.name, genre.albums.size, images, lettering = 30.dp, onClick = { onOpen(genre) })
                        }
                    }
                }
            }
        }
    }
}

/** Example: "14 genres". */
@Composable
private fun counts(count: Int): String =
    if (count == 1) stringResource(R.string.Genres_CountOne) else stringResource(R.string.Genres_Count, count)

/** Example: "12 albums". */
@Composable
private fun albumCount(count: Int): String =
    if (count == 1) stringResource(R.string.Stats_AlbumsOne) else stringResource(R.string.Stats_Albums, count)

/**
 * A collection with no genres. That means it has no records. A borrowed collection shows a message in the lender's
 * terms.
 */
@Composable
private fun Empty(lent: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(
            stringResource(if (lent) R.string.Rpm_Share_EmptyShelf else R.string.Rpm_Library_Empty),
            Modifier.widthIn(max = 300.dp),
            style = display(14f).copy(color = Ink.white(0x88), textAlign = TextAlign.Center),
        )
    }
}

/**
 * One genre on a wall or a shelf: its photograph on a portrait card, its name in its lettering at the foot, and
 * the number of records in it. This matches the iPhone's `GenreCard`.
 *
 * @param lettering how tall the lettering is. The wall's cards and the smaller cards on the page use different sizes.
 */
@Composable
private fun GenreCard(genre: String, albums: Int, images: ImageLoader, lettering: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // As round as a cover of its shorter side (CoverShape).
    val shape = rememberCoverShape()
    Box(
        modifier
            .aspectRatio(3f / 4f)
            .clip(shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick),
    ) {
        GenrePicture(genre, images, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GenreLettering(genre, Modifier.fillMaxWidth().height(lettering))
            BasicText(albumCount(albums), style = system(12f, FontWeight.Medium).copy(color = Ink.white(0xCC)))
        }
        Box(Modifier.fillMaxSize().border(1.dp, Ink.white(0x1A), shape))
    }
}

/**
 * The genre's colour, from the app's palette. A genre with no mark uses the background colour. That does not
 * happen for anything on the wall.
 */
private fun genreColour(genre: String): Color =
    GenreMarks.of(genre)?.let { mark -> GenreMarks.channels(mark).let { (r, g, b) -> Color(r, g, b) } } ?: Ink.ground

/**
 * A genre's photograph filling its box. The genre's colour rises from the foot, so the lettering is easy to
 * read. This matches the iPhone's `GenrePicture`. The photographs ship with the app. They are cut to
 * three by four by `scripts/gen_genre_photos_phone.py`.
 */
@Composable
internal fun GenrePicture(genre: String, images: ImageLoader, modifier: Modifier) {
    val colour = remember(genre) { genreColour(genre) }
    BoxWithConstraints(modifier.background(colour)) {
        AsyncImage(
            model = rememberPicture(genrePhoto(genre), maxWidth, maxHeight),
            contentDescription = null,
            imageLoader = images,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.35f to colour.copy(alpha = 0f),
                    0.72f to colour.copy(alpha = 0.6f),
                    1f to colour.copy(alpha = 0.92f),
                ),
            ),
        )
    }
}

/**
 * A genre's name, in the lettering that the desktop uses. It is drawn from the outline in [WordmarkPaths]. The
 * outline is fitted into the box it is given, keeps its shape, and stands on the box's bottom-left corner. It is
 * white with a soft shadow. This matches the iPhone's `GenreLettering`. Where the table has no outline for a
 * genre, the name is drawn in the app's bold font.
 */
@Composable
private fun GenreLettering(genre: String, modifier: Modifier) {
    val mark = remember(genre) { GenreMarks.of(genre) }
    if (mark == null) {
        Box(modifier, contentAlignment = Alignment.BottomStart) {
            BasicText(genre, maxLines = 1, overflow = TextOverflow.Ellipsis, style = displayBold(28f).copy(color = Color.White))
        }
        return
    }
    // Kept per genre, not per call site. A genre's page can be opened from another genre's page, and then both
    // use the same call site. Without this, the second page would show the first genre's lettering.
    val held = remember(genre) { mutableStateOf(WordmarkPaths.cached(mark)) }
    LaunchedEffect(genre) { if (held.value == null) held.value = WordmarkPaths.of(mark) }
    val path by held
    val density = LocalDensity.current
    val paint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            with(density) { setShadowLayer(6.dp.toPx(), 0f, 2.dp.toPx(), Color.Black.copy(alpha = 0.35f).toArgb()) }
        }
    }
    Canvas(modifier.semantics { contentDescription = genre }) {
        val drawn = path ?: return@Canvas
        val bounds = drawn.getBounds()
        if (bounds.width <= 0f || bounds.height <= 0f) return@Canvas
        val scale = minOf(size.width / bounds.width, size.height / bounds.height)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(0f, size.height - bounds.height * scale)
            native.scale(scale, scale)
            native.translate(-bounds.left, -bounds.top)
            native.drawPath(drawn.asAndroidPath(), paint)
            native.restore()
        }
    }
}

/**
 * One genre's page. It matches the iPhone's `GenrePage`. At the top is the genre's photograph. It runs up behind
 * the status bar, and shows the name in its lettering, and how many records and artists the genre has. Below it
 * are: the newest records, the songs most played in the genre, the artists with the most records in it, the
 * computer's mood lists for the genre, the related genres, the records by decade, and then all the records on
 * a wall. The shelves are [GenreShelves]. They are worked out when the page is drawn for a genre, or for a
 * collection that has not been drawn before.
 *
 * The photograph is drawn as the artist page draws its band ([HeadPhotograph]): blurred toward its foot and mirrored
 * one plate deep under the first rows, fading out into the page, and grown about its foot while the page is pulled down
 * past its top ([TopPull]). The page stands on [ArtGround] led by the genre's colour ([genreLead]), and its text takes
 * the soft outline the artist page has ([LocalTextFeather]), so white text stays readable on the bright genres.
 *
 * Not yet implemented: a song's menu on the most-played songs.
 */
@Composable
fun GenrePage(
    genre: GenreWallItem,
    ready: LibraryState.Ready,
    images: ImageLoader,
    artwork: Artwork,
    bottomRoom: Dp,
    onOpen: (CloudAlbum) -> Unit,
    onPlay: (List<CloudTrack>, Int, Tapped) -> Unit,
    onArtist: (ArtistWallItem) -> Unit,
    onList: (TrackList) -> Unit,
    onGenre: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val collection = ready.collection
    val page = rememberBackdrop()
    val rows = rememberLazyListState()
    ScrollsToTop(rows)
    val density = LocalDensity.current
    val statusBar = WindowInsets.statusBars.asPaddingValues(density).calculateTopPadding()
    val byKey = remember(collection) { collection.albums.associateBy { it.key } }
    val shelves = remember(genre, ready.walls, byKey) { GenreShelves.of(genre, ready.walls.genres, byKey, ready.walls.artists) }
    val records = remember(genre, byKey) { genre.albums.mapNotNull { byKey[it] } }
    val heard = remember(records, collection) { MostPlayed.songs(records.flatMap { it.tracks }, collection.plays, MOST_PLAYED) }
    val moods = remember(genre, collection) { GenreWall.moods(genre.name, collection.lists) }
    val spoken = remember(genre) { com.rpm.player.core.library.Genres.spoken(genre.name) }
    val picks = HomeActions(onOpen = onOpen, onOpenList = onList, onPlay = onPlay, onTune = {})
    fun albums(keys: List<String>) = keys.mapNotNull { byKey[it] }
    val pull = rememberTopPull()
    val lead = remember(genre.name) { genreLead(genre.name) }
    val photo = genrePhoto(genre.name)
    val feather = with(density) { Shadow(Color.Black.copy(alpha = FEATHER_ALPHA), Offset.Zero, blurRadius = FEATHER.toPx()) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        val (columns, art) = WallLayout.wall(width.value.toDouble(), 194.0, EDGE, WALL_GUTTER, WALL_COLUMNS)
        val caption = remember(art) { AlbumCaption.of(art) }
        val plate = minOf(width * HEAD_SHAPE, height * HEAD_MOST) + statusBar
        Box(Modifier.fillMaxSize().backdropSource(page)) {
            // The genre's colour under the whole page, with its photograph blurred over it faintly, as the artist page
            // stands on its photograph's colour. The reflection under the head fades out into it.
            ArtGround(photo, images, lead = lead)
            // The photograph and the rows dissolve together under the status bar and the back key.
            Box(Modifier.fillMaxSize().dissolvesAtTop(statusBar + BACK_ROW) { rows.scrolled() }) {
            HeadPhotograph(genre.name, photo, lead, width, plate, rows, pull, images)
            CompositionLocalProvider(LocalTextFeather provides feather) {
            LazyColumn(state = rows, modifier = Modifier.fillMaxSize(), overscrollEffect = pull) {
                item(key = "head") {
                    Head(genre.name, albums = genre.albums.size, acts = shelves.actCount, plate)
                }
                if (shelves.added.isNotEmpty()) {
                    item(key = "added") {
                        Box(Modifier.padding(top = SECTIONS_APART)) {
                            AlbumRow(stringResource(R.string.Genres_RecentlyAdded, spoken), albums(shelves.added), collection, images, onOpen)
                        }
                    }
                }
                if (heard.isNotEmpty()) {
                    item(key = "heard") {
                        Box(Modifier.padding(top = SECTIONS_APART)) {
                            PicksRow(
                                stringResource(R.string.Genres_YourMostPlayed, spoken),
                                heard.map { QuickPick.Song(it, collection.albumOf[it.rpmId]) },
                                collection,
                                images,
                                logoStamp = "",
                                actions = picks,
                                songLine = SongLine.Artist,
                                // The most played songs play from the one tapped, as on the desktop.
                                songTap = SongTap.TheRowFromIt,
                            )
                        }
                    }
                }
                if (shelves.acts.isNotEmpty()) {
                    item(key = "acts") {
                        Column(Modifier.padding(top = SECTIONS_APART), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Heading(stringResource(R.string.Genres_NotableArtists, spoken))
                            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(shelves.acts, key = { it.name }) { act ->
                                    ArtistBox(act, 200.dp, collection, images, artwork) { onArtist(act) }
                                }
                            }
                        }
                    }
                }
                if (moods.isNotEmpty()) {
                    item(key = "moods") {
                        Box(Modifier.padding(top = SECTIONS_APART)) {
                            ListsRow(stringResource(R.string.Genres_MoodPlaylists), moods, collection, images, onList)
                        }
                    }
                }
                if (shelves.related.isNotEmpty()) {
                    item(key = "related") {
                        Column(Modifier.padding(top = SECTIONS_APART), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Heading(stringResource(R.string.Genres_Subgenres))
                            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(shelves.related, key = { it.name }) { relation ->
                                    GenreCard(relation.name, relation.albums.size, images, lettering = 20.dp, onClick = { onGenre(relation.name) }, modifier = Modifier.width(120.dp))
                                }
                            }
                        }
                    }
                }
                if (shelves.eras.isNotEmpty()) {
                    item(key = "eras") { Box(Modifier.padding(top = SECTIONS_APART)) { Heading(stringResource(R.string.Genres_Eras, spoken)) } }
                    for ((decade, keys) in shelves.eras) {
                        item(key = "era:$decade") {
                            Box(Modifier.padding(top = ERAS_APART)) {
                                AlbumRow(stringResource(R.string.Genres_Decade, decade.toString()), albums(keys), collection, images, onOpen)
                            }
                        }
                    }
                }
                item(key = "wall") {
                    Box(Modifier.padding(top = SECTIONS_APART, bottom = 10.dp)) { Heading(stringResource(R.string.Artists_Albums)) }
                }
                val lines = records.chunked(columns)
                items(lines.size, key = { "wall:" + lines[it].first().key }) { line ->
                    Row(Modifier.padding(horizontal = EDGE.dp), horizontalArrangement = Arrangement.spacedBy(WALL_GUTTER.dp)) {
                        for (album in lines[line]) AlbumTile(album, collection.cover(album), caption, images) { onOpen(album) }
                    }
                }
                item(key = "foot") { Spacer(Modifier.height(FOOT + bottomRoom)) }
            }
            }
            }
        }
        Box(Modifier.padding(top = statusBar).height(BACK_ROW).padding(start = 20.dp), contentAlignment = Alignment.Center) {
            BackKey(page, onBack)
        }
    }
}

/**
 * The head: as tall as the photograph's plate, with the name in its lettering and the counts on its foot. The
 * photograph itself is drawn behind the list ([HeadPhotograph]), so the head takes its place in the rows and no more.
 */
@Composable
private fun Head(genre: String, albums: Int, acts: Int, tall: Dp) {
    Box(Modifier.fillMaxWidth().height(tall)) {
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GenreLettering(genre, Modifier.fillMaxWidth().height(52.dp))
            BasicText(stringResource(R.string.Genres_InLibrary, albums, acts), style = kicker())
        }
    }
}

@Composable
private fun Heading(text: String) {
    BasicText(text, Modifier.padding(horizontal = 20.dp), style = display(19f).copy(color = Color.White))
}

/** A genre's portrait photograph, shipped with the app (`GenreLooks.photo` on iPhone). */
private fun genrePhoto(genre: String): String = "file:///android_asset/genre-photos/${GenreMarks.photoName(genre)}.jpg"

/**
 * The genre's colour, no brighter than white text allows ([GroundLead], `GenreLooks.lead` on iPhone). The genre page
 * stands on it, and the foot of its photograph is washed with it, so the name and the rows over the photograph's
 * reflection stay readable on the bright colours of the palette. A genre with no colour stands on the app's ground.
 */
private fun genreLead(genre: String): Rgb {
    val glow = GenreMarks.of(genre)?.let { mark -> GenreMarks.channels(mark).let { (r, g, b) -> Rgb(r, g, b) } }
    return GroundLead.of(glow = glow, dominant = null) ?: Ink.ground.let { Rgb(it.red, it.green, it.blue) }
}

/**
 * The photograph behind the genre page's head (`GenrePage.photograph` on iPhone): the photograph as wide as the page and
 * one [plate] tall, with the genre's colour washed in toward its foot ([HeadPlate]), blurred toward its foot and mirrored
 * one plate deep under it, fading out into the page's ground ([RunOff], with the artist band's [BLUR_FROM] and
 * reflection).
 *
 * **Behind the list, not in it.** The mirror runs under the first rows, past the head's own row, so it is drawn under
 * the whole list and moved with the head as the list scrolls ([headTop]); in the list it would vanish with the head's
 * row while its lower half was still on screen. Only its layer reads the scroll and the pull.
 *
 * While the page is pulled down past its top, it comes down with the list and is scaled up about its foot by just enough
 * that its top stays at the top of the screen ([TopPull], `growsWithPull` on iPhone). The name is not scaled.
 *
 * **Nothing pops in.** The photograph is read and baked off the main thread, and the photograph and its bake fade in
 * together ([PHOTO_FADE_MS]), as on the artist page. A bake is kept for the genre ([HEAD_BAKES]), so a page shown again
 * starts with it, at full strength.
 */
@Composable
private fun HeadPhotograph(genre: String, photo: String, lead: Rgb, width: Dp, plate: Dp, rows: LazyListState, pull: TopPull, images: ImageLoader) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val platePx = with(density) { plate.toPx() }
    val wide = with(density) { width.roundToPx() }.coerceAtLeast(1)
    val tall = with(density) { plate.roundToPx() }.coerceAtLeast(1)
    val key = HeadBaking(genre, width, plate)
    val kept = remember(key) { HEAD_BAKES.get(key) }
    var made by remember(key) { mutableStateOf(kept) }
    val shown = remember(key) { Animatable(if (kept != null) 1f else 0f) }
    LaunchedEffect(key) {
        if (made != null) return@LaunchedEffect
        // Filled, not fitted: the photograph is a portrait and the plate is wider than it is tall.
        val request = ImageRequest.Builder(context).data(photo).size(wide, tall).scale(Scale.FILL).allowHardware(false).build()
        val picture = (images.execute(request) as? SuccessResult)?.image?.toBitmap()
        val baked = withContext(Dispatchers.Default) { HeadPlate.make(picture, lead, width.value, plate.value) }
        HEAD_BAKES.put(key, baked)
        made = baked
        shown.animateTo(1f, tween(PHOTO_FADE_MS, easing = LinearOutSlowInEasing))
    }
    val drawn = made ?: return
    val ink = remember(lead) { Color(lead.r, lead.g, lead.b) }
    Box(
        Modifier.fillMaxWidth().height(plate * 2).graphicsLayer {
            val top = headTop(rows, platePx)
            if (top == null) {
                alpha = 0f
                return@graphicsLayer
            }
            alpha = shown.value
            translationY = top + pull.pulled
            val grown = 1f + pull.pulled / platePx.coerceAtLeast(1f)
            scaleX = grown
            scaleY = grown
            // The plate's foot, half way down the plate and its mirror.
            transformOrigin = TransformOrigin(0.5f, 0.5f)
        },
    ) {
        // The sharp face, at the screen's resolution: the colour fills the plate with or without a photograph.
        Box(Modifier.fillMaxWidth().height(plate).background(ink)) {
            drawn.picture?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(*washStops(ink))))
        }
        // The blur toward the foot and the mirror under it, over the sharp face.
        Box(
            Modifier.matchParentSize().drawBehind {
                val baked = drawn.runOff
                val scale = size.width / baked.width
                drawImage(baked, dstSize = IntSize(size.width.roundToInt(), (baked.height * scale).roundToInt()), filterQuality = FilterQuality.Low)
            },
        )
    }
}

/**
 * Where the head's top is, in pixels from the top of the list, or null when neither the head nor the row after it is
 * on screen and so the mirror is off it too. Read in a layer, so a scroll moves the photograph and recomposes nothing.
 */
private fun headTop(rows: LazyListState, head: Float): Float? {
    if (rows.firstVisibleItemIndex == 0) return -rows.firstVisibleItemScrollOffset.toFloat()
    val next = rows.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 1 } ?: return null
    return next.offset - head
}

/** The genre's colour washed in toward the photograph's foot, where the name stands, as on the genre's card. */
private fun washStops(ink: Color): Array<Pair<Float, Color>> = arrayOf(
    0.35f to ink.copy(alpha = 0f),
    0.72f to ink.copy(alpha = 0.6f),
    1f to ink.copy(alpha = 0.92f),
)

/** The head's photograph as read ([picture], null for a genre with none) and its run-off baked over it. */
private class HeadPlate(val picture: ImageBitmap?, val runOff: ImageBitmap) {
    companion object {
        /** Bakes the plate [width] by [plate] dp: the face (colour, photograph, wash) drawn small, then blurred toward its
         * foot and mirrored one plate deep ([RunOff.bake]). Call it off the main thread. */
        fun make(picture: Bitmap?, lead: Rgb, width: Float, plate: Float): HeadPlate {
            val w = width.roundToInt().coerceAtLeast(1)
            val p = plate.roundToInt().coerceAtLeast(2)
            val face = Bitmap.createBitmap(w, p, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(face).apply {
                val colour = android.graphics.Color.rgb((lead.r * 255).roundToInt(), (lead.g * 255).roundToInt(), (lead.b * 255).roundToInt())
                drawColor(colour)
                if (picture != null) {
                    // The part of the photograph that ContentScale.Crop shows in the plate.
                    val fit = maxOf(w.toFloat() / picture.width, p.toFloat() / picture.height)
                    val cropW = (w / fit).roundToInt().coerceIn(1, picture.width)
                    val cropH = (p / fit).roundToInt().coerceIn(1, picture.height)
                    val left = (picture.width - cropW) / 2
                    val top = (picture.height - cropH) / 2
                    drawBitmap(picture, android.graphics.Rect(left, top, left + cropW, top + cropH), android.graphics.Rect(0, 0, w, p), Paint(Paint.FILTER_BITMAP_FLAG))
                }
                val stops = washStops(Color(colour))
                val wash = Paint().apply {
                    shader = android.graphics.LinearGradient(
                        0f, 0f, 0f, p.toFloat(),
                        IntArray(stops.size) { stops[it].second.toArgb() },
                        FloatArray(stops.size) { stops[it].first },
                        android.graphics.Shader.TileMode.CLAMP,
                    )
                }
                drawRect(0f, 0f, w.toFloat(), p.toFloat(), wash)
            }
            val runOff = RunOff.bake(face, width, plate, plate, BLUR_FROM, RunOff.Reflection.Blurred, darkened = false)
            face.recycle()
            return HeadPlate(picture?.asImageBitmap(), runOff)
        }
    }
}

/** A bake is for one genre at one width and plate. */
private data class HeadBaking(val genre: String, val width: Dp, val plate: Dp)

/** The heads baked lately: the genre open and the one it was opened from. */
private val HEAD_BAKES = LruCache<HeadBaking, HeadPlate>(3)
