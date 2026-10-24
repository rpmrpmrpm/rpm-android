package com.rpm.player.tv.ui

import com.rpm.player.core.library.listName
import com.rpm.player.core.playback.Queued
import com.rpm.player.core.playback.StationPlaying
import com.rpm.player.core.playback.tune
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import com.rpm.player.core.motion.LocalMotion
import com.rpm.player.core.motion.Motion
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.transformations
import com.rpm.player.core.R
import com.rpm.player.core.library.Artwork
import com.rpm.player.core.library.BannerCard
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.Home
import com.rpm.player.core.library.ListName
import com.rpm.player.core.library.QuickPick
import com.rpm.player.core.library.HomeShelf
import com.rpm.player.core.library.TrackList
import com.rpm.player.core.radio.RadioStation
import com.rpm.player.core.radio.StationBlurbs
import com.rpm.player.core.radio.StationLogo
import com.rpm.player.core.room.RoomPalette
import com.rpm.player.core.room.Rgb
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A quick song as the queue holds it: its record's names, cover and disc. */
fun queuedOf(song: QuickPick.Song, collection: Collection): Queued {
    val album = song.album
    return Queued(collection.shelf, song.track, album?.album.orEmpty(), album?.artist.orEmpty(), album?.let(collection::cover), album?.let(collection::disc))
}

/** A radio's song as the queue holds it, as [queuedOf] a quick song. */
fun queuedOf(radio: QuickPick.Radio, collection: Collection): Queued = queuedOf(QuickPick.Song(radio.track, radio.album), collection)

/** What home asks the app to do when a card is chosen. */
class HomeActions(
    val openAlbum: (CloudAlbum) -> Unit,
    val openList: (TrackList) -> Unit,
    val playSong: (QuickPick.Song) -> Unit,
    val playSongs: (List<QuickPick.Song>) -> Unit,
    val tune: (RadioStation) -> Unit,
    val openPlayer: () -> Unit,
    /** A radio entry chosen: its song plays and the queue keeps filling with songs like it. */
    val startRadio: (QuickPick.Radio) -> Unit = {},
)

/**
 * Pegasus's home: the desktop's HomeView as Pegasus shows it (HomeView.xaml, PegasusChrome.Home.cs).
 * Top to bottom: the banner, quick selection, Made for you, then the record rows. Content runs from 34
 * to `W - 36` desktop pixels, starting 64 down; each section is a heading, 8, its cards, 26.
 *
 * The remote centres the row it moves to (RemoteScroll.cs); within a row it only reveals the card,
 * keeping 24 of air. The focus starts on the first quick card. The page has no key for the side menu: the
 * remote's Menu key and Left held open it (RpmApp). The page tells [mini] whether it is
 * at the top. A held OK on a quick song opens the track's menu, the album page's ([trackChoices]); the Menu key on home
 * opens the side menu, wherever the focus is (RpmApp).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    collection: Collection,
    home: Home,
    images: ImageLoader,
    artwork: Artwork,
    radio: HomeRadio,
    mini: MiniPlayerState,
    actions: HomeActions,
    tracks: TrackActions,
) {
    val quickTops = remember { QuickTops() }
    val menu = rememberRowMenu()
    // The stations heard most join the banner and quick selection, as the desktop's radio card and
    // station picks do; they change as listening is counted, so they are added here, not in the shelf.
    val bannerCards = remember(home.banner, radio.stations) { HomeShelf.withStation(home.banner, radio.stations.firstOrNull()) }
    val quick = remember(home.quick, radio.stations) { HomeShelf.withStations(home.quick, radio.stations).take(QUICK_SHOWN) }
    val banner = remember { FocusRequester() }

    if (collection.albums.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Message(stringResource(R.string.Tv_Library_Empty))
        }
        return
    }

    // Every row stays composed once it has been, and the rows ahead are composed while nothing moves: a row
    // composed as it scrolls into view holds the main thread 50 to 133 ms on a low-end TV box, and a row scrolled
    // away would otherwise be composed again on the way back, which makes scrolling choppy during playback.
    // Home has eight rows at most.
    val list = rememberLazyListState(cacheWindow = LazyLayoutCacheWindow(aheadFraction = HOME_KEPT, behindFraction = HOME_KEPT))
    // At the top within the scroll a first move into quick selection makes to centre it, as the desktop's
    // mini player stays up over a page its remote has nudged (PlayerAway hides it on a scroll down).
    val nudge = with(LocalDensity.current) { NUDGE.roundToPx() }
    val atTop = remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset <= nudge } }
    // The screen in front hands the mini player its scroll answer, which it reads live; one kept under it does not
    // (LocalOnTop).
    val onTop = LocalOnTop.current
    SideEffect { if (onTop) mini.follow(atTop) }

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides CentreRows) {
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(start = PAGE_START, end = PAGE_END, top = 64.px, bottom = 18.px),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (bannerCards.isNotEmpty()) {
                    item(key = "banner") {
                        // Down from the banner goes to the top entry of Quick selection's leftmost column on
                        // screen, not to whichever is nearest under the banner: the first entry while the strip is
                        // at its start. Worked out when Down is pressed ([QuickTops.onScreen]): once the strip is
                        // scrolled sideways the first entry is not composed, and a move named at it goes nowhere.
                        val down = Modifier.focusProperties { if (quick.isNotEmpty()) down = quickTops.onScreen() }
                        Banner(bannerCards, collection, images, artwork, radio, Modifier.focusRequester(banner).then(down)) { card ->
                            when (card) {
                                is BannerCard.OfAlbum -> actions.openAlbum(card.album)
                                is BannerCard.OfList -> actions.openList(card.list)
                                is BannerCard.OfStation -> actions.tune(card.station)
                            }
                        }
                        Spacer(Modifier.height(26.px))
                    }
                }
                if (quick.isNotEmpty()) {
                    item(key = "quick") {
                        QuickSelection(quick, collection, images, radio, actions, tracks, menu, quickTops)
                        Spacer(Modifier.height(26.px))
                    }
                }
                if (home.madeForYou.isNotEmpty()) {
                    item(key = "lists") {
                        Heading(stringResource(R.string.Home_MadeForYou))
                        CardRow(home.madeForYou, key = { it.id }, ahead = null) { item ->
                            ListCard(item, collection, images) { actions.openList(item) }
                        }
                        Spacer(Modifier.height(26.px))
                    }
                }
                albumRow("listened", R.string.Home_Listened, home.listened, collection, images, actions)
                albumRow("suggested", R.string.Home_Suggested, home.suggested, collection, images, actions)
                albumRow("taste", R.string.Home_Taste, home.taste, collection, images, actions)
                albumRow("played", R.string.Home_Played, home.played, collection, images, actions)
                albumRow("added", R.string.Home_Added, home.added, collection, images, actions)
            }
        }
    }

    RowMenu(menu)

    // The focus starts on the banner, unlit until a key is pressed (LocalFocusVisibility), so the page opens
    // at its top with the banner whole. A start on the first quick entry would scroll the page to centre
    // quick selection and cut the banner off.
    LaunchedEffect(Unit) {
        runCatching { if (bannerCards.isNotEmpty()) banner.requestFocus() else quickTops.of(0).requestFocus() }
    }
}

/** How many screens of home's rows are kept composed ahead of and behind what is on screen: all of them. */
private const val HOME_KEPT = 3f

/** Quick selection's columns, all on the screen, and the entries it shows: four columns of four, as the desktop
 * shows them and as many as the iPhone holds. */
private const val QUICK_ACROSS = 4
private const val QUICK_SHOWN = QUICK_ACROSS * HomeShelf.QUICK_DOWN

/** How far the page may be scrolled and still count as at the top for the mini player. */
private val NUDGE = 120.px

/** Home's margins: the headings and the cards' pictures start 34 in from the screen's left edge and end 36
 * in from its right. */
private val PAGE_START = 34.px
private val PAGE_END = 36.px

/**
 * What is left of the margins beside a row outdented by a card's padding. A row is widened by it to the screen's
 * edges ([bleed]) and padded by it, so its cards rest where they did, a focused card at either end grows past the
 * margin instead of being cut by the row's edge, and cards scrolled past the end go out at the screen's edge.
 */
private val ROW_BLEED_START = PAGE_START - 8.px
private val ROW_BLEED_END = PAGE_END

/** The room a card row keeps above and below its cards, inside its dissolve's layer: a focused card grows by
 * [CARD_GROW] about its centre, 12 above and below a home card, and the layer would cut its last line. */
private val ROW_GROW_ROOM = 16.px

/** The page's scroll on a remote move: the element moved to is centred, as RemoteScroll does. */
@OptIn(ExperimentalFoundationApi::class)
internal val CentreRows = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset + size / 2 - containerSize / 2
}

/** A row's scroll on a remote move: only enough to show the card, with [start] to spare before it and [end]
 * after it, each measured from the row's own edge, padding included. */
@OptIn(ExperimentalFoundationApi::class)
private class Reveal(private val start: Float, private val end: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        size + start + end > containerSize || offset < start -> offset - start
        offset + size > containerSize - end -> offset + size - containerSize + end
        else -> 0f
    }
}

/** The size of a section heading on home, the playlists screen and the radio screen: 22·s, as the radio
 * screen's headings are, so the two screens match. */
val HEADING_TEXT = 22.ts

/** A section heading: [HEADING_TEXT] SemiBold in [Ink.Card] (`#F2FFFFFF`), as the radio screen's are,
 * then 12·s to the cards. */
@Composable
fun Heading(text: String) {
    Text(text, style = ui(HEADING_TEXT, FontWeight.SemiBold), color = Ink.Card, modifier = Modifier.padding(bottom = 12.s))
}

/** Moves a block [by] to the left and gives it that much more width, so a card's own padding lines its
 * picture up with the heading above it (the desktop's `Margin="-8,0,0,0"`). */
fun Modifier.outdent(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val wider = if (constraints.hasBoundedWidth) constraints.copy(maxWidth = constraints.maxWidth + extra, minWidth = constraints.minWidth + extra) else constraints
    val placeable = measurable.measure(wider)
    layout((placeable.width - extra).coerceAtLeast(0), placeable.height) { placeable.place(-extra, 0) }
}

/** Widens a block by [start] on the left and [end] on the right and makes it [vertical] taller above and below,
 * past its parent's padding, without moving its own place: the parent sees the size it gave and the block's
 * own content height. Pair it with the same content padding inside. */
fun Modifier.bleed(start: Dp, end: Dp, vertical: Dp): Modifier = layout { measurable, constraints ->
    val left = start.roundToPx()
    val right = end.roundToPx()
    val v = vertical.roundToPx()
    var wider = constraints
    if (wider.hasBoundedWidth) wider = wider.copy(maxWidth = wider.maxWidth + left + right, minWidth = wider.minWidth + left + right)
    if (wider.hasBoundedHeight) wider = wider.copy(maxHeight = wider.maxHeight + v * 2, minHeight = wider.minHeight + v * 2)
    val placeable = measurable.measure(wider)
    layout((placeable.width - left - right).coerceAtLeast(0), (placeable.height - v * 2).coerceAtLeast(0)) { placeable.place(-left, -v) }
}

// ── the banner ──

/**
 * The banner (HomeBanner.cs, HomeView.xaml FeatureFaceTemplate): a plate `min(0.68 strip, 760)` by 352
 * in the middle, the cards before and after it peeking out at the sides, 22 shorter, under it; arrows
 * at the outer edges and a row of dots under it. Every figure is at the banner's scale, `s`.
 *
 * Left and Right step it, sliding the whole strip in from 180 over 220 ms; it steps itself every 7
 * seconds while the remote is not on it, or is on it unlit as the app opens ([LocalFocusVisibility]).
 * It is one stop for the remote, the plate itself; OK
 * opens the card.
 */
@Composable
private fun Banner(
    cards: List<BannerCard>,
    collection: Collection,
    images: ImageLoader,
    artwork: Artwork,
    radio: HomeRadio,
    modifier: Modifier,
    onOpen: (BannerCard) -> Unit,
) {
    var index by remember(cards) { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val lit = focused && LocalFocusVisibility.current.shown
    val slide = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    fun step(by: Int) {
        if (cards.size < 2) return
        index = (index + by).mod(cards.size)
        scope.launch {
            // The cards swap at once and the strip slides in from the side it came from (HomeBanner.Step).
            slide.snapTo(by * with(density) { 180.s.toPx() })
            slide.animateTo(0f, tween(220, easing = BannerEase))
        }
    }

    // Not while another screen or the player is over home: the banner steps only where it is seen.
    val onTop = LocalOnTop.current
    LaunchedEffect(index, lit, cards, onTop) {
        if (onTop && !lit && cards.size > 1) {
            delay(DWELL_MS)
            step(1)
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val content = maxWidth
        val plate = min(content * 0.68f, 760.s)
        val sideRoom = (content - plate) / 2
        val peeks = sideRoom >= 124.s && cards.size > 1
        val peek = min(sideRoom + 24.s, plate)
        val inset = max(0.dp, sideRoom + 24.s - plate)

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(352.s)) {
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = slide.value }) {
                    // Every card has a face for as long as the banner is up, keyed by the card, and only the
                    // ones on show are placed: a card loads its pictures and its artist's photo once and keeps
                    // them however often it comes round, as on the desktop. Its pictures are asked for at the
                    // middle plate's size wherever it is, so moving it asks for nothing new.
                    val picture = with(density) { IntSize(plate.roundToPx(), 352.s.roundToPx()) }
                    val n = cards.size
                    fun placeOf(i: Int): Int? = when {
                        i == index -> 0
                        peeks && i == (index - 1).mod(n) -> -1
                        peeks && i == (index + 1).mod(n) -> 1
                        else -> null
                    }
                    cards.forEachIndexed { i, card ->
                        key(card) { BannerSlot(card, placeOf(i), focused, plate, peek, inset, picture, collection, images, artwork, radio) }
                    }
                    // With two cards the one not in the middle shows at both sides: the right is a second face.
                    if (peeks && n == 2) {
                        val card = cards[(index + 1).mod(n)]
                        key(card, "right") { BannerSlot(card, 1, focused, plate, peek, inset, picture, collection, images, artwork, radio) }
                    }
                }
                // The remote's one stop, over the plate; it does not slide. It draws nothing: the middle face
                // grows and the faces dim as a card does ([BannerSlot]).
                Box(
                    modifier
                        .align(Alignment.Center)
                        .size(plate, 352.s)
                        .onFocusChanged { focused = it.isFocused }
                        .remembersFocus()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft -> { step(-1); true }
                                Key.DirectionRight -> { step(1); true }
                                else -> false
                            }
                        }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            onOpen(cards[index.coerceIn(0, cards.size - 1)])
                        },
                )
                if (cards.size > 1) {
                    Box(Modifier.align(Alignment.CenterStart).padding(start = 4.s).size(44.s), contentAlignment = Alignment.Center) {
                        Glyph(Fa.CHEVRON_LEFT, 15.ts, Color.White.copy(alpha = 0.7f))
                    }
                    Box(Modifier.align(Alignment.CenterEnd).padding(end = 4.s).size(44.s), contentAlignment = Alignment.Center) {
                        Glyph(Fa.CHEVRON_RIGHT, 15.ts, Color.White.copy(alpha = 0.7f))
                    }
                }
            }
            if (cards.size > 1) {
                Row(Modifier.padding(top = DOTS_GAP), horizontalArrangement = Arrangement.spacedBy(7.s)) {
                    cards.indices.forEach { i ->
                        Box(Modifier.size(16.s), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(7.s).background(Color.White.copy(alpha = if (i == index) 1f else 0.32f), RoundedCornerShape(4.s)))
                        }
                    }
                }
            }
        }
    }
}

private const val DWELL_MS = 7_000L

/** The gap between the banner and its dots: the plate grows 8% when it has the focus, 14·s past its foot, so the
 * dots stand 30·s under it. */
internal val DOTS_GAP = 30.s

/**
 * One card's face in the banner, at [place]: -1 peeking at the left, 1 at the right, 0 the plate in the
 * middle (drawn over the two it overlaps), null not on show. A face not on show is measured but not placed,
 * so it is composed, keeps what it has loaded and draws nothing. One call whatever the place, which only
 * changes the modifiers: a call in another branch would be another composition.
 *
 * The middle face is the banner's card for the focus: it grows and is drawn at full brightness while the
 * banner is [focused], as a [CardButton] does; every other face is dimmed once the focus is shown.
 */
@Composable
private fun BoxScope.BannerSlot(
    card: BannerCard,
    place: Int?,
    focused: Boolean,
    plate: Dp,
    peek: Dp,
    inset: Dp,
    picture: IntSize,
    collection: Collection,
    images: ImageLoader,
    artwork: Artwork,
    radio: HomeRadio,
) {
    val chosen = focused && place == 0
    val look = rememberCardLook(chosen)
    val slot = when (place) {
        -1 -> Modifier
            .align(Alignment.CenterStart)
            .padding(start = inset)
            .size(peek, 330.s)
            .clip(RoundedCornerShape(topStart = 12.s, bottomStart = 12.s))
        1 -> Modifier
            .align(Alignment.CenterEnd)
            .padding(end = inset)
            .size(peek, 330.s)
            .clip(RoundedCornerShape(topEnd = 12.s, bottomEnd = 12.s))
        0 -> Modifier
            .zIndex(1f)
            .align(Alignment.Center)
            .size(plate, 352.s)
            .graphicsLayer {
                scaleX = look.grow
                scaleY = look.grow
            }
            .clip(RoundedCornerShape(12.s))
        else -> Modifier.align(Alignment.Center).size(plate, 352.s).unplaced()
    }
    val face = when (place) {
        -1 -> Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(plate).fillMaxHeight()
        1 -> Modifier.wrapContentWidth(Alignment.End, unbounded = true).width(plate).fillMaxHeight()
        else -> Modifier.fillMaxSize()
    }
    Box(slot) {
        // A still face that has not grown is kept in a layer and dimmed by that layer's colour filter. A face whose
        // room drifts changes 25 times a second, and the grown one is drawn sharp, so neither is kept in a layer;
        // their dim is a black veil over them (a face is opaque), because a colour filter would make the face a
        // layer of its own, drawn again on every frame on top of being shown. A drifting face keeps what is drawn
        // over its room in a layer ([BannerFace]'s keepOverlay), so a drift step draws the small room picture
        // again and not the lines, the sleeve and the shades.
        val still = card is BannerCard.OfAlbum || LocalMotion.current >= Motion.Still
        val dimmed = if (still && !chosen) face.cachedLayer(cached = true, light = { look.light }) else face.veiled { look.light }
        BannerFace(card, collection, images, artwork, radio, picture, shown = place != null, keepOverlay = !still && !chosen, modifier = dimmed)
    }
}

/** Dims an opaque block to [light] (read when drawn) with a black veil over it. */
private fun Modifier.veiled(light: () -> Float): Modifier = drawWithContent {
    drawContent()
    val l = light()
    if (l < 1f) drawRect(Color.Black.copy(alpha = 1f - l))
}

/** Measures what it wraps and does not place it: it stays composed and draws nothing. */
internal fun Modifier.unplaced(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {}
}

/** The size a banner card's ground is asked for at, in pixels: [Soft] shrinks it to 64 across. */
private const val GROUND_EDGE = 256

/** A card's own ground: the plate's backing, `#191B20`, under the glass, `#1AFFFFFF`, as one opaque colour.
 * A card draws it only until a picture covers it. */
private val BACKING = Color(0xFF303237)
private val BannerEase = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

/**
 * One banner card, bottom to top: the glass, a wash in the list's colour (lists), the ground (the sleeve
 * blurred), the artist's photo itself aligned right, a veil, a shade up from the bottom, the rim, and the
 * foot: the sleeve and three lines. The third line (the artist, or the artist's logo) is 19·s, 34·s high,
 * where the desktop has 13.5·s and 26·s, to be read from a couch. The photo fades in over the ground once
 * it arrives and the artist's name crosses to the logo once the logo is in; either is drawn whole at once
 * when the image loader had it in memory. The photo is asked for at [picture], the middle plate's size in
 * pixels, wherever the card is; a room drifts only while the card is [shown]. With [keepOverlay], everything drawn over the room (the
 * veil and shade, the foot, the rim) is kept in a layer of its own, so a drifting room's frames do not draw
 * it again.
 */
@Composable
private fun BannerFace(
    card: BannerCard,
    collection: Collection,
    images: ImageLoader,
    artwork: Artwork,
    radio: HomeRadio,
    picture: IntSize,
    shown: Boolean,
    keepOverlay: Boolean,
    modifier: Modifier,
) {
    if (card is BannerCard.OfStation) {
        StationFace(card.station, images, radio, shown, keepOverlay, modifier)
        return
    }
    val context = LocalContext.current
    val album = (card as? BannerCard.OfAlbum)?.album
    val list = (card as? BannerCard.OfList)?.list
    val sleeve: Any? = album?.let(collection::cover) ?: list?.let(collection::cover)
    // The artwork the app already holds for the artist is the first value ([Artwork.peek]), so a face composed
    // again draws its photo and logo from the first frame instead of asking and fading them in again.
    val known = album?.takeIf { it.artist.isNotBlank() }?.let { artwork.peek(it.artist) }
    val art by produceState<com.rpm.player.core.library.ArtistArtwork?>(known, album?.artist) {
        value = album?.takeIf { it.artist.isNotBlank() }?.let { artwork.of(it.artist, it.album) }
    }
    val photo = art?.backgrounds?.firstOrNull()

    // What is drawn under an opaque picture cannot be seen, and every layer is the whole card's pixels on
    // every frame the window draws: the glass and the ground are left out once a picture covers them.
    // The ground is the sleeve until the photo is in. Changing to the blurred photo as soon as the photo's
    // address arrives would show the flat backing until that picture has loaded.
    val ground: Any? = sleeve.takeIf { list == null }
    var groundIn by remember(ground) { mutableStateOf(false) }
    var landed by remember(photo) { mutableStateOf(false) }
    // A photo from memory is drawn whole at once; one that arrived late fades in.
    var photoCached by remember(photo) { mutableStateOf(false) }
    val alphaState = animateFloatAsState(if (landed) 1f else 0f, tween(if (photoCached) 0 else 420, easing = QuadInOut), label = "portrait")
    val photoIn by remember(photo) { derivedStateOf { landed && (photoCached || alphaState.value >= 1f) } }
    Box(modifier) {
        if (!groundIn && !photoIn) Box(Modifier.matchParentSize().background(BACKING))
        if (list != null) {
            val ink = listInk(list)
            Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(tinted(ink, 0.42f), tinted(ink, 0.16f)), start = Offset.Zero, end = Offset.Infinite)))
            CardRoomView(RoomPalette.from(ink.rgb, null, null), apart = 0, drift = shown, fewest = 8, soften = 0f, modifier = Modifier.matchParentSize())
        }
        if (ground != null && !photoIn) {
            AsyncImage(
                // At one size wherever the card is: a transformed picture is kept by its size, so the size
                // of the box it is in would make it a new picture.
                model = remember(ground) { ImageRequest.Builder(context).data(ground).size(GROUND_EDGE).transformations(Soft()).allowHardware(false).build() },
                imageLoader = images,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { groundIn = true },
                modifier = Modifier.matchParentSize().dithered(),
            )
        }
        if (photo != null) {
            AsyncImage(
                model = remember(photo, picture) { ImageRequest.Builder(context).data(photo).size(picture.width, picture.height).build() },
                imageLoader = images,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.CenterEnd,
                onSuccess = {
                    photoCached = it.result.dataSource == DataSource.MEMORY_CACHE
                    landed = true
                },
                modifier = Modifier.matchParentSize().graphicsLayer { this.alpha = if (photoCached) 1f else alphaState.value },
            )
        }
        Box(Modifier.matchParentSize().cachedLayer(cached = keepOverlay, light = { 1f })) {
            // The veil (#33000000) and the shade up from the bottom, as one layer: each stop is the shade's
            // over the veil, 1 - (1 - 0.2)(1 - a).
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to Color(0x33000000),
                        0.38f to Color(0x33000000),
                        0.38f + 0.62f * 0.45f to Color(0x4C000000),
                        0.38f + 0.62f * 0.75f to Color(0x71000000),
                        1f to Color(0xB8000000),
                    ),
                ),
            )
            Row(
                Modifier.align(Alignment.BottomStart).padding(horizontal = 22.s, vertical = 18.s),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    Modifier
                        .size(124.s)
                        .clip(RoundedCornerShape(8.s))
                        .background(if (list != null) Color(0x1FFFFFFF) else Color(0x4D000000))
                        .border(1.px, Color(0x3DFFFFFF), RoundedCornerShape(8.s)),
                    contentAlignment = Alignment.Center,
                ) {
                    Glyph(Fa.RECORD, 26.ts, Color(0x4DFFFFFF))
                    if (list != null) {
                        ListPicture(list, collection, images, drift = false, keepFrame = false, modifier = Modifier.matchParentSize())
                    } else if (sleeve != null) {
                        CoverImage(sleeve, images, ContentScale.Crop, Modifier.matchParentSize(), onShown = {})
                    }
                }
                Spacer(Modifier.width(20.s))
                Column(Modifier.weight(1f).padding(end = 96.s), verticalArrangement = Arrangement.spacedBy(6.s)) {
                    Text(
                        when (card) {
                            is BannerCard.OfAlbum -> stringResource(kickerName(card.kicker))
                            else -> stringResource(R.string.Home_MadeForYou)
                        },
                        // 18, the station card's description, where the desktop has 11: read from a couch.
                        style = ui(18.ts, FontWeight.SemiBold).copy(letterSpacing = 0.11.em, shadow = Shadow(Color(0xB3000000), Offset(0f, 1f), 4f)),
                        color = Color(0xA8FFFFFF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        album?.let { albumTitle(it) } ?: list?.let { listName(it.name) }.orEmpty(),
                        style = ui(24.ts, FontWeight.Bold).copy(lineHeight = 29.ts, shadow = Shadow(Color(0xB3000000), Offset(0f, 1.5f), 4f)),
                        color = Ink.Words,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(Modifier.height(34.s), contentAlignment = Alignment.CenterStart) {
                        val logo = art?.logo
                        // The name stays until the logo is in, then the two cross over [LOGO_CROSS_MS]. Swapped as
                        // soon as the logo's address arrives, the line would be empty until the logo has loaded.
                        var logoIn by remember(logo) { mutableStateOf(false) }
                        var logoCached by remember(logo) { mutableStateOf(false) }
                        val logoAlpha = animateFloatAsState(if (logoIn) 1f else 0f, tween(if (logoCached) 0 else LOGO_CROSS_MS, easing = QuadInOut), label = "logo")
                        val nameGone by remember(logo) { derivedStateOf { logoIn && (logoCached || logoAlpha.value >= 1f) } }
                        if (logo != null) {
                            AsyncImage(
                                // Cut to its lettering first, as the desktop's banner does (Trim), so the
                                // box holds the mark and not the catalogue's empty canvas around it. Decoded
                                // whole: cut after Coil has shrunk it to the box, the mark comes out a few
                                // pixels high and is drawn at that size.
                                model = remember(logo) {
                                    ImageRequest.Builder(context).data(logo).size(coil3.size.Size.ORIGINAL).transformations(Trim()).allowHardware(false).build()
                                },
                                imageLoader = images,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.CenterStart,
                                onSuccess = {
                                    logoCached = it.result.dataSource == DataSource.MEMORY_CACHE
                                    logoIn = true
                                },
                                modifier = Modifier.sizeIn(maxWidth = 340.s, maxHeight = 34.s).graphicsLayer { alpha = if (logoCached) 1f else logoAlpha.value },
                            )
                        }
                        if (!nameGone) {
                            Text(
                                album?.artist ?: list?.let { listCaption(it) }.orEmpty(),
                                style = ui(19.ts).copy(shadow = Shadow(Color(0x99000000), Offset(0f, 1f), 3f)),
                                color = Color(0xCCFFFFFF),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.graphicsLayer { alpha = if (logoCached) 0f else 1f - logoAlpha.value },
                            )
                        }
                    }
                }
            }
            Box(Modifier.matchParentSize().border(1.px, Color(0x2EFFFFFF), RoundedCornerShape(12.s)))
        }
    }
}

/**
 * A station's banner card (HomeView.xaml, the station face): the glass, a room in the station's colours,
 * the veil, and a centred stack 10 apart: the logo (at most 160 by 320), the name (22 Bold), what the
 * station is (18, two lines, 420 wide) and what it plays now (16, 520 wide); no kicker and no shade from
 * below. The last two are larger than the desktop's 13 and 11.5, to be read from a couch. With [keepOverlay]
 * what is drawn over the room is kept in a layer of its own, as on [BannerFace].
 */
@Composable
private fun StationFace(station: RadioStation, images: ImageLoader, radio: HomeRadio, shown: Boolean, keepOverlay: Boolean, modifier: Modifier) {
    val logo = StationLogo.of(station, radio.logoStamp)
    val reading = rememberCoverReading(logo, images)
    // Keyed on the admin's description too, so an edit that lands while the card is up is shown.
    val blurb by produceState<String?>(null, station.uuid, station.edit?.description) { value = radio.blurbs.of(station) }
    Box(modifier) {
        Box(Modifier.matchParentSize().background(BACKING))
        CardRoomView(reading?.room ?: RoomPalette.FALLBACK, apart = 0, drift = shown, fewest = 8, soften = 0f, modifier = Modifier.matchParentSize())
        Box(Modifier.matchParentSize().cachedLayer(cached = keepOverlay, light = { 1f })) {
            Box(Modifier.matchParentSize().background(Color(0x33000000)))
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.s)) {
                // Its full height is kept while it loads, as the radio banner's is, so the lines under it do not
                // move down when it arrives.
                Box(Modifier.height(160.s), contentAlignment = Alignment.Center) {
                    CoverImage(logo, images, ContentScale.Fit, Modifier.sizeIn(maxWidth = 320.s, maxHeight = 160.s), onShown = {})
                }
                Text(station.display, style = ui(22.ts, FontWeight.Bold), color = Ink.Words, maxLines = 1, overflow = TextOverflow.Ellipsis)
                blurb?.let {
                    Text(it, style = ui(18.ts).copy(lineHeight = 24.ts), color = Color(0xD1FFFFFF), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.s))
                }
                val said = radio.playing?.takeIf { station.answersTo(it.logo.uuid) }?.said as? com.rpm.player.core.radio.Icy.Said.Song
                if (said != null) {
                    Text(
                        stringResource(R.string.Radio_NowPlayingLine, listOfNotNull(said.title, said.artist).joinToString(" - ")),
                        style = ui(16.ts),
                        color = Color(0x8CFFFFFF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 520.s),
                    )
                }
            }
            Box(Modifier.matchParentSize().border(1.px, Color(0x2EFFFFFF), RoundedCornerShape(12.s)))
        }
    }
}

/** What home needs of the radio: the stations heard most, the logo set's stamp, the station blurbs and
 * the station playing. */
class HomeRadio(val stations: List<RadioStation>, val logoStamp: String, val blurbs: StationBlurbs, val playing: StationPlaying?)

/** The colour a generated list is dressed in, by its kind (SmartPlaylistFace's hues, from the rpm palette). */
fun listInk(list: TrackList): Color = when ((list.name as? ListName.Mood)?.kind ?: list.kind) {
    "RightNow", "Calm" -> Color(0xFF0371D0)
    "OnRepeat" -> Color(0xFFCC6E11)
    "FavoriteTracks", "LovedAndForgotten" -> Color(0xFFEA154E)
    "FirstListens", "Focus" -> Color(0xFF134C27)
    "Rediscover", "Danceable" -> Color(0xFFAC528D)
    "LateNight", "Sleep" -> Color(0xFF4750AE)
    "Energetic" -> Color(0xFFEE0709)
    else -> Color(0xFF0371D0)
}

private val Color.rgb: Rgb get() = Rgb(red, green, blue)

/** [ink] mixed into the card backing `#191B20` at [k], opaque (HomeView's Tinted). */
private fun tinted(ink: Color, k: Float): Color = Color(
    red = BACKING.red + (ink.red - BACKING.red) * k,
    green = BACKING.green + (ink.green - BACKING.green) * k,
    blue = BACKING.blue + (ink.blue - BACKING.blue) * k,
)

// ── quick selection ──

/**
 * Quick selection (HomeView.xaml, QuickPicks): the heading with "Play all tracks" at the right, then
 * [QUICK_SHOWN] entries in columns of [HomeShelf.QUICK_DOWN], filled down each column, four columns across the
 * screen and nothing past it, as the desktop shows them and as the iPhone's four groups of four hold them. A column
 * is `(content + 8 - 3 * 10) / 4` wide; an entry is a 64·s square and two lines, 64·s + 12 high. What is in it,
 * and in what order, is the iPhone's: the song radios, then song, album, playlist and station in turn.
 */
@Composable
private fun QuickSelection(
    picks: List<QuickPick>,
    collection: Collection,
    images: ImageLoader,
    radio: HomeRadio,
    actions: HomeActions,
    tracks: TrackActions,
    menu: RowMenuState,
    tops: QuickTops,
) {
    val songs = remember(picks) { picks.filterIsInstance<QuickPick.Song>() }
    Row(Modifier.fillMaxWidth().padding(bottom = 12.s), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.Home_QuickPicks), style = ui(HEADING_TEXT, FontWeight.SemiBold), color = Ink.Card, modifier = Modifier.weight(1f))
        if (songs.isNotEmpty()) {
            // 16 and a 14 glyph where the desktop has 11.5 and 10: larger because it is read from a couch.
            val shape = RoundedCornerShape(20.px)
            DeskButton(
                onClick = { actions.playSongs(songs) },
                corner = 20.px,
                modifier = Modifier.background(Color(0x14FFFFFF), shape).border(1.px, Color(0x26FFFFFF), shape).padding(horizontal = 20.px, vertical = 8.px),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.px)) {
                    Glyph(Fa.PLAY, 14.ts, Ink.Card)
                    Text(stringResource(R.string.Home_PlayAllTracks), style = ui(16.ts, FontWeight.SemiBold), color = Ink.Card)
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().outdent(8.px)) {
        val panel = (maxWidth - 30.px) / QUICK_ACROSS
        val columns = (picks.size + HomeShelf.QUICK_DOWN - 1) / HomeShelf.QUICK_DOWN
        QuickStrip(columns, tops.strip) { column ->
            // The column holding the focus is drawn over its neighbours, so the grown entry is not cut by them.
            var holds by remember { mutableStateOf(false) }
            Column(Modifier.zIndex(if (holds) 1f else 0f).onFocusChanged { holds = it.hasFocus }.width(panel)) {
                for (row in 0 until HomeShelf.QUICK_DOWN) {
                    val i = column * HomeShelf.QUICK_DOWN + row
                    val pick = picks.getOrNull(i) ?: continue
                    QuickEntry(pick, collection, images, radio, actions, tracks, menu, if (row == 0) Modifier.focusRequester(tops.of(column)) else Modifier)
                }
            }
        }
    }
}

/**
 * Quick selection's strip as Down from the banner needs it: its scroll, kept here so it outlives the page
 * scrolling Quick selection away, and one focus target for each column's top entry, made when first asked.
 */
@Stable
class QuickTops {
    val strip = LazyListState()
    private val tops = HashMap<Int, FocusRequester>()

    fun of(column: Int): FocusRequester = tops.getOrPut(column) { FocusRequester() }

    /** The top entry of the leftmost column wholly on screen, else of the first column on screen. Asked when
     * Down is pressed, so it names a column that is composed then. */
    fun onScreen(): FocusRequester {
        val shown = strip.layoutInfo.visibleItemsInfo
        val column = shown.firstOrNull { it.offset >= 0 } ?: shown.firstOrNull()
        return of(column?.index ?: 0)
    }
}

/**
 * Quick selection's [columns], 10 apart in one strip reaching the screen's edges ([ROW_BLEED_START]), scrolled
 * by the remote with 48 of air, so a grown entry clears the 48 dissolve, and a chevron at each side that has
 * more past it, as [CardRow] does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuickStrip(columns: Int, state: LazyListState, column: @Composable (Int) -> Unit) {
    val density = LocalDensity.current
    val reveal = remember(density) { with(density) { Reveal((48.px + ROW_BLEED_START).toPx(), (48.px + ROW_BLEED_END).toPx()) } }
    val ends = rememberRowEnds(state)
    // An entry grows 3 above and below, inside its own 6 of padding: no room is needed above and below.
    Box(Modifier.fillMaxWidth().bleed(ROW_BLEED_START, ROW_BLEED_END, vertical = 0.px)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides reveal) {
            LazyRow(
                state = state,
                horizontalArrangement = Arrangement.spacedBy(10.px),
                contentPadding = PaddingValues(start = ROW_BLEED_START, end = ROW_BLEED_END),
                modifier = Modifier.fillMaxWidth().edgeFades(ends),
            ) {
                items(columns, key = { it }) { column(it) }
            }
        }
        if (ends.beforeDrawn) RowChevron(Fa.CHEVRON_LEFT, Modifier.align(Alignment.CenterStart).padding(start = ROW_BLEED_START + 2.px).graphicsLayer { alpha = ends.before.value })
        if (ends.afterDrawn) RowChevron(Fa.CHEVRON_RIGHT, Modifier.align(Alignment.CenterEnd).padding(end = ROW_BLEED_END + 2.px).graphicsLayer { alpha = ends.after.value })
    }
}

/** One quick entry: the picture (64·s, corner 6, `#33000000`), the title ([CARD_TITLE_TEXT] SemiBold)
 * and what it is ([CARD_LINE_TEXT]), the album wall's sizes: the artist for a song, "Album • artist" (just
 * "Album" when the record names none), "Playlist • n songs", as on the iPhone. The desktop has 48, 12.5 and 11.
 * Where there is no picture the square shows a dim symbol of the kind, as the iPhone's does. A song's held OK opens
 * the track's menu, which gives the focus back to the entry when it closes. */
@Composable
private fun QuickEntry(
    pick: QuickPick,
    collection: Collection,
    images: ImageLoader,
    radio: HomeRadio,
    actions: HomeActions,
    tracks: TrackActions,
    menu: RowMenuState,
    modifier: Modifier,
) {
    val self = remember { FocusRequester() }
    val held = if (pick is QuickPick.Song) {
        Modifier.focusRequester(self).heldOkMenu {
            menu.open(
                title = pick.track.title.orEmpty(),
                choices = trackChoices(queuedOf(pick, collection), collection, tracks, play = { actions.playSong(pick) }),
                returnTo = self,
            )
        }
    } else {
        Modifier
    }
    val picture: Any?
    val title: String
    val line: String
    when (pick) {
        is QuickPick.Song -> {
            picture = pick.album?.let { collection.cover(it) }
            title = pick.track.title.orEmpty()
            line = pick.track.artist.orEmpty()
        }
        is QuickPick.OfAlbum -> {
            picture = collection.cover(pick.album)
            title = albumTitle(pick.album)
            line = pick.album.artist.takeIf { it.isNotBlank() }
                ?.let { stringResource(R.string.Home_QuickKindJoin, stringResource(R.string.Home_QuickAlbum), it) }
                ?: stringResource(R.string.Home_QuickAlbum)
        }
        is QuickPick.OfList -> {
            picture = collection.cover(pick.list)
            title = listName(pick.list.name)
            line = stringResource(R.string.Home_QuickKindJoin, stringResource(R.string.Home_QuickPlaylist), stringResource(R.string.Home_Songs, pick.list.tracks.size.toString()))
        }
        is QuickPick.OfStation -> {
            picture = StationLogo.of(pick.station, radio.logoStamp)
            title = pick.station.display
            line = stringResource(R.string.Home_QuickStation)
        }
        // Named for the song it starts from, the kind first on the line under it, so it reads as a radio and not as
        // the song's own entry further on.
        is QuickPick.Radio -> {
            picture = pick.album?.let { collection.cover(it) }
            title = pick.track.title.orEmpty()
            line = pick.track.artist?.takeIf { it.isNotBlank() }
                ?.let { stringResource(R.string.Home_QuickKindJoin, stringResource(R.string.Home_QuickMixtape), it) }
                ?: stringResource(R.string.Home_QuickMixtape)
        }
    }
    CardButton(
        onClick = {
            when (pick) {
                is QuickPick.Song -> actions.playSong(pick)
                is QuickPick.OfAlbum -> actions.openAlbum(pick.album)
                is QuickPick.OfList -> actions.openList(pick.list)
                is QuickPick.OfStation -> actions.tune(pick.station)
                is QuickPick.Radio -> actions.startRadio(pick)
            }
        },
        contentAlignment = Alignment.CenterStart,
        modifier = modifier.then(held).fillMaxWidth().padding(horizontal = 8.px, vertical = 6.px),
    ) { focused ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.s).clip(RoundedCornerShape(6.px)).background(Color(0x33000000)), contentAlignment = Alignment.Center) {
                // The kind's symbol, under the picture, so a missing or late picture leaves a sign of what it is.
                val symbol = when (pick) {
                    is QuickPick.Song -> Fa.MUSIC
                    is QuickPick.Radio -> Fa.TV_MUSIC
                    is QuickPick.OfAlbum -> Fa.RECORD
                    is QuickPick.OfList -> Fa.PLAYLISTS
                    is QuickPick.OfStation -> Fa.RADIO
                }
                Glyph(symbol, 20.ts, Color(0x3AFFFFFF))
                if (pick is QuickPick.OfList) {
                    ListPicture(pick.list, collection, images, drift = false, keepFrame = false, modifier = Modifier.matchParentSize())
                } else if (picture is StationLogo) {
                    // Dressed as the stations pane dresses it (HomeQuickCard.WearMark): a mark on its made
                    // ground, a square logo filling the square; what does not fill is held the desktop's
                    // 4 in 48 off the edges.
                    StationMark(picture, images, inset = 4f / 48f, fadeInMs = 0, onState = {}, modifier = Modifier.matchParentSize())
                } else if (picture != null) {
                    CoverImage(picture, images, ContentScale.Crop, Modifier.matchParentSize(), onShown = {})
                }
            }
            Spacer(Modifier.width(14.px))
            Column(verticalArrangement = Arrangement.spacedBy(3.px)) {
                Text(title, style = ui(CARD_TITLE_TEXT, FontWeight.SemiBold), color = Ink.Card, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line, style = ui(CARD_LINE_TEXT), color = Ink.Dim, maxLines = 1, overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis, modifier = Modifier.readAcross(focused))
            }
        }
    }
}

// ── rows ──

/** The desktop's album tile size: the album wall's slider, which this collection's desktop has at its
 * largest (260; the default is 150). A home card's picture is this times `s`. */
const val TILE = 260

private fun androidx.compose.foundation.lazy.LazyListScope.albumRow(
    key: String,
    heading: Int,
    albums: List<CloudAlbum>,
    collection: Collection,
    images: ImageLoader,
    actions: HomeActions,
) {
    if (albums.isEmpty()) return
    item(key = key) {
        Heading(stringResource(heading))
        CardRow(albums, key = { it.key }, ahead = coversAhead(collection, images)) { album ->
            AlbumCard(album, collection.cover(album), images, { actions.openAlbum(album) })
        }
        Spacer(Modifier.height(26.px))
    }
}

/**
 * A row of cards (HomeView.xaml, the row template): 6 apart, outdented by a card's padding and reaching the
 * screen's edges ([ROW_BLEED_START]), with a chevron and a 48 dissolve at each side that has more cards past
 * it, each coming and going over [EDGE_MS]. The remote moves card to card, and the row reveals the card it
 * moves to with 24 of air.
 *
 * [ahead] names the pictures of the cards past the row's end: the next [ROW_AHEAD] are decoded into memory as
 * the row scrolls, without composing those cards, so a card coming into view shows its picture at once. Null
 * for a row whose cards draw something else (rooms, photographs still being asked for, lettering).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> CardRow(items: List<T>, key: (T) -> Any, ahead: RowPictures<T>?, card: @Composable (T) -> Unit) {
    val state = rememberLazyListState()
    val density = LocalDensity.current
    val reveal = remember(density) { with(density) { Reveal((24.px + ROW_BLEED_START).toPx(), (24.px + ROW_BLEED_END).toPx()) } }
    if (ahead != null) {
        val context = LocalContext.current
        val edge = with(density) { ahead.edge.roundToPx() }
        val pictureOf by rememberUpdatedState(ahead.of)
        DecodeAhead(
            images = ahead.images,
            key = items to edge,
            lastVisible = { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 },
            wanted = { last ->
                (last + 1..minOf(items.lastIndex, last + ROW_AHEAD)).mapNotNull { i ->
                    pictureOf(items[i])?.let { aheadRequest(context, it, edge, edge) }
                }
            },
        )
    }
    val ends = rememberRowEnds(state)
    Box(Modifier.fillMaxWidth().outdent(8.px).bleed(ROW_BLEED_START, ROW_BLEED_END, vertical = ROW_GROW_ROOM)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides reveal) {
            LazyRow(
                state = state,
                horizontalArrangement = Arrangement.spacedBy(6.px),
                contentPadding = PaddingValues(start = ROW_BLEED_START, end = ROW_BLEED_END, top = ROW_GROW_ROOM, bottom = ROW_GROW_ROOM),
                modifier = Modifier.fillMaxWidth().edgeFades(ends),
            ) {
                items(items, key = key) { card(it) }
            }
        }
        // Centred on the pictures: the text under them is 7 + 22·s + 7 + 20·s (PictureCard).
        val under = 8.px + 7.px + 22.s + 7.px + 20.s + 8.px
        if (ends.beforeDrawn) RowChevron(Fa.CHEVRON_LEFT, Modifier.align(Alignment.CenterStart).padding(start = ROW_BLEED_START + 2.px, bottom = under).graphicsLayer { alpha = ends.before.value })
        if (ends.afterDrawn) RowChevron(Fa.CHEVRON_RIGHT, Modifier.align(Alignment.CenterEnd).padding(end = ROW_BLEED_END + 2.px, bottom = under).graphicsLayer { alpha = ends.after.value })
    }
}

/** How many cards past a row's end have their pictures decoded ahead. */
private const val ROW_AHEAD = 4

/** A row of album cards' pictures, for [CardRow]: each album's cover, at the card's side ([TILE]·s). */
fun coversAhead(collection: Collection, images: ImageLoader): RowPictures<CloudAlbum> =
    RowPictures(images, TILE.s) { collection.cover(it) }

/**
 * Whether a row has more past each side, eased over [EDGE_MS]: 0 nothing, 1 more. Read in a layer or a draw
 * block, so the ease draws the dissolve and the chevrons again without composing the row.
 */
@Stable
private class RowEnds(val before: State<Float>, val after: State<Float>) {
    /** Whether a chevron is composed at all: from the first frame of its fade in to the last of its fade out. Each
     * changes once per fade, so the fade's own frames compose nothing. */
    val beforeDrawn by derivedStateOf { before.value > 0f }
    val afterDrawn by derivedStateOf { after.value > 0f }
}

@Composable
private fun rememberRowEnds(state: LazyListState): RowEnds {
    val before = animateFloatAsState(if (state.canScrollBackward) 1f else 0f, tween(EDGE_MS), label = "before")
    val after = animateFloatAsState(if (state.canScrollForward) 1f else 0f, tween(EDGE_MS), label = "after")
    return remember(before, after) { RowEnds(before, after) }
}

/** How long a row's dissolve and chevron take to come and go at a side. */
private const val EDGE_MS = 150

/** How long the banner's artist name takes to cross to the artist's logo. */
private const val LOGO_CROSS_MS = 300

@Composable
private fun RowChevron(glyph: Char, modifier: Modifier) {
    Box(modifier.size(36.s, 44.s), contentAlignment = Alignment.Center) {
        Glyph(glyph, 15.ts, Color.White.copy(alpha = 0.7f))
    }
}

/** The row's sides dissolve over 48 where it has more cards past them. The edge itself is clear at 1 and whole
 * at 0 ([RowEnds]), so a dissolve comes and goes with its ease. */
private fun Modifier.edgeFades(ends: RowEnds): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val before = ends.before.value
            val after = ends.after.value
            if (before <= 0f && after <= 0f) return@drawWithContent
            val depth = 48.px.toPx()
            val w = size.width
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 1f - before),
                    (depth / w).coerceIn(0f, 0.5f) to Color.Black,
                    (1f - depth / w).coerceIn(0.5f, 1f) to Color.Black,
                    1f to Color.Black.copy(alpha = 1f - after),
                ),
                blendMode = BlendMode.DstIn,
            )
        }

/**
 * A record card on home (HomeView.xaml, AlbumTemplate): padding 8, corner 10; the sleeve `TILE·s`
 * square on `#33000000` with corner 6; 7 under it the title ([CARD_TITLE_TEXT] SemiBold, centred, with
 * the E) and 7 under that the artist ([CARD_LINE_TEXT], `#99FFFFFF`): the album wall's sizes, where the
 * desktop has 12 and 11, because it is read from a couch.
 */
@Composable
fun AlbumCard(album: CloudAlbum, cover: Any?, images: ImageLoader, onClick: () -> Unit) {
    var covered by remember(cover) { mutableStateOf(false) }
    PictureCard(
        art = { Cover(cover, images, onIn = { covered = true }) },
        title = albumTitle(album),
        line = albumArtist(album),
        explicit = album.tracks.any { it.tags?.advisory == EXPLICIT },
        well = Color(0x33000000),
        covered = covered,
        onClick = onClick,
    )
}

/** A Made for you card: the same card, the list's framed picture on `#1FFFFFFF`, its name, and the mood's
 * sentence or its length ([listCaption]). */
@Composable
internal fun ListCard(list: TrackList, collection: Collection, images: ImageLoader, onClick: () -> Unit) {
    PictureCard(
        art = { focused -> ListPicture(list, collection, images, drift = true, keepFrame = !focused, modifier = Modifier.fillMaxSize()) },
        title = listName(list.name),
        line = listCaption(list),
        explicit = list.tracks.any { it.tags?.advisory == EXPLICIT },
        well = Color(0x1FFFFFFF),
        covered = false,
        onClick = onClick,
    )
}

/** A cover filling its box, or nothing, leaving the well and its glyph. [onIn] is told when a cover with
 * no transparency in it is drawn whole ([CoverImage]: at once from memory, at the end of its fade otherwise),
 * so nothing under it can show and the well is not left out while the cover is still fading in over it. */
@Composable
private fun Cover(picture: Any?, images: ImageLoader, onIn: () -> Unit) {
    if (picture != null) {
        CoverImage(picture, images, ContentScale.Crop, Modifier.fillMaxSize(), onShown = { result ->
            if ((result.image as? coil3.BitmapImage)?.bitmap?.hasAlpha() == false) onIn()
        })
    }
}

@Composable
private fun PictureCard(art: @Composable (focused: Boolean) -> Unit, title: String, line: String, explicit: Boolean, well: Color, covered: Boolean, onClick: () -> Unit) {
    val edge = TILE.s
    CardButton(onClick = onClick, modifier = Modifier.padding(8.px)) { focused ->
        Column(Modifier.width(edge), horizontalAlignment = Alignment.CenterHorizontally) {
            // The well and its glyph are left out under a cover that has been drawn: [covered] says so, for
            // a picture that fills the square with no transparency in it.
            Box(Modifier.size(edge).clip(RoundedCornerShape(6.px)).then(if (covered) Modifier else Modifier.background(well)), contentAlignment = Alignment.Center) {
                if (!covered) Glyph(Fa.RECORD, 20.tpx, Color(0x4DFFFFFF))
                art(focused)
            }
            Row(
                Modifier.padding(top = 7.px).heightIn(min = 22.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = ui(CARD_TITLE_TEXT, FontWeight.SemiBold), color = Ink.Card, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = edge - 16.s - 5.px))
                if (explicit) {
                    Spacer(Modifier.width(5.px))
                    ExplicitBadge(16.s)
                }
            }
            Text(line, style = ui(CARD_LINE_TEXT), color = Ink.Dim, maxLines = 1, overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 7.px).heightIn(min = 20.s).readAcross(focused))
        }
    }
}
