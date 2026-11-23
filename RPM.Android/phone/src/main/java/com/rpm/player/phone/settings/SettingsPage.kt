package com.rpm.player.phone.settings

import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CloudQuota
import com.rpm.player.core.library.DiscStandIn
import com.rpm.player.core.playback.AudioLadder
import com.rpm.player.core.playback.EqCurve
import com.rpm.player.core.playback.EqPreset
import com.rpm.player.core.playback.Equalizer
import com.rpm.player.core.playback.LevelMode
import com.rpm.player.phone.design.AppColour
import com.rpm.player.phone.design.AppColourBubbles
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.controlColour
import com.rpm.player.phone.design.glassTint
import com.rpm.player.phone.design.OverChrome
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.GlassCard
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.PageTabs
import com.rpm.player.phone.design.UnderHeader
import com.rpm.player.phone.design.PageTitle
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.design.system
import com.rpm.player.phone.library.FOOT
import com.rpm.player.phone.library.KeyGroup
import com.rpm.player.phone.ui.Ground
import com.rpm.player.phone.ui.GroundTile
import com.rpm.player.phone.ui.VinylGround
import java.text.DecimalFormat
import com.rpm.player.phone.design.ScrollsToTop

/** The page's side margin, the iPhone's `RPMLayout.margin`. */
private val MARGIN = 20.dp

/** How deep the fade is under the header, once the page has scrolled that far. */
private val DISSOLVE = 30.dp

/** The space between two keys on one row. */
private val KEYS_APART = 10.dp

/** The height of a key on this screen. Smaller than the app's standard key: none of them is a screen's main action. */
private val KEY_HEIGHT = 34.dp

/** The height of an equaliser fader: the desktop's 110 plus room for the knob. */
private val FADER_TALL = 130.dp

/** A backdrop tile has the shape of the phone screen, small enough that five and part of a sixth fit across. */
private val TILE_WIDE = 62.dp
private val TILE_TALL = 116.dp

/**
 * The parts of the settings page, one tab each in the row under its header: the iPhone's `SettingsSection`.
 *
 * @param stored the name it is saved under, the same as the iPhone's.
 */
enum class SettingsSection(val stored: String, @StringRes val label: Int, val glyph: String) {
    /** The version, the acknowledgements, crash reports and the cover cache. */
    General("general", R.string.Rpm_Settings_General, Glyph.general),

    /** The background, night mode and what spins for a record with no disc picture. */
    Appearance("appearance", R.string.Rpm_Settings_Appearance, Glyph.picture),

    /** The streaming rates, volume levelling, the equaliser and karaoke. */
    Playback("playback", R.string.Rpm_Profile_Playback, Glyph.speaker),

    /** The albums on the phone and the rate that downloads ask for. */
    Downloads("downloads", R.string.Rpm_Downloads_Title, Glyph.download),

    /** The account, its cloud storage and its plan. */
    Account("account", R.string.Rpm_Settings_Account, Glyph.profile);

    companion object {
        fun of(stored: String?): SettingsSection? = entries.firstOrNull { it.stored == stored }
    }
}

/**
 * The settings tab: the account, and all the app settings that are choices and not records. It is the iPhone's
 * `SettingsPage`. It is a tab and not a gear key in a corner, because it holds more than settings: how much cloud
 * storage is used, the plan of the account, and the way to sign out.
 *
 * It is laid out like the library tab: the title, a row of tabs ([SettingsSection]), and the cards of the chosen
 * section under them. Send feedback is the round key beside the title. Every card has the same shape: a glyph and a
 * kicker, then the current value, then the actions.
 *
 * @param section the section that is shown. It is saved, so the page opens on the section used last.
 * @param reachSavesData whether Data Saver holds streaming down. The rate menus then show a note, because a menu that
 * says 320 while the stream is 128, with no reason on the screen, looks like a setting that does nothing.
 * @param downloaded how many albums and playlists are downloaded to this phone, and what they take.
 * @param onDownloads opens the list of downloaded albums and playlists. Null while that page does not exist; the key is then dim.
 * @param onDeleteAccount deletes the account and signs out. Returns null when done, and a sentence when it failed.
 * @param karaoke the Karaoke lyrics switch as stored. It shows off, and cannot be changed, for an account that is not a
 * supporter's.
 * @param bottomRoom how much of the bottom of the screen the chrome covers.
 */
@Composable
fun SettingsPage(
    section: SettingsSection,
    onSection: (SettingsSection) -> Unit,
    name: String,
    email: String,
    quota: CloudQuota?,
    offer: AudioLadder.RateOffer,
    onWifi: Int,
    onCellular: Int,
    onDownload: Int,
    onRates: Rates,
    levelling: LevelMode,
    levelsClassical: Boolean,
    onLevelling: (LevelMode) -> Unit,
    onLevelsClassical: (Boolean) -> Unit,
    reachSavesData: Boolean,
    cached: Long,
    downloaded: com.rpm.player.phone.downloads.Downloaded,
    onDownloads: (() -> Unit)?,
    ground: Ground,
    isGold: Boolean,
    onGround: (Ground) -> Unit,
    nightMode: Boolean,
    onNightMode: (Boolean) -> Unit,
    discStandIn: DiscStandIn,
    onDiscStandIn: (DiscStandIn) -> Unit,
    version: String,
    onClearCache: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: suspend () -> String?,
    onSupport: () -> Unit,
    crashReports: Boolean,
    onCrashReports: (Boolean) -> Unit,
    onFeedback: (() -> Unit)?,
    bottomRoom: Dp,
    karaoke: Boolean,
    onKaraoke: (Boolean) -> Unit,
    sharesListening: Boolean? = null,
    onSharesListening: (Boolean) -> Unit = {},
    openingSound: Boolean = true,
    onOpeningSound: (Boolean) -> Unit = {},
    articleLanguage: String = "",
    onArticleLanguage: (String) -> Unit = {},
    playerBars: Boolean = true,
    onPlayerBars: (Boolean) -> Unit = {},
    onReplayHints: () -> Unit = {},
    equalizer: EqControls? = null,
) {
    val page = rememberBackdrop()
    // What the header's glass frosts: the background and the cards that scroll under it.
    val behind = rememberBackdrop()
    val scroll = rememberScrollState()
    ScrollsToTop(scroll)
    var deletingAccount by remember { mutableStateOf(false) }
    var credits by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<MenuAsk?>(null) }
    BackHandler(enabled = menu != null) { menu = null }

    val cards = Cards(offer, onSupport, onAsk = { menu = it })
    // Each section opens at its top.
    LaunchedEffect(section) { scroll.scrollTo(0) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(page)) {
            // The header stays over the scroll, as on iPhone: it is the name of the page, and nobody should have to
            // scroll back to see it.
            UnderHeader(behind, ground = { VinylGround() }, header = { Header(behind, section, onSection, onFeedback) }) { top ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .dissolvesUnder(top, DISSOLVE) { scroll.value.toFloat() }
                        .verticalScroll(scroll)
                        .padding(horizontal = MARGIN)
                        .padding(top = top + 4.dp, bottom = FOOT + bottomRoom),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (section) {
                        SettingsSection.General -> {
                            cards.Opening(openingSound, onOpeningSound)
                            cards.AppLanguage()
                            cards.Descriptions(articleLanguage, onArticleLanguage)
                            cards.Hints(onReplayHints)
                            cards.About(version, onCredits = { credits = true }, crashReports, onCrashReports)
                            cards.Device(cached, onClearCache)
                        }
                        SettingsSection.Appearance -> {
                            cards.Backdrops(ground, isGold, onGround, nightMode, onNightMode, discStandIn, onDiscStandIn)
                            cards.Colour()
                            CoverCornerCard()
                            cards.Player(playerBars, onPlayerBars)
                        }
                        SettingsSection.Playback -> {
                            cards.Playback(onWifi, onCellular, onRates, reachSavesData, quota, karaoke, onKaraoke)
                            cards.Loudness(levelling, levelsClassical, onLevelling, onLevelsClassical)
                            equalizer?.let { cards.Eq(it) }
                        }
                        SettingsSection.Downloads -> cards.Downloads(downloaded, onDownloads, onDownload, onRates)
                        SettingsSection.Account -> {
                            cards.Account(name, email, supporter = quota?.wearsMark == true, onSignOut, onDelete = { deletingAccount = true })
                            if (sharesListening != null) cards.Sharing(sharesListening, onSharesListening)
                            cards.Storage(quota)
                            if (quota != null) cards.Plan(quota)
                        }
                    }
                }
            }
        }
        menu?.let { ask -> Menu(page, ask, bottomRoom, onClose = { menu = null }) }
        // Over the tab bar and the mini player, as the iPhone's sheet is.
        OverChrome {
            AnimatedVisibility(deletingAccount, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
                DeleteAccountSheet(email, page, bottomRoom, commit = onDeleteAccount, onClose = { deletingAccount = false })
            }
        }
    }
    if (credits) AboutPage(version, onClose = { credits = false })
}

/** The title with Send feedback beside it, and the row of sections under them. */
@Composable
private fun Header(backdrop: Backdrop, section: SettingsSection, onSection: (SettingsSection) -> Unit, onFeedback: (() -> Unit)?) {
    Column(Modifier.statusBarsPadding()) {
        // The key is drawn over the title's line and not in a row with it, as on the library tab: a 40 dp key is taller
        // than the letters and would set the height of the line.
        Box(Modifier.fillMaxWidth().padding(start = MARGIN, end = MARGIN, bottom = 10.dp)) {
            PageTitle(stringResource(R.string.Settings_Title))
            onFeedback?.let { FeedbackKey(backdrop, it, Modifier.align(Alignment.CenterEnd)) }
        }
        PageTabs(SettingsSection.entries, section, onSection, backdrop, label = { it.label }) { choice, ink ->
            BasicText(choice.glyph, style = icon(14f).copy(color = ink))
        }
    }
}

/** Send feedback: one round glass key, the same size as the library tab's header keys. */
@Composable
private fun FeedbackKey(backdrop: Backdrop, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.Shell_Feedback)
    Box(modifier) {
        KeyGroup(backdrop) {
            Box(
                Modifier
                    .size(40.dp)
                    .semantics { contentDescription = label }
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(Glyph.feedback, style = icon(16f).copy(color = Ink.white(0xB8)))
            }
        }
    }
}

/**
 * The equaliser card's state: the speakers the music goes to, their curve, and what a change does. [onCurve] is given
 * the whole new curve.
 */
class EqControls(val output: EqOutput, val curve: EqCurve, val onCurve: (EqCurve) -> Unit)

/** The three rate settings' setters. */
class Rates(val onWifi: (Int) -> Unit, val onCellular: (Int) -> Unit, val onDownload: (Int) -> Unit)

/** The cards of the page. They share the offer and the way to open a menu. */
private class Cards(val offer: AudioLadder.RateOffer, val onSupport: () -> Unit, val onAsk: (MenuAsk) -> Unit) {

    // ── account ──

    /**
     * The switch for "listening to" on the profile page. It applies to every device of the account, because the server
     * keeps it.
     */
    @Composable
    fun Sharing(on: Boolean, onChange: (Boolean) -> Unit) {
        val haptics = LocalHapticFeedback.current
        Card(Glyph.play, stringResource(R.string.Rpm_Tab_Profile)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Switch) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onChange(!on)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(stringResource(R.string.Rpm_Settings_ShareListening), Modifier.weight(1f), style = display(13f).copy(color = Ink.white(0xB3)))
                Spacer(Modifier.width(10.dp))
                Switch(on)
            }
        }
    }

    @Composable
    fun Account(name: String, email: String, supporter: Boolean, onSignOut: () -> Unit, onDelete: () -> Unit) {
        Card(Glyph.profile, stringResource(R.string.Rpm_Profile_Account)) {
            // No supporter badge here. It is shown in the Supporter card instead.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // A supporter's name is in gold, as on their profile page.
                BasicText(name.ifEmpty { email }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(17f).copy(color = if (supporter) Ink.gold else Color.White))
                if (name.isNotEmpty()) {
                    BasicText(email, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(13f).copy(color = Ink.white(0x88)))
                }
            }
            // One row: Sign out takes two thirds and Delete one third, so the action people need most is the larger key
            // and the destructive one is still on the card, easy to find. The sheet it opens asks for the email
            // address, so a tap on Delete deletes nothing by itself.
            Row(horizontalArrangement = Arrangement.spacedBy(KEYS_APART)) {
                Key(stringResource(R.string.Login_SignOut), Modifier.weight(2f), onClick = onSignOut)
                val label = stringResource(R.string.Account_Delete)
                GlassButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f).semantics { contentDescription = label },
                    height = KEY_HEIGHT,
                    radius = 9.dp,
                    inset = 12.dp,
                    size = 13f,
                ) { style ->
                    // One word: a third of the row is too narrow for "Delete account". The card says what is deleted.
                    BasicText(stringResource(R.string.Rpm_Profile_DeleteAccount), maxLines = 1, style = style.copy(color = Ink.red))
                }
            }
        }
    }

    // ── cloud storage ──

    @Composable
    fun Storage(quota: CloudQuota?) {
        Card(Glyph.cloud, stringResource(R.string.Cloud_Heading)) {
            if (quota != null) {
                BasicText(said(quota), style = display(17f).copy(color = Color.White))
                if (!quota.unlimited && quota.quotaBytes > 0) Meter(quota.usedBytes.toDouble() / quota.quotaBytes)
                BasicText(
                    if (quota.fileCount == 1) stringResource(R.string.Rpm_Profile_TracksOne) else stringResource(R.string.Rpm_Queue_Count, quota.fileCount),
                    style = display(11f).copy(color = Ink.white(0x66)),
                )
            } else {
                BasicText(stringResource(R.string.Rpm_Profile_QuotaUnknown), style = display(13f).copy(color = Ink.white(0x88)))
            }
        }
    }

    /** The usage bar: the same white as the seek knob, on a dark track. Red when nearly full. */
    @Composable
    private fun Meter(full: Double) {
        Box(Modifier.padding(vertical = 2.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0x22 / 255f))) {
            Box(
                Modifier
                    .fillMaxWidth(full.coerceIn(0.0, 1.0).toFloat())
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(if (full > 0.9) Ink.red else Ink.white(0xE6)),
            )
        }
    }

    // ── on this phone ──

    /** The albums and playlists kept on the phone for listening offline, and the way to the list of them. */
    @Composable
    fun Downloads(downloaded: com.rpm.player.phone.downloads.Downloaded, onDownloads: (() -> Unit)?, onDownload: Int, rates: Rates) {
        Card(Glyph.download, stringResource(R.string.Rpm_Downloads_Title)) {
            BasicText(
                com.rpm.player.phone.downloads.downloadsSummary(downloaded, ::bytes),
                style = display(17f).copy(color = Color.White),
            )
            Key(stringResource(R.string.Rpm_Downloads_Manage), enabled = onDownloads != null, onClick = { onDownloads?.invoke() })
            // A download asks for this rate on any network, so it is not with the streaming rates on Playback.
            Rate(stringResource(R.string.Rpm_Profile_QualityDownload), onDownload, rates.onDownload)
            Capped()
        }
    }

    // ── supporter status ──

    /** Only this account's status. The caller shows it only once the quota has been read, so a request that timed
     * out does not say "Not a supporter" to a supporter. */
    @Composable
    fun Plan(quota: CloudQuota) {
        Card(Glyph.heart, stringResource(R.string.Rpm_Profile_Plan)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // A supporter gets the gold badge, which holds the word "Supporter". The free plan and a closed beta
                // tester are named in cyan: a tester has the supporter features but does not pay, so has no badge.
                if (quota.wearsMark) {
                    SupporterMark(Modifier.padding(vertical = 2.dp))
                } else {
                    BasicText(
                        stringResource(if (quota.tester) R.string.Rpm_Profile_PlanTester else R.string.Rpm_Profile_PlanFree),
                        style = display(17f).copy(color = Ink.cyan),
                    )
                }
            }
        }
    }

    // ── the app background ──

    /** The built-in backdrops, as a row of the real pictures. The supporter's is shown to everybody and is not
     * greyed out, so people can see it; a tap on it without support opens the page that explains it. */
    @Composable
    fun Backdrops(
        standing: Ground,
        isGold: Boolean,
        onGround: (Ground) -> Unit,
        nightMode: Boolean,
        onNightMode: (Boolean) -> Unit,
        discStandIn: DiscStandIn,
        onDiscStandIn: (DiscStandIn) -> Unit,
    ) {
        val haptics = LocalHapticFeedback.current
        Card(Glyph.picture, stringResource(R.string.Settings_Background)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                for (choice in Ground.entries) {
                    Tile(choice, picked = choice == standing, locked = choice.isSupporters && !isGold) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        // Checked against what the ground itself reads, not the quota on this page: the two are known at
                        // different times, and a tile picked for a picture the ground would not show would be wrong.
                        if (choice.isSupporters && !isGold) onSupport() else onGround(choice)
                    }
                }
            }
            // Right under the backdrops, so the picture and how it is quietened are read together.
            BackdropVeilSliders()
            // Under the backdrops: it changes the other kind of background, the photograph a page stands on.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { onNightMode(!nightMode) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(stringResource(R.string.Settings_NightMode), Modifier.weight(1f), style = display(13f).copy(color = Ink.white(0xB3)))
                Spacer(Modifier.width(10.dp))
                Switch(nightMode)
            }

            // What spins for a record with no disc picture (BlankDisc), as on iPhone.
            BasicText(stringResource(R.string.Settings_DiscStandIn), style = display(13f).copy(color = Ink.white(0xB3)))
            val standIns = DiscStandIn.entries.map { named(it) }
            ClosedMenu(named(discStandIn), dimmed = false) { anchor ->
                onAsk(MenuAsk(anchor, standIns, discStandIn.ordinal) { onDiscStandIn(DiscStandIn.entries[it]) })
            }
        }
    }

    /** The colour of the app's glass: the profile badge's colours as bubbles, no colour first ([AppColour]). */
    @Composable
    fun Colour() {
        Card(Glyph.palette, stringResource(R.string.Settings_Colour)) { AppColourBubbles() }
    }

    /** The player's look: whether the bars beside the cover move with the music. */
    @Composable
    fun Player(bars: Boolean, onBars: (Boolean) -> Unit) {
        Card(Glyph.play, stringResource(R.string.Playlists_NowPlaying)) {
            Toggle(stringResource(R.string.Settings_PlayerBars), bars, onBars)
        }
    }

    // ── general ──

    /** The opening sound, on or off. */
    @Composable
    fun Opening(on: Boolean, onChange: (Boolean) -> Unit) {
        Card(Glyph.speaker, stringResource(R.string.Settings_Sound)) {
            Toggle(stringResource(R.string.Settings_StartupSound), on, onChange)
        }
    }

    /**
     * The language of the Wikipedia texts on the artist and record pages: the phone's own first, then the languages
     * offered ([ArticleLanguages]), each named in itself, as a language picker names them.
     */
    @Composable
    fun Descriptions(code: String, onPick: (String) -> Unit) {
        val own = java.util.Locale.getDefault()
        val phone = stringResource(R.string.Settings_ArticleLanguagePhone, ArticleLanguages.nameOf(own.language))
        val codes = listOf("") + ArticleLanguages.OFFERED
        val names = codes.map { if (it.isEmpty()) phone else ArticleLanguages.nameOf(it) }
        val chosen = codes.indexOf(code).coerceAtLeast(0)
        Card(Glyph.globe, stringResource(R.string.Settings_ArticleLanguage)) {
            ClosedMenu(names[chosen], dimmed = false) { anchor -> onAsk(MenuAsk(anchor, names, chosen) { onPick(codes[it]) }) }
        }
    }

    /**
     * The app's language: the phone's own first, then the five the app speaks, each named in itself ([AppLanguages]).
     * A new choice shows at once, on this page.
     */
    @Composable
    fun AppLanguage() {
        val context = androidx.compose.ui.platform.LocalContext.current
        // Asked again when the configuration changes: the choice can also be made in the phone's own settings.
        val chosen = remember(androidx.compose.ui.platform.LocalConfiguration.current) { AppLanguages.chosen(context) }
        val codes = listOf("") + AppLanguages.OFFERED
        val names = codes.map { if (it.isEmpty()) stringResource(R.string.Settings_AppLanguagePhone) else AppLanguages.name(it) }
        val at = codes.indexOf(chosen).coerceAtLeast(0)
        Card(Glyph.globe, stringResource(R.string.Settings_AppLanguage)) {
            ClosedMenu(names[at], dimmed = false) { anchor -> onAsk(MenuAsk(anchor, names, at) { AppLanguages.choose(context, codes[it]) }) }
        }
    }

    /** The key that shows the welcome and the first-run hints again. Once pressed it says so, and rests. */
    @Composable
    fun Hints(onReplay: () -> Unit) {
        var back by remember { mutableStateOf(false) }
        Card(Glyph.hint, stringResource(R.string.Settings_Hints)) {
            Key(stringResource(if (back) R.string.Settings_HintsBack else R.string.Settings_ShowHintsAgain), enabled = !back) {
                back = true
                onReplay()
            }
        }
    }

    /** A line of words with a switch at its end; the whole line turns it. */
    @Composable
    private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
        val haptics = LocalHapticFeedback.current
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Switch) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onChange(!on)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(label, Modifier.weight(1f), style = display(13f).copy(color = Ink.white(0xB3)))
            Spacer(Modifier.width(10.dp))
            Switch(on)
        }
    }

    @Composable
    private fun named(choice: DiscStandIn): String = stringResource(
        when (choice) {
            DiscStandIn.Cd -> R.string.Settings_DiscStandInCd
            DiscStandIn.Vinyl -> R.string.Settings_DiscStandInVinyl
        },
    )

    @Composable
    private fun Tile(choice: Ground, picked: Boolean, locked: Boolean, onClick: () -> Unit) {
        val shape = RoundedCornerShape(8.dp)
        val name = stringResource(choice.nameKey)
        Column(
            Modifier
                .width(TILE_WIDE)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = name },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(TILE_WIDE, TILE_TALL)
                    .clip(shape)
                    .background(Color.Black.copy(alpha = 0x22 / 255f)),
                contentAlignment = Alignment.Center,
            ) {
                GroundTile(choice, TILE_WIDE, TILE_TALL)
                if (picked) {
                    // On the picture and not beside the name: the tile is the thing that was chosen.
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                    BasicText(Glyph.check, style = icon(14f).copy(color = Color.White))
                } else if (locked) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(5.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(4.dp),
                    ) {
                        BasicText(Glyph.heart, style = icon(11f).copy(color = Ink.white(0xCC)))
                    }
                }
                // The chosen tile has a white edge, the others the card's own gradient edge. Over the picture, so it
                // is not covered by it.
                Box(Modifier.fillMaxSize().then(if (picked) Modifier.border(1.5.dp, Ink.white(0xDD), shape) else Modifier.border(1.dp, Ink.cardBorder, shape)))
            }
            BasicText(
                name,
                maxLines = 1,
                style = system(11f, if (picked) FontWeight.SemiBold else FontWeight.Normal).copy(color = if (picked) Color.White else Color.White.copy(alpha = 0.7f)),
            )
        }
    }

    // ── how much of a track comes down ──

    /**
     * Two settings for listening, not one: the original at home and less on a train. A single setting would often be
     * wrong. A third is for downloads, on the Downloads card, because it is a choice about space on the phone and not
     * about the network.
     */
    @Composable
    fun Playback(onWifi: Int, onCellular: Int, rates: Rates, savesData: Boolean, quota: CloudQuota?, karaoke: Boolean, onKaraoke: (Boolean) -> Unit) {
        Card(Glyph.speaker, stringResource(R.string.Rpm_Profile_Playback)) {
            Rate(stringResource(R.string.Rpm_Profile_QualityWifi), onWifi, rates.onWifi)
            Rate(stringResource(R.string.Rpm_Profile_QualityCellular), onCellular, rates.onCellular)
            // Next to the settings, and only while it applies: Data Saver overrides both menus.
            if (savesData) BasicText(stringResource(R.string.Rpm_Profile_QualityLowData), style = display(11f).copy(color = Ink.white(0x88)))
            Capped()
            KaraokeSwitch(quota, karaoke, onKaraoke)
        }
    }

    /**
     * Why some rates are greyed out, and the way to become a supporter. It goes directly under the rate menus, on
     * Playback and on Downloads, because a greyed-out control needs its reason next to it.
     */
    @Composable
    private fun Capped() {
        if (!offer.capped) return
        BasicText(stringResource(R.string.Rpm_Profile_QualityCapped), style = display(11f).copy(color = Ink.white(0x88)))
        Key(stringResource(R.string.Cloud_BecomeSupporter), onClick = onSupport)
    }

    /**
     * Karaoke lyrics ([com.rpm.player.phone.PhoneSettings.karaoke]). Shown to everyone and switchable only by a
     * supporter, with the reason under it for everyone else, as the quality menus above are. Hidden until the plan is
     * read, so a supporter does not see it greyed out for a moment. Off for anyone else, whatever is stored: a switch
     * that shows on and does nothing says the wrong thing.
     */
    @Composable
    private fun KaraokeSwitch(quota: CloudQuota?, on: Boolean, onChange: (Boolean) -> Unit) {
        quota ?: return
        val haptics = LocalHapticFeedback.current
        val allowed = quota.supporter
        Row(
            Modifier
                .fillMaxWidth()
                .alpha(if (allowed) 1f else 0.5f)
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = allowed, role = Role.Switch) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onChange(!on)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(stringResource(R.string.Tv_SettingsKaraoke), Modifier.weight(1f), style = display(13f).copy(color = Ink.white(0xB3)))
            Spacer(Modifier.width(10.dp))
            Switch(allowed && on)
        }
        if (!allowed) BasicText(stringResource(R.string.Supporters_Karaoke), style = display(11f).copy(color = Ink.white(0x88)))
    }

    @Composable
    private fun Rate(label: String, chosen: Int, onPick: (Int) -> Unit) {
        val names = offer.rungs.map { named(it) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(label, style = display(13f).copy(color = Ink.white(0xB3)))
            // A menu and not a segmented control: six steps across a phone would leave about four letters each. Shown
            // but disabled for a free account: the one step it has is still worth seeing.
            ClosedMenu(named(offer.settles(chosen)), dimmed = offer.capped) { anchor ->
                onAsk(MenuAsk(anchor, names, offer.rungs.indexOf(offer.settles(chosen))) { onPick(offer.rungs[it]) })
            }
        }
    }

    @Composable
    private fun named(rung: Int): String =
        if (rung == AudioLadder.ORIGINAL) stringResource(R.string.Rpm_Playback_Original) else stringResource(R.string.Rpm_Profile_QualityKbps, rung)

    // ── how loud ──

    /**
     * Volume levelling, with the desktop's three choices in its order: off, match albums, match tracks. A card of its own,
     * because Playback is about how much of a track comes down and this is about how it sounds. "Level classical
     * recordings too" reads the classical mark the desktop syncs.
     */
    @Composable
    fun Loudness(levelling: LevelMode, levelsClassical: Boolean, onLevelling: (LevelMode) -> Unit, onLevelsClassical: (Boolean) -> Unit) {
        val names = LevelMode.entries.map { named(it) }
        Card(Glyph.levelling, stringResource(R.string.Settings_Normalize)) {
            ClosedMenu(named(levelling), dimmed = false) { anchor ->
                onAsk(MenuAsk(anchor, names, levelling.ordinal) { onLevelling(LevelMode.entries[it]) })
            }
            // Hidden while levelling is off, as on the desktop: there is nothing to level.
            if (levelling != LevelMode.Off) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { onLevelsClassical(!levelsClassical) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(stringResource(R.string.Settings_NormalizeClassical), Modifier.weight(1f), style = display(13f).copy(color = Ink.white(0xB3)))
                    Spacer(Modifier.width(10.dp))
                    Switch(levelsClassical)
                }
            }
        }
    }

    @Composable
    private fun named(mode: LevelMode): String = stringResource(
        when (mode) {
            LevelMode.Off -> R.string.Settings_NormalizeOff
            LevelMode.Album -> R.string.Settings_NormalizeAlbum
            LevelMode.Track -> R.string.Settings_NormalizeTrack
        },
    )

    // ── the equaliser ──

    /**
     * The equaliser, laid out as the desktop's Sound settings lay it out (the iPhone app has none): whose curve it is,
     * the switch, the presets and Flat, then the pre-amp and the ten bands as faders from -12 to +12 dB in steps of 1.
     */
    @Composable
    fun Eq(eq: EqControls) {
        val curve = eq.curve
        val names = EqPreset.ALL.map { stringResource(it.name) }
        val preset = curve.preset()
        Card(Glyph.equalizer, stringResource(R.string.Settings_Eq)) {
            // Whose curve this is. The faders move by themselves when the music moves to other speakers. Without this
            // line, that would look like the app forgetting the settings.
            BasicText(
                eq.output.label?.let { stringResource(R.string.Settings_EqFor, it) } ?: stringResource(R.string.Settings_EqForCast),
                style = display(13f).copy(color = Ink.white(0xCC)),
            )
            Toggle(stringResource(R.string.Settings_EqOn), curve.on) { eq.onCurve(curve.copy(on = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(KEYS_APART), verticalAlignment = Alignment.CenterVertically) {
                ClosedMenu(preset?.let { stringResource(it.name) } ?: stringResource(R.string.Settings_EqPreset), dimmed = false, Modifier.weight(1f)) { anchor ->
                    onAsk(
                        MenuAsk(anchor, names, EqPreset.ALL.indexOf(preset)) { at ->
                            // A preset is a curve worth hearing, so choosing one turns the equaliser on, as on the
                            // desktop. Picking Bass boost and hearing no change would look like a broken control.
                            eq.onCurve(curve.withPreset(EqPreset.ALL[at]).copy(on = true))
                        },
                    )
                }
                Key(stringResource(R.string.Settings_EqReset), stretches = false) { eq.onCurve(curve.flattened()) }
            }
            Faders(curve, eq.onCurve)
        }
    }

    /**
     * The pre-amp, then the ten bands, one column each with its label under it, as on the desktop. Dimmed while the
     * equaliser is off: the values are kept but not heard. They can still be moved, as on the desktop.
     */
    @Composable
    private fun Faders(curve: EqCurve, onCurve: (EqCurve) -> Unit) {
        val labels = listOf(stringResource(R.string.Settings_EqPre)) + Equalizer.BANDS.map { Equalizer.bandLabel(it) }
        val values = listOf(curve.preamp) + curve.gains
        Row(Modifier.fillMaxWidth().alpha(if (curve.on) 1f else 0.55f)) {
            labels.forEachIndexed { at, label ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Fader(values[at], label) { db -> onCurve(if (at == 0) curve.withPreamp(db) else curve.withBand(at - 1, db)) }
                    BasicText(label, maxLines = 1, style = display(9f).copy(color = Ink.white(0x99)))
                }
            }
        }
    }

    /**
     * One upright fader, from +12 dB at the top to -12 at the bottom, with a line from 0 dB to the knob. A drag or a
     * tap sets the value under the finger, in whole dB. A drag that starts on a fader moves the fader and not the page.
     */
    @Composable
    private fun Fader(db: Double, label: String, onChange: (Double) -> Unit) {
        val haptics = LocalHapticFeedback.current
        val change by rememberUpdatedState(onChange)
        val current by rememberUpdatedState(db)
        // The value sent last in this touch, so one step is sent once even before the page has drawn it.
        val last = remember { doubleArrayOf(Double.NaN) }
        val max = Equalizer.MAX_GAIN_DB
        val knob = 7.dp
        val track = Ink.white(0x26)
        val lit = Ink.white(0xCC)

        fun send(value: Double) {
            val v = value.coerceIn(-max, max)
            if (v == current || v == last[0]) return
            last[0] = v
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            change(v)
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(FADER_TALL)
                .pointerInput(Unit) {
                    val inset = knob.toPx()
                    detectTapGestures {
                        last[0] = Double.NaN
                        send(faderValue(it.y, inset, size.height.toFloat(), max))
                    }
                }
                .pointerInput(Unit) {
                    val inset = knob.toPx()
                    detectVerticalDragGestures(
                        onDragStart = {
                            last[0] = Double.NaN
                            send(faderValue(it.y, inset, size.height.toFloat(), max))
                        },
                        onDragEnd = { last[0] = Double.NaN },
                        onDragCancel = { last[0] = Double.NaN },
                    ) { pointer, _ ->
                        pointer.consume()
                        send(faderValue(pointer.position.y, inset, size.height.toFloat(), max))
                    }
                }
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(db.toFloat(), -max.toFloat()..max.toFloat(), steps = (2 * max).toInt() - 1)
                    setProgress { target ->
                        last[0] = Double.NaN
                        send(Math.round(target).toDouble())
                        true
                    }
                },
        ) {
            val inset = knob.toPx()
            val x = size.width / 2
            val span = size.height - 2 * inset
            fun y(value: Double): Float = inset + ((max - value) / (2 * max)).toFloat() * span
            val stroke = 2.dp.toPx()
            drawLine(track, Offset(x, inset), Offset(x, size.height - inset), stroke, StrokeCap.Round)
            // The 0 dB mark, a short line across.
            val tick = 5.dp.toPx()
            drawLine(track, Offset(x - tick, y(0.0)), Offset(x + tick, y(0.0)), stroke / 2)
            // The raised part is lit in the glass colour, as a switch is ([controlColour]).
            drawLine(controlColour(lit), Offset(x, y(0.0)), Offset(x, y(db)), stroke, StrokeCap.Round)
            drawCircle(Color.White, radius = knob.toPx(), center = Offset(x, y(db)))
        }
    }

    /** The value in whole dB at height [y] of a fader [height] tall whose ends are [inset] in from its edges. */
    private fun faderValue(y: Float, inset: Float, height: Float, max: Double): Double {
        val share = ((y - inset) / (height - 2 * inset)).coerceIn(0f, 1f)
        return Math.round(max - share * 2 * max).toDouble()
    }

    // ── what is on the phone itself ──

    @Composable
    fun Device(cached: Long, onClearCache: () -> Unit) {
        Card(Glyph.phone, stringResource(R.string.Rpm_Playback_FromPhone)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(stringResource(R.string.Rpm_Profile_ArtCache), style = display(13f).copy(color = Ink.white(0xB3)))
                    BasicText(bytes(cached), style = display(17f).copy(color = Color.White))
                }
                Key(stringResource(R.string.Catalog_FanartClear), stretches = false, onClick = onClearCache)
            }
        }
    }

    // ── about ──

    @Composable
    fun About(version: String, onCredits: () -> Unit, crashReports: Boolean, onCrashReports: (Boolean) -> Unit) {
        Card(Glyph.about, stringResource(R.string.Rpm_Profile_About)) {
            BasicText(stringResource(R.string.Rpm_Profile_Tagline), style = display(13f).copy(color = Ink.white(0xB3)))
            BasicText(stringResource(R.string.Rpm_Profile_Version, version), style = display(11f).copy(color = Ink.white(0x66)))
            Key(stringResource(R.string.About_Acknowledgements), onClick = onCredits)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { onCrashReports(!crashReports) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(stringResource(R.string.Settings_CrashReports), Modifier.weight(1f), style = display(13f).copy(color = Ink.white(0xB3)))
                Spacer(Modifier.width(10.dp))
                Switch(crashReports)
            }
        }
    }

    // ── the words ──

    @Composable
    private fun said(quota: CloudQuota): String {
        if (quota.unlimited || quota.quotaBytes <= 0) return stringResource(R.string.Cloud_UsageUnlimited, bytes(quota.usedBytes))
        val full = quota.usedBytes.toDouble() / quota.quotaBytes
        val key = when {
            full > 0.95 -> R.string.Cloud_UsageFull
            full > 0.8 -> R.string.Cloud_UsageAlmostFull
            else -> R.string.Cloud_Usage
        }
        return stringResource(key, bytes(quota.usedBytes), bytes(quota.quotaBytes))
    }
}

// ── the shape of every card ──

/**
 * A card: a glyph and a kicker, then the content, on the flat section fill (`rpmSection` on iPhone: white at 7%, a
 * 22 dp corner, and the glass colour as a light wash, [AppColour]). A plain panel, not glass, as the iPhone's grouped
 * sections are.
 */
@Composable
internal fun Card(glyph: String, kicker: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Ink.white(0x12))
            .glassTint(RectangleShape, AppColour.ON_SECTION)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The kicker's own faint white and a small size, so the glyph is a quiet mark and not an icon.
            BasicText(glyph, style = icon(12f).copy(color = Ink.white(0x66)))
            BasicText(kicker, style = kicker())
        }
        content()
    }
}

/** A key on this screen: smaller than the standard key, because none of them is a screen's main action. */
@Composable
private fun Key(title: String, modifier: Modifier = Modifier, stretches: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    GlassButton(onClick = onClick, modifier = modifier, enabled = enabled, stretches = stretches, height = KEY_HEIGHT, radius = 9.dp, inset = 16.dp, size = 13f) { style ->
        BasicText(title, maxLines = 1, style = style)
    }
}

/**
 * How a menu on this screen looks while it is closed: the current choice in a dark well, with the up-down mark that
 * shows it opens. [onOpen] is told where the well is on the page, so the menu opens over it.
 */
@Composable
private fun ClosedMenu(text: String, dimmed: Boolean, modifier: Modifier = Modifier, onOpen: (Rect) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val ink = if (dimmed) Ink.white(0x59) else Ink.white(0xF0)
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .clip(shape)
            .background(Ink.white(0x14))
            .border(1.dp, Ink.white(0x1A), shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = !dimmed, role = Role.DropdownList) { onOpen(bounds) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text, Modifier.weight(1f), maxLines = 1, style = display(15f).copy(color = ink))
        BasicText(Glyph.sort, style = icon(11f).copy(color = ink))
    }
}

/**
 * An iOS switch: a 51 by 31 capsule with a white knob, lit in the tint the iPhone gives it: the glass colour, or white at
 * 0x88 without one (`rpmControlTint`, [controlColour]).
 */
@Composable
internal fun Switch(on: Boolean) {
    val knob by animateDpAsState(if (on) 22.dp else 2.dp, tween(180), label = "switch")
    Box(
        Modifier
            .size(51.dp, 31.dp)
            .clip(CircleShape)
            .drawBehind { drawRect(if (on) controlColour(Ink.white(0x88)) else Color(0x52787880)) },
    ) {
        Box(Modifier.offset(x = knob, y = 2.dp).size(27.dp).clip(CircleShape).background(Color.White))
    }
}

// ── the menu ──

/** A menu to open: where its closed well is, the choices' names, which is chosen, and what a pick does. */
private class MenuAsk(val anchor: Rect, val choices: List<String>, val chosen: Int, val onPick: (Int) -> Unit)

/**
 * The open menu, as glass over the page at the well that opened it: under it where there is room above the chrome,
 * over it where there is not. It lists the choices and ticks the one in use. A tap anywhere else closes it.
 */
@Composable
private fun Menu(backdrop: Backdrop, ask: MenuAsk, bottomRoom: Dp, onClose: () -> Unit) {
    val density = LocalDensity.current
    val gap = with(density) { 6.dp.roundToPx() }
    val foot = with(density) { (bottomRoom + 8.dp).roundToPx() }
    val ceiling = with(density) { WindowInsets.statusBars.asPaddingValues(this).calculateTopPadding().roundToPx() + 8.dp.roundToPx() }
    Layout(
        content = {
            GlassCard(backdrop, radius = 14.dp) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    ask.choices.forEachIndexed { at, label ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) {
                                    ask.onPick(at)
                                    onClose()
                                }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(label, Modifier.weight(1f), style = system(15f).copy(color = Color.White))
                            if (at == ask.chosen) BasicText(Glyph.check, style = icon(13f).copy(color = Color.White))
                        }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
    ) { measurables, constraints ->
        val width = ask.anchor.width.toInt().coerceAtLeast(0)
        val card = measurables.first().measure(Constraints.fixedWidth(width).copy(maxHeight = constraints.maxHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val below = ask.anchor.bottom.toInt() + gap
            val above = ask.anchor.top.toInt() - gap - card.height
            val floor = constraints.maxHeight - foot - card.height
            // Under the well where it fits, over it where that fits, and otherwise as near the well as the room between
            // the status bar and the chrome allows, covering it, as an iPhone menu does.
            val y = when {
                below <= floor -> below
                above >= ceiling -> above
                else -> above.coerceIn(ceiling, maxOf(ceiling, floor))
            }
            card.place(ask.anchor.left.toInt(), y)
        }
    }
}

// ── sizes ──

/**
 * A size in bytes as the iPhone's `ByteCountFormatter` gives it with a binary count: kilobytes below a million bytes,
 * otherwise megabytes with one decimal or gigabytes with two, in steps of 1024.
 */
internal fun bytes(count: Long): String {
    val kilo = 1024.0
    return when {
        count < 1_000_000 -> "${Math.round(count / kilo)} KB"
        count < kilo * kilo * kilo -> DecimalFormat("0.#").format(count / (kilo * kilo)) + " MB"
        else -> DecimalFormat("0.##").format(count / (kilo * kilo * kilo)) + " GB"
    }
}

/**
 * The scroll goes under the fixed header and dissolves there, as `DissolvingScroll` with `scrollsUnder` on iPhone:
 * nothing of it shows above the header's foot ([top]), and below that it fades in over [depth], once it has scrolled
 * that far. [scrolled] is read while drawing, so a scroll redraws without recomposing.
 */
private fun Modifier.dissolvesUnder(top: Dp, depth: Dp, scrolled: () -> Float): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val moved = scrolled()
        if (moved <= 0f) return@drawWithContent
        val foot = top.toPx()
        drawRect(Color.Black, size = Size(size.width, foot), blendMode = BlendMode.DstOut)
        val band = minOf(moved, depth.toPx())
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = foot, endY = foot + band),
            topLeft = Offset(0f, foot),
            size = Size(size.width, band),
            blendMode = BlendMode.DstIn,
        )
    }

/** The string for this account's standing: a closed beta tester, a paying supporter, or free. */
internal fun planString(quota: CloudQuota, supporter: Int, tester: Int, free: Int): Int = when {
    quota.tester -> tester
    quota.supporter -> supporter
    else -> free
}
