package com.rpm.player.phone.profile

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
import com.rpm.player.phone.ui.Ground
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * The profile tab, wired as the iPhone's `RootView` wires `ProfilePage` (`case .profile:`): the account from the
 * session, the plan and the storage from the quota, the settings from [PhoneSettings], and the account's actions from
 * [Account].
 *
 * @param downloaded how many albums are on the phone and what they take; the downloads page passes it.
 * @param onDownloads opens the downloads page; null until that page exists.
 * @param onFeedback opens Send feedback; null when this build does not offer it.
 */
@Composable
fun ProfileTab(
    services: Services,
    settings: PhoneSettings,
    controller: Player?,
    bottomRoom: Dp,
    downloaded: Pair<Int, Long> = 0 to 0L,
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

    // Each time the tab is shown: how much the kept covers take (it asks the cache), and the plan again if the last
    // read failed.
    LaunchedEffect(Unit) {
        services.ownFace.readAgainIfMissing()
        account.readCacheSize()
    }

    ProfilePage(
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
        version = account.version,
        onClearCache = { scope.launch { account.clearCache() } },
        onSignOut = { services.scope.launch { account.signOut(controller) } },
        // In the app's scope, not the sheet's: a deletion signs out, the gate replaces this page, and the sign-out must
        // not be cancelled with it.
        onDeleteAccount = { services.scope.async { account.deleteAccount(session?.email.orEmpty(), controller) }.await() },
        onSupport = { links.openUri(RpmSite.BECOME_SUPPORTER) },
        crashReports = crashReports,
        onCrashReports = { on ->
            services.diagnostics.sendsCrashReports = on
            crashReports = on
        },
        onFeedback = onFeedback,
        bottomRoom = bottomRoom,
    )
}
