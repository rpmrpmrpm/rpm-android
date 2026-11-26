package com.rpm.player.phone.radio

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.wrapContentHeight
import com.rpm.player.phone.design.HeaderText
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import com.rpm.player.core.R
import com.rpm.player.core.radio.Icy
import com.rpm.player.core.radio.RadioGenres
import com.rpm.player.core.radio.RadioNames
import com.rpm.player.core.radio.RadioRepository
import com.rpm.player.core.radio.RadioStation
import com.rpm.player.core.radio.SavedStations
import com.rpm.player.core.radio.StationOrder
import com.rpm.player.core.radio.Tuner
import com.rpm.player.core.radio.TunerAnswer
import com.rpm.player.core.radio.TunerList
import com.rpm.player.core.radio.TunerSettings
import com.rpm.player.core.radio.countryName
import com.rpm.player.phone.design.BackKey
import com.rpm.player.phone.design.GlassField
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.OverChrome
import com.rpm.player.phone.design.PullDownMenu
import com.rpm.player.phone.design.PullItem
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.dissolvesAtTop
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.design.rememberHaptics
import com.rpm.player.phone.design.rowPress
import com.rpm.player.phone.design.scrolled
import com.rpm.player.phone.design.system
import com.rpm.player.phone.design.UnderHeader
import com.rpm.player.phone.design.holdPress
import com.rpm.player.phone.library.AlbumCaption
import com.rpm.player.phone.library.EDGE
import com.rpm.player.phone.library.FOOT
import com.rpm.player.phone.library.KeyGroup
import com.rpm.player.phone.library.PlaylistLayout
import com.rpm.player.phone.library.WALL_COLUMNS
import com.rpm.player.phone.library.WALL_GUTTER
import com.rpm.player.phone.library.WallLayout
import com.rpm.player.phone.ui.RecordSpinner
import com.rpm.player.phone.ui.VinylGround
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import com.rpm.player.phone.design.ScrollsToTop
import com.rpm.player.phone.ui.coverSquare

/** Which of the page's menus is open, and where its key is on the screen. */
private class Picking(val which: Which, val from: Rect) {
    enum class Which { Genre, Country, Order, Layout }
}

/** The orders that the sort key offers, with their names (`RadioPage.named` on iPhone). */
private val ORDERS = listOf(
    StationOrder.Listed to R.string.Radio_SortListed,
    StationOrder.NameUp to R.string.Radio_SortNameUp,
    StationOrder.NameDown to R.string.Radio_SortNameDown,
    StationOrder.Votes to R.string.Radio_SortVotes,
    StationOrder.Quality to R.string.Radio_SortQuality,
)

/**
 * The tuner, opened from the radio tab's home ([RadioHome]). It matches the iPhone's `RadioPage` (RPMUI/RadioPage.swift). From the top: the dial, then the
 * stations it can reach. The dial and the list are one list of stations. So dragging the needle and tapping a
 * row tune the same station. Below the dial is the search field, and a row with the genre and the country. The keys
 * on the title's line choose the layout (rows, or a wall of the stations' squares), the order, and the saved
 * stations only. Each key and filter that offers a choice opens a menu that drops from it, as SwiftUI's `Menu` does.
 *
 * The question sent to the station directory, the answer, and the list built from it are in `:core`'s [Tuner].
 * The TV app's tuner reads the same code. The tuner waits until the typing pauses. It asks one question at a
 * time, and keeps answers for half an hour. The phone starts with every country, as the iPhone app does
 * ([com.rpm.player.core.radio.TunerOpensOn]). The genre and the country both go to the directory with the text.
 *
 * **The saved stations are the person's own, or a shared collection's owner's** ([SavedStations], the iPhone's
 * `savedStations`). The owner's are read each time the radio is shown and when another collection is opened
 * ([RadioRepository.refresh]); their hearts cannot be pressed, and stations they did not save have none.
 *
 * @param tuned the id of the station that is playing. The row's mark and the needle follow it.
 * @param said what that station announces now.
 * @param onSave saves a station, or unsaves it. It is only offered for the person's own saved stations.
 * @param onBack puts the page away, back to the radio home ([RadioHome]). The page then has a back key beside its title.
 * @param layout how the stations are shown: rows, or a wall of squares. The phone keeps it
 * ([com.rpm.player.phone.PhoneSettings.radioLayout]), so the tuner opens in the layout last chosen.
 * @param onLayout keeps a new layout.
 */
@Composable
fun RadioPage(
    radio: RadioRepository,
    images: ImageLoader,
    tuned: String?,
    said: Icy.Said?,
    bottomRoom: Dp,
    onTune: (RadioStation) -> Unit,
    onSave: (RadioStation) -> Unit,
    onBack: (() -> Unit)? = null,
    layout: PlaylistLayout = PlaylistLayout.List,
    onLayout: (PlaylistLayout) -> Unit = {},
) {
    val tuner = radio.tuner
    val settings by tuner.settings.collectAsStateWithLifecycle()
    val answer by tuner.answer.collectAsStateWithLifecycle()
    val countries by tuner.countries.collectAsStateWithLifecycle()
    val saved by radio.saved.collectAsStateWithLifecycle()
    val rules by radio.rules.collectAsStateWithLifecycle()
    val page = rememberBackdrop()
    // What the header's glass frosts: the ground and the page under the header, as on the playlists view.
    val ground = rememberBackdrop()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var picking by remember { mutableStateOf<Picking?>(null) }
    if (onBack != null) BackHandler(onBack = onBack)

    // Each time the radio is shown: the directory, the rules, and the saved stations (the owner's again on a shared
    // collection), as the iPhone's tasks on the radio tab.
    LaunchedEffect(Unit) {
        tuner.ensureLoaded()
        radio.refresh()
    }
    val list = remember(settings, answer, saved, rules) { Tuner.listOf(settings, answer, saved.stations, rules) }
    val announced = when (said) {
        is Icy.Said.Song -> said.artist?.let { "$it · ${said.title}" } ?: said.title
        Icy.Said.AdBreak -> stringResource(R.string.Radio_AdBreak)
        null -> null
    }

    val pick = { which: Picking.Which, from: Rect ->
        focusManager.clearFocus()
        picking = Picking(which, from)
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(page)) {
            // The header is drawn over the page so that its glass can frost it. The page starts under it.
            UnderHeader(ground, ground = { VinylGround() }, header = {
                Header(
                    ground,
                    title = stringResource(if (onBack != null) R.string.Radio_TunerTitle else R.string.Rpm_Tab_Radio),
                    onBack = onBack,
                    layout = layout,
                    sorted = settings.order != StationOrder.Listed,
                    savedOnly = settings.favoritesOnly,
                    onPick = pick,
                    onSaved = { tuner.update { it.copy(favoritesOnly = !it.favoritesOnly) } },
                )
            }) { top ->
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(top))
                    RadioDial(list.stations, tuned, announced, onTune, Modifier.padding(horizontal = 20.dp))
                    Controls(
                        text = settings.text,
                        onText = { typed -> tuner.update { it.copy(text = typed) } },
                        focus = focus,
                        genre = settings.sieve.genre,
                        country = countries.firstOrNull { it.code == settings.country }?.name,
                        onPick = pick,
                    )
                    if (list.directoryDown) Trouble()
                    Box(Modifier.weight(1f).imePadding()) {
                        Stations(list, settings, answer, images, rules.logoStamp, tuned, saved, bottomRoom, layout, onTune, onSave)
                    }
                }
            }
        }
        picking?.let { open ->
            val close = { picking = null }
            key(open) {
                val items = when (open.which) {
                    // Plain names, unlike the country menu: the kinds of station are a fixed list, not an answer from the
                    // directory. Neither of these two menus ticks its choice: on iPhone they are plain buttons, and the
                    // box shows what is chosen.
                    Picking.Which.Genre ->
                        listOf(PullItem(stringResource(R.string.Radio_AllGenres), dividerAfter = true) { tuner.update { it.copy(sieve = it.sieve.copy(genre = null)) } }) +
                            RadioGenres.all.map { kind -> PullItem(kind) { tuner.update { it.copy(sieve = it.sieve.copy(genre = kind)) } } }
                    // Names only, as on the desktop. The count decided which countries are listed; they are shown A to Z.
                    Picking.Which.Country ->
                        listOf(PullItem(stringResource(R.string.Radio_AllCountries), dividerAfter = true) { tuner.update { it.copy(country = null) } }) +
                            countries.map { where -> PullItem(where.name) { tuner.update { it.copy(country = where.code) } } }
                    // A picker: the order in force is ticked.
                    Picking.Which.Order ->
                        ORDERS.map { (order, label) -> PullItem(stringResource(label), ticked = order == settings.order) { tuner.update { it.copy(order = order) } } }
                    // A picker too, as the playlists' layout key: the layout shown is ticked.
                    Picking.Which.Layout ->
                        PlaylistLayout.entries.map { choice -> PullItem(stringResource(choice.label), ticked = choice == layout) { onLayout(choice) } }
                }
                // Over the tab bar and the mini player, as the iPhone's menu is.
                OverChrome { PullDownMenu(page, open.from, items, onDismiss = close) }
            }
        }
    }
}

/**
 * One line: the back key, the title beside it, and at the right the keys on one glass capsule (the layout, the order
 * and the saved stations only). The three are centred on one line and there is no line under the title, so the dial
 * starts close under them.
 */
@Composable
private fun Header(
    backdrop: Backdrop,
    title: String,
    onBack: (() -> Unit)?,
    layout: PlaylistLayout,
    sorted: Boolean,
    savedOnly: Boolean,
    onPick: (Picking.Which, Rect) -> Unit,
    onSaved: () -> Unit,
) {
    val haptics = rememberHaptics()
    // The row is as tall as the back key, so the key stays where every page's back key is; the title and the capsule
    // are centred on it, the title's taller line box spilling over the row without moving it.
    Row(
        Modifier.statusBarsPadding().padding(start = 20.dp, end = 16.dp, bottom = 12.dp).height(KEY_LINE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            BackKey(backdrop, onBack = { haptics.pick(); onBack() }, modifier = Modifier.padding(end = BESIDE_TITLE))
        }
        // The display face's line box has room for long descenders under the word, so its ink sits high in the box;
        // the nudge puts the ink, not the box, on the keys' centre line.
        HeaderText(title, Modifier.weight(1f).wrapContentHeight(unbounded = true).offset(y = TITLE_NUDGE))
        KeyGroup(backdrop) {
            // The playlists' layout key: a menu, not a toggle, so the key never has to show either the layout in
            // force or the one a press would give.
            GroupKey(layout.mark, on = false, label = stringResource(R.string.Rpm_Library_View)) { onPick(Picking.Which.Layout, it) }
            GroupKey(Glyph.sortAmount, on = sorted, label = stringResource(R.string.Radio_Sort)) { onPick(Picking.Which.Order, it) }
            GroupKey(Glyph.heart, on = savedOnly, label = stringResource(R.string.Radio_ShowFavorites)) { onSaved() }
        }
    }
}

/** The space between the back key and the title beside it (`RPMBackKey.besideTitle` on iPhone). */
private val BESIDE_TITLE = 12.dp

/** How far the title is moved down so its ink, not its line box, is centred on the keys. */
private val TITLE_NUDGE = 6.dp

/** The header's line: the back key's height. */
private val KEY_LINE = 40.dp

/**
 * One plain key on the header's capsule. **Its mark is cyan while it is in force**, which is the whole of its state:
 * without it, a list in a chosen order, or narrowed to the saved stations, would give no sign of that, and the page
 * would seem to be missing stations.
 */
@Composable
private fun GroupKey(glyph: String, on: Boolean, label: String, onClick: (Rect) -> Unit) {
    val place = remember { Place() }
    Box(
        Modifier
            .size(40.dp)
            .onGloballyPositioned { place.bounds = it.boundsInRoot() }
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onClick(place.bounds) }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(glyph, style = icon(16f).copy(color = if (on) Ink.cyan else Ink.white(0xB8)))
    }
}

/** The search field on a line of its own, and under it the genre and the country. */
@Composable
private fun Controls(
    text: String,
    onText: (String) -> Unit,
    focus: FocusRequester,
    genre: String?,
    country: String?,
    onPick: (Picking.Which, Rect) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    // The same gap between the dial, the search field and the two choosers, so the cards stand evenly spaced.
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The iPhone's `rpmField()`, not the library's search box: 44 dp, the system face at 15, and no clear key.
        GlassField(
            value = text,
            onValueChange = onText,
            placeholder = stringResource(R.string.Radio_SearchPlaceholder),
            modifier = Modifier.focusRequester(focus),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chooser(genre ?: stringResource(R.string.Radio_AllGenres), Modifier.weight(1f)) { onPick(Picking.Which.Genre, it) }
            Chooser(country ?: stringResource(R.string.Radio_AllCountries), Modifier.weight(1f)) { onPick(Picking.Which.Country, it) }
        }
    }
}

/** Where a key is on the screen, for the menu that drops from it. Kept outside composition: only a tap reads it. */
private class Place {
    var bounds = Rect.Zero
}

/** One of the two boxes that open a menu. It shows what is chosen, and an up-and-down mark. */
@Composable
private fun Chooser(said: String, modifier: Modifier, onClick: (Rect) -> Unit) {
    val well = RoundedCornerShape(8.dp)
    val place = remember { Place() }
    Row(
        modifier
            .height(40.dp)
            .onGloballyPositioned { place.bounds = it.boundsInRoot() }
            .clip(well)
            .background(Ink.white(0x14))
            .border(1.dp, Ink.white(0x1A), well)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.DropdownList) { onClick(place.bounds) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(said, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(13f).copy(color = Ink.white(0xE6)))
        BasicText(Glyph.sort, style = icon(10f).copy(color = Ink.white(0xE6)))
    }
}

/** Explains why the list is short. It is shown in its own row above the list, not over the first station. */
@Composable
private fun Trouble() {
    val well = RoundedCornerShape(8.dp)
    BasicText(
        stringResource(R.string.Radio_DirectoryDown),
        Modifier
            .padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
            .fillMaxWidth()
            .clip(well)
            .background(Color.Black.copy(alpha = 0x22 / 255f))
            .border(1.dp, Ink.cardBorder, well)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = display(12f).copy(color = Ink.white(0xAA)),
    )
}

@Composable
private fun Stations(
    list: TunerList,
    settings: TunerSettings,
    answer: TunerAnswer,
    images: ImageLoader,
    logoStamp: String,
    tuned: String?,
    saved: SavedStations,
    bottomRoom: Dp,
    layout: PlaylistLayout,
    onTune: (RadioStation) -> Unit,
    onSave: (RadioStation) -> Unit,
) {
    if (list.stations.isEmpty()) {
        // Centred in the space the chrome leaves, so it is not behind the mini player.
        Column(
            Modifier.fillMaxSize().padding(bottom = bottomRoom),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (answer is TunerAnswer.Asking || (settings.favoritesOnly && saved.reading)) {
                RecordSpinner(44.dp)
            } else {
                BasicText(Glyph.radio, style = icon(40f).copy(color = Ink.white(0x3A)))
                // As on iPhone: the saved stations say whose they are, and a filter says it found nothing.
                val nothing = when {
                    settings.favoritesOnly && saved.mine -> R.string.Radio_NoFavorites
                    settings.favoritesOnly && saved.unreadable -> R.string.Radio_SharedFavoritesError
                    settings.favoritesOnly -> R.string.Radio_SharedNoFavorites
                    settings.sieve.genre != null || settings.country != null -> R.string.Radio_NoFilterMatch
                    else -> R.string.Radio_NoResults
                }
                BasicText(stringResource(nothing), Modifier.widthIn(max = 300.dp), style = display(14f).copy(color = Ink.white(0x88), textAlign = TextAlign.Center))
            }
        }
        return
    }
    if (layout == PlaylistLayout.Grid) {
        StationWall(list, images, logoStamp, tuned, bottomRoom, onTune)
        return
    }
    val rows = rememberLazyListState()
    ScrollsToTop(rows)
    val focus = LocalFocusManager.current
    LaunchedEffect(rows.isScrollInProgress) { if (rows.isScrollInProgress) focus.clearFocus() }
    // Looked up once per list of saved stations, not once per row.
    val savedIds = remember(saved) { saved.stations.mapTo(HashSet()) { it.uuid.lowercase() } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = rows,
            modifier = Modifier.fillMaxSize().dissolvesAtTop(30.dp) { rows.scrolled() },
            contentPadding = PaddingValues(bottom = FOOT + bottomRoom),
        ) {
            items(list.stations, key = { it.uuid }, contentType = { "station" }) { station ->
                StationRow(
                    station,
                    images,
                    logoStamp,
                    playing = tuned != null && station.answersTo(tuned),
                    isSaved = station.uuid.lowercase() in savedIds,
                    mayChange = saved.mine,
                    onTune = onTune,
                    onSave = onSave,
                )
            }
        }
        // The last answer stays listed while a new one is being asked for. The spinner shows that more is coming.
        if (list.asking) RecordSpinner(34.dp, Modifier.align(Alignment.TopCenter).padding(top = 18.dp))
    }
}

/**
 * The same stations as a wall of their squares, each with its name under it, at the size the other walls open at
 * ([WALL_STRIDE]) and with the records wall's own sizing ([WallLayout.wall], [AlbumCaption]). A tap tunes the station,
 * as a row does, and a long press offers the same menu as a row's.
 */
@Composable
private fun StationWall(
    list: TunerList,
    images: ImageLoader,
    logoStamp: String,
    tuned: String?,
    bottomRoom: Dp,
    onTune: (RadioStation) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val (columns, art) = WallLayout.wall(maxWidth.value.toDouble(), WALL_STRIDE, EDGE, WALL_GUTTER, WALL_COLUMNS)
        val caption = remember(art) { AlbumCaption.of(art) }
        val grid = rememberLazyGridState()
        ScrollsToTop(grid)
        val focus = LocalFocusManager.current
        LaunchedEffect(grid.isScrollInProgress) { if (grid.isScrollInProgress) focus.clearFocus() }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
            modifier = Modifier.fillMaxSize().dissolvesAtTop(30.dp) { grid.scrolled() },
            contentPadding = PaddingValues(start = EDGE.dp, end = EDGE.dp, top = 4.dp, bottom = FOOT + bottomRoom),
            horizontalArrangement = Arrangement.spacedBy(WALL_GUTTER.dp),
        ) {
            gridItems(list.stations, key = { it.uuid }, contentType = { "station" }) { station ->
                Box(contentAlignment = Alignment.TopCenter) {
                    StationTile(station, images, logoStamp, playing = tuned != null && station.answersTo(tuned), caption, onTune)
                }
            }
        }
        // As over the rows: the last answer stays while a new one is asked for.
        if (list.asking) RecordSpinner(34.dp, Modifier.align(Alignment.TopCenter).padding(top = 18.dp))
    }
}

/** The width a tile on the station wall is asked to be: the size the records and playlists walls open at. */
private const val WALL_STRIDE = 194.0

/**
 * One station on the wall: its square, as the radio home's rows draw it, and its name under it in the room of a
 * record's two lines. **The station that is playing has a white edge**, as the row's white mark in the margin: on a
 * wall there is no margin to put it in.
 */
@Composable
private fun StationTile(
    station: RadioStation,
    images: ImageLoader,
    logoStamp: String,
    playing: Boolean,
    caption: AlbumCaption,
    onTune: (RadioStation) -> Unit,
) {
    val art = caption.art.dp
    val well = coverSquare(art)
    val holds = LocalStationHolds.current
    // The antenna follows the edge, as the mark on an empty playlist square does.
    val mark = min(max(caption.art * 0.2, 16.0), 52.0).toFloat()
    Column(
        Modifier
            .width(art)
            .height(caption.card.dp)
            .holdPress(remember { MutableInteractionSource() }, { onTune(station) }, holds?.let { { it.of(station) } }),
    ) {
        Box(
            Modifier
                .size(art)
                .clip(well)
                .background(Color.Black.copy(alpha = 0x22 / 255f))
                .border(if (playing) 2.dp else 1.dp, if (playing) PLAYING_EDGE else Ink.cardBorder, well),
        ) {
            // The logo is kept off the edges by the share of the side that the radio home's squares use.
            StationFace(station, logoStamp, images, art, inset = art * TILE_INSET, modifier = Modifier.fillMaxSize()) {
                BasicText(Glyph.radio, style = icon(mark).copy(color = Ink.white(0x3A)))
            }
            AdsCorner(station.carriesAds)
        }
        Spacer(Modifier.height(AlbumCaption.ART_GAP.dp))
        val nameRoom = (caption.albumLine + AlbumCaption.LINE_GAP + caption.artistLine).dp
        Box(Modifier.width(art).height(nameRoom), contentAlignment = Alignment.TopCenter) {
            BasicText(
                station.display,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = system(caption.album, FontWeight.Medium).copy(color = Color.White, textAlign = TextAlign.Center),
            )
        }
    }
}

/** How far a wall tile's logo is kept from its edges, as a share of its side: 16 dp on the radio home's 132 dp squares. */
private const val TILE_INSET = 16f / 132f

/** The white edge of the tile that is playing, as a brush so that it can stand in for [Ink.cardBorder]. */
private val PLAYING_EDGE = SolidColor(Color.White)

/**
 * One station: the playing mark in the margin, its logo, its name with the AD mark if it has ads, a line about
 * it, and the save key. The row lights while it is pressed, as every list row does on iPhone (`RowPress`).
 *
 * @param mayChange whether the heart can be pressed. On a shared collection the saved stations are its owner's: their
 * hearts are shown and cannot be pressed, and the other stations have none.
 */
@Composable
private fun StationRow(
    station: RadioStation,
    images: ImageLoader,
    logoStamp: String,
    playing: Boolean,
    isSaved: Boolean,
    mayChange: Boolean,
    onTune: (RadioStation) -> Unit,
    onSave: (RadioStation) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val holds = LocalStationHolds.current
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 58.dp)
            .rowPress(held = holds?.let { { it.of(station) } }) { onTune(station) }
            .padding(start = 6.dp, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(26.dp).clip(RoundedCornerShape(50)).background(if (playing) Color.White else Color.Transparent))
        Logo(station, images, logoStamp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            // The AD mark is right after the name. A long name gives way to it; the mark is not cut.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(station.display, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(14f).copy(color = Color.White))
                if (station.carriesAds) AdsBadge()
            }
            BasicText(said(station), maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(11f).copy(color = Ink.white(0x88)))
        }
        // The one key on the row. The bundled Font Awesome has a solid heart only, so "saved" is shown by colour.
        when {
            mayChange -> {
                val label = stringResource(R.string.Radio_Save)
                Box(
                    Modifier
                        .size(40.dp, 44.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onSave(station)
                        }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(Glyph.heart, style = icon(14f).copy(color = if (isSaved) Ink.cyan else Ink.white(0x44)))
                }
            }
            isSaved -> {
                val label = stringResource(R.string.Radio_SavedMark)
                Box(Modifier.size(40.dp, 44.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                    BasicText(Glyph.heart, style = icon(14f).copy(color = Ink.cyan))
                }
            }
            else -> Spacer(Modifier.size(40.dp, 44.dp))
        }
    }
}

/** Example: "Netherlands  ·  Jazz, Soul  ·  MP3 128k". It shows the country, up to three genres, and the quality. */
private fun said(station: RadioStation): String {
    val parts = ArrayList<String>()
    station.countryCode?.let { countryName(it, Locale.getDefault()) }?.let(parts::add)
        ?: station.country?.takeIf { it.isNotBlank() }?.let(parts::add)
    RadioNames.genres(station.shownTags).take(3).takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(", ") }
    station.quality?.let(parts::add)
    return parts.joinToString("  ·  ")
}

/**
 * The station's own logo in a 40 dp well, on a dark ground so a see-through logo and a white square both look right
 * (the iPhone's `StationLogo`). The antenna is shown when there is no logo, or when its address no longer works.
 */
@Composable
private fun Logo(station: RadioStation, images: ImageLoader, logoStamp: String) {
    // The rounding every cover has, from its size (CoverCorner).
    val well = coverSquare(LOGO_EDGE)
    Box(Modifier.size(LOGO_EDGE).clip(well).background(Color.Black.copy(alpha = 0x22 / 255f)).border(1.dp, Ink.cardBorder, well)) {
        StationFace(station, logoStamp, images, LOGO_EDGE, inset = 3.dp, modifier = Modifier.fillMaxSize()) {
            BasicText(Glyph.radio, style = icon(15f).copy(color = Ink.white(0x3A)))
        }
    }
}

/** The side of a row's logo (`StationLogo.edge`). */
private val LOGO_EDGE = 40.dp


