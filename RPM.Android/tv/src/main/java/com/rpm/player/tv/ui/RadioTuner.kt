package com.rpm.player.tv.ui

import com.rpm.player.core.playback.StationPlaying
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import com.rpm.player.core.R
import com.rpm.player.core.radio.AdsWanted
import com.rpm.player.core.radio.Icy
import com.rpm.player.core.radio.RadioAds
import com.rpm.player.core.radio.RadioGenres
import com.rpm.player.core.radio.RadioRepository
import com.rpm.player.core.radio.RadioStation
import com.rpm.player.core.radio.StationLogo
import com.rpm.player.core.radio.StationOrder
import com.rpm.player.core.radio.StationSieve
import com.rpm.player.core.radio.StationSign
import com.rpm.player.core.radio.Tuner
import com.rpm.player.core.radio.TunerList
import com.rpm.player.core.radio.countryName
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The tuner (PegasusChrome.RadioTuner.cs): the dial across the top, and under it the station list with
 * its tools on the left and the player on the right, over the room under `#73000000`.
 *
 * The focus starts on the dial. Left and Right walk its needle from station to station (holding keeps
 * walking, at the remote's repeat rate) without opening a stream; letting go tunes the station the needle
 * is on. OK on the dial, or the player's key, stops the station playing, or tunes the last station tuned
 * here, or the first listed. Down from the dial goes to the first station; Right out of the list goes to
 * the player's key. OK on a station tunes it, or stops it when it is the one playing; the heart keeps it.
 * Back closes the tuner. With [genresFirst] (the wheel's Music) the genre menu opens over the filter key.
 * Down from the tools goes to the first station, the list scrolled back to its top when it has to be. The search
 * field is a key until OK is pressed on it; only then does the keyboard come up. While the favourites key is on, the
 * country key is dimmed and takes no focus: the saved stations are listed whatever their country.
 *
 * [playing] is the station playing now, or reconnecting; a station stopped or failed is not playing.
 * The page draws no shade of its own: it stands where the collection stood, on the same shade.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RadioTuner(
    radio: RadioRepository,
    images: ImageLoader,
    playing: StationPlaying?,
    genresFirst: Boolean,
    onTune: (RadioStation) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val tuner = radio.tuner
    val settings by tuner.settings.collectAsStateWithLifecycle()
    val answer by tuner.answer.collectAsStateWithLifecycle()
    val countries by tuner.countries.collectAsStateWithLifecycle()
    val saved by radio.saved.collectAsStateWithLifecycle()
    val favorites = saved.stations
    val rules by radio.rules.collectAsStateWithLifecycle()
    val list = remember(settings, answer, favorites, rules) { Tuner.listOf(settings, answer, favorites, rules) }
    val stations = list.stations
    val tunedIndex = playing?.let { p -> stations.indexOfFirst { it.answersTo(p.logo.uuid) } } ?: -1
    var preview by remember(stations) { mutableStateOf<Int?>(null) }
    var lastTuned by remember { mutableStateOf(tuner.lastTuned) }
    var flash by remember { mutableStateOf<String?>(null) }
    var flashes by remember { mutableIntStateOf(0) }
    val menu = rememberRowMenu()
    val dial = remember { FocusRequester() }
    val firstRow = remember { FocusRequester() }
    val playKey = remember { FocusRequester() }
    val countryKey = remember { FocusRequester() }
    val sortKey = remember { FocusRequester() }
    val filterKey = remember { FocusRequester() }
    val savedKey = remember { FocusRequester() }
    val context = LocalContext.current
    val labels = tunerLabels()
    val rows = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        tuner.ensureLoaded()
        runCatching { dial.requestFocus() }
    }
    BackHandler(enabled = !menu.isOpen && LocalOnTop.current, onBack = onClose)

    // The dial and the play keys announce the station along the foot ("Tuning in to ..."); a row does not.
    fun tune(station: RadioStation, announce: Boolean) {
        lastTuned = station
        tuner.lastTuned = station
        onTune(station)
        if (announce) {
            flash = context.getString(R.string.Radio_TunedTo, station.display)
            flashes++
        }
    }

    // The dial's key and the player's key: stop what plays, or tune the last station tuned here, or the first.
    fun playOrStop() {
        if (playing != null) {
            onStop()
            return
        }
        (lastTuned ?: stations.firstOrNull())?.let { tune(it, announce = true) }
    }

    fun step(by: Int): Boolean {
        if (stations.isEmpty()) return false
        val current = preview ?: tunedIndex.takeIf { it >= 0 }
        val next = when {
            current == null -> if (by > 0) 0 else stations.lastIndex
            else -> current + by
        }
        if (next !in stations.indices) return false
        preview = next
        return true
    }

    fun commit() {
        val at = preview ?: return
        preview = null
        val station = stations.getOrNull(at) ?: return
        if (at != tunedIndex) tune(station, announce = true)
    }

    // Down from the dial or the tools: the first station. A focus requester on a row the list has scrolled out of
    // composition finds nothing to focus, and Down did nothing; so the list goes back to its top first.
    fun toList() {
        if (stations.isEmpty()) return
        if (rows.firstVisibleItemIndex == 0 && runCatching { firstRow.requestFocus() }.getOrDefault(false)) return
        scope.launch {
            rows.scrollToItem(0)
            withFrameNanos { }
            runCatching { firstRow.requestFocus() }
        }
    }

    fun genreMenu(all: Boolean): List<MenuLine> =
        listOf(MenuTick(labels.allGenres, settings.sieve.genre == null) { tuner.update { it.copy(sieve = it.sieve.copy(genre = null)) } }) +
            RadioGenres.all.filter { all || it !in RadioGenres.notMusic }.map { genre ->
                MenuTick(genre, settings.sieve.genre == genre) { tuner.update { it.copy(sieve = it.sieve.copy(genre = genre)) } }
            }

    // The wheel's Music opens the genre menu over the filter key, once the key can take the focus.
    LaunchedEffect(genresFirst) {
        if (!genresFirst) return@LaunchedEffect
        withFrameNanos { }
        runCatching { filterKey.requestFocus() }
        withFrameNanos { }
        menu.open("", genreMenu(all = false), returnTo = filterKey)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val side = max(34.px, maxWidth * 0.054f)
        val foot = 34.s
        val page = max(320.px, maxWidth - side * 2)
        val dialHeight = DIAL_U * 176f
        val body = max(200.px, maxHeight - 64.px - foot - dialHeight - 18.s)
        val playerWidth = page * 0.59f
        val r = 1.25f

        Column(Modifier.padding(start = side, top = 64.px, end = side, bottom = foot).width(page)) {
            // The dial, one stop for the remote.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(dialHeight)
                    .deskFocus(DIAL_U * 10f)
                    .focusRequester(dial)
                    .focusProperties { up = FocusRequester.Cancel }
                    .onPreviewKeyEvent { event ->
                        when (event.key) {
                            Key.DirectionDown -> {
                                if (event.type == KeyEventType.KeyDown) toList()
                                true
                            }
                            Key.DirectionLeft, Key.DirectionRight -> {
                                if (event.type == KeyEventType.KeyDown) step(if (event.key == Key.DirectionRight) 1 else -1)
                                if (event.type == KeyEventType.KeyUp) commit()
                                true
                            }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) playOrStop()
                                true
                            }
                            else -> false
                        }
                    }
                    .remembersFocus()
                    .focusable(),
            ) {
                val previewed = preview?.let { stations.getOrNull(it) }
                RadioDial(
                    face = DialFace(
                        names = stations.map { it.display },
                        at = preview ?: tunedIndex,
                        tuned = tunedIndex,
                        name = previewed?.display ?: stations.getOrNull(tunedIndex)?.display ?: playing?.name.orEmpty(),
                        status = statusOf(playing),
                        badge = playing?.logo,
                        reconnecting = playing?.reconnecting == true,
                        cover = rememberSongCover(playing),
                    ),
                    images = images,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Row(Modifier.padding(top = 18.s).height(body)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Tools(
                        text = settings.text,
                        onText = { typed -> tuner.update { it.copy(text = typed) } },
                        countryLabel = settings.country?.let { code -> countryName(code, Locale.getDefault()) ?: code } ?: labels.allCountries,
                        countryOn = !settings.favoritesOnly,
                        onCountry = {
                            menu.open(
                                "",
                                listOf(MenuTick(labels.allCountries, settings.country == null) { tuner.update { it.copy(country = null) } }) +
                                    countries.map { c -> MenuTick(c.name, settings.country == c.code) { tuner.update { it.copy(country = c.code) } } },
                                returnTo = countryKey,
                            )
                        },
                        ordered = settings.order != StationOrder.Listed,
                        onSort = {
                            menu.open(
                                "",
                                StationOrder.entries.map { order -> MenuTick(labels.orders.getValue(order), settings.order == order) { tuner.update { it.copy(order = order) } } },
                                returnTo = sortKey,
                            )
                        },
                        filtered = settings.sieve.isOn,
                        onFilter = {
                            menu.open(
                                "",
                                listOf(
                                    MenuSub(
                                        R.string.Radio_FilterFormat,
                                        listOf(MenuTick(labels.allFormats, settings.sieve.format == null) { tuner.update { it.copy(sieve = it.sieve.copy(format = null)) } }) +
                                            StationSieve.FORMATS.map { f -> MenuTick(f, settings.sieve.format == f) { tuner.update { it.copy(sieve = it.sieve.copy(format = f)) } } },
                                    ),
                                    MenuSub(R.string.Radio_FilterGenre, genreMenu(all = true)),
                                    MenuSub(
                                        R.string.Radio_FilterAds,
                                        AdsWanted.entries.map { a -> MenuTick(labels.ads.getValue(a), settings.sieve.ads == a) { tuner.update { it.copy(sieve = it.sieve.copy(ads = a)) } } },
                                    ),
                                    MenuGap,
                                    MenuChoice(R.string.Radio_ClearFilters) { tuner.update { it.copy(sieve = StationSieve.OFF) } },
                                ),
                                returnTo = filterKey,
                            )
                        },
                        saved = settings.favoritesOnly,
                        onSaved = { tuner.update { it.copy(favoritesOnly = !it.favoritesOnly) } },
                        keys = ToolKeys(countryKey, sortKey, filterKey, savedKey, dial),
                        onDown = ::toList,
                        r = r,
                    )
                    Box(Modifier.fillMaxSize()) {
                        val air = with(LocalDensity.current) { 24.px.toPx() }
                        CompositionLocalProvider(LocalBringIntoViewSpec provides remember(air) { CentreTunerRows }) {
                            LazyColumn(
                                state = rows,
                                verticalArrangement = Arrangement.spacedBy(4.s),
                                contentPadding = PaddingValues(bottom = 20.px),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                itemsIndexed(stations, key = { _, s -> s.uuid }) { i, station ->
                                    TunerRow(
                                        station = station,
                                        favourite = favorites.any { it.uuid.equals(station.uuid, ignoreCase = true) },
                                        images = images,
                                        logoStamp = rules.logoStamp,
                                        r = r,
                                        onPress = { if (i == tunedIndex) onStop() else tune(station, announce = false) },
                                        onHeart = if (saved.mine) ({ radio.favorites.toggle(station) }) else null,
                                        mainModifier = if (i == 0) Modifier.focusRequester(firstRow) else Modifier,
                                        heartModifier = Modifier.focusProperties { right = playKey },
                                    )
                                }
                            }
                        }
                        val note = when {
                            list.directoryDown -> R.string.Radio_DirectoryDown
                            list.empty == TunerList.Empty.NoResults -> R.string.Radio_NoResults
                            list.empty == TunerList.Empty.NoFavorites -> R.string.Radio_NoFavorites
                            list.empty == TunerList.Empty.NoFilterMatch -> R.string.Radio_NoFilterMatch
                            else -> null
                        }
                        if (note != null && stations.isEmpty()) {
                            Text(
                                stringResource(note),
                                style = ui((13f * r).ts),
                                color = Ink.Dim,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.align(Alignment.TopCenter).padding(start = 28.s, top = 26.s, end = 28.s),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(24.s))
                TunerPlayer(
                    shown = playing?.let { p -> stations.getOrNull(tunedIndex) ?: lastTuned?.takeIf { it.answersTo(p.logo.uuid) } } ?: lastTuned,
                    playing = playing,
                    logoStamp = rules.logoStamp,
                    images = images,
                    width = playerWidth,
                    height = body,
                    playKey = playKey,
                    onPlayOrStop = ::playOrStop,
                )
            }
        }

        Flash(flash, flashes, Modifier.align(Alignment.BottomCenter).padding(bottom = 44.px))
    }

    RowMenu(menu)
}

@OptIn(ExperimentalFoundationApi::class)
private val CentreTunerRows = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset + size / 2 - containerSize / 2
}

/** The readout's second line (RadioModel.StatusText): not tuned, reconnecting, an ad break, what the
 * station announces as it announces it, or "On air". */
@Composable
private fun statusOf(playing: StationPlaying?): String {
    // Held in a local: StationPlaying is :core's, and a property of another module cannot be smart-cast.
    val said = playing?.said
    return when {
        playing == null -> stringResource(R.string.Radio_NothingTuned)
        playing.reconnecting -> stringResource(R.string.Radio_Reconnecting)
        said == Icy.Said.AdBreak -> stringResource(R.string.Radio_AdBreak)
        said is Icy.Said.Song -> listOfNotNull(said.artist, said.title).joinToString(" - ")
        else -> stringResource(R.string.Radio_NoTrack)
    }
}

/** The tuner's menu labels, read once per composition so the menus can be built from a key press. */
private class TunerLabels(
    val allGenres: String,
    val allCountries: String,
    val allFormats: String,
    val orders: Map<StationOrder, String>,
    val ads: Map<AdsWanted, String>,
)

@Composable
private fun tunerLabels() = TunerLabels(
    allGenres = stringResource(R.string.Radio_AllGenres),
    allCountries = stringResource(R.string.Radio_AllCountries),
    allFormats = stringResource(R.string.Radio_AllFormats),
    orders = mapOf(
        StationOrder.Listed to stringResource(R.string.Radio_SortListed),
        StationOrder.NameUp to stringResource(R.string.Radio_SortNameUp),
        StationOrder.NameDown to stringResource(R.string.Radio_SortNameDown),
        StationOrder.Votes to stringResource(R.string.Radio_SortVotes),
        StationOrder.Quality to stringResource(R.string.Radio_SortQuality),
    ),
    ads = mapOf(
        AdsWanted.Either to stringResource(R.string.Radio_AdsEither),
        AdsWanted.Without to stringResource(R.string.Radio_AdsWithout),
        AdsWanted.With to stringResource(R.string.Radio_AdsWith),
    ),
)

/** The focus targets the tools row shares with the page. */
private class ToolKeys(
    val country: FocusRequester,
    val sort: FocusRequester,
    val filter: FocusRequester,
    val saved: FocusRequester,
    val dial: FocusRequester,
)

/**
 * The tools over the list, `30·r` high, 8·s apart: the search field, the country, the order, the filters
 * and the saved stations; a cyan dot on the order and the filter keys while one is in force.
 *
 * The search field is a key drawn as the field until OK: only then is it a text field, with the keyboard up.
 * Focused as a field, it put the keyboard up as soon as the remote passed over it, and the keyboard took Down.
 * When the keyboard goes (its search key, or Back, which the keyboard takes itself) the focus is back on the key.
 * Down from any of the tools calls [onDown]. With [countryOn] false the country key is dimmed and passed over.
 */
@Composable
private fun Tools(
    text: String,
    onText: (String) -> Unit,
    countryLabel: String,
    countryOn: Boolean,
    onCountry: () -> Unit,
    ordered: Boolean,
    onSort: () -> Unit,
    filtered: Boolean,
    onFilter: () -> Unit,
    saved: Boolean,
    onSaved: () -> Unit,
    keys: ToolKeys,
    onDown: () -> Unit,
    r: Float,
) {
    val height = (30f * r).s
    val nav = Modifier.focusProperties { up = keys.dial }
    val searchKey = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalKeyboardView.current
    val scope = rememberCoroutineScope()

    // Back to the key, asked again a frame later: a request made as the keyboard closes is spent waking the
    // view's focus, which the keyboard held (as the album wall's focusTwice).
    fun stopEditing() {
        if (!editing) return
        editing = false
        runCatching { searchKey.requestFocus() }
        scope.launch {
            withFrameNanos { }
            runCatching { searchKey.requestFocus() }
        }
    }

    // The keyboard going away, read off the window's insets: the keyboard takes the remote's Back itself.
    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        var seen = false
        while (true) {
            val up = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (seen && !up) stopEditing()
            seen = up
            delay(KEYBOARD_POLL_MS)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .onPreviewKeyEvent { event ->
                if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    if (editing) {
                        keyboard?.hide()
                        editing = false
                    }
                    onDown()
                }
                true
            },
        horizontalArrangement = Arrangement.spacedBy(8.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var focused by remember { mutableStateOf(false) }
        val shape = RoundedCornerShape(6.s)
        val fieldModifier = nav.weight(1f).fillMaxHeight()
        val look: @Composable (@Composable () -> Unit) -> Unit = { inner ->
            Row(
                Modifier.fillMaxSize().background(Color(0x1FFFFFFF), shape).border(1.px, Color(0x26FFFFFF), shape).padding(horizontal = 10.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(Fa.SEARCH, (12f * r).ts, Ink.Dim)
                Spacer(Modifier.width(8.s))
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) Text(stringResource(R.string.Radio_SearchPlaceholder), style = ui((13f * r).ts), color = if (focused) Color(0xA6FFFFFF) else Color(0x8CFFFFFF), maxLines = 1)
                    inner()
                }
            }
        }
        if (editing) {
            // Ended only after the field has held the focus: its first focus report says unfocused.
            var held by remember { mutableStateOf(false) }
            BasicTextField(
                value = text,
                onValueChange = onText,
                singleLine = true,
                textStyle = ui((13f * r).ts).copy(color = Ink.Card),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    stopEditing()
                }),
                modifier = fieldModifier
                    .focusRequester(field)
                    .deskFocus(6.s)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) held = true else if (held) editing = false
                    },
                decorationBox = { inner -> look(inner) },
            )
            LaunchedEffect(Unit) {
                runCatching { field.requestFocus() }
                withFrameNanos { }
                keyboard?.show()
            }
        } else {
            DeskButton(
                onClick = { editing = true },
                corner = 6.s,
                contentAlignment = Alignment.CenterStart,
                modifier = fieldModifier.focusRequester(searchKey).onFocusChanged { focused = it.isFocused },
            ) {
                look { Text(text, style = ui((13f * r).ts), color = Ink.Card, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        ToolKey(
            onCountry,
            nav.focusRequester(keys.country).focusProperties { canFocus = countryOn }.graphicsLayer { alpha = if (countryOn) 1f else 0.35f }.width((132f * r).s).fillMaxHeight(),
            dot = false,
            r = r,
        ) {
            Row(Modifier.padding(horizontal = 10.s), verticalAlignment = Alignment.CenterVertically) {
                Text(countryLabel, style = ui((12f * r).ts), color = Ink.Words, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Glyph(Fa.CHEVRON_DOWN, (8f * r).ts, Ink.Words.copy(alpha = 0.7f))
            }
        }
        ToolKey(onSort, nav.focusRequester(keys.sort).size((34f * r).s), dot = ordered, r = r) { Glyph(Fa.SORT, (12f * r).ts, Ink.Words) }
        ToolKey(onFilter, nav.focusRequester(keys.filter).size((34f * r).s), dot = filtered, r = r) { Glyph(FILTER, (12f * r).ts, Ink.Words) }
        ToolKey(onSaved, nav.focusRequester(keys.saved).size((34f * r).s), dot = false, r = r) {
            Glyph(Fa.HEART, (12f * r).ts, if (saved) Ink.Loved else Ink.Unloved, regular = !saved)
        }
    }
    Spacer(Modifier.height(10.s))
}

private const val FILTER = ''

private const val KEYBOARD_POLL_MS = 150L

/** A key of the tools row (PegasusMenuButton: `#14FFFFFF`, a 1 px `#B3FFFFFF` edge, corner 6·s). */
@Composable
private fun ToolKey(onClick: () -> Unit, modifier: Modifier, dot: Boolean, r: Float, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(6.s)
    Box {
        DeskButton(onClick = onClick, corner = 6.s, modifier = modifier.background(Color(0x14FFFFFF), shape).border(1.px, Color(0xB3FFFFFF), shape)) { content() }
        if (dot) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = 2.s, y = (-1).s).size((5f * r).s).background(Color(0xFF04C8FF), CircleShape))
        }
    }
}

/**
 * One station on the tuner (PegasusStationRow, all sizes times `r`): the main key, holding the logo in its
 * well (51), the lamp (lit while the directory last reached the stream), the name (13), the AD badge for a
 * station that carries advertising, the country and format (11), and the call sign in brackets; then the
 * heart (34, round). No play key: pressing the row tunes the station.
 */
@Composable
private fun TunerRow(
    station: RadioStation,
    favourite: Boolean,
    images: ImageLoader,
    logoStamp: String,
    r: Float,
    onPress: () -> Unit,
    /** Null on a shared collection: the heart then shows the owner's mark and cannot be pressed. */
    onHeart: (() -> Unit)?,
    mainModifier: Modifier,
    heartModifier: Modifier,
) {
    Row(horizontalArrangement = Arrangement.spacedBy((6f * r).s), verticalAlignment = Alignment.CenterVertically) {
        DeskButton(
            onClick = onPress,
            corner = (8f * r).s,
            contentAlignment = Alignment.CenterStart,
            modifier = mainModifier.weight(1f).padding(horizontal = (6f * r).s, vertical = (5f * r).s),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy((10f * r).s), verticalAlignment = Alignment.CenterVertically) {
                LogoWell(StationLogo.of(station, logoStamp), images, (51f * r).s, Modifier)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Lamp(station.reachable, r)
                        Spacer(Modifier.width((5f * r).s))
                        Text(station.display, style = ui((13f * r).ts), color = Color(0xEEFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (RadioAds.carriesAds(station.tags, station.name, station.homepage)) {
                            Spacer(Modifier.width((5f * r).s))
                            AdsBadge(r)
                        }
                    }
                    val country = station.countryCode?.let { countryName(it, Locale.getDefault()) } ?: station.country
                    val detail = listOfNotNull(country, station.codec?.uppercase()?.takeIf { it.isNotBlank() && it != "UNKNOWN" }).joinToString(" · ")
                    if (detail.isNotEmpty()) Text(detail, style = ui((11f * r).ts), color = Color(0x77FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val sign = StationSign.of(station.display)
                if (sign.isNotEmpty()) {
                    Text("($sign)", style = ui((10f * r).ts).copy(letterSpacing = 0.06.em), color = Color(0xAA04C8FF), maxLines = 1)
                }
            }
        }
        if (onHeart != null) {
            DeskButton(onClick = onHeart, corner = (17f * r).s, modifier = heartModifier.size((34f * r).s)) {
                Glyph(Fa.HEART, (13f * r).ts, if (favourite) Ink.Loved else Ink.Unloved, regular = !favourite)
            }
        } else if (favourite) {
            Box(Modifier.size((34f * r).s), contentAlignment = Alignment.Center) { Glyph(Fa.HEART, (13f * r).ts, Ink.Loved) }
        }
    }
}

/** The on-air lamp: a red dot in a red glow while the directory last reached the stream; a dim dot when not. */
@Composable
private fun Lamp(lit: Boolean, r: Float) {
    Box(Modifier.size((12f * r).s), contentAlignment = Alignment.Center) {
        if (lit) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(Brush.radialGradient(0f to Color(0xB3FF3B30), 0.45f to Color(0x59FF3B30), 1f to Color(0x00FF3B30)))
            }
        }
        Box(Modifier.size((6f * r).s).background(if (lit) Color(0xFFFF4438) else Color(0x33FFFFFF), CircleShape))
    }
}

/** "AD" after a station that carries advertising: `#8CFFFFFF`, a bold `#0B0D10` AD, corner 2.5. */
@Composable
private fun AdsBadge(r: Float) {
    Box(
        Modifier.size((19f * r).s, (13f * r).s).background(Color(0x8CFFFFFF), RoundedCornerShape((2.5f * r).s)),
        contentAlignment = Alignment.Center,
    ) {
        Text("AD", style = ui((8f * r).ts, FontWeight.Bold), color = Color(0xFF0B0D10))
    }
}

/**
 * The player beside the list: a room in the station's colours under `#5C000000` (20% darker than `#33000000`,
 * so the keys and lines over it read) and a glass rim, with no glass fill over the room,
 * corner 14·s, and in the middle the logo in its well, the title (26·s Bold), the artist (20·s), the
 * station (16·s) and the round play key (64·s). Not tuned, it says so, under the last station tuned here.
 */
@Composable
private fun TunerPlayer(
    shown: RadioStation?,
    playing: StationPlaying?,
    logoStamp: String,
    images: ImageLoader,
    width: Dp,
    height: Dp,
    playKey: FocusRequester,
    onPlayOrStop: () -> Unit,
) {
    val shape = RoundedCornerShape(14.s)
    val logo = shown?.let { StationLogo.of(it, logoStamp) } ?: playing?.logo
    Box(Modifier.size(width, height).clip(shape)) {
        val palette = if (shown != null) stationPalette(shown, StationLogo.of(shown, logoStamp), images) else com.rpm.player.core.room.RoomPalette.FALLBACK
        CardRoomView(palette, apart = 0, drift = true, fewest = 8, soften = 0f, modifier = Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Color(0x5C000000)))
        Box(Modifier.matchParentSize().border(1.px, Brush.verticalGradient(0f to Ink.SmokeRimTop, 0.5f to Ink.SmokeRimMiddle, 1f to Ink.SmokeRimBottom), shape))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.s)) {
            if (logo != null) {
                // The song playing shows over the logo, as on the mini player, while the card is of the station playing.
                val ours = playing != null && (shown == null || shown.answersTo(playing.logo.uuid))
                val cover = rememberSongCover(playing?.takeIf { ours })
                LogoWell(logo, images, min(height * 0.44f, width * 0.4f), Modifier.padding(bottom = 22.s - 6.s), songCover = cover)
            }
            val said = playing?.said as? Icy.Said.Song
            val title = when {
                playing == null -> stringResource(R.string.Radio_NothingTuned)
                said != null -> said.title
                else -> statusOf(playing)
            }
            val textWidth = width * 0.85f
            Text(title, style = ui(26.ts, FontWeight.Bold), color = Ink.Words, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = textWidth))
            if (playing != null && !said?.artist.isNullOrBlank()) {
                Text(said.artist.orEmpty(), style = ui(20.ts), color = Color(0xD9FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = textWidth))
            }
            val station = shown?.display ?: playing?.name
            if (!station.isNullOrBlank()) {
                Text(station, style = ui(16.ts), color = Ink.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = textWidth))
            }
            val keyShape = CircleShape
            DeskButton(
                onClick = onPlayOrStop,
                corner = 32.s,
                modifier = Modifier
                    .padding(top = 22.s - 6.s)
                    .focusRequester(playKey)
                    .size(64.s)
                    .background(Color(0x14FFFFFF), keyShape)
                    .border(1.px, Color(0xB3FFFFFF), keyShape),
            ) {
                Glyph(if (playing != null) Fa.STOP else Fa.PLAY, 22.ts, Ink.Words)
            }
        }
    }
}

/**
 * The desktop's toast (PegasusPlayer.xaml, Flash): bottom centre, `#D8000000` with a 1 px `#26FFFFFF` edge,
 * corner 16·s, 14·s type; in over 140 ms, held 2.6 s, out over 320 ms. [count] shows it again for the
 * same words.
 */
@Composable
fun Flash(text: String?, count: Int, modifier: Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(count) {
        if (text == null || count == 0) return@LaunchedEffect
        alpha.animateTo(1f, tween(140, easing = QuadInOut))
        delay(2_600)
        alpha.animateTo(0f, tween(320, easing = QuadInOut))
    }
    if (text == null) return
    val shape = RoundedCornerShape(16.s)
    Box(
        modifier
            .padding(horizontal = 24.px)
            .graphicsLayer { this.alpha = alpha.value }
            .widthIn(max = 640.s)
            .background(Color(0xD8000000), shape)
            .border(1.px, Color(0x26FFFFFF), shape)
            .padding(horizontal = 18.s, vertical = 9.s),
    ) {
        Text(text, style = ui(18.ts), color = Color(0xF2FFFFFF), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
