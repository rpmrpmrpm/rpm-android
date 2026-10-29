package com.rpm.player.phone.profile

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.rpm.player.core.diag.Diag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.Player
import com.rpm.player.core.R
import com.rpm.player.core.Services
import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.cloud.CloudQuota
import com.rpm.player.core.library.LibraryState
import com.rpm.player.core.net.NetworkReach
import com.rpm.player.core.playback.AudioLadder
import com.rpm.player.phone.PhoneSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The account and the settings that follow from it: the part of the iPhone's `AppModel` that the profile tab shows
 * and changes. It is made with the app, before any screen, because the rate the player asks for depends on it, and the
 * playback service can start without the activity (a media key, the notification).
 */
class Account(
    private val context: Context,
    private val services: Services,
    private val settings: PhoneSettings,
    /** Tells the sign-in screen that the account was deleted, so it says so (the iPhone's `notice`). */
    private val onDeleted: () -> Unit,
) {
    /** The one place the phone asks how far it can reach. The rate and the profile's Data Saver note read it. */
    val reach = NetworkReach(context)

    /** What the account holds, from the same read as the account's face. Null until read: "not known", never "free". */
    val quota: StateFlow<CloudQuota?> = services.ownFace.quota

    /** How much space the kept covers use on the phone. Read when the profile is shown, because it asks the cache. */
    var cachedBytes by mutableLongStateOf(0L)
        private set

    /** The version of this build, for the About card: the name and the build number, as the iPhone shows them. */
    val version: String by lazy {
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        }.getOrNull()
        val build = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } ?: 0
        "${info?.versionName ?: "0"} ($build)"
    }

    /** What the account may choose between: the whole ladder for a supporter, one rung for a free account, and the
     * whole ladder while the plan is not known. */
    fun offer(quota: CloudQuota?): AudioLadder.RateOffer =
        quota?.let { AudioLadder.RateOffer.of(isSupporter = it.supporter) } ?: AudioLadder.RateOffer.UNREAD

    /** Starts the watches. Called once, as the app starts. */
    fun begin() {
        reach.begin()
        settleTheRate()
        keepTheGround()
    }

    /**
     * Tells the player what to ask for, whenever the network, the plan or either setting changes (`settleTheRate` on
     * iPhone). The player does not read the settings itself: it signs an address on a loader thread, and the value it
     * reads there is the one written here.
     */
    private fun settleTheRate() {
        val rates = snapshotFlow { settings.rateOnWifi to settings.rateOnCellular }
        services.scope.launch {
            combine(rates, quota, reach.now) { (onWifi, onCellular), quota, reach ->
                offer(quota).settles(onWifi, onCellular, reach)
            }.distinctUntilChanged().collect { kilobits ->
                services.streamRate.kilobits = kilobits
                Diag.d(TAG, "streams ask for ${if (kilobits == AudioLadder.ORIGINAL) "the original" else "$kilobits kbps"}")
            }
        }
        // The network came back with the plan still unread: ask again, as the iPhone does.
        services.scope.launch {
            reach.now.map { it.isOnline }.distinctUntilChanged().collect { online ->
                if (online) services.ownFace.readAgainIfMissing()
            }
        }
    }

    /**
     * Keeps the note of whether the account is a supporter's, for the gold backdrop (`SupporterGround`). A plan read
     * just after a sign-in, while the library is still being read and nothing of the account is on screen, is used at
     * once. Any other read only leaves a note for the next launch.
     */
    private fun keepTheGround() {
        var signedIn = services.auth.session.value != null
        var fresh = false
        services.scope.launch {
            services.auth.session.map { it?.userId }.distinctUntilChanged().collect { user ->
                fresh = user != null && !signedIn
                signedIn = user != null
            }
        }
        services.scope.launch {
            quota.collect { read ->
                read ?: return@collect
                if (fresh && services.library.state.value !is LibraryState.Ready) settings.adoptSupporter(read.supporter)
                else settings.rememberSupporter(read.supporter)
                fresh = false
            }
        }
    }

    suspend fun readCacheSize() {
        cachedBytes = withContext(Dispatchers.IO) { services.images.diskCache?.size ?: 0L }
    }

    /** Empties the kept covers on disk. Those on screen stay, from memory, as on iPhone. */
    suspend fun clearCache() {
        withContext(Dispatchers.IO) { services.images.diskCache?.clear() }
        cachedBytes = 0
    }

    /**
     * Signs out, as the iPhone's `signOut`: the music stops and the queue is forgotten, because the next person who
     * signs in on this phone is not listening to this one's; the session ends and its token is revoked
     * (`auth/revoke`); the gold backdrop comes down. The sign-in gate follows the session by itself.
     */
    suspend fun signOut(controller: Player?) {
        controller?.run {
            stop()
            clearMediaItems()
        }
        withContext(Dispatchers.IO) { services.images.diskCache?.clear() }
        services.images.memoryCache?.clear()
        cachedBytes = 0
        settings.adoptSupporter(false)
        // The downloaded files are this account's music, and the next person who signs in on this phone must not find them.
        (context.applicationContext as com.rpm.player.phone.PhoneApplication).downloads.forgetEverything()
        services.auth.signOut()
    }

    /**
     * Deletes the account: the accounts service deletes it and the cloud erases the library within two hours. On
     * success the phone signs out as the Sign out key does. Returns null when done, and a sentence for the sheet when
     * not.
     */
    suspend fun deleteAccount(confirmEmail: String, controller: Player?): String? =
        when (services.auth.deleteAccount(confirmEmail)) {
            AuthClient.Deletion.Deleted -> {
                // Before the sign-out, so the gate it shows already says why.
                onDeleted()
                signOut(controller)
                null
            }
            AuthClient.Deletion.EmailRefused -> context.getString(R.string.Account_DeleteEmailRefused)
            AuthClient.Deletion.Failed -> context.getString(R.string.Account_DeleteFailed)
        }

    private companion object {
        const val TAG = "rpm.account"
    }
}
