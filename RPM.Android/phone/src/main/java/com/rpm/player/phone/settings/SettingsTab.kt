package com.rpm.player.phone.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.rpm.player.core.Services
import com.rpm.player.phone.PhoneApplication
import com.rpm.player.phone.PhoneSettings
import com.rpm.player.phone.supporter.SupporterSheet
import com.rpm.player.phone.ui.Ground
import com.rpm.player.core.cloud.ownPage
import com.rpm.player.core.cloud.setSharesListening
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * The settings tab, wired as the iPhone's `RootView` wires `SettingsPage` (`case .settings:`): the account from the
 * session, the plan and the storage from the quota, the settings from [PhoneSettings], and the account's actions from
 * [Account].
 *
 * @param downloaded how many albums and playlists are on the phone and what they take; the downloads page passes it.
 * @param onDownloads opens the downloads page; null until that page exists.
 * @param onFeedback opens Send feedback; null when this build does not offer it.
 */
@Composable
fun SettingsTab(
    services: Services,
    settings: PhoneSettings,
    controller: Player?,
    bottomRoom: Dp,
    downloaded: com.rpm.player.phone.downloads.Downloaded = com.rpm.player.phone.downloads.Downloaded.None,
    onDownloads: (() -> Unit)? = null,
    onFeedback: (() -> Unit)?,
) {
    val account = (LocalContext.current.applicationContext as PhoneApplication).account
    val session by services.auth.session.collectAsStateWithLifecycle()
    val quota by account.quota.collectAsStateWithLifecycle()
    val reach by account.reach.now.collectAsStateWithLifecycle()
    val links = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var crashReports by remember { mutableStateOf(services.diagnostics.sendsCrashReports) }
    // Whether the profile shows what plays. Read from the reader's own profile; true until then, as the server's default.
    var sharesListening by remember { mutableStateOf(true) }
    // The subscription sheet, over the page.
    var supporting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { services.community.ownPage() }.onSuccess { sharesListening = it.sharesListening } }

    // Each time the tab is shown: how much the kept covers take (it asks the cache), and the plan again if the last
    // read failed.
    LaunchedEffect(Unit) {
        services.ownFace.readAgainIfMissing()
        account.readCacheSize()
    }

    SettingsPage(
        section = settings.settingsSection,
        onSection = settings::changeSettingsSection,
        name = session?.displayName.orEmpty(),
        email = session?.email.orEmpty(),
        quota = quota,
        offer = account.offer(quota),
        onWifi = settings.rateOnWifi,
        onCellular = settings.rateOnCellular,
        onDownload = settings.rateForDownloads,
        onRates = Rates(settings::changeRateOnWifi, settings::changeRateOnCellular, settings::changeRateForDownloads),
        levelling = settings.levelMode,
        levelsClassical = settings.levelsClassical,
        onLevelling = settings::changeLevelMode,
        onLevelsClassical = settings::changeLevelsClassical,
        reachSavesData = reach.isConstrained,
        cached = account.cachedBytes,
        downloaded = downloaded,
        onDownloads = onDownloads,
        ground = Ground.standing(settings.backdrop, settings.isGold),
        isGold = settings.isGold,
        onGround = { settings.changeBackdrop(it.id) },
        nightMode = settings.nightMode,
        onNightMode = settings::changeNightMode,
        discStandIn = settings.discStandIn,
        onDiscStandIn = settings::changeDiscStandIn,
        karaoke = settings.karaoke,
        onKaraoke = settings::changeKaraoke,
        sharesListening = sharesListening,
        onSharesListening = { on ->
            val before = sharesListening
            sharesListening = on
            scope.launch { runCatching { services.community.setSharesListening(on) }.onFailure { sharesListening = before } }
        },
        version = account.version,
        onClearCache = { scope.launch { account.clearCache() } },
        onSignOut = { services.scope.launch { account.signOut(controller) } },
        // In the app's scope, not the sheet's: a deletion signs out, the gate replaces this page, and the sign-out must
        // not be cancelled with it.
        onDeleteAccount = { services.scope.async { account.deleteAccount(session?.email.orEmpty(), controller) }.await() },
        onSupport = { supporting = true },
        onManageSubscription = { links.openUri(RpmSite.PLAY_SUBSCRIPTION) },
        crashReports = crashReports,
        onCrashReports = { on ->
            services.diagnostics.sendsCrashReports = on
            crashReports = on
        },
        onFeedback = onFeedback,
        bottomRoom = bottomRoom,
        openingSound = settings.openingSound,
        onOpeningSound = settings::changeOpeningSound,
        articleLanguage = settings.articleLanguage,
        onArticleLanguage = { code ->
            settings.changeArticleLanguage(code)
            // The pages read the descriptions through the dossiers, which ask in these languages from now on.
            services.dossiers.languages = settings.articleLanguages()
        },
        playerBars = settings.playerBars,
        onPlayerBars = settings::changePlayerBars,
        landscapePlayer = settings.landscapePlayer,
        onLandscapePlayer = settings::changeLandscapePlayer,
        onReplayHints = settings::replayWelcome,
        equalizer = settings.eqOutput?.let { EqControls(it, settings.eqCurve, settings::changeEq) },
    )

    val accountId = session?.userId
    if (supporting && accountId != null) {
        val app = LocalContext.current.applicationContext as PhoneApplication
        SupporterSheet(app.supporters, accountId, onClose = { supporting = false })
    }
}
