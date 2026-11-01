package com.rpm.player.phone.library

import android.graphics.Paint
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
import com.rpm.player.phone.ui.VinylGround

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
        VinylGround(Modifier.backdropSource(ground))
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.padding(start = 20.dp, end = 16.dp, bottom = 10.dp)) {
                PageHeading(stringResource(R.string.Genres_Title), counts(genres.size)) {}
            }
            ModeTabs(LibraryMode.Genres, onMode = settings::changeLibraryMode, backdrop = ground)
            Box(Modifier.weight(1f)) {
                if (genres.isEmpty()) {
                    Empty(lent = ready.collection.shelf != Shelf.Own)
                } else PullToRefresh(reading.refreshing, reading.onRefresh) {
                    val grid = rememberLazyGridState()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = grid,
                        modifier = Modifier.fillMaxSize().dissolvesAtTop(30.dp) { grid.scrolled() },
                        contentPadding = PaddingValues(start = EDGE.dp, end = EDGE.dp, top = 4.dp, bottom = FOOT + bottomRoom),
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
    val shape = RoundedCornerShape(18.dp)
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
private fun GenrePicture(genre: String, images: ImageLoader, modifier: Modifier) {
    val colour = remember(genre) { genreColour(genre) }
    Box(modifier.background(colour)) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data("file:///android_asset/genre-photos/${GenreMarks.photoName(genre)}.jpg").build(),
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
    onPlay: (List<CloudTrack>) -> Unit,
    onArtist: (ArtistWallItem) -> Unit,
    onList: (TrackList) -> Unit,
    onGenre: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val collection = ready.collection
    val page = rememberBackdrop()
    val rows = rememberLazyListState()
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        val (columns, art) = WallLayout.wall(width.value.toDouble(), 194.0, EDGE, WALL_GUTTER, WALL_COLUMNS)
        val caption = remember(art) { AlbumCaption.of(art) }
        Box(Modifier.fillMaxSize().backdropSource(page)) {
            VinylGround()
            LazyColumn(state = rows, modifier = Modifier.fillMaxSize().dissolvesAtTop(statusBar + BACK_ROW) { rows.scrolled() }) {
                item(key = "head") {
                    Head(genre.name, albums = genre.albums.size, acts = shelves.actCount, minOf(width * HEAD_SHAPE, height * HEAD_MOST) + statusBar, images)
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
        Box(Modifier.padding(top = statusBar).height(BACK_ROW).padding(start = 20.dp), contentAlignment = Alignment.Center) {
            BackKey(page, onBack)
        }
    }
}

/**
 * The genre's photograph, as wide as the page. It fades into the page's background at its foot. The name and the counts
 * are drawn on it.
 */
@Composable
private fun Head(genre: String, albums: Int, acts: Int, tall: Dp, images: ImageLoader) {
    Box(Modifier.fillMaxWidth().height(tall)) {
        GenrePicture(genre, images, Modifier.fillMaxSize())
        // Fades into the page's background at the foot, so the content below does not start on a hard edge.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.7f to Ink.ground.copy(alpha = 0f), 1f to Ink.ground.copy(alpha = 0.85f))))
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
