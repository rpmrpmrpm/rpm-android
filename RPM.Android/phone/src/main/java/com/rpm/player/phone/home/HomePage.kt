package com.rpm.player.phone.home

import android.os.Build
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.rememberBackdrop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.art.ArtScene
import android.graphics.Bitmap
import com.rpm.player.phone.PhoneApplication
import com.rpm.player.core.library.BannerCard
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.HomeShelf
import com.rpm.player.core.library.Kicker
import com.rpm.player.core.library.LibraryState
import com.rpm.player.core.library.QuickPick
import com.rpm.player.core.library.TrackList
import com.rpm.player.phone.library.FramedList
import com.rpm.player.phone.library.PlaylistSleeve
import com.rpm.player.core.library.listName
import com.rpm.player.core.radio.RadioStation
import com.rpm.player.core.radio.StationLogo
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.PageTitle
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.design.dissolvesAtTop
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.library.songPress
import com.rpm.player.phone.design.pt
import com.rpm.player.phone.design.radiusFor
import com.rpm.player.phone.design.system
import com.rpm.player.phone.library.AlbumCaption
import com.rpm.player.phone.library.AlbumTile
import com.rpm.player.phone.library.FOOT
import com.rpm.player.phone.ui.ArtGround
import com.rpm.player.phone.ui.RunOff
import com.rpm.player.phone.ui.rememberAccent
import com.rpm.player.phone.radio.StationFace
import com.rpm.player.phone.design.TopPull
import com.rpm.player.phone.design.growsWithPull
import com.rpm.player.phone.design.rememberTopPull
import com.rpm.player.phone.design.rememberHaptics
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt
import com.rpm.player.phone.ui.LiveRoom
import com.rpm.player.phone.ui.roomReflection
import com.rpm.player.core.room.PaintedRoom
import com.rpm.player.phone.ui.rememberRoomColours
import com.rpm.player.core.room.RoomPalette

/**
 * The height of the banner's plate, before it reaches up behind the title and the status bar (`HomeBannerMetrics` on
 * iPhone).
 */
private val BANNER = 352.dp
private val BANNER_SLEEVE = 124.dp
private val BANNER_MARGIN = 22.dp

/** The cover size in a row (`HomeRowView.art` on iPhone). */
private const val ROW_ART = 132.0

/**
 * How deep the banner's reflection runs under the card (`HomeBannerMetrics.reflection` on iPhone): past the dots, the
 * gap to the first row, its heading and its covers, and a little more, so the picture fades into the page and does not
 * stop at an edge. It is the same whatever stands under the card, the quick selection included.
 */
private fun bannerMirror(cards: Int): Dp = ((if (cards > 1) 10 + 5 else 0) + 26 + (23 + 10) + ROW_ART + 18).dp

/** Where a card's blur starts, as a share of its height: the face is cut into the upper half (`HomeFeatureCard.blurFrom`). */
private const val BANNER_BLUR_FROM = 0.6f

/**
 * The quick selection: sixteen picks, four lines to a panel, with the edge of the next panel showing (`QuickPicks` on
 * iPhone).
 */
private const val QUICK_PICKS = 16
private const val QUICK_TALL = 4
private val QUICK_LINE = 60.dp
private val QUICK_ART = 44.dp
private val PEEK = 32.dp
private val PANELS_APART = 16.dp

/** The space between the sections of the page. */
private val SECTIONS_APART = 26.dp

/** What home's taps do: open a record or a list, play songs, tune a station. */
class HomeActions(
    val onOpen: (CloudAlbum) -> Unit,
    val onOpenList: (TrackList) -> Unit,
    val onPlay: (List<CloudTrack>) -> Unit,
    val onTune: (RadioStation) -> Unit,
    /** Start radio on a song: it plays now and the queue keeps filling with songs like it. */
    val onRadio: (CloudTrack) -> Unit = {},
)

/**
 * [stations] are the stations heard most, as the rules from the server show them. They feed the banner's radio
 * card and the quick selection's stations.
 *
 * Home: what has been played, what to play next, and what was added last. This matches the iPhone's `HomePage`
 * (RPMUI/HomePage.swift). It is drawn from the home data that `:core` builds for the TV app, using the desktop's
 * rules. From the top: the banner, the quick selection directly under it, the rows of records, and the computer's
 * lists.
 *
 * The page uses the colours of the banner's front card, the one card with a picture. The title stands over the
 * scroll. The banner runs up behind the title and behind the status bar.
 *
 * The banner turns to its next card every seven seconds while the page is on screen, at its top, and the strip is
 * still, and it runs round from the last card to the first. A collection with fewer than twenty records shows the
 * lock instead ([HomeLock]), which says how many more home needs. The key on the title line chooses whose collection
 * the app shows ([SwitchKey]), when anybody shares one with this account.
 *
 * Not yet implemented: the banner's copy in the page (its reflection).
 *
 * @param onScreen whether home is the page showing: the banner turns only then.
 * @param onSwitch opens the collections picker, or null when there is only the person's own to choose.
 * @param onAddMusic what the lock's key does: the library's albums.
 * @param onReady called once the page has drawn what it opens on: the banner's front card with its photograph looked
 * for, or no banner at all. The launch keeps its wait cover until then.
 */
@Composable
fun HomePage(
    ready: LibraryState.Ready,
    stations: List<RadioStation>,
    logoStamp: String,
    images: ImageLoader,
    bottomRoom: Dp,
    actions: HomeActions,
    onScreen: Boolean,
    onSwitch: (() -> Unit)?,
    onAddMusic: () -> Unit,
    onReady: () -> Unit,
) {
    val collection = ready.collection
    val home = ready.home
    // The glass on home (the switch key, the lock's key) frosts the page's ground, never the page itself: a key that
    // drew a layer holding itself would nest without end.
    val backdrop = rememberBackdrop()
    // Home needs a row's worth of records before it can say anything (`HomeGate` on iPhone).
    if (collection.albums.size < HomeShelf.ROW_LENGTH) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().backdropSource(backdrop)) { ArtGround(null, images) }
            LaunchedEffect(Unit) { onReady() }
            HomeLock(collection.albums.size, HomeShelf.ROW_LENGTH, backdrop, onAddMusic, Modifier.align(Alignment.Center).padding(bottom = bottomRoom))
            if (onSwitch != null) {
                SwitchKey(backdrop, onSwitch, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 4.dp, end = 16.dp))
            }
        }
        return
    }
    val density = LocalDensity.current
    val banner = remember(home.banner, stations) { HomeShelf.withStation(home.banner, stations.firstOrNull()) }
    val quick = remember(home.quick, stations) { HomeShelf.withStations(home.quick, stations).take(QUICK_PICKS) }
    val statusBar = WindowInsets.statusBars.asPaddingValues(density).calculateTopPadding()
    var heading by remember { mutableStateOf(0.dp) }
    var dress by remember { mutableStateOf<Any?>(null) }
    val scroll = rememberScrollState()
    // The iPhone's band at the top: pulled past its top, the page comes down and the banner grows to cover the gap.
    val pull = rememberTopPull()
    // Read once per change of the answer, not on every frame of a scroll.
    val atTop by remember { derivedStateOf { scroll.value < 1 } }
    // How far the banner reaches up: the title and the status bar. This is known once the title has been measured.
    val overhang = if (banner.isNotEmpty() && heading > 0.dp) heading + statusBar else 0.dp

    Box(Modifier.fillMaxSize()) {
        // A cover's accent tints the ground, as on iPhone (`BannerDress.accent`). A photograph gives none: its colour is
        // the photograph's own.
        val accent = rememberAccent(dress?.takeIf { it !is Bitmap }, images)
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) { ArtGround(dress, images, accent) }
        Column(
            Modifier
                .fillMaxSize()
                .dissolvesAtTop(statusBar + heading) { scroll.value.toFloat() }
                .verticalScroll(scroll, overscrollEffect = pull)
                .padding(bottom = FOOT + bottomRoom),
            verticalArrangement = Arrangement.spacedBy(SECTIONS_APART),
        ) {
            if (banner.isNotEmpty()) {
                Banner(banner, collection, images, logoStamp, overhang, statusBar, turns = onScreen && atTop, pull, roomsRun = onScreen, onDress = { dress = it }, onReady = onReady, actions = actions)
            } else {
                Spacer(Modifier.height(heading + statusBar))
                // No banner to wait for.
                LaunchedEffect(Unit) { onReady() }
            }
            if (quick.isNotEmpty()) PicksRow(stringResource(R.string.Home_QuickPicks), quick, collection, images, logoStamp, actions, SongLine.Artist)
            for ((label, albums) in listOf(
                R.string.Home_Listened to home.listened,
                R.string.Home_Suggested to home.suggested,
                R.string.Home_Taste to home.taste,
                R.string.Home_Played to home.played,
                R.string.Home_Added to home.added,
            )) {
                if (albums.isNotEmpty()) AlbumRow(stringResource(label), albums, collection, images, actions.onOpen)
            }
            // "Playlists" when the row holds somebody's own playlists (a borrowed collection), "Made for you" when it
            // holds only generated lists, as the desktop names its row.
            if (home.madeForYou.isNotEmpty()) {
                val heading = if (home.madeForYou.any { !it.generated }) R.string.Playlists_Title else R.string.Home_MadeForYou
                ListsRow(stringResource(heading), home.madeForYou, collection, images, actions.onOpenList)
            }
        }
        // The title is drawn over the scroll. It does not move with the scroll. It sits on the same line as the title
        // of every other page.
        PageTitle(
            stringResource(R.string.Home_Heading),
            Modifier
                .statusBarsPadding()
                .padding(start = 20.dp, end = 16.dp, bottom = 10.dp)
                .onSizeChanged { heading = with(density) { it.height.toDp() } + 10.dp },
        )
        // On the title's line, where the library's keys are, 16 from the edge. A layer of its own, because the title
        // lets taps through to the banner under it and this key must take them.
        if (onSwitch != null) {
            SwitchKey(backdrop, onSwitch, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 4.dp, end = 16.dp))
        }
    }
}

/** How long after the banner is drawn the other cards' photographs start to be cut: after the front card's own, so the
 * two do not compete for the network. */
private const val PRELOAD_AFTER_MS = 2_500L

/** How long a banner card stays in front before the next one turns in (`BannerDwell.seconds` on iPhone). */
private const val DWELL_MS = 7_000L

// ── the banner ──

/**
 * Cards across the top of the page, one screen wide. They are swiped one card at a time, with a dot under them
 * for each card. The card in front sets the page's colours.
 */
@Composable
private fun Banner(
    cards: List<BannerCard>,
    collection: Collection,
    images: ImageLoader,
    logoStamp: String,
    overhang: Dp,
    statusBar: Dp,
    turns: Boolean,
    pull: TopPull,
    roomsRun: Boolean,
    onDress: (Any?) -> Unit,
    onReady: () -> Unit,
    actions: HomeActions,
) {
    // Every card's room runs on one clock, so the copy of the first card after the last draws the same frame as the first
    // card when one takes the other's place (`RoomView.Zero.shared` on iPhone).
    val roomZero = remember { System.nanoTime() }
    // A copy of the first card stands after the last, so the strip runs on from the last card to the first. When it comes
    // to rest on the copy, it is put back on the first card without a movement (`BannerRing` on iPhone).
    val ring = cards.size > 1
    val slots = if (ring) cards.size + 1 else cards.size
    val pager = rememberPagerState { slots }
    LaunchedEffect(pager, cards.size) {
        snapshotFlow { pager.settledPage to pager.isScrollInProgress }.collect { (settled, moving) ->
            if (ring && !moving && settled == cards.size) pager.scrollToPage(0)
        }
    }
    // The turn: seven seconds after the strip last moved, while the page is on screen and at its top. Any change to those
    // starts the count again.
    // The step runs in a scope of its own: the strip moving under it is one of the changes that start the count again,
    // and that must not stop the step itself.
    val stepping = rememberCoroutineScope()
    LaunchedEffect(ring, turns, pager) {
        if (!ring || !turns) return@LaunchedEffect
        snapshotFlow { pager.settledPage to pager.isScrollInProgress }.collectLatest { (at, moving) ->
            if (moving) return@collectLatest
            delay(DWELL_MS)
            stepping.launch { pager.animateScrollToPage(at + 1) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // The photograph is cut for the height the card is drawn at, including the overhang. If it were cut for
            // the plate alone and then stretched to the taller card, a face in the clear area would be pushed up behind
            // the status bar.
            val scene = remember(maxWidth, overhang, statusBar) { bannerScene(maxWidth, overhang, statusBar) }
            // The other cards' photographs are cut ahead, one at a time, once the front card has had its turn, so a card
            // that turns in has its photograph already (`HomeBannerView.preload` on iPhone).
            val portraits = (LocalContext.current.applicationContext as PhoneApplication).portraits
            LaunchedEffect(cards, scene, overhang > 0.dp) {
                if (overhang <= 0.dp) return@LaunchedEffect
                delay(PRELOAD_AFTER_MS)
                for (card in cards.drop(1)) {
                    val record = (card as? BannerCard.OfAlbum)?.album?.takeIf { it.artist.isNotBlank() } ?: continue
                    portraits.portrait(record.artist, record.album, scene)
                }
            }
            val plate = BANNER + overhang
            val mirror = bannerMirror(cards.size)
            val platePx = with(LocalDensity.current) { plate.toPx() }
            // Each card's photograph as baked for its run-off and reflection ([RunOff]), by card. The strip draws the
            // reflections from here, under the dots and the rows.
            val runOffs = remember(cards) { mutableStateMapOf<Int, ImageBitmap>() }
            // Each card's room, by card, for a card that stands on one: the strip turns it over under the card as its
            // reflection (`RoomView.fold` on iPhone).
            val rooms = remember(cards) { mutableStateMapOf<Int, PaintedRoom>() }
            // Scaled up about the card's foot while the page is pulled down past its top, so the picture zooms and the
            // page's ground does not show above it (`growsWithPull` on iPhone). The whole strip is scaled, because a
            // pager clips its pages to itself; the writing is scaled back (`keepsSize`), so only the picture grows.
            HorizontalPager(pager, Modifier.fillMaxWidth().height(plate).growsWithPull(pull, platePx, TransformOrigin(0.5f, 1f))) { page ->
                val card = page % cards.size
                // No cut is made until the heading has been measured, because the overhang it gives changes the frame.
                BannerCardView(
                    cards[card], live = page == pager.currentPage, roomRuns = roomsRun && page == pager.currentPage, roomZero, collection, images, scene.takeIf { overhang > 0.dp }, logoStamp,
                    width = maxWidth, plate = plate, mirror = mirror,
                    keepsSize = Modifier.graphicsLayer {
                        val grown = 1f + pull.pulled / platePx.coerceAtLeast(1f)
                        scaleX = 1f / grown
                        scaleY = 1f / grown
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    },
                    onRunOff = { baked -> if (baked != null) runOffs[card] = baked else runOffs.remove(card) },
                    onRoom = { room, here -> if (here) rooms[card] = room else if (rooms[card] === room) rooms.remove(card) },
                    onDress = onDress, onReady = onReady, actions = actions,
                )
            }
            BannerReflections(pager, cards.size, runOffs, rooms, plate, mirror, pull, platePx)
        }
        if (cards.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                for (i in cards.indices) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(if (i == pager.currentPage % cards.size) Color.White else Ink.white(0x44)))
                }
            }
        }
    }
}

/**
 * The frame that a banner photograph is cut for. It matches the iPhone's `HomeFeatureCard.scene` and
 * `HomeBannerMetrics`. The frame is the card's width and drawn height, with the status bar over its top, the
 * right margin, and the space for the cover and the writing along its foot.
 */
private fun bannerScene(width: Dp, overhang: Dp, statusBar: Dp): ArtScene {
    val wide = width.value.toDouble()
    val plate = (BANNER + overhang).value.toDouble()
    val band = (BANNER_MARGIN + BANNER_SLEEVE + BANNER_MARGIN).value.toDouble()
    val covered = minOf(statusBar.value, overhang.value).coerceAtLeast(0f).toDouble()
    return ArtScene.forClearLeft(
        wide,
        plate,
        clearTop = minOf(0.5, covered / plate),
        clearRight = if (wide > 0) ((wide - BANNER_MARGIN.value) / wide).coerceIn(0.2, 1.0) else 1.0,
        clearBottom = ((plate - band) / plate).coerceIn(0.2, 1.0),
    )
}

/**
 * One card: the artist's photograph where there is one, dimmed by a fifth and darker toward the foot where the
 * writing is. It also shows the cover, the kind of card, the title and the artist. A list's card, a station's card and
 * a record's card whose artist has no photograph stand on the room instead ([LiveRoom]), moving while the card is in
 * front ([roomRuns]), on the banner's one clock ([roomZero]).
 */
@Composable
private fun BannerCardView(
    card: BannerCard,
    live: Boolean,
    roomRuns: Boolean,
    roomZero: Long,
    collection: Collection,
    images: ImageLoader,
    scene: ArtScene?,
    logoStamp: String,
    width: Dp,
    plate: Dp,
    mirror: Dp,
    keepsSize: Modifier,
    onRunOff: (ImageBitmap?) -> Unit,
    onRoom: (PaintedRoom, Boolean) -> Unit,
    onDress: (Any?) -> Unit,
    onReady: () -> Unit,
    actions: HomeActions,
) {
    val album = (card as? BannerCard.OfAlbum)?.album
    val list = (card as? BannerCard.OfList)?.list
    val station = (card as? BannerCard.OfStation)?.station
    val cover: Any? = album?.let { collection.cover(it) } ?: list?.let { collection.cover(it) }
    val portraits = (LocalContext.current.applicationContext as PhoneApplication).portraits
    // The artist's square photograph, if its face is clear of the frame. Otherwise the backdrop, cropped around the
    // faces. This is what the iPhone's card asks for (`BackdropStore.portrait`).
    // Whether the photograph has been looked for, found or not: the front card is drawn once it has (`onReady`).
    var looked by remember(album?.key, scene) { mutableStateOf(false) }
    val photo by produceState<Bitmap?>(null, album?.key, scene) {
        val record = album?.takeIf { it.artist.isNotBlank() }
        if (record == null) {
            looked = true
            return@produceState
        }
        value = scene?.let { portraits.portrait(record.artist, record.album, it) }
        if (scene != null) looked = true
    }
    val wearing = photo ?: cover
    // The photograph blurred toward the card's foot and mirrored under it, baked once per photograph and size.
    val runOff by produceState<ImageBitmap?>(null, photo, width, plate, mirror) {
        val picture = photo ?: return@produceState
        value = withContext(Dispatchers.Default) {
            RunOff.bake(picture, width.value, plate.value, mirror.value, BANNER_BLUR_FROM, RunOff.Reflection.Blurred)
        }
    }
    LaunchedEffect(runOff) { onRunOff(runOff) }
    LaunchedEffect(live, wearing) { if (live && wearing != null) onDress(wearing) }
    LaunchedEffect(live, looked) { if (live && looked) onReady() }

    val kicker = when (card) {
        is BannerCard.OfAlbum -> stringResource(kickerOf(card.kicker))
        is BannerCard.OfList -> stringResource(R.string.Home_MadeForYou)
        is BannerCard.OfStation -> stringResource(R.string.Rpm_Tab_Radio)
    }
    val title = album?.album ?: list?.let { listName(it.name) } ?: station?.display.orEmpty()
    // A generated list's card stands on the room in the list's own colours, and its sleeve wears the list's frame, as
    // on the playlists view (`HomeBannerView`'s list card on iPhone).
    val framed = list?.let { FramedList.of(it, title) }
    // A list's card and a station's stand on their room; a record's card stands on it once its photograph has been
    // looked for and not found (`HomeFeatureCard.standingOnARoom`). The room is in a generated list's own colours, or
    // in those of the card's cover or the station's logo. It moves only on the card in front, while home is showing.
    val roomed = list != null || station != null || (looked && photo == null)
    val colours = rememberRoomColours(if (roomed && framed == null) station?.let { StationLogo.of(it, logoStamp) } ?: cover else null, images)
    val seed = album?.key ?: station?.uuid ?: title
    val subtitle = album?.artist

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.ground)
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                when {
                    album != null -> actions.onOpen(album)
                    list != null -> actions.onOpenList(list)
                    station != null -> actions.onTune(station)
                }
            },
    ) {
        when {
            roomed -> LiveRoom(framed?.look?.room ?: colours ?: RoomPalette.FALLBACK, seed, running = roomRuns, Modifier.fillMaxSize(), zeroNanos = roomZero, onRoom = onRoom)
            photo != null -> {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(photo).build(),
                    contentDescription = null,
                    imageLoader = images,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = RunOff.HELD)))
            }
        }
        // Darker towards the foot, where the writing stands.
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
        // The blur toward the foot, over the sharp photograph: the plate's part of the baked run-off.
        runOff?.let { baked ->
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val rows = (plate.value * RunOff.SCALE).roundToInt().coerceAtMost(baked.height)
                    drawImage(baked, srcSize = IntSize(baked.width, rows), dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
                },
            )
        }
        // The writing keeps its size while the picture grows with a pull, as on iPhone.
        Box(Modifier.matchParentSize().then(keepsSize)) {
        Row(
            Modifier.align(Alignment.BottomStart).padding(BANNER_MARGIN),
            horizontalArrangement = Arrangement.spacedBy(BANNER_MARGIN),
            verticalAlignment = Alignment.Bottom,
        ) {
            BannerSleeve(cover, station, list != null, framed, logoStamp, images)
            val shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), blurRadius = 6f)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText(kicker, style = display(12f).copy(color = Ink.white(0xCC), letterSpacing = pt(1.5f), shadow = shadow))
                BasicText(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = displayBold(22f).copy(color = Color.White, shadow = shadow))
                if (!subtitle.isNullOrEmpty()) {
                    BasicText(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = system(13f).copy(color = Ink.white(0xD1), shadow = shadow))
                }
            }
        }
        }
    }
}

/**
 * The reflections under the banner's cards, drawn by the strip and not by the cards: a pager clips each page to
 * itself, and a reflection runs down past the card behind the dots and the first rows. Each card's own reflection
 * moves with the card under the finger, as on iPhone, where every card casts its own. It is the mirror part of the
 * card's baked [RunOff], so it matches the card row for row where they meet. A card that stands on a room casts the
 * room's own picture turned over ([roomReflection]), so the reflection moves with the room, as the iPhone's one program
 * draws the card and its fold.
 *
 * It takes no room: it is laid out with no height at the card's foot, and drawn [mirror] deep under it. It grows with a
 * pull about the card's foot, as the card above it does. Only the draw reads the strip's position and the pull.
 */
@Composable
private fun BannerReflections(
    pager: PagerState,
    cards: Int,
    runOffs: Map<Int, ImageBitmap>,
    rooms: Map<Int, PaintedRoom>,
    plate: Dp,
    mirror: Dp,
    pull: TopPull,
    platePx: Float,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val deep = mirror.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, deep))
                layout(constraints.maxWidth, 0) {
                    placeable.placeWithLayer(0, plate.roundToPx()) {
                        val grown = 1f + pull.pulled / platePx.coerceAtLeast(1f)
                        scaleX = grown
                        scaleY = grown
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                }
            }
            .drawBehind {
                if (cards == 0) return@drawBehind
                val at = pager.currentPage + pager.currentPageOffsetFraction
                val first = floor(at).toInt()
                for (page in first..first + 1) {
                    val x = ((page - at) * size.width).roundToInt()
                    val baked = runOffs[page.mod(cards)]
                    if (baked == null) {
                        rooms[page.mod(cards)]?.let { roomReflection(it, x, platePx) }
                        continue
                    }
                    val from = (plate.value * RunOff.SCALE).roundToInt().coerceAtMost(baked.height)
                    if (baked.height - from <= 0) continue
                    drawImage(
                        baked,
                        srcOffset = IntOffset(0, from),
                        srcSize = IntSize(baked.width, baked.height - from),
                        dstOffset = IntOffset(x, 0),
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    )
                }
            },
    )
}

@Composable
private fun BannerSleeve(cover: Any?, station: RadioStation?, list: Boolean, framed: FramedList?, logoStamp: String, images: ImageLoader) {
    val shape = RoundedCornerShape(10.dp)
    if (framed != null) {
        Box(Modifier.size(BANNER_SLEEVE).shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))) {
            PlaylistSleeve(cover, Glyph.playlists, BANNER_SLEEVE, corner = 10.dp, mark = 34f, images, framed)
        }
        return
    }
    Box(
        Modifier
            .size(BANNER_SLEEVE)
            .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
            .clip(shape)
            .background(Color.Black.copy(alpha = 0x22 / 255f))
            .border(1.dp, Ink.cardBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (station != null) {
            // The station's own logo on its made ground, as on the radio tab and the player (`StationFace` on iPhone).
            StationFace(station, logoStamp, images, BANNER_SLEEVE, inset = 14.dp, Modifier.fillMaxSize()) {
                BasicText(Glyph.radio, style = icon(34f).copy(color = Ink.white(0x3A)))
            }
        } else {
            BasicText(if (list) Glyph.playlists else Glyph.record, style = icon(34f).copy(color = Ink.white(0x3A)))
            if (cover != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(cover).build(),
                    contentDescription = null,
                    imageLoader = images,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun kickerOf(kicker: Kicker): Int = when (kicker) {
    Kicker.CONTINUE -> R.string.Home_Continue
    Kicker.ADDED -> R.string.Home_Added
    Kicker.MADE_FOR_YOU -> R.string.Home_MadeForYou
    Kicker.SUGGESTED -> R.string.Home_Suggested
    Kicker.PLAYED -> R.string.Home_Played
    Kicker.LISTENED -> R.string.Home_Listened
}

// ── the quick selection, and any row of picks ──

/** What the line under a song says: its artist, or, on a page that is already about the artist, its record. */
enum class SongLine { Artist, Record }

/**
 * A row of picks under a heading. This matches the iPhone's `HomePicksRow`. Home's quick selection and an
 * artist's popular songs both use it. The picks are lines, not squares. They fill panels of four, from the top
 * down. The user swipes one panel at a time, and the edge of the next panel shows. The play key beside the
 * heading plays the songs, and only the songs. A record is a group of songs, but a station is not a set of
 * songs, so the key plays only songs.
 */
@Composable
fun PicksRow(
    heading: String,
    picks: List<QuickPick>,
    collection: Collection,
    images: ImageLoader,
    logoStamp: String,
    actions: HomeActions,
    songLine: SongLine,
) {
    val songs = picks.filterIsInstance<QuickPick.Song>().map { it.track }
    val haptics = rememberHaptics()
    val panels = picks.chunked(QUICK_TALL)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.height(34.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(heading, style = display(19f).copy(color = Color.White))
            if (songs.isNotEmpty()) {
                Box(
                    Modifier.size(34.dp).clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { haptics.pick(); actions.onPlay(songs) },
                    contentAlignment = Alignment.Center,
                ) { BasicText(Glyph.play, style = icon(12f).copy(color = Ink.white(0xB8))) }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = maxWidth - 40.dp - if (panels.size > 1) PEEK else 0.dp
            val trailing = maxOf(20.dp, maxWidth - 20.dp - width)
            HorizontalPager(
                rememberPagerState { panels.size },
                pageSize = PageSize.Fixed(width),
                contentPadding = PaddingValues(start = 20.dp, end = trailing),
                pageSpacing = PANELS_APART,
                modifier = Modifier.height(QUICK_LINE * (panels.maxOfOrNull { it.size } ?: 0)),
                verticalAlignment = Alignment.Top,
            ) { page ->
                Column { for (pick in panels[page]) QuickLine(pick, collection, images, logoStamp, actions, songLine) }
            }
        }
    }
}

@Composable
private fun QuickLine(pick: QuickPick, collection: Collection, images: ImageLoader, logoStamp: String, actions: HomeActions, songLine: SongLine) {
    val album: CloudAlbum? = when (pick) {
        is QuickPick.Song -> pick.album
        is QuickPick.Radio -> pick.album
        is QuickPick.OfAlbum -> pick.album
        else -> null
    }
    val picture: Any? = when (pick) {
        is QuickPick.OfStation -> null
        is QuickPick.OfList -> collection.cover(pick.list)
        else -> album?.let { collection.cover(it) }
    }
    val (title, subtitle, glyph) = when (pick) {
        is QuickPick.Song -> Triple(
            pick.track.title.orEmpty(),
            if (songLine == SongLine.Record) pick.album?.album ?: pick.track.album else pick.track.artist,
            Glyph.song,
        )
        is QuickPick.OfAlbum -> Triple(pick.album.album, kindLine(stringResource(R.string.Home_QuickAlbum), pick.album.artist), Glyph.record)
        is QuickPick.OfList -> Triple(
            listName(pick.list.name),
            kindLine(stringResource(R.string.Home_QuickPlaylist), stringResource(R.string.Home_Songs, pick.list.tracks.size)),
            Glyph.playlists,
        )
        is QuickPick.OfStation -> Triple(pick.station.display, stringResource(R.string.Home_QuickStation), Glyph.radio)
        // Named for the song it starts from, the kind first on the line under it, so it reads as a radio and not as
        // the song's own line further on.
        is QuickPick.Radio -> Triple(pick.track.title.orEmpty(), kindLine(stringResource(R.string.Home_QuickMixtape), pick.track.artist), Glyph.mixtape)
    }
    val onClick: (() -> Unit)? = when (pick) {
        is QuickPick.Song -> { { actions.onPlay(listOf(pick.track)) } }
        is QuickPick.OfAlbum -> { { actions.onOpen(pick.album) } }
        is QuickPick.OfStation -> { { actions.onTune(pick.station) } }
        is QuickPick.OfList -> { { actions.onOpenList(pick.list) } }
        is QuickPick.Radio -> { { actions.onRadio(pick.track) } }
    }
    val presses = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .height(QUICK_LINE)
            // A song has the song menu on a long press, as on iPhone. A song's radio is not the song, and has none.
            .then(
                if (pick is QuickPick.Song) {
                    Modifier.songPress(pick.track, presses) { onClick?.invoke() }
                } else {
                    Modifier.clickable(presses, indication = null, enabled = onClick != null) { onClick?.invoke() }
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(5.dp)
        // A generated list's line wears the list's frame, as its square does everywhere else.
        val framed = (pick as? QuickPick.OfList)?.let { FramedList.of(it.list, title) }
        if (framed != null) PlaylistSleeve(picture, Glyph.playlists, QUICK_ART, corner = 5.dp, mark = 15f, images, framed)
        // A station's logo on its made ground, in the cover's square (`HomePicksRow.stationSquare` on iPhone).
        else if (pick is QuickPick.OfStation) Box(Modifier.size(QUICK_ART).clip(shape).background(Color.Black.copy(alpha = 0x22 / 255f))) {
            StationFace(pick.station, logoStamp, images, QUICK_ART, inset = 4.dp, Modifier.fillMaxSize()) {
                BasicText(Glyph.radio, style = icon(15f).copy(color = Ink.white(0x3A)))
            }
        }
        else Box(Modifier.size(QUICK_ART).clip(shape).background(Color.Black.copy(alpha = 0x22 / 255f)), contentAlignment = Alignment.Center) {
            BasicText(glyph, style = icon(15f).copy(color = Ink.white(0x3A)))
            if (picture != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(picture).build(),
                    contentDescription = null,
                    imageLoader = images,
                    contentScale = if (pick is QuickPick.OfStation) ContentScale.Fit else ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = if (pick is QuickPick.OfStation) Modifier.fillMaxSize().padding(4.dp) else Modifier.fillMaxSize(),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = system(14f, FontWeight.Medium).copy(color = Color.White))
            if (!subtitle.isNullOrEmpty()) {
                BasicText(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = system(12f).copy(color = Ink.secondary))
            }
        }
    }
}

/**
 * Example: "Album • Sting", "Playlist • 24 songs". It shows the kind, then the artist, or the number of songs in the
 * list.
 */
@Composable
private fun kindLine(kind: String, rest: String?): String =
    if (rest.isNullOrBlank()) kind else stringResource(R.string.Home_QuickKindJoin, kind, rest)

// ── the rows ──

/**
 * A row of records, which scrolls sideways inside the page. Its heading is above the covers, at the row's cover size.
 */
@Composable
internal fun AlbumRow(heading: String, albums: List<CloudAlbum>, collection: Collection, images: ImageLoader, onOpen: (CloudAlbum) -> Unit) {
    val caption = remember { AlbumCaption.of(ROW_ART) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText(heading, Modifier.padding(horizontal = 20.dp), style = display(19f).copy(color = Color.White))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(albums, key = { it.key }) { album -> AlbumTile(album, collection.cover(album), caption, images) { onOpen(album) } }
        }
    }
}

/** The computer's lists, drawn as a square like a record, at the row's cover size. */
@Composable
internal fun ListsRow(heading: String, lists: List<TrackList>, collection: Collection, images: ImageLoader, onOpen: (TrackList) -> Unit) {
    val art = ROW_ART.dp
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText(heading, Modifier.padding(horizontal = 20.dp), style = display(19f).copy(color = Color.White))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(lists, key = { it.id }) { list ->
                Column(
                    Modifier.width(art).clickable(remember { MutableInteractionSource() }, indication = null) { onOpen(list) },
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    val name = listName(list.name)
                    // A generated list wears its frame here too, as on the playlists view and its page (`FramedList`).
                    PlaylistSleeve(collection.cover(list), Glyph.playlists, art, corner = 10.dp, mark = 26f, images, FramedList.of(list, name))
                    BasicText(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = system(12f, FontWeight.Medium).copy(color = Color.White))
                }
            }
        }
    }
}
