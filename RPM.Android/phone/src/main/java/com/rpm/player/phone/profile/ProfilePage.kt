package com.rpm.player.phone.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CloudQuota
import com.rpm.player.core.playback.AudioLadder
import com.rpm.player.core.playback.LevelMode
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.OverChrome
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.GlassCard
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.PageTitle
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.design.system
import com.rpm.player.phone.library.FOOT
import com.rpm.player.phone.ui.Ground
import com.rpm.player.phone.ui.GroundTile
import com.rpm.player.phone.ui.VinylGround
import java.text.DecimalFormat

/** The page's side margin, the iPhone's `RPMLayout.margin`. */
private val MARGIN = 20.dp

/** How deep the fade is under the header, once the page has scrolled that far. */
private val DISSOLVE = 30.dp

/** The space between two keys on one row. */
private val KEYS_APART = 10.dp

/** The height of a key on this screen. Smaller than the app's standard key: none of them is a screen's main action. */
private val KEY_HEIGHT = 34.dp

/** A backdrop tile has the shape of the phone screen, small enough that five and part of a sixth fit across. */
private val TILE_WIDE = 62.dp
private val TILE_TALL = 116.dp

/**
 * The account, and all the app settings that are choices and not records: the iPhone's `ProfilePage`. It is a tab
 * and not a gear key in a corner, because it holds more than settings: how much cloud storage is used, the plan of the
 * account, and the way to sign out. Every card has the same shape: a glyph and a kicker, then the current value, then
 * the actions.
 *
 * @param reachSavesData whether Data Saver holds streaming down. The rate menus then show a note, because a menu that
 * says 320 while the stream is 128, with no reason on the screen, looks like a setting that does nothing.
 * @param downloaded how many albums are downloaded to this phone, and what they take.
 * @param onDownloads opens the list of downloaded albums. Null while that page does not exist; the key is then dim.
 * @param onDeleteAccount deletes the account and signs out. Returns null when done, and a sentence when it failed.
 * @param bottomRoom how much of the bottom of the screen the chrome covers.
 */
@Composable
fun ProfilePage(
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
    downloaded: Pair<Int, Long>,
    onDownloads: (() -> Unit)?,
    ground: Ground,
    isGold: Boolean,
    onGround: (Ground) -> Unit,
    version: String,
    onClearCache: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: suspend () -> String?,
    onSupport: () -> Unit,
    crashReports: Boolean,
    onCrashReports: (Boolean) -> Unit,
    onFeedback: (() -> Unit)?,
    bottomRoom: Dp,
) {
    val density = LocalDensity.current
    val page = rememberBackdrop()
    val scroll = rememberScrollState()
    val statusBar = WindowInsets.statusBars.asPaddingValues(density).calculateTopPadding()
    var heading by remember { mutableStateOf(0.dp) }
    var deletingAccount by remember { mutableStateOf(false) }
    var credits by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<MenuAsk?>(null) }
    BackHandler(enabled = menu != null) { menu = null }

    val cards = Cards(offer, onSupport, onAsk = { menu = it })

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(page)) {
            VinylGround()
            Column(
                Modifier
                    .fillMaxSize()
                    .dissolvesUnder(statusBar + heading, DISSOLVE) { scroll.value.toFloat() }
                    .verticalScroll(scroll)
                    .padding(horizontal = MARGIN)
                    .padding(top = statusBar + heading + 4.dp, bottom = FOOT + bottomRoom),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                cards.Account(name, email, onSignOut, onDelete = { deletingAccount = true })
                cards.Storage(quota)
                cards.Downloads(downloaded, onDownloads)
                if (quota != null) cards.Plan(quota)
                cards.Backdrops(ground, isGold, onGround)
                cards.Playback(onWifi, onCellular, onDownload, onRates, reachSavesData)
                cards.Loudness(levelling, levelsClassical, onLevelling, onLevelsClassical)
                cards.Device(cached, onClearCache)
                cards.About(version, onCredits = { credits = true }, crashReports, onCrashReports, onFeedback)
            }
            // The header stays over the scroll, as on iPhone: it is the name of the page, and nobody should have to
            // scroll back to see it.
            Column(
                Modifier
                    .statusBarsPadding()
                    .padding(start = MARGIN, end = MARGIN, bottom = 8.dp)
                    .onSizeChanged { heading = with(density) { it.height.toDp() } + 8.dp },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PageTitle(stringResource(R.string.Rpm_Tab_Profile))
                BasicText(stringResource(R.string.Rpm_Profile_Subtitle), style = kicker())
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

/** The three rate settings' setters. */
class Rates(val onWifi: (Int) -> Unit, val onCellular: (Int) -> Unit, val onDownload: (Int) -> Unit)

/** The cards of the page. They share the offer and the way to open a menu. */
private class Cards(val offer: AudioLadder.RateOffer, val onSupport: () -> Unit, val onAsk: (MenuAsk) -> Unit) {

    // ── account ──

    @Composable
    fun Account(name: String, email: String, onSignOut: () -> Unit, onDelete: () -> Unit) {
        Card(Glyph.profile, stringResource(R.string.Rpm_Profile_Account)) {
            // No supporter badge here. It is shown in the Supporter card instead.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(name.ifEmpty { email }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(17f).copy(color = Color.White))
                if (name.isNotEmpty()) {
                    BasicText(email, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(13f).copy(color = Ink.white(0x88)))
                }
            }
            BasicText(stringResource(R.string.Rpm_Profile_SignOutDetail), style = display(11f).copy(color = Ink.white(0x66)))
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

    /** The albums kept on the phone for listening offline, and the way to the list of them. */
    @Composable
    fun Downloads(downloaded: Pair<Int, Long>, onDownloads: (() -> Unit)?) {
        val (albums, size) = downloaded
        Card(Glyph.download, stringResource(R.string.Rpm_Downloads_Title)) {
            BasicText(
                when (albums) {
                    0 -> stringResource(R.string.Rpm_Downloads_None)
                    1 -> stringResource(R.string.Rpm_Downloads_SummaryOne, bytes(size))
                    else -> stringResource(R.string.Rpm_Downloads_Summary, albums, bytes(size))
                },
                style = display(17f).copy(color = Color.White),
            )
            BasicText(stringResource(R.string.Rpm_Downloads_Detail), style = display(11f).copy(color = Ink.white(0x66)))
            Key(stringResource(R.string.Rpm_Downloads_Manage), enabled = onDownloads != null, onClick = { onDownloads?.invoke() })
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
                BasicText(
                    stringResource(
                        planString(quota, R.string.Rpm_Profile_PlanSupporterDetail, R.string.Rpm_Profile_PlanTesterDetail, R.string.Rpm_Profile_PlanFreeDetail),
                    ),
                    style = display(12f).copy(color = Ink.white(0x88)),
                )
                BasicText(
                    stringResource(
                        planString(quota, R.string.Rpm_Profile_PlanSupporterThanks, R.string.Rpm_Profile_PlanTesterThanks, R.string.Rpm_Profile_PlanFreeAppeal),
                    ),
                    Modifier.padding(top = 6.dp),
                    style = display(12f).copy(color = Ink.white(0xB3)),
                )
            }
        }
    }

    // ── the app background ──

    /** The five built-in backdrops, as a row of the real pictures. The supporter's is shown to everybody and is not
     * greyed out, so people can see it; a tap on it without support opens the page that explains it. */
    @Composable
    fun Backdrops(standing: Ground, isGold: Boolean, onGround: (Ground) -> Unit) {
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
        }
    }

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
     * wrong. A third is for downloads, which is a choice about space on the phone and not about the network.
     */
    @Composable
    fun Playback(onWifi: Int, onCellular: Int, onDownload: Int, rates: Rates, savesData: Boolean) {
        Card(Glyph.speaker, stringResource(R.string.Rpm_Profile_Playback)) {
            Rate(stringResource(R.string.Rpm_Profile_QualityWifi), onWifi, rates.onWifi)
            Rate(stringResource(R.string.Rpm_Profile_QualityCellular), onCellular, rates.onCellular)
            // Next to the settings, and only while it applies: Data Saver overrides both menus.
            if (savesData) BasicText(stringResource(R.string.Rpm_Profile_QualityLowData), style = display(11f).copy(color = Ink.white(0x88)))
            // After the Data Saver note, which is about streaming only. A download asks for this rate on any network.
            Rate(stringResource(R.string.Rpm_Profile_QualityDownload), onDownload, rates.onDownload)
            BasicText(stringResource(R.string.Rpm_Profile_QualityDownloadDetail), style = display(11f).copy(color = Ink.white(0x88)))
            // Directly under the menus, because a greyed-out control needs its reason next to it.
            if (offer.capped) {
                BasicText(stringResource(R.string.Rpm_Profile_QualityCapped), style = display(11f).copy(color = Ink.white(0x88)))
                Key(stringResource(R.string.Cloud_BecomeSupporter), onClick = onSupport)
            }
            BasicText(stringResource(R.string.Rpm_Profile_QualityDetail), style = display(11f).copy(color = Ink.white(0x66)))
        }
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
            BasicText(stringResource(R.string.Rpm_Profile_LevellingDetail), style = display(11f).copy(color = Ink.white(0x88)))
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
                BasicText(stringResource(R.string.Settings_NormalizeClassicalHint), style = display(11f).copy(color = Ink.white(0x88)))
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
            BasicText(stringResource(R.string.Rpm_Profile_ArtCacheDetail), style = display(11f).copy(color = Ink.white(0x66)))
        }
    }

    // ── about ──

    @Composable
    fun About(version: String, onCredits: () -> Unit, crashReports: Boolean, onCrashReports: (Boolean) -> Unit, onFeedback: (() -> Unit)?) {
        Card(Glyph.about, stringResource(R.string.Rpm_Profile_About)) {
            BasicText(stringResource(R.string.Rpm_Profile_Tagline), style = display(13f).copy(color = Ink.white(0xB3)))
            BasicText(stringResource(R.string.Rpm_Profile_Version, version), style = display(11f).copy(color = Ink.white(0x66)))
            Key(stringResource(R.string.About_Acknowledgements), onClick = onCredits)
            onFeedback?.let { Key(stringResource(R.string.Shell_Feedback), onClick = it) }
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
            BasicText(stringResource(R.string.Settings_CrashReportsDesc), style = display(11f).copy(color = Ink.white(0x66)))
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
 * 22 dp corner). A plain panel, not glass, as the iPhone's grouped sections are.
 */
@Composable
private fun Card(glyph: String, kicker: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Ink.white(0x12))
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
private fun ClosedMenu(text: String, dimmed: Boolean, onOpen: (Rect) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val ink = if (dimmed) Ink.white(0x59) else Ink.white(0xF0)
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Row(
        Modifier
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

/** An iOS switch: a 51 by 31 capsule with a white knob, lit in the tint the iPhone gives it (white at 0x88). */
@Composable
internal fun Switch(on: Boolean) {
    val knob by animateDpAsState(if (on) 22.dp else 2.dp, tween(180), label = "switch")
    Box(
        Modifier
            .size(51.dp, 31.dp)
            .clip(CircleShape)
            .background(if (on) Ink.white(0x88) else Color(0x52787880)),
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
