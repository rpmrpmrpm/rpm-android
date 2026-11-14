package com.rpm.player.phone.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.items
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
import com.rpm.player.core.radio.RadioAds
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
import com.rpm.player.phone.design.GlassField
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.PageTitle
import com.rpm.player.phone.design.OverChrome
import com.rpm.player.phone.design.PullDownMenu
import com.rpm.player.phone.design.PullItem
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.dissolvesAtTop
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.design.rowPress
import com.rpm.player.phone.design.scrolled
import com.rpm.player.phone.design.system
import com.rpm.player.phone.library.FOOT
import com.rpm.player.phone.ui.RecordSpinner
import com.rpm.player.phone.ui.VinylGround
import java.util.Locale
import com.rpm.player.phone.design.ScrollsToTop

/** Which of the filter row's menus is open, and where its key is on the screen. */
private class Picking(val which: Which, val from: Rect) {
    enum class Which { Genre, Country, Order }
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
 * Internet radio. It matches the iPhone's `RadioPage` (RPMUI/RadioPage.swift). From the top: the dial, then the
 * stations it can reach. The dial and the list are one list of stations. So dragging the needle and tapping a
 * row tune the same station. Below the dial is the search field, and a row of filters: the genre, the country,
 * the order, and the saved stations only. Each filter opens a menu that drops from its key, as SwiftUI's `Menu` does.
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
) {
    val tuner = radio.tuner
    val settings by tuner.settings.collectAsStateWithLifecycle()
    val answer by tuner.answer.collectAsStateWithLifecycle()
    val countries by tuner.countries.collectAsStateWithLifecycle()
    val saved by radio.saved.collectAsStateWithLifecycle()
    val rules by radio.rules.collectAsStateWithLifecycle()
    val page = rememberBackdrop()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var picking by remember { mutableStateOf<Picking?>(null) }

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

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(page)) {
            VinylGround()
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PageTitle(stringResource(R.string.Rpm_Tab_Radio))
                    BasicText(stringResource(R.string.Radio_Subtitle), style = kicker())
                }
                RadioDial(list.stations, tuned, announced, onTune, Modifier.padding(horizontal = 20.dp).padding(top = 4.dp))
                Controls(
                    text = settings.text,
                    onText = { typed -> tuner.update { it.copy(text = typed) } },
                    focus = focus,
                    genre = settings.sieve.genre,
                    country = countries.firstOrNull { it.code == settings.country }?.name,
                    sorted = settings.order != StationOrder.Listed,
                    savedOnly = settings.favoritesOnly,
                    onPick = { which, from ->
                        focusManager.clearFocus()
                        picking = Picking(which, from)
                    },
                    onSaved = { tuner.update { it.copy(favoritesOnly = !it.favoritesOnly) } },
                )
                if (list.directoryDown) Trouble()
                Box(Modifier.weight(1f).imePadding()) {
                    Stations(list, settings, answer, images, rules.logoStamp, tuned, saved, bottomRoom, onTune, onSave)
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
                }
                // Over the tab bar and the mini player, as the iPhone's menu is.
                OverChrome { PullDownMenu(page, open.from, items, onDismiss = close) }
            }
        }
    }
}

/**
 * The search field on a line of its own, and under it the genre, the country, the order and the saved stations. On
 * the desktop these share one row; a phone is not wide enough.
 */
@Composable
private fun Controls(
    text: String,
    onText: (String) -> Unit,
    focus: FocusRequester,
    genre: String?,
    country: String?,
    sorted: Boolean,
    savedOnly: Boolean,
    onPick: (Picking.Which, Rect) -> Unit,
    onSaved: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            // Icon only, unlike the two boxes beside it: a third box would make them too narrow to read.
            Key(Glyph.sortAmount, on = sorted, label = stringResource(R.string.Radio_Sort)) { onPick(Picking.Which.Order, it) }
            Key(Glyph.heart, on = savedOnly, label = stringResource(R.string.Radio_ShowFavorites)) { onSaved() }
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

/**
 * A square key at the end of the filter row. **Cyan while it is in force**, which is the whole of its state: without
 * it, a list in a chosen order, or narrowed to the saved stations, would give no sign of that.
 */
@Composable
private fun Key(glyph: String, on: Boolean, label: String, onClick: (Rect) -> Unit) {
    val well = RoundedCornerShape(8.dp)
    val place = remember { Place() }
    Box(
        Modifier
            .size(44.dp, 40.dp)
            .onGloballyPositioned { place.bounds = it.boundsInRoot() }
            .clip(well)
            .background(if (on) Ink.cyan.copy(alpha = 0x1F / 255f) else Ink.white(0x14))
            .border(1.dp, if (on) Ink.cyan.copy(alpha = 0x55 / 255f) else Ink.white(0x1A), well)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onClick(place.bounds) }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(glyph, style = icon(14f).copy(color = if (on) Ink.cyan else Ink.white(0x88)))
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
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 58.dp)
            .rowPress { onTune(station) }
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
                if (RadioAds.carriesAds(station.tags, station.name, station.homepage)) AdsBadge()
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
    val well = RoundedCornerShape(6.dp)
    Box(Modifier.size(LOGO_EDGE).clip(well).background(Color.Black.copy(alpha = 0x22 / 255f)).border(1.dp, Ink.cardBorder, well)) {
        StationFace(station, logoStamp, images, LOGO_EDGE, inset = 3.dp, modifier = Modifier.fillMaxSize()) {
            BasicText(Glyph.radio, style = icon(15f).copy(color = Ink.white(0x3A)))
        }
    }
}

/** The side of a row's logo (`StationLogo.edge`). */
private val LOGO_EDGE = 40.dp

/** The "AD" mark, cut out of a small pale tile. It is shown after the name of a station that carries advertising. */
@Composable
private fun AdsBadge() {
    val said = stringResource(R.string.Radio_CarriesAds)
    Box(
        Modifier.size(19.dp, 13.dp).clip(RoundedCornerShape(2.5.dp)).background(Ink.white(0x8C)).semantics { contentDescription = said },
        contentAlignment = Alignment.Center,
    ) {
        BasicText("AD", style = system(9f, FontWeight.Bold).copy(color = Ink.ground))
    }
}
