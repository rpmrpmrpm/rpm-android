package com.rpm.player.phone.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import com.rpm.player.phone.design.LocalTextFeather
import android.util.LruCache
import com.rpm.player.phone.ui.rememberLead
import androidx.compose.ui.draw.alpha
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import com.rpm.player.phone.PhoneApplication
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
import com.rpm.player.core.library.ArtistShelves
import com.rpm.player.core.library.RelatedArtists
import com.rpm.player.core.library.Walls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.rpm.player.core.library.ArtistWallItem
import com.rpm.player.core.library.ArtistPopular
import com.rpm.player.core.art.ArtScene
import com.rpm.player.core.library.Artwork
import com.rpm.player.core.library.QuickPick
import com.rpm.player.core.papers.ArtistSubject
import com.rpm.player.core.papers.Dossiers
import com.rpm.player.core.papers.PlaysRecord
import com.rpm.player.core.papers.WorldPlays
import com.rpm.player.phone.home.HomeActions
import com.rpm.player.phone.home.PicksRow
import com.rpm.player.phone.home.SongLine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.Collection
import com.rpm.player.phone.design.BackKey
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.design.dissolvesAtTop
import com.rpm.player.phone.design.TopPull
import com.rpm.player.phone.design.rememberTopPull
import com.rpm.player.phone.ui.RunOff
import com.rpm.player.core.room.PaintedRoom
import com.rpm.player.core.room.RoomPalette
import com.rpm.player.phone.ui.LiveRoom
import com.rpm.player.phone.ui.rememberRoomColours
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.design.scrolled
import com.rpm.player.phone.design.system
import com.rpm.player.phone.ui.ArtGround
import com.rpm.player.core.room.Rgb
import com.rpm.player.core.room.CoverColor
import com.rpm.player.core.room.GroundLead
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.GlassKey
import com.rpm.player.phone.design.Weight
import com.rpm.player.phone.ui.LocalNightMode

/** The space between the name and the foot of the photograph. */
private val WRITING_FOOT = 14.dp

/**
 * The space at the band's right edge, and the height of the writing along its foot. The photograph's crop keeps
 * faces out of these areas (`HomeBannerMetrics.margin` and `ArtistPage.writing` on iPhone).
 */
private const val BANNER_MARGIN = 22.0
private const val WRITING = 96.0

/**
 * The darkening over the photograph in night mode ([LocalNightMode]). Home's banner uses the same value
 * (`HomeFeatureCard.held` on iPhone).
 */
private const val HELD = 0.2f

/** The space above Popular tracks, above the first shelf and above Related artists: where a new part of the page starts. */
private val SECTION_TOP = 40.dp

/** The space above every shelf after the first. */
private val SHELF_TOP = 18.dp

/**
 * One artist's page. It matches the iPhone's `ArtistPage` (RPMUI/ArtistPage.swift). From the top: the artist's
 * photograph, a page wide and as tall as that. Their name is at its foot. Then the encyclopedia text, centred, and
 * three keys: a mixtape, Play and Shuffle. Then their popular songs. Then their records on shelves by kind: their own records, records made
 * with others, live albums, soundtracks, compilations, singles, and the records they appear on. Last, the acts
 * related to them in the collection. There is no list of every song: search finds any of them, as on the iPhone
 * (dropped there on 2026-10-18). The page uses the photograph as its blurred background.
 *
 * The popular songs are ranked by how often each song has been played by all listeners ([ArtistPopular]). The
 * plays are asked for up to four records at a time. A page opened again starts from the plays that are already known.
 *
 * The photograph is cropped so that the artist's face stays in view ([Portraits]).
 *
 * The title is the artist's logo, where a catalogue has one, cut to its lettering ([Logos]). Otherwise the title
 * is the artist's name.
 *
 * The photograph is blurred toward its foot and mirrored under the band, behind the encyclopedia text and the keys
 * ([RunOff]). Pulled down past its top, the page comes down and the photograph grows about its foot to cover the gap,
 * while the writing keeps its size ([TopPull], `growsWithPull` on iPhone).
 *
 * Not yet implemented: the background that the page shows before a photograph arrives. A song's long press among the
 * popular songs opens the song menu ([LocalSongMenu]).
 *
 * @param walls the artists wall, which the related acts are picked from.
 * @param onPlay plays the songs from the one at that index: a tap on a song.
 * @param onPlayAll the Play and Shuffle keys: all of these songs, in this order. See [AlbumPage] for why a key and a
 * row differ.
 */
@Composable
fun ArtistPage(
    artist: ArtistWallItem,
    collection: Collection,
    images: ImageLoader,
    dossiers: Dossiers,
    worldPlays: WorldPlays,
    walls: Walls,
    artwork: Artwork,
    bottomRoom: Dp,
    onPlay: (List<CloudTrack>, Int) -> Unit,
    onOpen: (CloudAlbum) -> Unit,
    onOpenArtist: (ArtistWallItem) -> Unit,
    onBack: () -> Unit,
    onPlayAll: (List<CloudTrack>) -> Unit = { onPlay(it, 0) },
) {
    BackHandler(onBack = onBack)
    val page = rememberBackdrop()
    val rows = rememberLazyListState()
    val pull = rememberTopPull()
    val density = LocalDensity.current
    val statusBar = WindowInsets.statusBars.asPaddingValues(density).calculateTopPadding()
    val portraits = (LocalContext.current.applicationContext as PhoneApplication).portraits
    val shelves = remember(artist, collection) { ArtistShelves.of(artist, collection.albums.associateBy { it.key }) }
    val related = rememberRelated(artist, walls, collection)
    val popular = rememberPopular(shelves, worldPlays)
    // Identified through their own records, never by the name alone (ArtistSubject.of).
    val subject = remember(artist.name, shelves) { ArtistSubject.of(artist.name, shelves) }
    var article by remember(subject) { mutableStateOf(dossiers.cached(subject)?.article) }
    LaunchedEffect(subject) { dossiers.read(subject) {}?.let { article = it.article } }
    // A mixtape starts from one of their songs, chosen at random each time. Null where the app gives no way to start one.
    val startRadio = LocalSongMenu.current?.startRadio
    val picks = HomeActions(
        onOpen = onOpen,
        onOpenList = {},
        onPlay = { tracks -> onPlay(tracks, 0) },
        onTune = {},
    )

    val feather = with(density) { Shadow(Color.Black.copy(alpha = FEATHER_ALPHA), Offset.Zero, blurRadius = FEATHER.toPx()) }
    CompositionLocalProvider(LocalTextFeather provides feather) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val plate = maxWidth
            // The frame of the band, as the iPhone cuts it: a square as wide as the page, with the status bar above it,
            // space for the keys at its right, and space for the name along its foot.
            val scene = remember(plate, statusBar) {
                val edge = plate.value.toDouble()
                ArtScene.forClearLeft(edge, edge, clearTop = statusBar.value / edge, clearRight = (edge - BANNER_MARGIN) / edge, clearBottom = (edge - WRITING) / edge)
            }
            // The artist's logo cut for the band, or their name where there is no logo. Nothing is drawn until the
            // lookup has answered. So the name is not drawn and then replaced a moment later.
            val logos = (LocalContext.current.applicationContext as PhoneApplication).logos
            val cap = with(density) { LOGO_CAP.dp.toPx() }.toInt() + 1
            val room = with(density) { (plate * LOGO_SHARE).toPx() }.toInt()
            val title by produceState(
                logos.held(artist.name, cap, room)?.let { Title.Logo(artist.name, it) }
                    ?: Title.Name(artist.name).takeIf { artwork.peek(artist.name)?.let { it.logo.isNullOrBlank() } == true },
                artist.name,
                cap,
                room,
            ) {
                val album = artist.own.firstNotNullOfOrNull { key -> collection.album(key)?.album?.takeIf { it.isNotBlank() } }.orEmpty()
                value = logos.logo(artist.name, album, cap, room)?.let { Title.Logo(artist.name, it) } ?: Title.Name(artist.name)
            }
            // Whether the photograph has been looked for, found or not: without one, the band stands on the room.
            val kept = remember(artist.name, scene) { portraits.held(artist.name, scene) }
            var looked by remember(artist.name, scene) { mutableStateOf(kept != null) }
            val photo by produceState(kept, artist.name, scene) {
                val album = artist.own.firstNotNullOfOrNull { key -> collection.album(key)?.album?.takeIf { it.isNotBlank() } }.orEmpty()
                value = portraits.portrait(artist.name, album, scene)
                looked = true
            }
            // The room in the colours of their first record (`ArtistPage.dress` on iPhone), or the app's blue.
            val first = shelves.records.firstOrNull() ?: shelves.together.firstOrNull()
            val colours = rememberRoomColours(first?.let { collection.cover(it) }, images)
            val roomed = if (looked && photo == null) colours ?: RoomPalette.FALLBACK else null
            val sleeve = (plate.value * 0.38f).coerceIn(120f, 200f).toDouble()
            Box(Modifier.fillMaxSize().backdropSource(page)) {
                // The photograph's colour leads the ground ([GroundLead], `ArtistPage.lead` on iPhone). Read off the main
                // thread from a 48 pixel copy, and handed over with the photograph, so the two come in as one fade. A band
                // that stands on the room is led by the room's own colour.
                val dressed = rememberLead(photo, images)
                val roomLead = roomed?.let { GroundLead.of(glow = it.glow, dominant = it.tint) }
                ArtGround(dressed?.first, images, lead = dressed?.second ?: roomLead)
                LazyColumn(state = rows, modifier = Modifier.fillMaxSize().dissolvesAtTop(statusBar + BACK_ROW) { rows.scrolled() }, overscrollEffect = pull) {
                    item(key = "band") {
                        // The band's height, read from its layout: the photograph's mirror runs under everything below the
                        // plate, the text when there is any and the keys, so it is right whether the text is there at once,
                        // arrives later, or never does.
                        var tall by remember { mutableIntStateOf(0) }
                        Box(Modifier.onSizeChanged { tall = it.height }) {
                            val depth = with(density) { (tall.toDp() - plate).coerceAtLeast(0.dp) }
                            BandGround(photo, roomed, artist.name, plate, depth, pull, Modifier.matchParentSize())
                            Column {
                                Band(title?.takeIf { it.artist == artist.name }, plate, photo, LocalNightMode.current)
                                ArrivingAbout(article) { CentredAboutText(it, Modifier.padding(horizontal = 20.dp).padding(top = 10.dp)) }
                                // The space is kept when an artist has no songs and so no keys, as on iPhone.
                                Box(Modifier.defaultMinSize(minHeight = KEYS_ROW)) {
                                    if (shelves.songs.isNotEmpty()) {
                                        Keys(
                                            onPlay = { onPlayAll(shelves.songs.map { it.first }) },
                                            onShuffle = { onPlayAll(shelves.songs.map { it.first }.shuffled()) },
                                            onMixtape = startRadio?.let { start -> { shelves.songs.randomOrNull()?.let { start(it.first) } } },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (popular.isNotEmpty()) {
                        item(key = "popular") {
                            Box(Modifier.padding(top = SECTION_TOP)) {
                                PicksRow(
                                    stringResource(R.string.Artists_PopularTracks),
                                    popular.map { (track, album) -> QuickPick.Song(track, album) },
                                    collection,
                                    images,
                                    logoStamp = "",
                                    actions = picks,
                                    songLine = SongLine.Record,
                                )
                            }
                        }
                    }
                    // The shelves that hold something, in order. The first opens a new part of the page, so it has the larger
                    // space above it. A shelf with nothing on it is not shown: a heading over an empty row would tell the
                    // reader what this artist did not do.
                    listOf(
                        R.string.Artists_Albums to shelves.records,
                        R.string.Artists_Collaborations to shelves.together,
                        R.string.Artists_Eps to shelves.eps,
                        R.string.Artists_LiveAlbums to shelves.live,
                        R.string.Artists_Mixtapes to shelves.mixtapes,
                        R.string.Artists_Soundtracks to shelves.soundtracks,
                        R.string.Artists_Compilations to shelves.compilations,
                        R.string.Artists_Singles to shelves.singles,
                        R.string.Artists_AppearsOn to shelves.appearances,
                    ).filter { it.second.isNotEmpty() }.forEachIndexed { index, (heading, records) ->
                        shelf(heading, records, collection, images, sleeve, if (index == 0) SECTION_TOP else SHELF_TOP, onOpen)
                    }
                    if (related.isNotEmpty()) {
                        item(key = "related") { Related(related, collection, images, artwork, onOpenArtist) }
                    }
                    item(key = "foot") { Spacer(Modifier.height(40.dp + bottomRoom)) }
                }
            }
            Box(Modifier.padding(top = statusBar).height(BACK_ROW).padding(start = 20.dp), contentAlignment = Alignment.Center) {
                BackKey(page, onBack)
            }
        }
    }
}

/**
 * The artist's popular songs. The plays that are already known are used at once. The full answer comes once
 * every record has been asked, up to four at a time (`ArtistPage.rankPopular` on iPhone). The list is empty
 * until there is enough to show.
 */
@Composable
private fun rememberPopular(shelves: ArtistShelves, worldPlays: WorldPlays): List<Pair<CloudTrack, CloudAlbum>> {
    val known = remember(shelves) {
        val records = ArtistPopular.records(shelves.songs)
        val plays = records.associate { it.key to worldPlays.cached(PlaysRecord.of(it)) }
        if (plays.values.all { it != null }) ArtistPopular.ranked(ArtistPopular.MOST, shelves.songs, plays.mapValues { it.value!! }) else emptyList()
    }
    val ranked by produceState(known, shelves) {
        val gate = Semaphore(POPULAR_ASKING)
        val plays = coroutineScope {
            ArtistPopular.records(shelves.songs).map { record ->
                async { gate.withPermit { record.key to worldPlays.plays(PlaysRecord.of(record)) } }
            }.awaitAll()
        }.mapNotNull { (key, found) -> found?.let { key to it } }.toMap()
        value = ArtistPopular.ranked(ArtistPopular.MOST, shelves.songs, plays)
    }
    return ranked
}

/** How many records' plays are asked for at once. */
private const val POPULAR_ASKING = 4

/**
 * The keys' row and the space round it: 14 above the keys, 50 for them, 10 under (`ArtistPage.keysRow` on iPhone). It is
 * kept when an artist has no songs, so the band is the same height either way.
 */
private val KEYS_ROW = 14.dp + 50.dp + 10.dp

/** What a band's bake is made from: the photograph (as itself, not its pixels) and whether it is night mode. */
private data class Baking(val photo: Bitmap, val night: Boolean)

/** A band's bake, and the band it was made for. */
private class Baked(val plate: Dp, val depth: Dp, val image: ImageBitmap)

/**
 * The last bands baked, so an artist's page shown again (after another tab, or back from a record) has its band from
 * its first frame instead of baking it again and fading it in. A few are kept; each is about a megabyte.
 */
private val BAKES = LruCache<Baking, Baked>(4)

/** How long the photograph and its blur take to fade in once both are ready (`.easeOut(duration: 0.35)` on iPhone). */
private const val PHOTO_FADE_MS = 350

/** How long a new bake of the blur takes to fade in over the one on screen. */
private const val REBAKE_FADE_MS = 250

/** Where the photograph's blur starts, as a share of the plate from the top (`ArtistPage.blurFrom` on iPhone). */
private const val BLUR_FROM = 0.6f

/**
 * The photograph behind the band, as wide as the page and as tall, blurred toward its foot and mirrored [depth] under it,
 * fading into the page (`ArtistPage.photograph` on iPhone). In night mode ([LocalNightMode]) it is also dimmed and
 * darker towards the foot where the name stands. Otherwise the name is read by its shadow. The mirror is the
 * photograph at the blur of its foot all the way down ([RunOff.Reflection.Blurred], `ArtistPage.reflection` on iPhone),
 * so the text and the keys stand on colour that is as soft as the band's foot, and no part of a face shows upside
 * down behind them.
 *
 * Where the artist has no photograph, the band stands on the room instead ([room], in the colours of their first record),
 * moving. It gets everything the photograph gets: each picture the room paints is blurred toward its foot and mirrored
 * under it the same way ([RunOff], at the room's own tenth of the pixels), and it fades in.
 *
 * It grows about the plate's foot while the page is pulled down past its top. Only its layer reads the pull.
 *
 * **Nothing of the photograph pops in.** The blur and the mirror are baked off the main thread, a moment after the
 * photograph is known. The photograph is held back until its first bake is done, and then the two fade in together
 * ([PHOTO_FADE_MS], as the iPhone fades its photograph in). A later bake, for a band that grew when the encyclopedia
 * text arrived or for night mode, fades in over the one on screen ([REBAKE_FADE_MS]) and does not replace it in one
 * frame. The photograph is drawn from its bitmap directly, not through the image loader, so it cannot land a frame
 * before or after its blur.
 */
@Composable
private fun BandGround(photo: Bitmap?, room: RoomPalette?, seed: String, plate: Dp, depth: Dp, pull: TopPull, modifier: Modifier) {
    var held by remember { mutableStateOf<PaintedRoom?>(null) }
    val density = LocalDensity.current
    val platePx = with(density) { plate.toPx() }
    val night = LocalNightMode.current
    val picture = remember(photo) { photo?.asImageBitmap() }
    // A bake kept from the last time this photograph was shown, for a band of this width: the page shown again starts
    // with it, at full strength, and does not fade in again.
    val kept = remember(photo, night) { photo?.let { BAKES.get(Baking(it, night)) }?.takeIf { it.plate == plate } }
    // The bake on screen, and the one before it while the new one fades in over it.
    var over by remember(photo) { mutableStateOf(kept?.image) }
    var under by remember(photo) { mutableStateOf<ImageBitmap?>(null) }
    val covered = remember(photo) { Animatable(1f) }
    // How far the photograph and its bake have faded in: nothing until the first bake is done.
    val shown = remember(photo) { Animatable(if (kept != null) 1f else 0f) }
    LaunchedEffect(photo, plate, depth, night) {
        val source = photo ?: return@LaunchedEffect
        // Not before the band has been measured: the band is always deeper than the plate (the keys' row is kept even
        // with no keys), so no depth means no layout yet, and a bake then would have no mirror and be replaced at once.
        if (depth <= 0.dp) return@LaunchedEffect
        val held = BAKES.get(Baking(source, night))
        if (held != null && held.plate == plate && held.depth == depth && over === held.image) return@LaunchedEffect
        val baked = held?.takeIf { it.plate == plate && it.depth == depth }?.image ?: withContext(Dispatchers.Default) {
            RunOff.bake(source, plate.value, plate.value, depth.value, BLUR_FROM, RunOff.Reflection.Blurred, darkened = night)
        }.also { BAKES.put(Baking(source, night), Baked(plate, depth, it)) }
        coroutineScope {
            // Also after a bake that replaced one still fading in, so the photograph always ends fully shown.
            launch { shown.animateTo(1f, tween(PHOTO_FADE_MS, easing = LinearOutSlowInEasing)) }
            val before = over
            if (before == null) {
                over = baked
            } else {
                under = before
                over = baked
                covered.snapTo(0f)
                covered.animateTo(1f, tween(REBAKE_FADE_MS))
                under = null
            }
        }
    }
    // The room's run-off: its newest picture baked as the photograph is, again for every picture the room paints. The
    // picture is a tenth of the band's pixels, so a bake is small; a bake still running when the next picture comes is
    // dropped for it. The room fades in with its first bake, as the photograph does.
    var roomBaked by remember(room != null) { mutableStateOf<ImageBitmap?>(null) }
    val roomShown = remember(room != null) { Animatable(0f) }
    LaunchedEffect(held, plate, depth, night) {
        val painted = held ?: return@LaunchedEffect
        if (depth <= 0.dp) return@LaunchedEffect
        val effect = this
        snapshotFlow { painted.frame }.collectLatest {
            // Copied here, on the main thread: the painter reuses its pictures' pixels for later ones.
            val small = painted.picture?.asAndroidBitmap()?.copy(Bitmap.Config.ARGB_8888, false) ?: return@collectLatest
            val baked = withContext(Dispatchers.Default) {
                RunOff.bake(
                    small, plate.value, plate.value, depth.value, BLUR_FROM, RunOff.Reflection.Blurred,
                    darkened = night, scale = small.width / plate.value,
                )
            }
            roomBaked = baked
            if (roomShown.value < 1f && !roomShown.isRunning) {
                effect.launch { roomShown.animateTo(1f, tween(PHOTO_FADE_MS, easing = LinearOutSlowInEasing)) }
            }
        }
    }
    Box(
        modifier.graphicsLayer {
            val grown = 1f + pull.pulled / platePx.coerceAtLeast(1f)
            scaleX = grown
            scaleY = grown
            transformOrigin = TransformOrigin(0.5f, if (size.height > 0f) (platePx / size.height).coerceAtMost(1f) else 1f)
        },
    ) {
        // The photograph's parts are faded in by [shown], the room's by [roomShown]; the empty band is not.
        val fading = Modifier.graphicsLayer {
            alpha = when {
                photo != null -> shown.value
                room != null -> roomShown.value
                else -> 1f
            }
        }
        Box(Modifier.fillMaxWidth().height(plate).then(fading)) {
            if (picture != null) {
                Image(picture, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (night) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = HELD)))
            } else if (room != null) {
                LiveRoom(room, seed, running = true, Modifier.fillMaxSize(), onRoom = { r, here -> held = if (here) r else held.takeIf { it !== r } })
            } else {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0x22 / 255f)))
            }
            // In night mode, darker towards the foot, where the name stands. Home's banner does the same.
            if (night) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.4f to Color.Black.copy(alpha = 0.25f),
                            0.68f to Color.Black.copy(alpha = 0.7f),
                            1f to Color.Black.copy(alpha = 0.7f),
                        ),
                    ),
                )
            }
        }
        // The room's blur toward the foot and its mirror, while the band stands on one.
        val roomOff = roomBaked
        if (photo == null && room != null && roomOff != null) {
            Box(
                Modifier.matchParentSize().then(fading).drawBehind {
                    val scale = size.width / roomOff.width
                    drawImage(
                        roomOff,
                        dstSize = IntSize(size.width.roundToInt(), (roomOff.height * scale).roundToInt()),
                        filterQuality = FilterQuality.Low,
                    )
                },
            )
        }
        // The blur toward the foot and the mirror under it, baked once per photograph and depth, at the page's width.
        if (over != null) {
            Box(
                Modifier.matchParentSize().then(fading).drawBehind {
                    fun draw(baked: ImageBitmap, alpha: Float) {
                        val scale = size.width / baked.width
                        drawImage(baked, dstSize = IntSize(size.width.roundToInt(), (baked.height * scale).roundToInt()), alpha = alpha)
                    }
                    under?.let { draw(it, 1f) }
                    over?.let { draw(it, if (under != null) covered.value else 1f) }
                },
            )
        }
    }
}

/**
 * The band's writing: the title, centred on the photograph's foot. The photograph is drawn behind it ([BandGround]).
 *
 * A white logo on a bright part of the photograph is drawn in its inverse colours, dark, so it can be read
 * ([logoIsLost]). In night mode the photograph is darkened, and the logo is always drawn as it is.
 */
@Composable
private fun Band(title: Title?, plate: Dp, photo: Bitmap?, night: Boolean) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth().height(plate)) {
        val shadow = with(density) { Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1.dp.toPx()), blurRadius = 6.dp.toPx()) }
        when (title) {
            is Title.Logo -> {
                // Drawn at its own pixel size. The logo store has already reduced it to the pixels it is drawn at.
                val mark = remember(title.picture) { title.picture.asImageBitmap() }
                val platePx = with(density) { plate.toPx() }
                val footPx = with(density) { WRITING_FOOT.toPx() }
                val inverted by produceState(false, title.picture, photo, night, platePx) {
                    value = !night && photo != null &&
                        withContext(Dispatchers.Default) { logoIsLost(title.picture, photo, platePx, footPx) }
                }
                Image(
                    mark,
                    contentDescription = title.artist,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = WRITING_FOOT)
                        .size(with(density) { mark.width.toDp() }, with(density) { mark.height.toDp() }),
                    filterQuality = FilterQuality.None,
                    colorFilter = if (inverted) INVERTED else null,
                )
            }
            is Title.Name -> BasicText(
                title.artist,
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp).padding(bottom = WRITING_FOOT),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = displayBold(26f).copy(color = Color.White, textAlign = TextAlign.Center, shadow = shadow),
            )
            null -> Unit
        }
    }
}

/**
 * Whether a white [logo] would be lost on the [photo] under it: the logo is mostly white, and the part of the photograph
 * it stands on is bright. The logo is drawn centred at the foot of a [platePx] square band, [footPx] above its foot, at
 * its own pixel size; the photograph fills the band as `ContentScale.Crop` fills it. Both are sampled, not read whole.
 */
private fun logoIsLost(logo: Bitmap, photo: Bitmap, platePx: Float, footPx: Float): Boolean {
    // The logo: how light and how grey its drawn pixels are.
    var drawn = 0
    var light = 0.0
    var colour = 0.0
    val stepX = (logo.width / 48).coerceAtLeast(1)
    val stepY = (logo.height / 24).coerceAtLeast(1)
    for (y in 0 until logo.height step stepY) for (x in 0 until logo.width step stepX) {
        val p = logo.getPixel(x, y)
        if ((p ushr 24) < 128) continue
        val r = (p shr 16 and 0xFF) / 255.0
        val g = (p shr 8 and 0xFF) / 255.0
        val b = (p and 0xFF) / 255.0
        drawn++
        light += 0.2126 * r + 0.7152 * g + 0.0722 * b
        colour += maxOf(r, g, b) - minOf(r, g, b)
    }
    if (drawn == 0 || light / drawn < LOGO_WHITE || colour / drawn > LOGO_GREY) return false

    // The photograph under it, mapped from the band to the photograph's pixels as Crop maps them.
    val fit = maxOf(platePx / photo.width, platePx / photo.height)
    val left = (photo.width * fit - platePx) / 2
    val top = (photo.height * fit - platePx) / 2
    val x0 = ((platePx - logo.width) / 2 + left) / fit
    val x1 = ((platePx + logo.width) / 2 + left) / fit
    val y1 = (platePx - footPx + top) / fit
    val y0 = y1 - logo.height / fit
    var seen = 0
    var bright = 0.0
    val sx = ((x1 - x0) / 32).coerceAtLeast(1f)
    val sy = ((y1 - y0) / 16).coerceAtLeast(1f)
    var y = y0
    while (y < y1) {
        var x = x0
        while (x < x1) {
            val px = x.toInt().coerceIn(0, photo.width - 1)
            val py = y.toInt().coerceIn(0, photo.height - 1)
            val p = photo.getPixel(px, py)
            bright += 0.2126 * (p shr 16 and 0xFF) / 255.0 + 0.7152 * (p shr 8 and 0xFF) / 255.0 + 0.0722 * (p and 0xFF) / 255.0
            seen++
            x += sx
        }
        y += sy
    }
    return seen > 0 && bright / seen > PHOTO_BRIGHT
}

/** A logo this light on average, over its drawn pixels, is a white logo. */
private const val LOGO_WHITE = 0.8

/** And this grey: the spread between its brightest and darkest channel, on average. A coloured logo is left alone. */
private const val LOGO_GREY = 0.15

/** A photograph this light on average, under the logo, is too bright for a white one. */
private const val PHOTO_BRIGHT = 0.6

/** A logo's colours turned over, keeping its alpha: white becomes black. */
private val INVERTED = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/** The soft outline on every text of the page ([LocalTextFeather]): how far it is feathered, and how dark it is. */
private val FEATHER = 5.dp
private const val FEATHER_ALPHA = 0.55f

/**
 * The band's title, and whose it is: the artist's logo, or the artist's name if there is no logo or it could
 * not be read. It is held with the artist, so a title from another artist is never drawn.
 */
private sealed interface Title {
    val artist: String

    class Logo(override val artist: String, val picture: Bitmap) : Title

    class Name(override val artist: String) : Title
}

/**
 * The tallest a logo is drawn, in points, and the largest share of the band's width it may take (`ArtistPage.logoCap`
 * and `logoShare` on iPhone).
 */
private const val LOGO_CAP = 50
private const val LOGO_SHARE = 0.7f

/**
 * Three keys in the middle of the page: a mixtape, Play, and Shuffle (`ArtistPage.keys` on iPhone). Play is a round glass
 * key in the middle. The two beside it are their glyphs alone, with no glass, so Play is the key the eye finds first.
 * Without [onMixtape] its place is kept empty, so Play stays in the middle.
 *
 * **Play is the flat glass face ([GlassButton]), not the frosted [GlassKey].** A frosted key draws the page's backdrop
 * ([backdropSource]), and these keys are inside the page that the backdrop records. The page would then draw the key and
 * the key the page, without end, and the render thread runs out of stack on the first frame.
 */
@Composable
private fun Keys(onPlay: () -> Unit, onShuffle: () -> Unit, onMixtape: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(KEYS_APART, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onMixtape != null) {
            SideKey(Glyph.mixtape, stringResource(R.string.Track_StartMixtape), onMixtape)
        } else {
            Spacer(Modifier.size(SIDE_KEY_WIDTH, KEY_HEIGHT))
        }
        val play = stringResource(R.string.Rpm_Artist_PlayAll)
        GlassButton(
            onClick = onPlay,
            modifier = Modifier.size(KEY_HEIGHT).semantics { contentDescription = play },
            weight = Weight.Primary,
            stretches = false,
            height = KEY_HEIGHT,
            radius = KEY_HEIGHT / 2,
            inset = 0.dp,
        ) { style ->
            // The triangle's weight is left of its middle, so it is moved right a little to look centred in the circle.
            BasicText(Glyph.play, Modifier.offset(x = 2.dp), style = icon(18f).copy(color = style.color))
        }
        SideKey(Glyph.shuffle, stringResource(R.string.AlbumMenu_Shuffle), onShuffle)
    }
}

/**
 * A key beside Play: its glyph, in a box as tall as Play, which is the part that answers a tap. The artist page and the
 * record page put one on each side of Play.
 */
@Composable
internal fun SideKey(glyph: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .size(SIDE_KEY_WIDTH, KEY_HEIGHT)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(glyph, style = icon(17f).copy(color = Ink.white(0xF0)))
    }
}

/** Play's diameter, and the height of the keys beside it. */
internal val KEY_HEIGHT = 50.dp

/** The width a key beside Play answers taps in. */
internal val SIDE_KEY_WIDTH = 56.dp

/** The space between Play and each key beside it. */
internal val KEYS_APART = 12.dp

/** One shelf of records, scrolled sideways, with its heading above the covers, [top] under what is above it. */
private fun LazyListScope.shelf(
    heading: Int,
    records: List<CloudAlbum>,
    collection: Collection,
    images: ImageLoader,
    sleeve: Double,
    top: Dp,
    onOpen: (CloudAlbum) -> Unit,
) {
    if (records.isEmpty()) return
    item(key = "shelf:$heading") {
        val caption = remember(sleeve) { AlbumCaption.of(sleeve) }
        Column(Modifier.padding(top = top), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(stringResource(heading).uppercase(), Modifier.padding(horizontal = 20.dp), style = kicker())
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(records, key = { it.key }) { album -> AlbumTile(album, collection.cover(album), caption, images) { onOpen(album) } }
            }
        }
    }
}

/**
 * The acts related to them in this collection ([RelatedArtists]), worked out off the main thread because the rule goes
 * over every track of the collection, and shown in one step. Empty until then, and for an act with none.
 */
@Composable
private fun rememberRelated(artist: ArtistWallItem, walls: Walls, collection: Collection): List<ArtistWallItem> {
    val related by produceState(emptyList<ArtistWallItem>(), artist, walls, collection) {
        value = withContext(Dispatchers.Default) { RelatedArtists.of(artist, walls, collection.albums.associateBy { it.key }, collection.lists) }
    }
    return related
}

/**
 * The related acts, as the artists wall's own boxes, which open the same page. It is the last section, as on the
 * iPhone (`ArtistPage.relatedSection`).
 */
@Composable
private fun Related(acts: List<ArtistWallItem>, collection: Collection, images: ImageLoader, artwork: Artwork, onOpenArtist: (ArtistWallItem) -> Unit) {
    Column(Modifier.padding(top = SECTION_TOP), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(stringResource(R.string.Artists_Related).uppercase(), Modifier.padding(horizontal = 20.dp), style = kicker())
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(acts, key = { it.name }) { act -> ArtistBox(act, RELATED_BOX, collection, images, artwork) { onOpenArtist(act) } }
        }
    }
}

/** The width of a related act's box (`ArtistBox(width: 200)` on iPhone). */
private val RELATED_BOX = 200.dp
