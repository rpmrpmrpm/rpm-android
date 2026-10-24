package com.rpm.player.tv.ui

import com.rpm.player.core.motion.Motion
import com.rpm.player.core.motion.LocalMotion
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import coil3.size.Scale
import coil3.size.Precision
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.EaseOutQuad
import com.rpm.player.core.playback.StationPlaying
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.transformations
import com.rpm.player.core.radio.StationBadge
import com.rpm.player.core.R
import com.rpm.player.core.radio.Essential
import com.rpm.player.core.radio.Icy
import com.rpm.player.core.radio.RadioQuery
import com.rpm.player.core.radio.RadioRepository
import com.rpm.player.core.radio.RadioResults
import com.rpm.player.core.radio.RadioStation
import com.rpm.player.core.radio.StationBlurbs
import com.rpm.player.core.radio.StationLogo
import com.rpm.player.core.radio.StationRules
import com.rpm.player.core.radio.StationSieve
import com.rpm.player.core.radio.countryName
import com.rpm.player.core.room.RoomPalette
import com.rpm.player.core.room.RpmPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a search on the radio screen holds: nothing asked yet, a search out, the stations found, or a failure. */
private sealed interface Search {
    data object Idle : Search
    data object Searching : Search
    data class Found(val query: String, val stations: List<RadioStation>) : Search
    data object Failed : Search
}

/**
 * Pegasus's radio screen (PegasusChrome.Radio.cs, PegasusRadioViews.cs): the banner of five station
 * cards with the front one in the middle (the stations heard most on every device, then the favourites,
 * then the essential stations, at most seven), then the essential stations for the TV's country (or the
 * most popular anywhere when the country has none), four across in 16:9, then the favourites, six across
 * and square. Content is 34 in from the sides and starts 64 down; every card stands on a small room in
 * the station's colours.
 *
 * OK on a station tunes it and opens the player; a held OK or the Menu key opens its menu (Play, Add to
 * favorites or Remove from favorites). The radio's five ways in (Tuner, Search, Music, News, Local) are a row of
 * keys under the banner, as on the Apple TV, because holding a key for 2 seconds is not easy to find. Right held
 * for 2 seconds also opens them as the desktop's wheel; its first press
 * moves as any Right does and its repeats move nothing. Search opens
 * the station search over the screen; the other four open the tuner in the collection's place, crossing
 * over 160 ms, Music with its genre menu open, News on news from every country, Local on the TV's
 * country. The wheel never takes the focus: its keys are routed to it first, so the item under it keeps
 * the focus and has it when the wheel closes. Closing the search or the tuner gives the focus to the
 * banner.
 *
 * The essential stations and the hidden list are read when the screen opens, unless still fresh
 * ([RadioRepository.refresh]); hidden stations are taken out when a row is drawn.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RadioScreen(
    radio: RadioRepository,
    blurbs: StationBlurbs,
    images: ImageLoader,
    playing: StationPlaying?,
    tuned: StationPlaying?,
    mini: MiniPlayerState,
    backdrop: Backdrop,
    front: RadioFront,
    onTune: (RadioStation, StationRules) -> Unit,
    onTuneHere: (RadioStation) -> Unit,
    onStop: () -> Unit,
) {
    val essential by radio.essential.collectAsStateWithLifecycle()
    val rules by radio.rules.collectAsStateWithLifecycle()
    // The person's saved stations, or the owner's while a shared collection is open (read only then).
    val saved by radio.saved.collectAsStateWithLifecycle()
    val favorites = saved.stations
    val heard by radio.listens.top.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    // The wheel while it shows, a new state each time it opens.
    var wheel by remember { mutableStateOf<RadioWheelState?>(null) }
    var tunerUp by remember { mutableStateOf(false) }
    var genresFirst by remember { mutableStateOf(false) }
    // Set once the cross to the tuner has landed; the collection then leaves the composition, so the
    // remote cannot reach it from the tuner.
    var listGone by remember { mutableStateOf(false) }
    val cross = remember { Animatable(0f) }
    var swallowRight by remember { mutableStateOf(false) }
    val menu = rememberRowMenu()
    // The banner takes the focus when it first appears and when the search or the tuner closes. It asks
    // for it itself, once it is composed, since a request made from here can run before the list is
    // composed again.
    var bannerWanted by remember { mutableStateOf(true) }
    var tunerClosing by remember { mutableStateOf(false) }
    val content = remember { FocusRequester() }
    val firstMode = remember { FocusRequester() }
    // The opening: until the stations are read and their pictures decoded, only the background and the turning record
    // show; then the record fades out and the page fades in over it ([warmRadioHome]). Kept for as long as the screen
    // is, so coming back from the tuner does not open it again.
    var opened by remember { mutableStateOf(false) }
    val reveal = remember { Animatable(0f) }
    val context = LocalContext.current
    val bannerKey = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // Derived from the layers every time they change, and reset when the screen leaves, so the side menu's
    // held Left never reads a layer that is gone.
    DisposableEffect(front, wheel, searching, tunerUp) {
        front.inFront = wheel == null && !searching && !tunerUp
        onDispose { front.inFront = true }
    }
    // The tuner stands in the collection's place, and the mini player goes with the collection.
    // Read once per opening: the dispose must uncover what this effect covered. Asking the state again there read it
    // already false, so the tuner's cover was never taken off, and the radio's home had no mini player after it.
    val tunerCovers = tunerUp
    DisposableEffect(mini, tunerCovers) {
        if (tunerCovers) mini.cover()
        onDispose { if (tunerCovers) mini.uncover() }
    }
    val locale = Locale.getDefault()
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val atTop = remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 } }
    // The screen in front hands the mini player its scroll answer, which it reads live; one kept under it does not
    // (LocalOnTop).
    val onTop = LocalOnTop.current
    SideEffect { if (onTop) mini.follow(atTop) }

    LaunchedEffect(Unit) { radio.refresh() }

    val essentialShown = (essential as? Essential.Read)?.let { rules.shown(it.stations).take(RadioRepository.ESSENTIAL_COUNT) }.orEmpty()
    val favoritesShown = rules.shown(favorites)
    // The stations heard most on every device, then the favourites, then the essential stations; each
    // once, at most seven, as on the desktop.
    val heardShown = rules.shown(heard.map { it.station }).take(BANNER_HEARD)
    val bannerCards = (heardShown + favoritesShown + essentialShown).distinctBy { it.uuid.lowercase() }.take(BANNER_MOST)

    // Where the focus goes when the banner it was meant for is not there: the collection's first item.
    fun focusCollection() {
        scope.launch {
            withFrameNanos { }
            runCatching { content.requestFocus() }
        }
    }

    fun openTuner(genres: Boolean) {
        genresFirst = genres
        tunerUp = true
        scope.launch {
            cross.animateTo(1f, tween(TUNER_CROSS_MS, easing = QuadInOut))
            listGone = true
        }
    }

    fun closeTuner() {
        listGone = false
        tunerClosing = true
        bannerWanted = true
        scope.launch {
            cross.animateTo(0f, tween(TUNER_CROSS_MS, easing = QuadInOut))
            tunerUp = false
            tunerClosing = false
            // With no banner there is nothing asking; the collection's first item takes it instead.
            if (bannerWanted && bannerCards.isEmpty()) focusCollection()
        }
    }

    // The wheel's entries (PegasusChrome.RadioWheel.cs PickRadioWheel). News and Local set the tuner's
    // country and filters; Music and Tuner keep them.
    fun pick(entry: WheelEntry) {
        radio.tuner.wheelPicked = entry.ordinal
        val tuner = radio.tuner
        when (entry) {
            WheelEntry.Tuner -> openTuner(genres = false)
            WheelEntry.Search -> searching = true
            WheelEntry.Music -> {
                tuner.update { it.copy(favoritesOnly = false) }
                openTuner(genres = true)
            }
            WheelEntry.News -> {
                tuner.update { it.copy(favoritesOnly = false, country = null, sieve = StationSieve.OFF.copy(genre = NEWS)) }
                openTuner(genres = false)
            }
            WheelEntry.Local -> {
                val code = radio.listenerCountry()
                tuner.update { it.copy(favoritesOnly = false, sieve = StationSieve.OFF, country = code ?: it.country) }
                openTuner(genres = false)
            }
        }
    }

    fun openMenu(station: RadioStation, returnTo: FocusRequester) {
        val kept = radio.favorites.holds(station.uuid)
        menu.open(
            title = station.display,
            choices = buildList {
                add(MenuChoice(R.string.Radio_PlayStation) { onTune(station, rules) })
                // Not on a shared collection, whose saved stations are its owner's.
                if (saved.mine) add(MenuChoice(if (kept) R.string.Tv_Radio_RemoveFavorite else R.string.Tv_Radio_AddFavorite) { radio.favorites.toggle(station) })
            },
            returnTo = returnTo,
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                // The rest of the press that opened the wheel is swallowed, whatever is in front by then.
                if (key.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && swallowRight) {
                    if (key.action == KeyEvent.ACTION_UP) swallowRight = false
                    return@onPreviewKeyEvent true
                }
                // The wheel takes every key before anything behind it sees one, Back excepted.
                wheel?.let { return@onPreviewKeyEvent it.onKey(key) }
                if (key.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return@onPreviewKeyEvent false
                // Only radio's own screen opens the wheel; on the tuner the dial scans with a held Right.
                if (searching || tunerUp || menu.isOpen) return@onPreviewKeyEvent false
                // Right held for 2 seconds opens the wheel, decided on a repeat of the same press, on the
                // key's own clock; the repeats move nothing.
                if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount > 0) {
                    if (key.eventTime - key.downTime >= WHEEL_HOLD_MS) {
                        wheel = RadioWheelState(radio.tuner.wheelPicked, WheelEntry.entries.size, scope)
                        swallowRight = true
                    }
                    return@onPreviewKeyEvent true
                }
                false
            },
    ) {
        if (!listGone) Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - cross.value }
                .focusRequester(content)
                .focusGroup(),
        ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val strip = maxWidth - 68.px
            val density = LocalDensity.current
            val warming by rememberUpdatedState(Warming(bannerCards, essentialShown, favoritesShown, rules))
            LaunchedEffect(Unit) {
                if (opened) return@LaunchedEffect
                // The essential stations first, so the rows are known; then their pictures, logos, colours and
                // lines, decoded at the sizes the cards draw them. Neither is waited on for longer than its cap: a
                // slow directory or a slow station page opens the page as it is.
                withTimeoutOrNull(OPEN_READ_MS) { snapshotFlow { essential !is Essential.NotRead }.first { it } }
                withTimeoutOrNull(OPEN_WARM_MS) { warmRadioHome(context, images, blurbs, warming, RadioSizes.of(strip, density)) }
                opened = true
                reveal.animateTo(1f, tween(OPEN_FADE_MS, easing = EaseOutQuad))
            }
            if (reveal.value < 1f) {
                // Shown only once the opening has taken [SPINNER_WAIT_MS], so a quick one shows no record at all.
                val spinnerIn = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    delay(SPINNER_WAIT_MS)
                    spinnerIn.animateTo(1f, tween(OPEN_FADE_MS))
                }
                RecordSpinner(Modifier.align(Alignment.Center).size(64.s).graphicsLayer { alpha = spinnerIn.value * (1f - reveal.value) })
            }
            if (opened) CompositionLocalProvider(LocalBringIntoViewSpec provides CentreRadioRows) {
                LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(start = RADIO_MARGIN, end = RADIO_MARGIN, top = 64.px, bottom = 34.s),
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        alpha = reveal.value
                        // The whole page in one fade: its alpha on each draw, no screen-sized layer to compose it in,
                        // which the Amiko's GPU cannot afford (as the screens' own fade, StackedScreen).
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
                ) {
                    if (bannerCards.isNotEmpty()) {
                        item(key = "banner") {
                            StationBanner(
                                stations = bannerCards,
                                strip = strip,
                                rules = rules,
                                images = images,
                                blurbs = blurbs,
                                playing = playing,
                                onTune = { onTune(it, rules) },
                                onMenu = ::openMenu,
                                takeFocus = bannerWanted && wheel == null && !searching && (!tunerUp || tunerClosing),
                                onFocusTaken = { bannerWanted = false },
                                // Down from the banner goes to the first of the mode keys, not to whichever is
                                // nearest under the front card.
                                modifier = Modifier.focusRequester(bannerKey).focusProperties { down = firstMode },
                            )
                        }
                    }
                    // The five ways in, under the banner, out of sight until the remote comes down to them.
                    item(key = "modes") {
                        RadioModes(self = firstMode, onPick = ::pick)
                    }
                    // Nothing until the first answer, so no country is named before it is known.
                    when (val read = essential) {
                        Essential.NotRead -> {}
                        Essential.Unavailable -> item(key = "essential") {
                            RadioHeading(stringResource(R.string.Viz_RadioPopular))
                            Note(stringResource(R.string.Radio_DirectoryDown))
                        }
                        is Essential.Read -> item(key = "essential") {
                            val country = read.countryCode?.let { countryName(it, locale) }
                            RadioHeading(if (country == null) stringResource(R.string.Viz_RadioPopular) else stringResource(R.string.Viz_RadioEssential, country))
                            if (essentialShown.isNotEmpty()) {
                                Box(Modifier.upToModes(firstMode)) {
                                    StationRow(essentialShown, across = 4, wide = true, strip, rules, images, blurbs, locale, onTune, ::openMenu)
                                }
                            }
                        }
                    }
                    item(key = "favorites") {
                        RadioHeading(stringResource(R.string.Viz_RadioFavorites))
                        if (favoritesShown.isEmpty()) {
                            Note(stringResource(R.string.Tv_Radio_NoFavorites))
                        } else {
                            // Up from the favourites reaches the mode keys only where there is no essential row between.
                            val under = essential !is Essential.Read || essentialShown.isEmpty()
                            Box(if (under) Modifier.upToModes(firstMode) else Modifier) {
                                StationRow(favoritesShown, across = 6, wide = false, strip, rules, images, null, locale, onTune, ::openMenu)
                            }
                        }
                    }
                }
            }
        }
        }

        if (tunerUp) {
            // The keys' glow is 30% softer on the tuner.
            androidx.compose.runtime.CompositionLocalProvider(LocalGlowStrength provides TUNER_GLOW) {
                RadioTuner(
                    radio = radio,
                    images = images,
                    playing = tuned,
                    genresFirst = genresFirst,
                    onTune = onTuneHere,
                    onStop = onStop,
                    onClose = ::closeTuner,
                    modifier = Modifier.graphicsLayer { alpha = cross.value },
                )
            }
        }
        if (searching) {
            StationSearch(radio, rules, images, backdrop, mini, onTune = { onTune(it, rules) }, onMenu = ::openMenu, onClose = {
                searching = false
                bannerWanted = true
                if (bannerCards.isEmpty()) focusCollection()
            })
        }
        wheel?.let { shown ->
            RadioWheel(
                state = shown,
                entries = WheelEntry.entries.map { stringResource(it.label) },
                backdrop = backdrop,
                onPick = { i ->
                    wheel = null
                    pick(WheelEntry.entries[i])
                },
                onClose = { wheel = null },
            )
        }
    }

    RowMenu(menu)
}

/**
 * Whether radio's own screen is in front, with no wheel, search or tuner over it (the desktop's
 * RadioHomeInFront). The held Left that opens the side menu asks it.
 */
@Stable
class RadioFront {
    var inFront by mutableStateOf(true)
        internal set
}

/** The radio's ways in: the wheel's entries, top to bottom (PegasusChrome.RadioWheel.cs), and the mode keys
 * under the banner, left to right, each with the glyph its key shows. */
private enum class WheelEntry(@StringRes val label: Int, val glyph: Char) {
    Tuner(R.string.Viz_RadioTuner, Fa.TOWER),
    Search(R.string.Viz_MenuSearch, Fa.SEARCH),
    Music(R.string.Viz_RadioMusic, Fa.MUSIC),
    News(R.string.Viz_RadioNews, Fa.NEWSPAPER),
    Local(R.string.Viz_RadioLocal, Fa.PIN),
}

/**
 * The radio's five ways in (the Apple TV's RadioView mode keys) as a carousel under the banner, out of sight until
 * the remote comes down to it. Down from the banner gives it the focus: it slides down into place from above over
 * [MODES_IN_MS] as it fades in, taking its room as it comes, and it goes the same way once the remote moves on.
 *
 * It works as the banner over it does: one stop for the remote, the key in hand in the middle, lit, and [MODES_SIDE]
 * keys at each side of it, dimmer the further out they stand. Left and Right turn it, sliding the keys along by one
 * over 220 ms, and it wraps: with five ways in, the keys at the ends are the far side of the round. OK takes the key in
 * the middle. Each key is its glyph and its name in the menu key's glass (PegasusMenuButton: `#14FFFFFF`, a 1 px
 * `#B3FFFFFF` edge, corner 6·s), [MODE_WIDTH] wide and 52·s high, 30·s under the banner.
 */
@Composable
private fun RadioModes(self: FocusRequester, onPick: (WheelEntry) -> Unit) {
    val entries = WheelEntry.entries
    // Which key is in the middle, counted without wrapping, so a turn always slides the way it was made.
    var at by remember { mutableIntStateOf(0) }
    val turn = remember { Animatable(0f) }
    var focused by remember { mutableStateOf(false) }
    val shown = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val motion = LocalMotion.current
    LaunchedEffect(focused) {
        val to = if (focused) 1f else 0f
        if (motion >= Motion.Still) shown.snapTo(to) else shown.animateTo(to, tween(MODES_IN_MS, easing = EaseOutQuad))
    }
    fun step(by: Int) {
        at += by
        scope.launch {
            turn.snapTo(by.toFloat())
            turn.animateTo(0f, tween(220, easing = BannerOut))
        }
    }
    val density = LocalDensity.current
    val drop = with(density) { MODES_DROP.toPx() }
    val pitch = with(density) { (MODE_WIDTH + MODE_GAP).toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            // Its room grows and shrinks with it, so the rows under it glide rather than jump.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, (placeable.height * shown.value).roundToInt()) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                alpha = shown.value
                translationY = -(1f - shown.value) * drop
            }
            .focusRequester(self)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { step(-1); true }
                    Key.DirectionRight -> { step(1); true }
                    else -> false
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                onPick(entries[at.mod(entries.size)])
            },
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 30.s).height(52.s * MODE_GROW)) {
            // One key past each end as well, so a turn slides a key in from the edge rather than drawing it there.
            for (d in -(MODES_SIDE + 1)..(MODES_SIDE + 1)) {
                val entry = entries[(at + d).mod(entries.size)]
                val shape = RoundedCornerShape(6.s)
                androidx.compose.runtime.key(d) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                val place = d + turn.value
                                val far = abs(place)
                                // The middle key, lit while the carousel has the remote; the others dimmer the
                                // further out, and gone past the last of [MODES_SIDE].
                                val lit = (1f - far).coerceIn(0f, 1f) * (if (focused) 1f else 0f)
                                translationX = place * pitch
                                alpha = when {
                                    far <= MODES_SIDE -> 1f - far * MODE_FADE
                                    else -> ((MODES_SIDE + 1 - far) * (1f - MODES_SIDE * MODE_FADE)).coerceAtLeast(0f)
                                }
                                scaleX = 1f + (MODE_GROW - 1f) * lit
                                scaleY = scaleX
                            }
                            .size(MODE_WIDTH, 52.s)
                            .background(if (d == 0 && focused) Color(0x40FFFFFF) else Color(0x14FFFFFF), shape)
                            .border(if (d == 0 && focused) 2.px else 1.px, if (d == 0 && focused) Color.White else Color(0xB3FFFFFF), shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(Modifier.padding(horizontal = 16.s), verticalAlignment = Alignment.CenterVertically) {
                            Glyph(entry.glyph, 17.ts, Ink.Words)
                            Spacer(Modifier.width(10.s))
                            Text(stringResource(entry.label), style = ui(19.ts, FontWeight.SemiBold), color = Ink.Words, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Up out of the row this is on goes to the mode keys ([modes]), which are out of sight, and so no part of the
 * remote's search by layout, while the remote is below them. The row is one line of cards, so Up from any of them
 * leaves it; the key is taken before the search runs. (A redirect from the group's onExit was not honoured: the
 * search went on to the back key in the corner.)
 */
private fun Modifier.upToModes(modes: FocusRequester): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionUp) return@onPreviewKeyEvent false
        runCatching { modes.requestFocus() }.isSuccess
    }

/** How many keys of the carousel stand at each side of the middle one, and how much dimmer each step out is. */
private const val MODES_SIDE = 3
private const val MODE_FADE = 0.2f

/** A mode key's width, the room between two, and how much the middle key grows while it has the remote. */
private val MODE_WIDTH = 196.s
private val MODE_GAP = 14.s
private const val MODE_GROW = 1.08f

/** How long the carousel takes to come and go, and how far above its place it starts. */
private const val MODES_IN_MS = 260
private val MODES_DROP = 26.s

/** The genre the wheel's News sets, by its name in [com.rpm.player.core.radio.RadioGenres]. */
private const val NEWS = "News"
private const val TUNER_CROSS_MS = 160

@OptIn(ExperimentalFoundationApi::class)
private val CentreRadioRows = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset + size / 2 - containerSize / 2
}

/** A heading on the radio screen: [HEADING_TEXT] (22·s) SemiBold `#F2FFFFFF`, 30·s above and 12·s below. */
@Composable
private fun RadioHeading(text: String) {
    Text(text, style = ui(HEADING_TEXT, FontWeight.SemiBold), color = Ink.Card, modifier = Modifier.padding(top = 30.s, bottom = 12.s))
}

/** A note on the radio screen: 18·s `#99FFFFFF` (the desktop's 14, read from a couch). */
@Composable
private fun Note(text: String) {
    Text(text, style = ui(18.ts), color = Ink.Dim)
}

// ── the banner ──

/**
 * The banner, laid out as home's is ([Banner]), so that its cards do not clip through each other: the front
 * card is a plate `min(0.68 strip, 760·s)` wide and 352·s high in the middle, drawn over the two it overlaps,
 * and the cards before and after it peek out at the left and
 * right, cut at the plate's edge less 24·s, 330·s high. With fewer than 124·s at each side there are no peeks. A row
 * of dots under it says where the front card is. Left and Right step it: the cards swap at once and the strip slides
 * in from 180·s over 220 ms, from the side it came from. It wraps. One stop for the remote, the front card; OK tunes
 * it, a held OK opens its menu. The front card grows and every other card is dimmed as a card is ([rememberCardLook]).
 *
 * Every card is composed for as long as the banner is up, keyed by its station, and only the ones on show are
 * placed, so a card keeps its colours, blurb and logo however often it comes round.
 */
@Composable
private fun StationBanner(
    stations: List<RadioStation>,
    strip: Dp,
    rules: StationRules,
    images: ImageLoader,
    blurbs: StationBlurbs,
    playing: StationPlaying?,
    onTune: (RadioStation) -> Unit,
    onMenu: (RadioStation, FocusRequester) -> Unit,
    takeFocus: Boolean,
    onFocusTaken: () -> Unit,
    modifier: Modifier,
) {
    var index by remember(stations) { mutableIntStateOf(0) }
    val front = index.coerceIn(0, stations.size - 1)
    val slide = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val self = remember { FocusRequester() }
    // Asked here, after the key below is composed and attached.
    LaunchedEffect(takeFocus) {
        if (!takeFocus) return@LaunchedEffect
        runCatching { self.requestFocus() }
        onFocusTaken()
    }

    fun step(by: Int) {
        if (stations.size < 2) return
        index = (index + by).mod(stations.size)
        scope.launch {
            slide.snapTo(by * with(density) { 180.s.toPx() })
            slide.animateTo(0f, tween(220, easing = BannerOut))
        }
    }

    var focused by remember { mutableStateOf(false) }
    val plate = androidx.compose.ui.unit.min(strip * 0.68f, 760.s)
    val sideRoom = (strip - plate) / 2
    val peeks = sideRoom >= 124.s && stations.size > 1
    val peek = androidx.compose.ui.unit.min(sideRoom + 24.s, plate)
    val inset = max(androidx.compose.ui.unit.Dp(0f), sideRoom + 24.s - plate)

    Column(Modifier.width(strip), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(PLATE_HIGH)) {
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = slide.value }) {
                val n = stations.size
                fun placeOf(i: Int): Int? = when {
                    i == front -> 0
                    peeks && i == (front - 1).mod(n) -> -1
                    peeks && i == (front + 1).mod(n) -> 1
                    else -> null
                }
                stations.forEachIndexed { i, station ->
                    androidx.compose.runtime.key(station.uuid) {
                        StationSlot(station, placeOf(i), focused, plate, peek, inset, rules, images, blurbs, playing)
                    }
                }
                // With two stations the one not in front shows at both sides: the right is a second card.
                if (peeks && n == 2) {
                    val station = stations[(front + 1).mod(n)]
                    androidx.compose.runtime.key(station.uuid, "right") {
                        StationSlot(station, 1, focused, plate, peek, inset, rules, images, blurbs, playing)
                    }
                }
            }
            // The remote's one stop, over the plate; it does not slide. It draws nothing: the cards under it grow
            // and dim.
            Box(
                modifier
                    .align(Alignment.Center)
                    .size(plate, PLATE_HIGH)
                    .focusRequester(self)
                    .heldOkMenu { onMenu(stations[front], self) }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> { step(-1); stations.size > 1 }
                            Key.DirectionRight -> { step(1); stations.size > 1 }
                            else -> false
                        }
                    }
                    .onFocusChanged { focused = it.isFocused }
                    .remembersFocus()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onTune(stations[front]) },
            )
            if (stations.size > 1) {
                Box(Modifier.align(Alignment.CenterStart).padding(start = 4.s).size(44.s), contentAlignment = Alignment.Center) {
                    Glyph(Fa.CHEVRON_LEFT, 15.ts, Color.White.copy(alpha = 0.7f))
                }
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 4.s).size(44.s), contentAlignment = Alignment.Center) {
                    Glyph(Fa.CHEVRON_RIGHT, 15.ts, Color.White.copy(alpha = 0.7f))
                }
            }
        }
        if (stations.size > 1) {
            Row(Modifier.padding(top = DOTS_GAP), horizontalArrangement = Arrangement.spacedBy(7.s)) {
                stations.indices.forEach { i ->
                    Box(Modifier.size(16.s), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(7.s).background(Color.White.copy(alpha = if (i == front) 1f else 0.32f), RoundedCornerShape(4.s)))
                    }
                }
            }
        }
    }
}

/**
 * One station's card in the banner, at [place]: -1 peeking at the left, 1 at the right, 0 the plate in the middle,
 * null not on show (measured, not placed: it stays composed and draws nothing). One call whatever the place, which
 * only changes the modifiers, as home's [BannerSlot] does. A peek shows the side of the card nearest the screen's
 * edge, at the plate's width.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.StationSlot(
    station: RadioStation,
    place: Int?,
    focused: Boolean,
    plate: Dp,
    peek: Dp,
    inset: Dp,
    rules: StationRules,
    images: ImageLoader,
    blurbs: StationBlurbs,
    playing: StationPlaying?,
) {
    val look = rememberCardLook(focused && place == 0)
    val high = if (place == -1 || place == 1) PEEK_HIGH else PLATE_HIGH
    val slot = when (place) {
        -1 -> Modifier
            .align(Alignment.CenterStart)
            .padding(start = inset)
            .size(peek, PEEK_HIGH)
            .clip(RoundedCornerShape(topStart = 12.s, bottomStart = 12.s))
        1 -> Modifier
            .align(Alignment.CenterEnd)
            .padding(end = inset)
            .size(peek, PEEK_HIGH)
            .clip(RoundedCornerShape(topEnd = 12.s, bottomEnd = 12.s))
        0 -> Modifier
            .zIndex(1f)
            .align(Alignment.Center)
            .size(plate, PLATE_HIGH)
            .graphicsLayer {
                scaleX = look.grow
                scaleY = look.grow
            }
        else -> Modifier.align(Alignment.Center).size(plate, PLATE_HIGH).unplaced()
    }
    val face = when (place) {
        -1 -> Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
        1 -> Modifier.wrapContentWidth(Alignment.End, unbounded = true)
        else -> Modifier
    }
    Box(slot) {
        StationBannerCard(
            station = station,
            width = plate,
            height = high,
            rules = rules,
            images = images,
            blurbs = blurbs,
            playing = playing,
            live = place == 0,
            dim = { 1f - look.light },
            modifier = face,
        )
    }
}

private val BannerOut = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

/** The front card's height and a peeking card's, as home's banner has them. */
private val PLATE_HIGH = 352.s
private val PEEK_HIGH = 330.s

/** How strong a focused key's glow is on the tuner, against 1 elsewhere. */
private const val TUNER_GLOW = 0.7f

/** The radio screen's margin at each side. */
private val RADIO_MARGIN = 34.px

/**
 * One banner card: the glass, a room in the station's colours (drifting on the front card only), a
 * veil, the logo, the name (22·s Bold), what the station is (18·s, two lines, 420·s wide), what it plays
 * now when it is the one playing (16·s), the dim ([dim], read when drawn, so its ease does not compose the
 * card again), and a 1 px `#33FFFFFF` rim, corner 12·s. The two lines
 * are home's station card's sizes, larger than the desktop's 13 and 11.5 to be read from a couch.
 */
@Composable
private fun StationBannerCard(
    station: RadioStation,
    width: Dp,
    height: Dp,
    rules: StationRules,
    images: ImageLoader,
    blurbs: StationBlurbs,
    playing: StationPlaying?,
    live: Boolean,
    dim: () -> Float,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(12.s)
    val logo = StationLogo.of(station, rules.logoStamp)
    val palette = stationPalette(station, logo, images)
    // Keyed on the admin's description too, so an edit that lands while the card is up is shown.
    val blurb by produceState<String?>(null, station.uuid, station.edit?.description) { value = blurbs.of(station) }
    Box(modifier.size(width, height).clip(shape)) {
        Box(Modifier.matchParentSize().background(Color(0x1AFFFFFF)))
        CardRoomView(palette, apart = 0, drift = live, fewest = 8, soften = 0f, modifier = Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Color(0x26000000)))
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.s),
        ) {
            Box(Modifier.height(height * 0.40f), contentAlignment = Alignment.Center) {
                var missing by remember(logo) { mutableStateOf(false) }
                if (missing) {
                    Glyph(Fa.RADIO, (height.value * 0.20f).sp, Color(0x59FFFFFF))
                } else {
                    val context = LocalContext.current
                    // Decoded for the front plate's box whatever this card's place, so a peek and the front card share
                    // one decode, and the opening's ([warmRadioHome]) is the one found.
                    val edge = with(LocalDensity.current) { logoEdge((width * BANNER_LOGO_WIDE).roundToPx(), (PLATE_HIGH * BANNER_LOGO_HIGH).roundToPx()) }
                    AsyncImage(
                        model = remember(logo, edge) { bareLogoRequest(context, logo, edge) },
                        imageLoader = images,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        onState = { if (it is AsyncImagePainter.State.Error) missing = true },
                        modifier = Modifier.sizeIn(maxWidth = width * BANNER_LOGO_WIDE, maxHeight = height * BANNER_LOGO_HIGH),
                    )
                }
            }
            Text(station.display, style = ui(22.ts, FontWeight.Bold), color = Ink.Words, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = width * 0.8f))
            blurb?.let {
                Text(it, style = ui(18.ts).copy(lineHeight = 24.ts), color = Color(0xD1FFFFFF), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.s))
            }
            val said = playing?.takeIf { station.answersTo(it.logo.uuid) }?.said as? Icy.Said.Song
            if (said != null) {
                Text(
                    stringResource(R.string.Radio_NowPlayingLine, listOfNotNull(said.title, said.artist).joinToString(" - ")),
                    style = ui(16.ts),
                    color = Color(0x8CFFFFFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = width * 0.7f),
                )
            }
        }
        Box(
            Modifier.matchParentSize().drawBehind {
                val d = dim()
                if (d > 0f) drawRect(Color.Black.copy(alpha = d))
            },
        )
        Box(Modifier.matchParentSize().border(1.px, Color(0x33FFFFFF), shape))
    }
}

private val Float.sp get() = androidx.compose.ui.unit.TextUnit(this, androidx.compose.ui.unit.TextUnitType.Sp)

/**
 * A station's room colours: its logo's (CoverRoom.Of), and until the logo has been read, a colour picked
 * from its name, so a card is never drawn grey while its logo loads.
 */
@Composable
internal fun stationPalette(station: RadioStation, logo: StationLogo, images: ImageLoader): RoomPalette {
    val reading = rememberCoverReading(logo, images)
    // A station with no logo yet takes a colour off the palette's ring by its name.
    val ring = RpmPalette.RING
    return reading?.room ?: remember(station.uuid) { RoomPalette.from(ring[abs(station.display.lowercase().hashCode()) % ring.size], null, null) }
}

// ── the rows ──

/**
 * A row of stations (RadioRowCard): [across] cards across the strip, 22·s apart; the essential row's
 * pictures are 16:9, the favourites' square. Entering the row lands on its first card the first time and
 * on the last card focused after that.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationRow(
    stations: List<RadioStation>,
    across: Int,
    wide: Boolean,
    strip: Dp,
    rules: StationRules,
    images: ImageLoader,
    blurbs: StationBlurbs?,
    locale: Locale,
    onTune: (RadioStation, StationRules) -> Unit,
    onMenu: (RadioStation, FocusRequester) -> Unit,
) {
    val gap = ROW_GAP
    val width = rowCardWidth(strip, across)
    val first = remember { FocusRequester() }
    // The row reaches the screen's edges and is padded by the page's margin, so a focused card at either end
    // grows past the margin instead of being cut by the row's edge. The air is counted from the row's edge.
    val air = with(LocalDensity.current) { (24.px + RADIO_MARGIN).toPx() }
    CompositionLocalProvider(LocalBringIntoViewSpec provides remember(air) { RevealStation(air) }) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(gap),
            contentPadding = PaddingValues(horizontal = RADIO_MARGIN),
            // The row has no layer of its own, and a row is not cut above and below: no room is needed there.
            modifier = Modifier.bleed(RADIO_MARGIN, RADIO_MARGIN, vertical = 0.px).focusRestorer(first),
        ) {
            itemsIndexed(stations, key = { _, station -> station.uuid }) { i, station ->
                StationCard(
                    station = station,
                    logo = StationLogo.of(station, rules.logoStamp),
                    // Only the essential row's wide cards wear the admin's 16:9 picture.
                    picture = if (wide) rules.cards.addressOf(station) else null,
                    images = images,
                    blurbs = blurbs,
                    locale = locale,
                    wide = wide,
                    onClick = { onTune(station, rules) },
                    onMenu = onMenu,
                    modifier = Modifier.width(width).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private class RevealStation(private val air: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        size + air * 2 > containerSize || offset < air -> offset - air
        offset + size > containerSize - air -> offset + size - containerSize + air
        else -> 0f
    }
}

/**
 * A station's card: a still room in its colours with corner 10·s, the logo in a well in the middle, then 7·s under it
 * the name ([CARD_TITLE_TEXT] SemiBold) and the country and quality ([CARD_LINE_TEXT], `#99FFFFFF`): the album wall's
 * sizes, where the desktop has 14 and 12.
 *
 * The essential row's wide cards ([wide], 16:9) are dressed differently: the card picture the admin chose ([picture],
 * cropped to the card's middle) under a wash in the logo's own main colour, or the room (softened,
 * [ESSENTIAL_SOFTEN]) where there is no picture, with no logo drawn over it (the admin's pictures carry the station's
 * own mark); and under it, centred, the name and what the station is ([blurbs]), or its country where nothing says.
 */
@Composable
private fun StationCard(
    station: RadioStation,
    logo: StationLogo,
    picture: String?,
    images: ImageLoader,
    blurbs: StationBlurbs?,
    locale: Locale,
    wide: Boolean,
    onClick: () -> Unit,
    onMenu: (RadioStation, FocusRequester) -> Unit,
    modifier: Modifier,
) {
    if (wide) {
        WideStationCard(station, logo, picture, images, blurbs, locale, onClick, onMenu, modifier)
        return
    }
    val self = remember { FocusRequester() }
    val palette = stationPalette(station, logo, images)
    CardButton(
        onClick = onClick,
        modifier = modifier.focusRequester(self).heldOkMenu { onMenu(station, self) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.s)) {
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 1f).clip(RoundedCornerShape(10.s))) {
                CardRoomView(palette, apart = 0, drift = false, fewest = 32, soften = if (wide) ESSENTIAL_SOFTEN else 0f, modifier = Modifier.matchParentSize())
                if (picture != null) {
                    CoverImage(picture, images, ContentScale.Crop, Modifier.matchParentSize(), onShown = {})
                    Box(Modifier.matchParentSize().background(CARD_PICTURE_VEIL))
                }
                LogoWell(logo, images, side = maxHeight * 0.5f, modifier = Modifier.align(Alignment.Center))
            }
            Column {
                Text(station.display, style = ui(CARD_TITLE_TEXT, FontWeight.SemiBold), color = Ink.Card, maxLines = 1, overflow = TextOverflow.Ellipsis)
                station.detail(locale)?.let { Text(it, style = ui(CARD_LINE_TEXT), color = Ink.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.s)) }
            }
        }
    }
}

/** The essential row's card ([StationCard], [wide]). */
@Composable
private fun WideStationCard(
    station: RadioStation,
    logo: StationLogo,
    picture: String?,
    images: ImageLoader,
    blurbs: StationBlurbs?,
    locale: Locale,
    onClick: () -> Unit,
    onMenu: (RadioStation, FocusRequester) -> Unit,
    modifier: Modifier,
) {
    val self = remember { FocusRequester() }
    val palette = stationPalette(station, logo, images)
    // The logo's main colour, read once for the room as well; the picture is washed in it.
    val tint = rememberCoverReading(logo, images)?.dominant?.let { Color(it.r, it.g, it.b) }
    // Keyed on the admin's description too, so an edit that lands while the card is up is shown.
    val blurb by produceState(blurbs?.peek(station), station.uuid, station.edit?.description) { if (blurbs != null) value = blurbs.of(station) }
    val line = blurb ?: (station.countryCode?.let { countryName(it, locale) } ?: station.country?.takeIf { it.isNotBlank() })
    CardButton(
        onClick = onClick,
        modifier = modifier.focusRequester(self).heldOkMenu { onMenu(station, self) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.s), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.s))) {
                CardRoomView(palette, apart = 0, drift = false, fewest = 32, soften = ESSENTIAL_SOFTEN, modifier = Modifier.matchParentSize())
                if (picture != null) {
                    CoverImage(picture, images, ContentScale.Crop, Modifier.matchParentSize(), onShown = {})
                    Box(Modifier.matchParentSize().background(tint?.copy(alpha = PICTURE_TINT) ?: CARD_PICTURE_VEIL))
                }
            }
            Text(station.display, style = ui(CARD_TITLE_TEXT, FontWeight.SemiBold), color = Ink.Card, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            line?.let { Text(it, style = ui(CARD_LINE_TEXT), color = Ink.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

/** Laid over a card picture where the logo's colour is not read yet. */
private val CARD_PICTURE_VEIL = Color(0x40000000)

/** How strongly an essential card's picture is washed in its logo's colour. */
private const val PICTURE_TINT = 0.55f

/** The box a banner card's bare logo is fitted into, as shares of the card. */
private const val BANNER_LOGO_WIDE = 0.55f
private const val BANNER_LOGO_HIGH = 0.40f

/** The room between two cards of a row. */
private val ROW_GAP = 22.s

/** A card's width in a row of [across] across the [strip]. */
private fun rowCardWidth(strip: Dp, across: Int): Dp = (strip - ROW_GAP * (across - 1)) / across

/** The square a bare logo is decoded into for a box [width] by [height] pixels: its longer side, rounded up to 32
 * pixels, so two boxes a pixel apart share one decode. */
private fun logoEdge(width: Int, height: Int): Int = ((maxOf(width, height, 1) + 31) / 32) * 32

/** A bare logo's request, the same wherever it is made, so a decode ahead is found by the banner card ([StationBannerCard]). */
private fun bareLogoRequest(context: android.content.Context, logo: StationLogo, edge: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(logo)
        .size(edge)
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .build()

// ── the opening ──

/** The stations the radio home shows, read for its opening. */
private class Warming(val banner: List<RadioStation>, val essential: List<RadioStation>, val favorites: List<RadioStation>, val rules: StationRules)

/** The sizes in pixels the radio home's cards draw their pictures at, for a [strip] of the screen's width. */
private class RadioSizes(val plate: Int, val plateHigh: Int, val wide: Int, val square: Int, val bannerLogo: Int, val mark: Int) {
    companion object {
        fun of(strip: Dp, density: androidx.compose.ui.unit.Density): RadioSizes = with(density) {
            val plate = androidx.compose.ui.unit.min(strip * 0.68f, 760.s)
            val wide = rowCardWidth(strip, 4)
            val square = rowCardWidth(strip, 6)
            RadioSizes(
                plate = plate.roundToPx(),
                plateHigh = PLATE_HIGH.roundToPx(),
                wide = wide.roundToPx(),
                square = square.roundToPx(),
                bannerLogo = logoEdge((plate * BANNER_LOGO_WIDE).roundToPx(), (PLATE_HIGH * BANNER_LOGO_HIGH).roundToPx()),
                // The well is half the square card, and the mark is made at the well's size ([StationMark]).
                mark = markEdge((square * 0.5f).roundToPx()),
            )
        }
    }
}

/**
 * Everything the radio home's first screen draws, decoded before it is drawn, so the page comes up whole rather
 * than a logo, a room's colours and a line at a time, with the stutter of each. Every request is the one the card
 * makes, at the card's size, so the card finds it in memory: the banner's logos ([bareLogoRequest]), the essential
 * cards' pictures ([aheadRequest]), the favourites' made marks ([stationMarkRequest]) for the first
 * [WARM_FAVORITES], every station's colours ([readCoverColours]) and the essential and banner stations' lines
 * ([StationBlurbs]). All at once; the caller caps the wait.
 */
private suspend fun warmRadioHome(context: android.content.Context, images: ImageLoader, blurbs: StationBlurbs, warming: Warming, sizes: RadioSizes) = coroutineScope {
    val rules = warming.rules
    fun logoOf(station: RadioStation) = StationLogo.of(station, rules.logoStamp)
    val asks = ArrayList<Deferred<Any?>>()
    fun ask(block: suspend () -> Any?) {
        asks += async {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
    for (station in warming.banner) {
        val logo = logoOf(station)
        ask { images.execute(bareLogoRequest(context, logo, sizes.bannerLogo)) }
        ask { readCoverColours(images, logo, context) }
        ask { blurbs.of(station) }
    }
    for (station in warming.essential) {
        val logo = logoOf(station)
        ask { readCoverColours(images, logo, context) }
        ask { blurbs.of(station) }
        rules.cards.addressOf(station)?.let { picture -> ask { images.execute(aheadRequest(context, picture, sizes.wide, sizes.wide * 9 / 16)) } }
    }
    for (station in warming.favorites.take(WARM_FAVORITES)) {
        val logo = logoOf(station)
        ask { images.execute(stationMarkRequest(context, logo, CARD_MARK_INSET, sizes.mark, 0)) }
        ask { readCoverColours(images, logo, context) }
    }
    asks.awaitAll()
}

/** How long the opening waits for the essential stations, and then for their pictures, before showing the page. */
private const val OPEN_READ_MS = 2500L
private const val OPEN_WARM_MS = 2500L

/** How long the page takes to fade in once it is ready, and how long before the turning record shows at all. */
private const val OPEN_FADE_MS = 360
private const val SPINNER_WAIT_MS = 150L

/** How many favourites' marks are decoded ahead: the row's first screen and the next. */
private const val WARM_FAVORITES = 12

/** How far the mark sits off the edge of a station card's well. */
private const val CARD_MARK_INSET = 4f / 51f

/**
 * A station's logo in its well (StationWell): [side] square, corner `side * 6 / 51`, `#22000000` with a
 * 1 px `#18FFFFFF` edge, the logo dressed by [StationMark] and held `side * 4 / 51` off the edges where
 * it does not fill; the antenna at `side * 20 / 51` in `#44FFFFFF` for a station with no logo.
 */
@Composable
fun LogoWell(logo: StationLogo, images: ImageLoader, side: Dp, modifier: Modifier, songCover: String? = null) {
    val shape = RoundedCornerShape(side * 6f / 51f)
    var missing by remember(logo) { mutableStateOf(false) }
    Box(
        modifier.size(side).clip(shape).background(Color(0x22000000)).border(1.px, Color(0x18FFFFFF), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (missing) {
            Glyph(Fa.ANTENNA, (side.value * 20f / 51f).sp, Color(0x44FFFFFF))
        } else {
            StationMark(
                logo,
                images,
                inset = CARD_MARK_INSET, fadeInMs = 0,
                onState = { if (it is AsyncImagePainter.State.Error) missing = true },
                modifier = Modifier.matchParentSize(),
            )
        }
        // The song the station is playing, where its cover was found, over the logo ([SongCoverOver]).
        SongCoverOver(songCover, images, Modifier.matchParentSize())
    }
}

/**
 * A station's logo as the desktop dresses it ([StationBadge]): a mark with nothing behind it on its made
 * ground, a square picture filling the box, anything else fitted whole; the last two kinds and the mark
 * held [inset] of the edge off each side. Fills its square box; the caller clips it to its shape.
 *
 * The logo is read at no less than [MARK_READ] pixels whatever the box, so whether it is a mark, and the
 * colours of its ground, are decided on the same picture on every surface. The badge is made at the
 * box's size rounded up to 32 pixels, so a box a pixel larger reuses it.
 *
 * The badge fades in over [fadeInMs] once it is made; 0 shows it at once. A card shows it at once, since a
 * fade would draw its kept layer again on every frame of it; the player's sleeve fades it in, so it does not
 * appear in one frame on the empty sleeve.
 */
@Composable
fun StationMark(logo: StationLogo, images: ImageLoader, inset: Float, fadeInMs: Int, onState: (AsyncImagePainter.State) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    BoxWithConstraints(modifier) {
        val edge = markEdge(constraints.maxWidth)
        val request = remember(logo, inset, edge, fadeInMs) { stationMarkRequest(context, logo, inset, edge, fadeInMs) }
        AsyncImage(
            model = request,
            imageLoader = images,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            onState = onState,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/** The size a mark is made at for a box [width] pixels wide: rounded up to 32 pixels, at most [MARK_MOST]. */
private fun markEdge(width: Int): Int = ((width.coerceIn(1, MARK_MOST) + 31) / 32) * 32

/** A made mark's request ([StationMark]), the same wherever it is made, so a decode ahead is found by the card. */
private fun stationMarkRequest(context: android.content.Context, logo: StationLogo, inset: Float, edge: Int, fadeInMs: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(logo)
        .size(maxOf(edge, MARK_READ))
        .allowHardware(false)
        .transformations(StationBadge(logo.uuid, inset, edge))
        .crossfade(fadeInMs)
        .build()

private const val MARK_READ = 256
private const val MARK_MOST = 1024

// ── the search ──

/**
 * The station search over the screen (RadioSearch.cs): the screen blurred under `#33000000`, a column
 * `max(560, w / 2)` wide in the middle, the search field (`64·s` high, corner 12·s, the light glass, 26·s
 * type) with the go key beside it, and under it a card of the stations found, four across. Asked 450 ms
 * after typing stops; the next keystroke cancels the search before it. Back closes it.
 */
@Composable
private fun StationSearch(
    radio: RadioRepository,
    rules: StationRules,
    images: ImageLoader,
    backdrop: Backdrop,
    mini: MiniPlayerState,
    onTune: (RadioStation) -> Unit,
    onMenu: (RadioStation, FocusRequester) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // The search covers the whole screen, the mini player with it.
    androidx.compose.runtime.DisposableEffect(mini) {
        mini.cover()
        onDispose { mini.uncover() }
    }
    var search by remember { mutableStateOf<Search>(Search.Idle) }
    val field = remember { FocusRequester() }
    val firstFound = remember { FocusRequester() }
    BackHandler(enabled = LocalOnTop.current, onBack = onClose)
    LaunchedEffect(Unit) {
        runCatching { field.requestFocus() }
        backdrop.capture()
    }
    LaunchedEffect(query) {
        val text = query.trim()
        if (text.isEmpty()) {
            search = Search.Idle
            return@LaunchedEffect
        }
        delay(TYPING_PAUSE_MS)
        // The turning record while the directory is asked, where the stations will be (the desktop's
        // RadioResultsSpinner). The next keystroke cancels this effect, so a search overtaken never lands.
        search = Search.Searching
        search = try {
            Search.Found(text, RadioResults.onePerStation(radio.browser.search(RadioQuery(name = text, tag = null, countryCode = null, limit = SEARCH_LIMIT))))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Search.Failed
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The blurred screen, whole, and the shade over it.
        GlassBackdrop(backdrop, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Color(0x33000000)))
        val column = max(560.px, maxWidth * 0.5f)
        val fieldH = 64.s
        val found = search
        val top = if (found is Search.Idle) max(64.px, maxHeight / 2 - fieldH / 2) else 64.px
        Column(Modifier.align(Alignment.TopCenter).padding(top = top, bottom = 34.s).width(column)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.s)) {
                GlassPanel(backdrop, 12.s, Modifier.weight(1f).height(fieldH)) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = ui(26.ts).copy(color = Ink.Words),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { runCatching { firstFound.requestFocus() } }),
                        modifier = Modifier.focusRequester(field).deskFocus(12.s).align(Alignment.CenterStart).fillMaxWidth().padding(horizontal = 22.s),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) Text(stringResource(R.string.Radio_SearchPlaceholder), style = ui(26.ts), color = Ink.Artist, maxLines = 1)
                                inner()
                            }
                        },
                    )
                }
                val shape = RoundedCornerShape(12.s)
                DeskButton(
                    onClick = { runCatching { firstFound.requestFocus() } },
                    corner = 12.s,
                    modifier = Modifier.size(fieldH).background(Color(0x14FFFFFF), shape).border(1.px, Color(0xB3FFFFFF), shape),
                ) { Glyph(Fa.SEARCH, 22.ts, Ink.Words) }
            }
            if (found !is Search.Idle) {
                GlassPanel(backdrop, 12.s, Modifier.padding(top = 12.s).fillMaxWidth().weight(1f)) {
                    when (found) {
                        // The wait and the two answers with no stations stand at the top of the card, not in its
                        // middle: the TV's keyboard is up while the field is typed in and covers the lower half of a
                        // 1080p screen, so a record in the middle would be half under it.
                        Search.Searching -> Box(Modifier.fillMaxSize().padding(top = NOTHING_YET_TOP), contentAlignment = Alignment.TopCenter) {
                            RecordSpinner(Modifier.size(46.s))
                        }
                        Search.Failed -> Box(Modifier.fillMaxSize().padding(top = NOTHING_YET_TOP), contentAlignment = Alignment.TopCenter) {
                            Text(stringResource(R.string.Radio_DirectoryDown), style = ui(18.ts), color = Color(0xCCFFFFFF), textAlign = TextAlign.Center)
                        }
                        is Search.Found -> {
                            val shown = rules.shown(found.stations)
                            if (shown.isEmpty()) {
                                Box(Modifier.fillMaxSize().padding(top = NOTHING_YET_TOP), contentAlignment = Alignment.TopCenter) {
                                    Text(stringResource(R.string.Radio_NoResults), style = ui(18.ts), color = Color(0xCCFFFFFF), textAlign = TextAlign.Center)
                                }
                            } else {
                                val tile = (column - 44.s - 78.s) / 4
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(4),
                                    contentPadding = PaddingValues(22.s),
                                    horizontalArrangement = Arrangement.spacedBy(26.s),
                                    verticalArrangement = Arrangement.spacedBy(22.s),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    items(shown, key = { it.uuid }) { station ->
                                        val self = remember { FocusRequester() }
                                        CardButton(
                                            onClick = { onTune(station) },
                                            modifier = Modifier
                                                .focusRequester(self)
                                                .then(if (station === shown.first()) Modifier.focusRequester(firstFound) else Modifier)
                                                .heldOkMenu { onMenu(station, self) },
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.s)) {
                                                LogoWell(StationLogo.of(station, rules.logoStamp), images, tile, Modifier)
                                                Text(station.display, style = ui(CARD_TITLE_TEXT), color = Ink.Card, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(tile), textAlign = TextAlign.Center)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Search.Idle -> Unit
                    }
                }
            }
        }
    }
}

private const val TYPING_PAUSE_MS = 450L

/** How far down the results card the wait and the no-stations answers stand: clear of the TV's keyboard. */
private val NOTHING_YET_TOP = 48.s
private const val SEARCH_LIMIT = 120
private const val WHEEL_HOLD_MS = 2_000L
private const val BANNER_MOST = 7
private const val BANNER_HEARD = 5
