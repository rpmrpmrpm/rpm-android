package com.rpm.player.tv.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.rpm.player.core.R
import com.rpm.player.core.Region
import com.rpm.player.core.room.RoomStyle
import kotlinx.coroutines.launch

/** Where an entry of the side menu goes: a screen, opened over home, or the player. */
sealed interface MenuGo {
    data class Screen(val route: Route) : MenuGo
    data object Player : MenuGo
}

/** One entry of the side menu: its glyph, its name, and where it goes. */
data class MenuEntry(val glyph: Char, @StringRes val label: Int, val go: MenuGo)

/** Who the side menu's header names: the collection's owner as they show themselves ([com.rpm.player.core.library.Face]),
 * and the line under the name. [name] is never empty: the caller falls back to the account's e-mail or the
 * collection's kind. */
data class MenuHeader(val name: String, val line: String, val picture: String?, val supporter: Boolean)

/**
 * Pegasus's side menu (PegasusChrome.xaml, SideMenuPanel; PegasusChrome.SideMenu.cs), opened from home
 * or radio with Left held for 2 seconds or with the remote's Menu key.
 *
 * A panel of the light glass `495·s` wide, `10·s` in from the left, top and bottom: the person at the
 * top (their picture, else a round mark with their initial; their name; "My collection"; the supporter mark
 * when the collection's owner is one) with the collections key and the reload key beside them (the reload key at the
 * top right reads the collection from the cloud again and turns while it does), the entries under it, and Exit rpm
 * with the settings key at the foot. It was 330·s; half as wide again, a long name such as a first name and a
 * family name with a particle is not cut short beside the two keys. Nothing else is placed from its width: the
 * screen under it does not move. The glass is dimmed ([GLASS_DIM]), which the desktop's is not, so white
 * text stays readable over a bright banner photo. It fades in over 140 ms and out over
 * 110; the screen behind it is neither dimmed nor moved.
 *
 * The entries are the Apple TV's sidebar's: Now playing while something is loaded, which opens the player,
 * then Home and the screens. The focus starts on the entry of the screen in front ([current]), as a sidebar's
 * does, else on the first. On the television the collections key opens the computers on the home network, the
 * settings key opens [SettingsPanel], and Exit rpm leaves the app.
 */
@Composable
fun SideMenu(
    backdrop: Backdrop,
    header: MenuHeader,
    images: coil3.ImageLoader,
    entries: List<MenuEntry>,
    /** The entry of the screen in front, which takes the focus first; null for none. */
    current: MenuGo?,
    onCollections: () -> Unit,
    /** Whether the collection is being read from the cloud again; the reload key turns while it is. */
    reloading: Boolean,
    /** Reads the collection from the cloud again. The menu stays open, so the turning key can be seen. */
    onReload: () -> Unit,
    onPick: (MenuGo) -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
    /** Whether Back here leaves the app: over home, where Back opened the menu. Then the first Back says so at the
     * foot of the screen ([onExitNotice]) and a second within [EXIT_WINDOW_MS] leaves; elsewhere Back closes it. */
    exitOnBack: Boolean,
    onExitNotice: () -> Unit,
    onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    // When the last Back was pressed here, on the uptime clock; 0 for none.
    var backAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val fade = remember { Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }

    fun close(then: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch {
            fade.animateTo(0f, tween(MENU_OUT_MS, easing = QuadInOut))
            then()
        }
    }

    BackHandler {
        if (!exitOnBack) return@BackHandler close(onClose)
        val now = android.os.SystemClock.uptimeMillis()
        if (backAt != 0L && now - backAt <= EXIT_WINDOW_MS) {
            close(onExit)
        } else {
            backAt = now
            onExitNotice()
        }
    }
    // Whether the screen under the menu has been taken for its glass. The panel fades in only after that, so its
    // first frames never show an older picture or the sharp screen through the glass; a screen that could not be
    // taken leaves the glass smoked without a picture, as the row menu's is.
    var taken by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { first.requestFocus() }
        taken = backdrop.capture()
        // A close asked for while the screen was being taken has started the fade out; the fade in would cancel it.
        if (!closing) fade.animateTo(1f, tween(MENU_IN_MS, easing = QuadInOut))
    }

    val spot = remember { FocusSpot() }
    val settingsKey = remember { FocusRequester() }
    var onExitKey by remember { mutableStateOf(false) }
    var onSettingsKey by remember { mutableStateOf(false) }
    val reloadKey = remember { FocusRequester() }
    var onSwitchKey by remember { mutableStateOf(false) }
    var onReloadKey by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .onPreviewKeyEvent { event ->
                // Right with nothing further right closes the menu, as on the desktop. Exit rpm has the
                // settings key to its right, and the collections key the reload key, so Right there moves instead.
                // Left goes nowhere, except from the settings key back to Exit rpm: the menu is the screen's left edge,
                // and the system's search for the nearest thing to the left finds Exit rpm, which sits a little
                // further left than the entries, so Left and then OK would close the app.
                when {
                    event.type != KeyEventType.KeyDown -> false
                    event.key == Key.DirectionRight && !onExitKey && !onSwitchKey -> {
                        close(onClose)
                        true
                    }
                    event.key == Key.DirectionLeft && !onSettingsKey && !onReloadKey -> true
                    else -> false
                }
            },
    ) {
        LiveGlassCard(
            backdrop = backdrop,
            corner = 7.s,
            modifier = Modifier.padding(start = 10.s, top = 10.s, bottom = 10.s).width(495.s).fillMaxHeight(),
        ) {
            Box(Modifier.matchParentSize().background(GLASS_DIM))
            SpotScope(spot, Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(start = 16.s, top = 20.s, end = 16.s, bottom = 16.s)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FacePicture(header.picture, header.name, Fa.HOUSE, images, 64.s, 26.ts, Modifier)
                        Column(Modifier.weight(1f).padding(start = 14.s, end = 10.s)) {
                            Text(header.name, style = ui(24.ts, FontWeight.SemiBold), color = Ink.Words, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(header.line, style = ui(15.ts), color = Ink.Artist, maxLines = 1)
                            if (header.supporter) SupporterMark(13f, Modifier.padding(top = 6.s))
                        }
                        MenuButton(
                            onClick = { close(onCollections) },
                            modifier = Modifier.size(52.s).onFocusChanged { onSwitchKey = it.isFocused }.focusProperties { right = reloadKey },
                        ) {
                            Glyph(Fa.SWITCH, 20.ts, Ink.Words)
                        }
                        val reloadLabel = stringResource(R.string.Tv_MenuReload)
                        MenuButton(
                            onClick = { if (!reloading) onReload() },
                            modifier = Modifier
                                .padding(start = 8.s)
                                .size(52.s)
                                .focusRequester(reloadKey)
                                .onFocusChanged { onReloadKey = it.isFocused }
                                .semantics { contentDescription = reloadLabel },
                        ) {
                            val turn = if (reloading) {
                                rememberInfiniteTransition(label = "reload")
                                    .animateFloat(0f, 360f, infiniteRepeatable(tween(RELOAD_TURN_MS, easing = LinearEasing), RepeatMode.Restart), label = "turn")
                                    .value
                            } else {
                                0f
                            }
                            Box(Modifier.rotate(turn)) { Glyph(Fa.RELOAD, 20.ts, Ink.Words) }
                        }
                    }
                    Column(Modifier.padding(top = 24.s), verticalArrangement = Arrangement.spacedBy(4.s)) {
                        val focused = entries.indexOfFirst { it.go == current }.takeIf { it >= 0 } ?: 0
                        entries.forEachIndexed { i, entry ->
                            Entry(
                                entry = entry,
                                onClick = { close { onPick(entry.go) } },
                                modifier = if (i == focused) Modifier.focusRequester(first) else Modifier,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.s)) {
                        MenuButton(
                            onClick = { close(onExit) },
                            modifier = Modifier
                                .weight(1f)
                                .height(60.s)
                                .onFocusChanged { onExitKey = it.isFocused }
                                .focusProperties { right = settingsKey },
                        ) {
                            Text(stringResource(R.string.Tv_MenuExit), style = ui(22.ts, FontWeight.SemiBold), color = Ink.Words)
                        }
                        MenuButton(onClick = { close(onSettings) }, modifier = Modifier.size(60.s).focusRequester(settingsKey).onFocusChanged { onSettingsKey = it.isFocused }) {
                            Glyph(Fa.GEAR, 22.ts, Ink.Words)
                        }
                    }
                }
            }
        }
    }
}

/** One entry: its glyph (22·s in a 32·s column) and its name (24·s SemiBold), 60·s high, lit `#33FFFFFF`
 * while the remote stands on it. */
@Composable
private fun Entry(entry: MenuEntry, onClick: () -> Unit, modifier: Modifier) {
    var lit by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(7.s)
    DeskButton(
        onClick = onClick,
        corner = 7.s,
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .onFocusChanged { lit = it.isFocused }
            .fillMaxWidth()
            .height(60.s)
            .background(if (lit) LIT else androidx.compose.ui.graphics.Color.Transparent, shape)
            .padding(horizontal = 12.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(32.s), contentAlignment = Alignment.Center) {
                Glyph(entry.glyph, 22.ts, Ink.Words)
            }
            Spacer(Modifier.width(16.s))
            Text(stringResource(entry.label), style = ui(24.ts, FontWeight.SemiBold), color = Ink.Words, maxLines = 1)
        }
    }
}

/**
 * The settings: two cards of the menu's dimmed glass, centred over the screen under a
 * `#66000000` shade. On the left the sections ([SettingsPage]), each its glyph and its name; on the right the
 * section's name and its rows, each its name on the left and its value on the right (22·s, the value
 * `#CCFFFFFF`). OK on a row moves it to the next value.
 *
 * The remote starts on the first section. Up and Down move through the sections, and the right card shows the
 * section the remote stands on; Right goes into its rows, Left or Back comes back out to the section. Back on the
 * sections closes the panel. Nothing else reaches the screen underneath.
 *
 * It fades in over [MENU_IN_MS] once the screen under it has been taken for its glass, and out over
 * [MENU_OUT_MS] before [onClose], as the side menu does.
 */
@Composable
fun SettingsPanel(backdrop: Backdrop, settings: PlayerSettings, account: SettingsAccount, region: Region, onClose: () -> Unit) {
    val pages = SettingsPage.entries
    val pageKeys = remember { List(pages.size) { FocusRequester() } }
    val rowKeys = remember { List(MOST_ROWS) { FocusRequester() } }
    var page by remember { mutableIntStateOf(0) }
    var row by remember { mutableIntStateOf(-1) }
    val fade = remember { Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val lines = settingLines(pages[page], settings, account, region)

    fun toPage() {
        runCatching { pageKeys[page].requestFocus() }
    }

    BackHandler(enabled = !closing) {
        if (row >= 0) return@BackHandler toPage()
        closing = true
        scope.launch {
            fade.animateTo(0f, tween(MENU_OUT_MS, easing = QuadInOut))
            onClose()
            // Redraws the screens at the resolution chosen, if it changed; after the fade, so it is not cut short.
            settings.applyResolution()
        }
    }
    LaunchedEffect(Unit) {
        runCatching { pageKeys[0].requestFocus() }
        backdrop.capture()
        // A close asked for while the screen was being taken has started the fade out; the fade in would cancel it.
        if (!closing) fade.animateTo(1f, tween(MENU_IN_MS, easing = QuadInOut))
    }
    val spot = remember { FocusSpot() }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(androidx.compose.ui.graphics.Color(0x66000000))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (row < 0) {
                    when (event.key) {
                        Key.DirectionDown -> { runCatching { pageKeys[(page + 1).coerceAtMost(pages.size - 1)].requestFocus() }; true }
                        Key.DirectionUp -> { runCatching { pageKeys[(page - 1).coerceAtLeast(0)].requestFocus() }; true }
                        Key.DirectionRight -> { if (lines.isNotEmpty()) runCatching { rowKeys[0].requestFocus() }; true }
                        Key.DirectionLeft -> true
                        else -> false
                    }
                } else {
                    when (event.key) {
                        Key.DirectionDown -> { runCatching { rowKeys[(row + 1).coerceAtMost(lines.size - 1)].requestFocus() }; true }
                        Key.DirectionUp -> { runCatching { rowKeys[(row - 1).coerceAtLeast(0)].requestFocus() }; true }
                        Key.DirectionLeft -> { toPage(); true }
                        Key.DirectionRight -> true
                        else -> false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        SpotScope(spot, Modifier) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.s)) {
                LiveGlassCard(backdrop = backdrop, corner = 7.s, modifier = Modifier.width(PAGES_WIDE).height(PANEL_HIGH)) {
                    Box(Modifier.matchParentSize().background(GLASS_DIM))
                    Column(Modifier.padding(10.s), verticalArrangement = Arrangement.spacedBy(4.s)) {
                        pages.forEachIndexed { i, p ->
                            PageRow(
                                page = p,
                                // The section shown on the right stays marked while the remote is in its rows.
                                chosen = i == page,
                                onClick = { if (lines.isNotEmpty()) runCatching { rowKeys[0].requestFocus() } },
                                modifier = Modifier.focusRequester(pageKeys[i]).onFocusChanged {
                                    if (it.isFocused) {
                                        page = i
                                        row = -1
                                    }
                                },
                            )
                        }
                    }
                }
                LiveGlassCard(backdrop = backdrop, corner = 7.s, modifier = Modifier.width(ROWS_WIDE).height(PANEL_HIGH)) {
                    Box(Modifier.matchParentSize().background(GLASS_DIM))
                    Column(Modifier.padding(horizontal = 16.s, vertical = 20.s)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.s, bottom = 14.s)) {
                            Glyph(pages[page].glyph, 22.ts, Ink.Words)
                            Spacer(Modifier.width(14.s))
                            Text(stringResource(pages[page].title), style = ui(26.ts, FontWeight.SemiBold), color = Ink.Words)
                        }
                        lines.forEachIndexed { i, line ->
                            SettingRow(
                                label = line.label,
                                value = line.value,
                                onClick = line.onClick,
                                modifier = Modifier.focusRequester(rowKeys[i]).onFocusChanged { if (it.isFocused) row = i },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The sections of the settings, in the order the left card lists them, each with its glyph and its name. */
enum class SettingsPage(val title: Int, val glyph: Char) {
    General(R.string.Tv_SettingsGeneral, Fa.GEAR),
    Profile(R.string.Settings_Profile, Fa.USER),
    Display(R.string.Settings_Display, Fa.TV),
    Sound(R.string.Settings_Sound, Fa.VOLUME),
    Player(R.string.Tv_SettingsPlayer, Fa.PLAY),
    Visualisation(R.string.Tv_SettingsVisualisation, Fa.WAVEFORM),
}

/** Who is signed in, for the Profile section, and the way to sign out. [supporter] is whether the account has the
 * supporter features (paying supporters and closed beta testers), null while not yet known; [tester] says which.
 * [crashReports] is the Send crash reports switch; [onFeedback] opens Send feedback, null when this build does not
 * offer it (ReportScope). */
class SettingsAccount(
    val name: String,
    val email: String,
    val supporter: Boolean?,
    val tester: Boolean,
    val signOut: () -> Unit,
    val crashReports: Boolean,
    val switchCrashReports: () -> Unit,
    val onFeedback: (() -> Unit)?,
)

/** One row of a section: its name, its value as shown, and what OK does. */
private class SettingLine(val label: String, val value: String, val onClick: () -> Unit)

/** The rows of [page]. */
@Composable
private fun settingLines(page: SettingsPage, settings: PlayerSettings, account: SettingsAccount, region: Region): List<SettingLine> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val on = stringResource(R.string.Viz_SettingOn)
    val off = stringResource(R.string.Viz_SettingOff)
    return when (page) {
        SettingsPage.General -> {
            val language = Region.languageOf(context)
            val locale = java.util.Locale.forLanguageTag(language)
            listOf(
                SettingLine(stringResource(R.string.Tv_SettingsAlbumView), stringResource(if (settings.albumCarousel) R.string.Tv_AlbumViewCarousel else R.string.Tv_AlbumViewGrid), settings::switchAlbumCarousel),
                SettingLine(stringResource(R.string.Tv_SettingsAlbumSort), stringResource(settings.albumOrder.label), settings::nextAlbumOrder),
                SettingLine(
                    stringResource(R.string.Tv_SettingsScreensaver),
                    when (val minutes = settings.screensaverMinutes) {
                        0 -> off
                        1 -> stringResource(R.string.Tv_SettingsAfterMinute)
                        else -> stringResource(R.string.Tv_SettingsAfterMinutes, minutes)
                    },
                    settings::nextScreensaver,
                ),
                SettingLine(stringResource(R.string.Tv_SettingsKeyLegend), if (settings.keyLegend) on else off, settings::switchKeyLegend),
                // The next language takes effect as the screens are drawn again, as on the set-up screen.
                SettingLine(stringResource(R.string.Tv_Setup_Language), locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }) {
                    val languages = Region.LANGUAGES
                    region.language = languages[(languages.indexOf(language) + 1) % languages.size]
                    (context as? android.app.Activity)?.recreate()
                },
            )
        }
        SettingsPage.Profile -> {
            var asked by remember { mutableStateOf(false) }
            listOf(
                SettingLine(stringResource(R.string.Tv_SettingsName), account.name) {},
                SettingLine(stringResource(R.string.Rpm_Profile_Account), account.email) {},
                SettingLine(
                    stringResource(R.string.Rpm_Profile_Plan),
                    when {
                        account.supporter == null -> ""
                        account.tester -> stringResource(R.string.Rpm_Profile_PlanTester)
                        account.supporter -> stringResource(R.string.Rpm_Profile_PlanSupporter)
                        else -> stringResource(R.string.Rpm_Profile_PlanFree)
                    },
                ) {},
                // Two presses, so a stray OK does not sign the television out.
                SettingLine(stringResource(R.string.Login_SignOut), if (asked) stringResource(R.string.Tv_SignOutConfirm) else "") {
                    if (asked) account.signOut() else asked = true
                },
                SettingLine(stringResource(R.string.Settings_CrashReports), if (account.crashReports) on else off, account.switchCrashReports),
            ) + listOfNotNull(
                account.onFeedback?.let { SettingLine(stringResource(R.string.Shell_Feedback), "", it) },
            )
        }
        SettingsPage.Display -> listOf(
            SettingLine(stringResource(R.string.Tv_SettingsResolution), resolutionLabel(settings.resolution, settings.outputHeight), settings::nextResolution),
            SettingLine(
                stringResource(R.string.Tv_SettingsPicture),
                stringResource(
                    when (settings.picture) {
                        PictureLook.Standard -> R.string.Tv_PictureStandard
                        PictureLook.Softer -> R.string.Tv_PictureSofter
                        PictureLook.Muted -> R.string.Tv_PictureMuted
                    },
                ),
                settings::nextPicture,
            ),
            SettingLine(
                stringResource(R.string.Settings_Background),
                stringResource(
                    when (settings.background) {
                        PageBackground.Default -> R.string.Settings_BackgroundDefault
                        PageBackground.Pegasus -> R.string.Settings_BackgroundPegasus
                        PageBackground.Dark -> R.string.Tv_BackgroundDark
                        PageBackground.Room -> R.string.Tv_BackgroundMoving
                    },
                ),
                settings::nextBackground,
            ),
            SettingLine(stringResource(R.string.Tv_SettingsShowStats), if (settings.showStats) on else off, settings::switchStats),
        )
        SettingsPage.Sound -> buildList {
            add(
                SettingLine(
                    stringResource(R.string.Settings_Normalize),
                    stringResource(
                        when (settings.levelMode) {
                            com.rpm.player.core.playback.LevelMode.Off -> R.string.Settings_NormalizeOff
                            com.rpm.player.core.playback.LevelMode.Album -> R.string.Settings_NormalizeAlbum
                            com.rpm.player.core.playback.LevelMode.Track -> R.string.Settings_NormalizeTrack
                        },
                    ),
                    settings::nextLevelMode,
                ),
            )
            // Only while levelling is on.
            if (settings.levelMode != com.rpm.player.core.playback.LevelMode.Off) {
                add(SettingLine(stringResource(R.string.Settings_NormalizeClassical), if (settings.levelsClassical) on else off, settings::switchLevelsClassical))
            }
        }
        SettingsPage.Player -> listOf(
            SettingLine(stringResource(R.string.Tv_SettingsMiniPlayer), stringResource(settings.miniPlace.label), settings::nextMiniPlace),
            // Karaoke is for supporters, as on the desktop. The row stays so it can be found; for
            // anyone else it says so and does nothing, and the stored setting is kept.
            SettingLine(
                stringResource(R.string.Tv_SettingsKaraoke),
                when {
                    account.supporter != true -> stringResource(R.string.Supporters_Title)
                    settings.karaoke -> on
                    else -> off
                },
            ) { if (account.supporter == true) settings.switchKaraoke() },
            SettingLine(stringResource(R.string.Tv_SettingsMixtapeAfter), if (settings.mixtapeAfter) on else off, settings::switchMixtapeAfter),
        )
        SettingsPage.Visualisation -> listOf(
            SettingLine(
                stringResource(R.string.Tv_SettingsDefaultLayout),
                stringResource(
                    when (settings.defaultView) {
                        PegasusView.Spread -> R.string.Viz_LayoutFull
                        PegasusView.Centred -> R.string.Viz_LayoutCompact
                    },
                ),
                settings::nextDefaultView,
            ),
            SettingLine(
                stringResource(R.string.Tv_SettingsRoomStyle),
                stringResource(
                    when (settings.roomStyle) {
                        RoomStyle.Original -> R.string.Tv_RoomStyleOriginal
                        RoomStyle.Yang -> R.string.Tv_RoomStyleYang
                        RoomStyle.Lava -> R.string.Tv_RoomStyleLava
                        RoomStyle.Alchemy -> R.string.Tv_RoomStyleAlchemy
                    },
                ),
                settings::nextRoomStyle,
            ),
        )
    }
}

/** A section in the left card: its glyph (22·s in a 32·s column) and its name (24·s SemiBold), 60·s high, lit
 * `#33FFFFFF` while the remote stands on it, and `#1AFFFFFF` while it is the section shown and the remote is in
 * its rows. */
@Composable
private fun PageRow(page: SettingsPage, chosen: Boolean, onClick: () -> Unit, modifier: Modifier) {
    var lit by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(7.s)
    DeskButton(
        onClick = onClick,
        corner = 7.s,
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .onFocusChanged { lit = it.isFocused }
            .fillMaxWidth()
            .height(60.s)
            .background(
                when {
                    lit -> LIT
                    chosen -> CHOSEN
                    else -> androidx.compose.ui.graphics.Color.Transparent
                },
                shape,
            )
            .padding(horizontal = 14.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(32.s), contentAlignment = Alignment.Center) { Glyph(page.glyph, 22.ts, Ink.Words) }
            Spacer(Modifier.width(14.s))
            Text(stringResource(page.title), style = ui(24.ts, FontWeight.SemiBold), color = Ink.Words, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The settings' two cards: the sections' width, the rows' width, and the height of both. */
private val PAGES_WIDE = 340.s
private val ROWS_WIDE = 760.s
private val PANEL_HIGH = 640.s

/** The section shown while the remote is in its rows. */
private val CHOSEN = androidx.compose.ui.graphics.Color(0x1AFFFFFF)

/** One settings row (SettingRow): the name (22·s SemiBold) and the value (22·s `#CCFFFFFF`) 40·s apart,
 * in a row 60·s high, lit `#33FFFFFF` while the remote stands on it. */
/** A resolution as the settings show it; Automatic names the height it comes to on this box. */
@Composable
fun resolutionLabel(resolution: ScreenResolution, outputHeight: Int): String = when (resolution) {
    ScreenResolution.Automatic -> stringResource(R.string.Tv_SettingsResolutionAuto, "${outputHeight}p")
    ScreenResolution.P2160 -> stringResource(R.string.Tv_Resolution2160)
    ScreenResolution.P1440 -> stringResource(R.string.Tv_Resolution1440)
    ScreenResolution.P1080 -> stringResource(R.string.Tv_Resolution1080)
    ScreenResolution.P720 -> stringResource(R.string.Tv_Resolution720)
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier) {
    var lit by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(7.s)
    DeskButton(
        onClick = onClick,
        corner = 7.s,
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .onFocusChanged { lit = it.isFocused }
            .fillMaxWidth()
            .height(60.s)
            .background(if (lit) LIT else androidx.compose.ui.graphics.Color.Transparent, shape)
            .padding(horizontal = 14.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = ui(22.ts, FontWeight.SemiBold), color = Ink.Words, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(40.s))
            Text(value, style = ui(22.ts), color = androidx.compose.ui.graphics.Color(0xCCFFFFFF), maxLines = 1)
        }
    }
}

/** The menu's boxed key (PegasusMenuButton): `#14FFFFFF` with a 1 px `#B3FFFFFF` rim, corner 7·s. */
@Composable
private fun MenuButton(onClick: () -> Unit, modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(7.s)
    var lit by remember { mutableStateOf(false) }
    DeskButton(
        onClick = onClick,
        corner = 7.s,
        modifier = modifier
            .onFocusChanged { lit = it.isFocused }
            .background(if (lit) LIT else MENU_FILL, shape)
            .border(1.px, if (lit) MENU_RIM_LIT else MENU_RIM, shape),
        content = content,
    )
}

private val LIT = androidx.compose.ui.graphics.Color(0x33FFFFFF)
private val MENU_FILL = androidx.compose.ui.graphics.Color(0x14FFFFFF)
private val MENU_RIM = androidx.compose.ui.graphics.Color(0xB3FFFFFF)
private val MENU_RIM_LIT = androidx.compose.ui.graphics.Color(0xE6FFFFFF)
/** The most rows a settings section has. */
private const val MOST_ROWS = 8
private const val MENU_IN_MS = 140

/** One turn of the reload key while the collection is read again. */
private const val RELOAD_TURN_MS = 1_000

/** How soon a second Back must follow the first for the side menu over home to leave the app. */
private const val EXIT_WINDOW_MS = 2_000L
private const val MENU_OUT_MS = 110
