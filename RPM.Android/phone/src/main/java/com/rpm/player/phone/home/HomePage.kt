package com.rpm.player.phone.home

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
import com.rpm.player.phone.design.holdPress
import com.rpm.player.core.library.isExplicit
import com.rpm.player.phone.design.MarkedTitle
import com.rpm.player.phone.design.rememberPicture
import com.rpm.player.phone.design.frostsAtTop
import com.rpm.player.phone.design.tapsToTop
import com.rpm.player.phone.design.ScrollsToTop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
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
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import android.content.Context
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.art.ArtScene
import android.graphics.Bitmap
import com.rpm.player.phone.PhoneApplication
import com.rpm.player.core.library.BannerCard
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.HomeShelf
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.library.Kicker
import com.rpm.player.core.library.LibraryState
import com.rpm.player.core.library.QuickPick
import com.rpm.player.core.library.TrackList
import com.rpm.player.core.frames.FramedList
import com.rpm.player.phone.library.PlaylistSleeve
import com.rpm.player.core.library.listName
import com.rpm.player.core.radio.RadioStation
import com.rpm.player.core.radio.StationCards
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
import com.rpm.player.phone.radio.stationCardWords
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
import com.rpm.player.core.room.Rgb
import com.rpm.player.core.playback.Tapped
import com.rpm.player.phone.ui.LocalNightMode
import com.rpm.player.phone.ui.rememberLead
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.unit.Density
import com.rpm.player.phone.ui.coverSquare

/**
 * The height of the banner's plate, before it reaches up behind the title and the status bar (`HomeBannerMetrics` on
 * iPhone).
 */
private val BANNER = 352.dp
private val BANNER_SLEEVE = 124.dp
private val BANNER_MARGIN = 22.dp

/** The cover size in a row (`HomeRowView.art` on iPhone). */
internal const val ROW_ART = 132.0

/**
 * How deep the banner's reflection runs under the card (`HomeBannerMetrics.reflection` on iPhone): past the dots, the
 * gap to the first row, its heading and its covers, and a little more, so the picture fades into the page and does not
 * stop at an edge. It is the same whatever stands under the card, the quick selection included.
 */
private fun bannerMirror(cards: Int): Dp = ((if (cards > 1) 10 + 5 else 0) + 26 + (23 + 10) + ROW_ART + 18).dp

/** In night mode, the wash over the whole card toward its foot, where the writing stands. */
private val NIGHT_FOOT = Brush.verticalGradient(
    0f to Color.Transparent,
    0.4f to Color.Black.copy(alpha = 0.25f),
    0.68f to Color.Black.copy(alpha = 0.7f),
    1f to Color.Black.copy(alpha = 0.7f),
)

/**
 * Outside night mode, how dark the shade is at the card's foot, and how dark the mirror under the card is. The two must
 * be the same: the foot's last row and the mirror's first row are the same pixels of the photograph, and a different
 * shade on each side shows as a hard line where the card ends.
 */
private const val DAY_SHADE = 0.45f

/**
 * Outside night mode, the shade behind the words at the card's foot: nothing over the upper half, so the photograph shows
 * as the artist page shows it, and enough under the kicker, the title and the artist for white letters with their
 * shadow to read on a light photograph. It ends at [DAY_SHADE], where the mirror ([MIRROR_HELD]) continues it.
 */
private val DAY_FOOT = Brush.verticalGradient(
    0f to Color.Transparent,
    0.5f to Color.Transparent,
    0.72f to Color.Black.copy(alpha = 0.22f),
    1f to Color.Black.copy(alpha = DAY_SHADE),
)

/**
 * Outside night mode, how much the mirror under the banner is darkened, as dark as the card's foot ([DAY_SHADE]).
 * Quick selection's lines are read over it. The mirror fades out further down, and the darkening with it.
 */
private val MIRROR_HELD = ColorFilter.tint(Color.Black.copy(alpha = DAY_SHADE), BlendMode.SrcAtop)

/**
 * How far down the shade at the top of a banner card runs, and how dark it is at the top. The clock and the other
 * status icons, and the page's title, are white and stand on the card; on a light photograph they cannot be read
 * without it.
 */
private val TOP_FADE = 140.dp
private const val TOP_SHADE = 0.45f

/** Where a card's blur starts, as a share of its height: the face is cut into the upper half (`HomeFeatureCard.blurFrom`). */
private const val BANNER_BLUR_FROM = 0.6f

/**
 * The quick selection: sixteen picks, four lines to a panel, with the edge of the next panel showing (`QuickPicks` on
 * iPhone).
 */
private const val QUICK_PICKS = 16
internal const val QUICK_TALL = 4
internal val QUICK_LINE = 60.dp
internal val QUICK_ART = 44.dp
/** How far a station's logo stands in from the edges of its square on a line of the quick selection. */
internal val QUICK_STATION_INSET = 4.dp
internal val PEEK = 32.dp
internal val PANELS_APART = 16.dp

/** The space between the sections of the page. */
internal val SECTIONS_APART = 26.dp

/** What home's taps do: open a record or a list, play songs, tune a station. */
class HomeActions(
    val onOpen: (CloudAlbum) -> Unit,
    val onOpenList: (TrackList) -> Unit,
    /** Plays these tracks from this position: one song from the quick selection, a row from the song tapped in it, or
     * all its songs. */
    val onPlay: (List<CloudTrack>, Int, Tapped) -> Unit,
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
 * @param onReady called once the page has drawn what it opens on: the banner's front card with its photograph looked
 * for, or no banner at all. The launch keeps its wait cover until then.
 * @param quickAdded what the person put on Quick selection themselves ([QuickRef]), newest first. Shown after the
 * suggested mixtapes.
 * @param stationCards the stations with a card picture from the admin. A station's card on the banner shows its picture
 * and description, as on the radio home.
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
    onReady: () -> Unit,
    quickAdded: List<String> = emptyList(),
    stationCards: StationCards = StationCards.NONE,
) {
    val collection = ready.collection
    val home = ready.home
    // The glass on home (the switch key) frosts the page's ground, never the page itself: a key that
    // drew a layer holding itself would nest without end.
    val backdrop = rememberBackdrop()
    // Home needs a row's worth of records before it can say anything (`HomeGate` on iPhone).
    if (collection.albums.size < HomeShelf.ROW_LENGTH) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().backdropSource(backdrop)) { ArtGround(null, images) }
            LaunchedEffect(Unit) { onReady() }
            HomeLock(collection.albums.size, HomeShelf.ROW_LENGTH, lent = collection.shelf != Shelf.Own, Modifier.align(Alignment.Center).padding(bottom = bottomRoom))
            if (onSwitch != null) {
                SwitchKey(backdrop, onSwitch, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 4.dp, end = 16.dp))
            }
        }
        return
    }
    val density = LocalDensity.current
    val banner = remember(home.banner, stations) { HomeShelf.withStation(home.banner, stations.firstOrNull()) }
    val quick = remember(home.quick, stations, quickAdded, collection) {
        QuickRef.merged(HomeShelf.withStations(home.quick, stations), quickAdded, collection, QUICK_PICKS)
    }
    val statusBar = WindowInsets.statusBars.asPaddingValues(density).calculateTopPadding()
    var heading by remember { mutableStateOf(0.dp) }
    var dress by remember { mutableStateOf<BannerDress?>(null) }
    // Where the banner ends, from the page's top, in pixels: the ground's change is measured from it ([leadShare]).
    var bannerFoot by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    ScrollsToTop(scroll)
    // The rows as they scroll, for the frost under the title.
    val rows = rememberBackdrop()
    // The iPhone's band at the top: pulled past its top, the page comes down and the banner grows to cover the gap.
    val pull = rememberTopPull()
    // Read once per change of the answer, not on every frame of a scroll.
    val atTop by remember { derivedStateOf { scroll.value < 1 } }
    // How far the banner reaches up: the title and the status bar. This is known once the title has been measured.
    val overhang = if (banner.isNotEmpty() && heading > 0.dp) heading + statusBar else 0.dp

    // The pictures the rows show before they are swiped, read now, so a card built on scroll starts from memory.
    val context = LocalContext.current
    LaunchedEffect(home, quick, collection, logoStamp) {
        val rowsOfRecords = listOf(home.listened, home.suggested, home.taste, home.played, home.added)
        val art = HomeArt(quick = QUICK_ART, quickStationInset = QUICK_STATION_INSET, row = ROW_ART.dp)
        warmHome(images, warmRequests(context, density, art, quick, rowsOfRecords, home.madeForYou, collection, logoStamp))
    }

    Box(Modifier.fillMaxSize()) {
        val wearing = dress?.wearing
        // A cover's accent tints the ground, as on iPhone (`BannerDress.accent`). A photograph gives none: its colour is
        // the photograph's own.
        val accent = rememberAccent(wearing?.takeIf { it !is Bitmap }, images)
        // The front card's colour, the photograph's or the cover's, as the artist and album pages are led by theirs
        // (`BannerDress.lead` on iPhone). None for a card on a room, and none in night mode, where a ground has no lead.
        val lead = rememberLead(wearing?.takeIf { dress?.roomed == false && !LocalNightMode.current }, images)?.second
        // How far the page is through the ground's change. Only the two layers' graphicsLayer reads it, so a scroll
        // redraws their opacity and nothing else (`LeadsOnScroll` on iPhone).
        val top = if (heading > 0.dp) statusBar + heading else 0.dp
        val share: Density.() -> Float = { leadShare(scroll.value.toFloat(), bannerFoot.toFloat(), top.toPx()) }
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
            // The ground on the front card's picture. With a colour coming in over it, it is taken away once covered,
            // so its picture is not drawn under an opaque layer.
            Box(Modifier.fillMaxSize().then(if (lead != null) Modifier.graphicsLayer { alpha = if (share() >= 1f) 0f else 1f } else Modifier)) {
                ArtGround(wearing, images, accent, colours = dress?.colours.orEmpty())
            }
            // **Lower down the page, the card's colour leads the ground**, with its picture faint over it, as on the
            // artist page and the album page. The blurred picture is the ground only while the banner is in view. Its
            // blur is the one the layer under it already made, kept by the image loader: no new blur is drawn.
            if (lead != null) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = share() }) { ArtGround(wearing, images, lead = lead) }
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .dissolvesAtTop(statusBar + heading, frosted = true) { scroll.value.toFloat() }
                .backdropSource(rows)
                .verticalScroll(scroll, overscrollEffect = pull)
                .padding(bottom = FOOT + bottomRoom),
            verticalArrangement = Arrangement.spacedBy(SECTIONS_APART),
        ) {
            if (banner.isNotEmpty()) {
                // A station's card as the radio home draws it: the admin's card picture and description.
                val radioKicker = stringResource(R.string.Rpm_Tab_Radio)
                Banner(
                    banner, collection, images, logoStamp, overhang, statusBar, turns = onScreen && atTop, pull, roomsRun = onScreen,
                    onDress = { dress = it }, onReady = onReady, actions = actions, Modifier.onSizeChanged { bannerFoot = it.height },
                    stationWords = { stationCardWords(it, radioKicker) },
                    stationPicture = { stationCards.addressOf(it) },
                )
            } else {
                Spacer(Modifier.height(heading + statusBar))
                // No banner to wait for.
                LaunchedEffect(Unit) { onReady() }
            }
            if (quick.isNotEmpty()) PicksRow(stringResource(R.string.Home_QuickPicks), quick, collection, images, logoStamp, actions, SongLine.Artist, SongTap.ThatSong)
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
        // What scrolls up under the title, frosted as it goes, over the rows fading out there.
        Box(Modifier.fillMaxWidth().height(statusBar + heading + 30.dp).frostsAtTop(rows, statusBar + heading) { scroll.value.toFloat() })
        // **The title's band takes every touch**, so a row or the banner under the title cannot be opened or played
        // through it, and a tap on it takes home back to its top ([tapsToTop]). Only the switch key, drawn over this
        // band, answers otherwise. The strip below, where the rows fade out, stays open.
        Box(Modifier.fillMaxWidth().height(statusBar + heading).tapsToTop())
        // The title is drawn over the scroll. It does not move with the scroll. It sits on the same line as the title
        // of every other page.
        PageTitle(
            stringResource(R.string.Home_Heading),
            Modifier
                .statusBarsPadding()
                .padding(start = 20.dp, end = 16.dp, bottom = 10.dp)
                .onSizeChanged { heading = with(density) { it.height.toDp() } + 10.dp },
        )
        // On the title's line, where the library's keys are, 16 from the edge. A layer of its own, over the title's band,
        // so this key takes the taps the band keeps from the page.
        if (onSwitch != null) {
            SwitchKey(backdrop, onSwitch, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 4.dp, end = 16.dp))
        }
    }
}

/**
 * What the banner's front card dresses the page in: the picture it wears, its photograph or its cover, or, for a card that
 * stands on a room, the room's colours and no picture. A card on a room gives the page no leading colour (`BannerDress`
 * on iPhone).
 */
internal data class BannerDress(val wearing: Any?, val roomed: Boolean, val colours: List<Rgb> = emptyList()) {
    companion object {
        /**
         * A card on its room: no picture, and the room's colours from the top of the page to its foot, the vivid accent
         * at the top through the main tone to the room's dark ground (`BannerDress.colours` on iPhone).
         */
        fun onRoom(room: RoomPalette) = BannerDress(null, roomed = true, colours = listOf(room.glow, room.tint, room.ground))
    }
}

/**
 * What a station's banner card says over its title and under it, where the page has its own words for it. The radio
 * home says why the station is there; home says nothing more than that it is a station. [lines] is how many lines
 * [line] may take: two for the station's description, which the admin keeps to 80 characters, one for anything else.
 */
internal class BannerWords(val kicker: String, val line: String?, val lines: Int = 1)

/**
 * A station's card picture ([com.rpm.player.core.radio.StationCards]) read to fill a card [width] by [height] pixels,
 * or null where it could not be read. Not hardware-backed, so [RunOff] can draw it. The card and the banner's reading
 * ahead make the same request, so a picture read ahead is found in the image loader's caches.
 */
private suspend fun readStationPicture(context: Context, images: ImageLoader, address: String, width: Int, height: Int): Bitmap? {
    if (width <= 0 || height <= 0) return null
    val request = ImageRequest.Builder(context).data(address).size(width, height).scale(Scale.FILL).allowHardware(false).build()
    return (images.execute(request) as? SuccessResult)?.image?.toBitmap()
}

/** Where the ground starts to move to the card's colour: when this share of the way to the quick selection is scrolled. */
private const val LEAD_FROM = 0.4f

/**
 * How far through the ground's change the page has scrolled, from 0 to 1 (`LeadsOnScroll.share` and `HomePage.leadRange`
 * on iPhone). It starts when the banner is 40% of the way off and ends when the banner's foot reaches the title, where
 * the quick selection is read. All in pixels: [scrolled] from the top, [bannerFoot] from the page's top, and [top] the
 * status bar and the title. It is 0 until the banner and the title are measured (either 0), and the picture stays the
 * ground until then.
 */
internal fun leadShare(scrolled: Float, bannerFoot: Float, top: Float): Float {
    if (bannerFoot <= 0f || top <= 0f) return 0f
    val end = maxOf(1f, bannerFoot - top)
    val start = end * LEAD_FROM
    return ((scrolled - start) / (end - start)).coerceIn(0f, 1f)
}

/** How long after the banner is drawn the other cards' photographs start to be cut: after the front card's own, so the
 * two do not compete for the network. */
private const val PRELOAD_AFTER_MS = 2_500L

/** A banner card's photograph as cut for [scene], and the run-off baked from it for a card of that size. */
private class HeldPhoto(val scene: ArtScene, val photo: Bitmap, val runOff: ImageBitmap, val width: Dp, val plate: Dp, val mirror: Dp, val night: Boolean) {
    /** The run-off, if it was baked from [picture] at this size, for the same night mode. */
    fun bakedFor(picture: Bitmap?, width: Dp, plate: Dp, mirror: Dp, night: Boolean): ImageBitmap? =
        runOff.takeIf { picture === photo && width == this.width && plate == this.plate && mirror == this.mirror && night == this.night }
}

/** How long a turn waits at most for the next card's photograph to be cut, after the card in front has had its time. */
private const val NEXT_WAITS_AT_MOST_MS = 10_000L

/** How long a banner card's photograph and run-off take to fade in once both are ready. */
private const val PHOTO_IN_MS = 300

/** How long a banner card stays in front before the next one turns in (`BannerDwell.seconds` on iPhone). */
private const val DWELL_MS = 7_000L

// ── the banner ──

/**
 * Cards across the top of the page, one screen wide. They are swiped one card at a time, with a dot under them
 * for each card. The card in front sets the page's colours.
 */
@Composable
internal fun Banner(
    cards: List<BannerCard>,
    collection: Collection,
    images: ImageLoader,
    logoStamp: String,
    overhang: Dp,
    statusBar: Dp,
    turns: Boolean,
    pull: TopPull,
    roomsRun: Boolean,
    onDress: (BannerDress) -> Unit,
    onReady: () -> Unit,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    stationWords: ((RadioStation) -> BannerWords)? = null,
    stationPicture: ((RadioStation) -> String?)? = null,
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
    // Which cards have their photograph and run-off in, or have none to wait for, by card.
    //
    // This map, and the run-offs and rooms below, are NOT started again when the list of cards changes: each card reports
    // only when its own state changes, so a card that stays where it is would never report again, and its reflection
    // would be lost. That happens on the radio home, whose local stations arrive after the first cards are drawn. A card
    // that is replaced reports its own state, which takes the old card's place.
    val cardReady = remember { mutableStateMapOf<Int, Boolean>() }
    LaunchedEffect(ring, turns, pager) {
        if (!ring || !turns) return@LaunchedEffect
        snapshotFlow { pager.settledPage to pager.isScrollInProgress }.collectLatest { (at, moving) ->
            if (moving) return@collectLatest
            delay(DWELL_MS)
            // Not onto a card whose photograph is still being cut, the first time round after a launch: it would turn in
            // bare and fill in under the person's eyes. Waited for, not for ever.
            withTimeoutOrNull(NEXT_WAITS_AT_MOST_MS) { snapshotFlow { cardReady[(at + 1) % cards.size] == true }.first { it } }
            stepping.launch { pager.animateScrollToPage(at + 1) }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // The photograph is cut for the height the card is drawn at, including the overhang. If it were cut for
            // the plate alone and then stretched to the taller card, a face in the clear area would be pushed up behind
            // the status bar.
            val scene = remember(maxWidth, overhang, statusBar) { bannerScene(maxWidth, overhang, statusBar) }
            // The other cards' photographs are cut ahead, one at a time, once the front card has had its turn, so a card
            // that turns in has its photograph already (`HomeBannerView.preload` on iPhone).
            val portraits = (LocalContext.current.applicationContext as PhoneApplication).portraits
            // A station's card picture is read ahead the same way, at the card's size.
            val context = LocalContext.current
            val density = LocalDensity.current
            LaunchedEffect(cards, scene, overhang > 0.dp) {
                if (overhang <= 0.dp) return@LaunchedEffect
                delay(PRELOAD_AFTER_MS)
                for (card in cards.drop(1)) {
                    val address = (card as? BannerCard.OfStation)?.station?.let { stationPicture?.invoke(it) }
                    if (address != null) {
                        with(density) { readStationPicture(context, images, address, maxWidth.roundToPx(), (BANNER + overhang).roundToPx()) }
                        continue
                    }
                    val record = (card as? BannerCard.OfAlbum)?.album?.takeIf { it.artist.isNotBlank() } ?: continue
                    portraits.portrait(record.artist, record.album, scene)
                }
            }
            val plate = BANNER + overhang
            val mirror = bannerMirror(cards.size)
            val platePx = with(LocalDensity.current) { plate.toPx() }
            // Each card's photograph as baked for its run-off and reflection ([RunOff]), by card. The strip draws the
            // reflections from here, under the dots and the rows.
            val runOffs = remember { mutableStateMapOf<Int, ImageBitmap>() }
            // Each card's photograph and run-off once made, by card, so a card that turns in again starts with them and
            // does not cut and bake them again in front of the person (Chivan, 2026-11-04: "the photo loads in again
            // every time"). The pager lets go of a card once it is out of view.
            val held = remember(cards) { mutableMapOf<Int, HeldPhoto>() }
            // Each card's room, by card, for a card that stands on one: the strip turns it over under the card as its
            // reflection (`RoomView.fold` on iPhone).
            val rooms = remember { mutableStateMapOf<Int, PaintedRoom>() }
            // Scaled up about the card's foot while the page is pulled down past its top, so the picture zooms and the
            // page's ground does not show above it (`growsWithPull` on iPhone). The whole strip is scaled, because a
            // pager clips its pages to itself; the writing is scaled back (`keepsSize`), so only the picture grows.
            // The next card is made while the one before it is in front, so it turns in whole.
            HorizontalPager(pager, Modifier.fillMaxWidth().height(plate).growsWithPull(pull, platePx, TransformOrigin(0.5f, 1f)), beyondViewportPageCount = 1) { page ->
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
                    held = held[card],
                    onHeld = { held[card] = it },
                    onWhole = { cardReady[card] = it },
                    onRunOff = { baked -> if (baked != null) runOffs[card] = baked else runOffs.remove(card) },
                    onRoom = { room, here -> if (here) rooms[card] = room else if (rooms[card] === room) rooms.remove(card) },
                    onDress = onDress, onReady = onReady, actions = actions, stationWords = stationWords,
                    cardPicture = (cards[card] as? BannerCard.OfStation)?.station?.let { stationPicture?.invoke(it) },
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
 *
 * A station's card with a card picture from the admin ([cardPicture], its address) shows that picture in place of the
 * room, drawn as a record's photograph is: blurred and mirrored at the foot, with the same shade behind the words. It
 * fills the card from its middle. Where it cannot be read, the card stands on its room.
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
    held: HeldPhoto?,
    onHeld: (HeldPhoto) -> Unit,
    onWhole: (Boolean) -> Unit,
    onRunOff: (ImageBitmap?) -> Unit,
    onRoom: (PaintedRoom, Boolean) -> Unit,
    onDress: (BannerDress) -> Unit,
    onReady: () -> Unit,
    actions: HomeActions,
    stationWords: ((RadioStation) -> BannerWords)?,
    cardPicture: String?,
) {
    val album = (card as? BannerCard.OfAlbum)?.album
    val list = (card as? BannerCard.OfList)?.list
    val station = (card as? BannerCard.OfStation)?.station
    val cover: Any? = album?.let { collection.cover(it) } ?: list?.let { collection.cover(it) }
    val portraits = (LocalContext.current.applicationContext as PhoneApplication).portraits
    // The artist's square photograph, if its face is clear of the frame. Otherwise the backdrop, cropped around the
    // faces. This is what the iPhone's card asks for (`BackdropStore.portrait`).
    // Whether the photograph has been looked for, found or not: the front card is drawn once it has (`onReady`).
    var looked by remember(album?.key, scene, cardPicture) { mutableStateOf(false) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val photo by produceState(held?.takeIf { it.scene == scene }?.photo, album?.key, scene, cardPicture) {
        if (cardPicture != null) {
            // Read once the frame is known, as a photograph is cut, at the size the banner reads it ahead.
            if (scene == null) return@produceState
            value = with(density) { readStationPicture(context, images, cardPicture, width.roundToPx(), plate.roundToPx()) }
            looked = true
            return@produceState
        }
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
    // Outside night mode the photograph is baked as it is, as the artist page bakes its band: the words get their own
    // shade over it ([DAY_FOOT]). In night mode it is baked darkened.
    val night = LocalNightMode.current
    val runOff by produceState(held?.bakedFor(photo, width, plate, mirror, night), photo, width, plate, mirror, night) {
        val picture = photo ?: return@produceState
        held?.bakedFor(picture, width, plate, mirror, night)?.let { value = it; return@produceState }
        value = withContext(Dispatchers.Default) {
            RunOff.bake(picture, width.value, plate.value, mirror.value, BANNER_BLUR_FROM, RunOff.Reflection.Blurred, darkened = night)
        }
    }
    LaunchedEffect(photo, runOff) {
        val picture = photo
        val baked = runOff
        if (scene != null && picture != null && baked != null) onHeld(HeldPhoto(scene, picture, baked, width, plate, mirror, night))
    }
    LaunchedEffect(runOff) { onRunOff(runOff) }
    // Ready once the photograph and its run-off are both in, so the front card comes in whole: the run-off is baked a
    // fifth of a second after the photograph, and a card shown between the two has no blur or fade at its foot.
    LaunchedEffect(live, looked, runOff) { if (live && looked && (photo == null || runOff != null)) onReady() }
    LaunchedEffect(looked, photo, runOff) { onWhole(looked && (photo == null || runOff != null)) }

    val words = station?.let { stationWords?.invoke(it) }
    val kicker = when (card) {
        is BannerCard.OfAlbum -> stringResource(kickerOf(card.kicker))
        is BannerCard.OfList -> stringResource(R.string.Home_MadeForYou)
        is BannerCard.OfStation -> words?.kicker ?: stringResource(R.string.Rpm_Tab_Radio)
    }
    val title = album?.album ?: list?.let { listName(it.name) } ?: station?.display.orEmpty()
    // A generated list's card stands on the room in the list's own colours, and its sleeve wears the list's frame, as
    // on the playlists view (`HomeBannerView`'s list card on iPhone).
    val framed = list?.let { FramedList.of(it, title) }
    // A list's card and a station's without a card picture stand on their room; a record's card, and a station's with
    // one, stand on it once the picture has been looked for and not found (`HomeFeatureCard.standingOnARoom`). The room
    // is in a generated list's own colours, or in those of the card's cover or the station's logo. It moves only on the
    // card in front, while home is showing.
    val roomed = list != null || (station != null && cardPicture == null) || (looked && photo == null)
    val colours = rememberRoomColours(if (roomed && framed == null) station?.let { StationLogo.of(it, logoStamp) } ?: cover else null, images)
    // A card on its room dresses the page in the room's colours once they are known, so the page does not flash the
    // fallback blue first. Any other card dresses it in its picture.
    val room = if (roomed) framed?.look?.room ?: colours else null
    LaunchedEffect(live, wearing, roomed, room) {
        if (!live) return@LaunchedEffect
        when {
            room != null -> onDress(BannerDress.onRoom(room))
            !roomed && wearing != null -> onDress(BannerDress(wearing, roomed = false))
        }
    }
    val seed = album?.key ?: station?.uuid ?: title
    val subtitle = album?.artist ?: words?.line
    // The photograph and its run-off come in together, faded in: drawn apart, the photograph shows for a fifth of a
    // second with no blur or fade at the card's foot (Chivan, 2026-11-04: the front card at launch "lost its blur and
    // popped it back in").
    val whole = photo != null && runOff != null
    val photoIn by animateFloatAsState(if (whole) 1f else 0f, tween(PHOTO_IN_MS), label = "photo")

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
            whole -> BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = photoIn }) {
                AsyncImage(
                    model = rememberPicture(photo, maxWidth, maxHeight),
                    contentDescription = null,
                    imageLoader = images,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // The photograph is held darker in night mode only, as on the artist page.
                if (night) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = RunOff.HELD)))
            }
        }
        // In night mode, darker towards the foot over the whole card, under the baked blur, which carries the same wash.
        if (night) Box(Modifier.fillMaxSize().background(NIGHT_FOOT))
        // The blur toward the foot, over the sharp photograph: the plate's part of the baked run-off.
        runOff?.takeIf { whole }?.let { baked ->
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = photoIn }.drawBehind {
                    val rows = (plate.value * RunOff.SCALE).roundToInt().coerceAtMost(baked.height)
                    drawImage(baked, srcSize = IntSize(baked.width, rows), dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
                },
            )
        }
        // Outside night mode, a shade only behind the words at the foot, over the blur, so they read on any photograph
        // while the face above them is shown as it is.
        if (!night) Box(Modifier.fillMaxSize().background(DAY_FOOT))
        // A shade at the top, under the status bar and the page's title, so they read on a light photograph.
        Box(
            Modifier.fillMaxSize().drawBehind {
                val deep = TOP_FADE.toPx().coerceAtMost(size.height)
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = TOP_SHADE),
                        0.45f to Color.Black.copy(alpha = TOP_SHADE * 0.55f),
                        1f to Color.Transparent,
                        startY = 0f,
                        endY = deep,
                    ),
                    size = Size(size.width, deep),
                )
            },
        )
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
                    BasicText(subtitle, maxLines = words?.lines ?: 1, overflow = TextOverflow.Ellipsis, style = system(13f).copy(color = Ink.white(0xD1), shadow = shadow))
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
    // Outside night mode the bake is not darkened, so the mirror is held down here: Quick selection's lines are read over
    // it. In night mode the bake already carries the darkening.
    val night = LocalNightMode.current
    val quiet = if (night) null else MIRROR_HELD
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
                        // Outside night mode a room's mirror is held as a photograph's is ([MIRROR_HELD]), as dark as
                        // the card's foot ([DAY_FOOT]), so no line shows where they meet.
                        rooms[page.mod(cards)]?.let { if (night) roomReflection(it, x, platePx) else roomReflection(it, x, platePx) { DAY_SHADE } }
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
                        colorFilter = quiet,
                    )
                }
            },
    )
}

@Composable
private fun BannerSleeve(cover: Any?, station: RadioStation?, list: Boolean, framed: FramedList?, logoStamp: String, images: ImageLoader) {
    // The rounding every cover has, from its size (CoverCorner).
    val shape = coverSquare(BANNER_SLEEVE)
    if (framed != null) {
        Box(Modifier.size(BANNER_SLEEVE).shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))) {
            PlaylistSleeve(cover, Glyph.playlists, BANNER_SLEEVE, mark = 34f, images, framed)
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
                    model = rememberPicture(cover, BANNER_SLEEVE),
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
 * What a tap on one song's line plays (`HomePicksRow.SongTap` on iPhone).
 *
 * **Stated by every page, with no default**, because the desktop does both. Its home page plays the one song: the quick
 * selection is a mix of unrelated things. An artist's popular songs and a genre's most played songs play the row from
 * the song that was tapped, so the row becomes the queue.
 */
enum class SongTap {
    /** That song alone. */
    ThatSong,

    /** Every song in the row, starting with that one. */
    TheRowFromIt,
}

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
    songTap: SongTap,
) {
    val songs = picks.filterIsInstance<QuickPick.Song>().map { it.track }
    // A tap on a song's line ([SongTap]).
    val onSong: (CloudTrack) -> Unit = { track ->
        when (songTap) {
            SongTap.ThatSong -> actions.onPlay(listOf(track), 0, Tapped.List)
            SongTap.TheRowFromIt -> actions.onPlay(songs, songs.indexOfFirst { it.rpmId == track.rpmId }.coerceAtLeast(0), Tapped.Song)
        }
    }
    val haptics = rememberHaptics()
    val panels = picks.chunked(QUICK_TALL)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.height(34.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(heading, style = display(19f).copy(color = Color.White))
            if (songs.isNotEmpty()) {
                Box(
                    Modifier.size(34.dp).clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { haptics.pick(); actions.onPlay(songs, 0, Tapped.List) },
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
                Column { for (pick in panels[page]) QuickLine(pick, collection, images, logoStamp, actions, songLine, onSong) }
            }
        }
    }
}

@Composable
private fun QuickLine(pick: QuickPick, collection: Collection, images: ImageLoader, logoStamp: String, actions: HomeActions, songLine: SongLine, onSong: (CloudTrack) -> Unit) {
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
        is QuickPick.Song -> { { onSong(pick.track) } }
        is QuickPick.OfAlbum -> { { actions.onOpen(pick.album) } }
        is QuickPick.OfStation -> { { actions.onTune(pick.station) } }
        is QuickPick.OfList -> { { actions.onOpenList(pick.list) } }
        is QuickPick.Radio -> { { actions.onRadio(pick.track) } }
    }
    val presses = remember { MutableInteractionSource() }
    val holds = LocalHolds.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(QUICK_LINE)
            // Every line has a menu on a long press. A song has the song menu, as on iPhone, and so has a mixtape: it is
            // named for its song, and holding it offers that song. A record and a list have theirs ([Holds]); a station
            // has none.
            .then(
                when {
                    pick is QuickPick.Song -> Modifier.songPress(pick.track, presses) { onClick?.invoke() }
                    pick is QuickPick.Radio -> Modifier.songPress(pick.track, presses) { onClick?.invoke() }
                    pick is QuickPick.OfAlbum && holds != null -> Modifier.holdPress(presses, { onClick?.invoke() }) { holds.album(pick.album) }
                    pick is QuickPick.OfList && holds != null -> Modifier.holdPress(presses, { onClick?.invoke() }) { holds.list(pick.list) }
                    else -> Modifier.clickable(presses, indication = null, enabled = onClick != null) { onClick?.invoke() }
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The rounding every cover has, from its size (CoverCorner).
        val shape = coverSquare(QUICK_ART)
        // A generated list's line wears the list's frame, as its square does everywhere else.
        val framed = (pick as? QuickPick.OfList)?.let { FramedList.of(it.list, title) }
        if (framed != null) PlaylistSleeve(picture, Glyph.playlists, QUICK_ART, mark = 15f, images, framed)
        // A station's logo on its made ground, in the cover's square (`HomePicksRow.stationSquare` on iPhone).
        else if (pick is QuickPick.OfStation) Box(Modifier.size(QUICK_ART).clip(shape).background(Color.Black.copy(alpha = 0x22 / 255f))) {
            StationFace(pick.station, logoStamp, images, QUICK_ART, inset = QUICK_STATION_INSET, Modifier.fillMaxSize()) {
                BasicText(Glyph.radio, style = icon(15f).copy(color = Ink.white(0x3A)))
            }
        }
        else Box(Modifier.size(QUICK_ART).clip(shape).background(Color.Black.copy(alpha = 0x22 / 255f)), contentAlignment = Alignment.Center) {
            BasicText(glyph, style = icon(15f).copy(color = Ink.white(0x3A)))
            if (picture != null) {
                AsyncImage(
                    model = rememberPicture(picture, QUICK_ART),
                    contentDescription = null,
                    imageLoader = images,
                    contentScale = if (pick is QuickPick.OfStation) ContentScale.Fit else ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = if (pick is QuickPick.OfStation) Modifier.fillMaxSize().padding(4.dp) else Modifier.fillMaxSize(),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            // The E on a song and on a record. A mixtape is a radio and not the song it starts from, so it has none.
            val explicit = when (pick) {
                is QuickPick.Song -> pick.track.isExplicit
                is QuickPick.OfAlbum -> pick.album.isExplicit
                else -> false
            }
            MarkedTitle(title, explicit, system(14f, FontWeight.Medium).copy(color = Color.White))
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
                val holds = LocalHolds.current
                Column(
                    Modifier.width(art).holdPress(remember { MutableInteractionSource() }, { onOpen(list) }, holds?.let { { it.list(list) } }),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    val name = listName(list.name)
                    // A generated list wears its frame here too, as on the playlists view and its page (`FramedList`).
                    PlaylistSleeve(collection.cover(list), Glyph.playlists, art, mark = 26f, images, FramedList.of(list, name))
                    BasicText(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = system(12f, FontWeight.Medium).copy(color = Color.White))
                }
            }
        }
    }
}
