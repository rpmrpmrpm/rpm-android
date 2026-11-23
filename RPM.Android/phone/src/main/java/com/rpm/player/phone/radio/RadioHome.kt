package com.rpm.player.phone.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import com.rpm.player.core.R
import com.rpm.player.core.library.BannerCard
import com.rpm.player.core.library.Collection
import com.rpm.player.core.radio.Essential
import com.rpm.player.core.radio.RadioGenres
import com.rpm.player.core.radio.RadioHomePicks
import com.rpm.player.core.radio.RadioRepository
import com.rpm.player.core.radio.RadioStation
import com.rpm.player.core.radio.StationPick
import com.rpm.player.core.radio.StationWhy
import com.rpm.player.core.radio.countryName
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.GlassKey
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.PageTitle
import com.rpm.player.phone.design.ScrollsToTop
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.dissolvesAtTop
import com.rpm.player.phone.design.frostsAtTop
import com.rpm.player.phone.design.holdPress
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.design.rememberTopPull
import com.rpm.player.phone.design.system
import com.rpm.player.phone.design.tapsToTop
import com.rpm.player.phone.home.Banner
import com.rpm.player.phone.home.BannerDress
import com.rpm.player.phone.home.BannerWords
import com.rpm.player.phone.home.HomeActions
import com.rpm.player.phone.home.PANELS_APART
import com.rpm.player.phone.home.PEEK
import com.rpm.player.phone.home.QUICK_ART
import com.rpm.player.phone.home.QUICK_LINE
import com.rpm.player.phone.home.QUICK_STATION_INSET
import com.rpm.player.phone.home.QUICK_TALL
import com.rpm.player.phone.home.ROW_ART
import com.rpm.player.phone.home.SECTIONS_APART
import com.rpm.player.phone.library.FOOT
import com.rpm.player.phone.ui.RecordSpinner
import com.rpm.player.phone.ui.ArtGround
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale

/** How many local stations the row under Quick selection shows. */
private const val LOCAL_ROW = 20

/** How many of the world's most played stations the last row shows. */
private const val POPULAR_ROW = 20

/**
 * The radio tab's first page, built like home ([com.rpm.player.phone.home.HomePage]). From the top:
 *
 * - **The banner**: the stations heard most and the local stations, one of each in turn ([RadioHomePicks.banner]). Each
 *   card stands on its station's room, as home's radio card does, and says why the station is there.
 * - **Quick selection**, drawn as home's is: the stations the person pinned, then their saved stations, then stations
 *   suggested from the genres they listen to ([RadioHomePicks.quick]). A long press on any station pins it here
 *   ([StationHolds]).
 * - **A row of local stations**: the most played stations of the listener's country. Left out when the directory has
 *   none for that country: the stations read then are the world's, and the last row already shows those.
 * - **A row of the world's most played stations**, without the local ones.
 *
 * The round key on the title's line opens the tuner: the dial, the search and the full list of stations ([RadioPage]).
 *
 * The page is dressed in the front card's colours, as home is: each card stands on its station's room, and the
 * ground under the whole page takes that room's colours ([BannerDress]).
 *
 * @param pinned the stations pinned to Quick selection, in the order they were pinned.
 * @param onScreen whether this page is the one showing: the banner turns only then.
 */
@Composable
fun RadioHome(
    radio: RadioRepository,
    collection: Collection,
    images: ImageLoader,
    pinned: List<RadioStation>,
    bottomRoom: Dp,
    onScreen: Boolean,
    onTune: (RadioStation) -> Unit,
    onOpenTuner: () -> Unit,
) {
    val essential by radio.essential.collectAsStateWithLifecycle()
    val rules by radio.rules.collectAsStateWithLifecycle()
    val saved by radio.saved.collectAsStateWithLifecycle()
    val heardRows by radio.listens.top.collectAsStateWithLifecycle()
    val popularAll by radio.popular.collectAsStateWithLifecycle()
    // Each time the radio is shown: what is stale is read again, as the tuner does.
    LaunchedEffect(Unit) { radio.refresh() }

    val local = remember(essential, rules) {
        (essential as? Essential.Read)?.takeIf { it.countryCode != null }?.let { rules.shown(it.stations) }.orEmpty().take(LOCAL_ROW)
    }
    val heard = remember(heardRows, rules) { rules.shown(heardRows.map { it.station }) }
    val banner = remember(heard, local) { RadioHomePicks.banner(heard, local) }
    val pinnedShown = remember(pinned, rules) { rules.shown(pinned) }
    val savedShown = remember(saved, rules) { rules.shown(saved.stations) }
    // The person's own stations, the pinned and saved ones first: they decide a tie between two genres.
    val theirs = remember(pinnedShown, savedShown, heard) { pinnedShown + savedShown + heard.take(RadioHomePicks.TASTE_FROM) }
    val genres = remember(theirs) { RadioHomePicks.taste(theirs) }
    // The directory's answers for those genres. With no genres yet, the local and the world's stations stand in.
    val answers by produceState(emptyList<List<RadioStation>>(), genres) {
        value = coroutineScope { genres.map { genre -> async { radio.stationsOf(genre) } }.awaitAll() }
    }
    val popular = remember(popularAll, rules, local) { RadioHomePicks.without(rules.shown(popularAll), local).take(POPULAR_ROW) }
    val suggested = remember(answers, rules, theirs, genres, local, popular) {
        val from = if (genres.isEmpty()) listOf(local, popular) else answers.map(rules::shown)
        RadioHomePicks.suggested(from, theirs)
    }
    val quick = remember(pinnedShown, savedShown, suggested) { RadioHomePicks.quick(pinnedShown, savedShown, suggested) }
    val why = remember(banner) { banner.associate { it.station.uuid to it.why } }

    val density = LocalDensity.current
    val statusBar = WindowInsets.statusBars.asPaddingValues(density).calculateTopPadding()
    var heading by remember { mutableStateOf(0.dp) }
    var dress by remember { mutableStateOf<BannerDress?>(null) }
    val scroll = rememberScrollState()
    ScrollsToTop(scroll)
    // The ground, which the tuner key frosts, and the rows as they scroll, which the band under the title frosts.
    val ground = rememberBackdrop()
    val rows = rememberBackdrop()
    val pull = rememberTopPull()
    val atTop by remember { derivedStateOf { scroll.value < 1 } }
    val overhang = if (banner.isNotEmpty() && heading > 0.dp) heading + statusBar else 0.dp
    val actions = remember(onTune) { HomeActions(onOpen = {}, onOpenList = {}, onPlay = { _, _, _ -> }, onTune = onTune) }
    val nearYou = stringResource(R.string.Radio_HomeNearYou)
    val onRepeat = stringResource(R.string.Radio_HomeOnRepeat)

    Box(Modifier.fillMaxSize()) {
        // The front card's colours, as home's ground takes them. The app's own ground until a card has dressed the page.
        Box(Modifier.fillMaxSize().backdropSource(ground)) { ArtGround(null, images, colours = dress?.colours.orEmpty()) }
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
                Banner(
                    banner.map { BannerCard.OfStation(it.station) },
                    collection,
                    images,
                    rules.logoStamp,
                    overhang,
                    statusBar,
                    turns = onScreen && atTop,
                    pull,
                    roomsRun = onScreen,
                    onDress = { dress = it },
                    onReady = {},
                    actions = actions,
                    stationWords = { station ->
                        BannerWords(if (why[station.uuid] == StationWhy.Local) nearYou else onRepeat, shortLine(station))
                    },
                )
            } else {
                Spacer(Modifier.height(heading + statusBar))
            }
            QuickStations(quick, images, rules.logoStamp, onTune)
            if (local.isNotEmpty()) StationShelf(stringResource(R.string.Radio_HomeLocal), local, images, rules.logoStamp, onTune)
            if (popular.isNotEmpty()) StationShelf(stringResource(R.string.Radio_HomePopular), popular, images, rules.logoStamp, onTune)
            when {
                local.isEmpty() && popular.isEmpty() && essential is Essential.Unavailable -> Trouble()
                local.isEmpty() && popular.isEmpty() -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RecordSpinner(34.dp) }
            }
        }
        // What scrolls up under the title, frosted as it goes, as on home.
        Box(Modifier.fillMaxWidth().height(statusBar + heading + 30.dp).frostsAtTop(rows, statusBar + heading) { scroll.value.toFloat() })
        // The title's band takes every touch, so nothing under it is tuned through it, and a tap takes the page to its top.
        Box(Modifier.fillMaxWidth().height(statusBar + heading).tapsToTop())
        PageTitle(
            stringResource(R.string.Rpm_Tab_Radio),
            Modifier
                .statusBarsPadding()
                .padding(start = 20.dp, end = 16.dp, bottom = 10.dp)
                .onSizeChanged { heading = with(density) { it.height.toDp() } + 10.dp },
        )
        // Where home has its collections key: on the title's line, 16 from the edge, over the title's band.
        TunerKey(ground, onOpenTuner, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 4.dp, end = 16.dp))
    }
}

/** The round glass key that opens the tuner. */
@Composable
private fun TunerKey(backdrop: Backdrop, onOpenTuner: () -> Unit, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    val label = stringResource(R.string.Radio_HomeTuner)
    GlassKey(
        backdrop,
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onOpenTuner()
        },
        modifier = modifier.semantics { contentDescription = label },
    ) {
        BasicText(Glyph.tuner, style = icon(15f).copy(color = Ink.white(0xB8)))
    }
}

/**
 * Quick selection, drawn as home's is (`PicksRow`): lines in panels of four, from the top down, swiped one panel at a
 * time with the edge of the next panel showing. While it has nothing, a line says how to pin a station.
 */
@Composable
private fun QuickStations(picks: List<StationPick>, images: ImageLoader, logoStamp: String, onTune: (RadioStation) -> Unit) {
    val panels = picks.chunked(QUICK_TALL)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.height(34.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(stringResource(R.string.Home_QuickPicks), style = display(19f).copy(color = Color.White))
        }
        if (panels.isEmpty()) {
            BasicText(stringResource(R.string.Radio_HomeQuickEmpty), Modifier.padding(horizontal = 20.dp), style = system(13f).copy(color = Ink.secondary))
            return@Column
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
                Column { for (pick in panels[page]) QuickStation(pick, images, logoStamp, onTune) }
            }
        }
    }
}

/** One line of Quick selection: the station's logo, its name, and why it is there with its country. */
@Composable
private fun QuickStation(pick: StationPick, images: ImageLoader, logoStamp: String, onTune: (RadioStation) -> Unit) {
    val station = pick.station
    val holds = LocalStationHolds.current
    val kind = stringResource(
        when (pick.why) {
            StationWhy.Pinned -> R.string.Radio_HomePinned
            StationWhy.Saved -> R.string.Radio_HomeSaved
            else -> R.string.Radio_HomeForYou
        },
    )
    val place = station.countryCode?.let { countryName(it, Locale.getDefault()) } ?: station.country
    val line = if (place.isNullOrBlank()) kind else stringResource(R.string.Home_QuickKindJoin, kind, place)
    Row(
        Modifier
            .fillMaxWidth()
            .height(QUICK_LINE)
            .holdPress(remember { MutableInteractionSource() }, { onTune(station) }, holds?.let { { it.of(station) } }),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(QUICK_ART).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0x22 / 255f))) {
            StationFace(station, logoStamp, images, QUICK_ART, inset = QUICK_STATION_INSET, Modifier.fillMaxSize()) {
                BasicText(Glyph.radio, style = icon(15f).copy(color = Ink.white(0x3A)))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BasicText(station.display, maxLines = 1, overflow = TextOverflow.Ellipsis, style = system(14f, FontWeight.Medium).copy(color = Color.White))
            BasicText(line, maxLines = 1, overflow = TextOverflow.Ellipsis, style = system(12f).copy(color = Ink.secondary))
        }
    }
}

/** A row of stations under a heading, scrolled sideways, at the size of home's rows of records. */
@Composable
private fun StationShelf(heading: String, stations: List<RadioStation>, images: ImageLoader, logoStamp: String, onTune: (RadioStation) -> Unit) {
    val art = ROW_ART.dp
    val holds = LocalStationHolds.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText(heading, Modifier.padding(horizontal = 20.dp), style = display(19f).copy(color = Color.White))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(stations, key = { it.uuid }) { station ->
                Column(
                    Modifier.width(art).holdPress(remember { MutableInteractionSource() }, { onTune(station) }, holds?.let { { it.of(station) } }),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    val well = RoundedCornerShape(10.dp)
                    Box(Modifier.size(art).clip(well).background(Color.Black.copy(alpha = 0x22 / 255f)).border(1.dp, Ink.cardBorder, well)) {
                        StationFace(station, logoStamp, images, art, inset = 16.dp, Modifier.fillMaxSize()) {
                            BasicText(Glyph.radio, style = icon(30f).copy(color = Ink.white(0x3A)))
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        BasicText(station.display, maxLines = 1, overflow = TextOverflow.Ellipsis, style = system(12f, FontWeight.Medium).copy(color = Color.White))
                        shortLine(station)?.let {
                            BasicText(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = system(11f).copy(color = Ink.secondary))
                        }
                    }
                }
            }
        }
    }
}

/** Shown when the directory did not answer and there is nothing to list. */
@Composable
private fun Trouble() {
    BasicText(
        stringResource(R.string.Radio_DirectoryDown),
        Modifier.padding(horizontal = 20.dp),
        style = system(13f).copy(color = Ink.secondary),
    )
}

/** Example: "Jazz · Netherlands". The station's first genre and its country, either of them, or null with neither. */
private fun shortLine(station: RadioStation): String? {
    val genre = station.shownTags.firstNotNullOfOrNull { RadioGenres.names(it).firstOrNull() }
    val place = station.countryCode?.let { countryName(it, Locale.getDefault()) } ?: station.country?.takeIf { it.isNotBlank() }
    return listOfNotNull(genre, place).joinToString(" · ").ifEmpty { null }
}
