package com.rpm.player.tv

import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import android.graphics.drawable.ColorDrawable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.snapshotFlow
import com.rpm.player.core.library.coverEdgeFor
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import com.rpm.player.core.diag.Diag
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import com.rpm.player.tv.ui.FocusVisibility
import com.rpm.player.tv.ui.LocalFocusVisibility
import com.rpm.player.tv.ui.LocalKeyboardView
import com.rpm.player.tv.ui.LocalScreensWindow
import android.view.Window
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.rpm.player.core.playback.KeyRules
import com.rpm.player.core.services
import com.rpm.player.core.playback.PlaybackService
import com.rpm.player.core.playback.meansToPlay
import com.rpm.player.core.playback.perform
import com.rpm.player.core.motion.Motion
import com.rpm.player.core.room.RoomTuning
import com.rpm.player.tv.ui.RpmApp
import com.rpm.player.tv.ui.Screensaver

class MainActivity : ComponentActivity() {

    /** The controller of the playback service, or null until it has connected. Nothing plays
     * before then. */
    private val controller = mutableStateOf<MediaController?>(null)

    /** Unlit until the first key press of this activity's life (FocusVisibility). */
    private val focusVisibility = FocusVisibility(initiallyShown = false)

    /** The screens put away after the minutes the settings name with no key ([Screensaver]). */
    private val screensaver = Screensaver()

    /** Counts the asks to show the player that reached this activity (a launch by the launcher's now playing card,
     * [fromNowPlayingCard]), so each opens the player once. */
    private val playerAsks = androidx.compose.runtime.mutableIntStateOf(0)

    /** The key whose press brought the screens back, swallowed until it is let go; null otherwise. Main thread. */
    private var wakingKey: Int? = null
    private var connecting: ListenableFuture<MediaController>? = null

    /** The screens drawn at the output's own pixels ([SharpHost]), or null where they are this window's content. */
    private var sharp: SharpHost? = null

    /** The two facts the window's background is worked out from ([showBackground]). Main thread. */
    private var firstFrameDrawn = false
    private var roomCovers = false

    /**
     * The window's background: the opening's dimmed wordmark (opening_window.xml) until the first frame,
     * there only to cover the seconds before it; then nothing while the room's picture covers the whole
     * window, where it would be a full-screen fill the GPU draws on every frame and nobody sees; black
     * otherwise, so the wordmark can never show through. Worked out from both facts whenever either
     * changes, so the order they arrive in does not matter.
     */
    private fun showBackground() {
        if (!firstFrameDrawn) return
        window.setBackgroundDrawable(if (roomCovers) null else ColorDrawable(android.graphics.Color.BLACK))
    }

    /** The interface language chosen in the set-up ([com.rpm.player.core.Region]), in force for everything this
     * activity draws; the system's when none was chosen. */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.rpm.player.core.Region.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            RoomTuning.scale = intent.getFloatExtra("rpm.roomScale", RoomTuning.scale).coerceIn(0.1f, 1f)
            RoomTuning.measure = intent.getBooleanExtra("rpm.roomMeasure", RoomTuning.measure)
            // Holds the motion level for this run, for measuring each level:
            // `--es rpm.motion TwoThirds`.
            intent.getStringExtra("rpm.motion")?.let { name ->
                Motion.entries.firstOrNull { it.name == name }?.let(services.motion::pin)
            }
            // Opens the first collection shared with this account once the list arrives, so a test can
            // reach it without the held Left the side menu needs: `--ez rpm.openShared true`.
            if (intent.getBooleanExtra("rpm.openShared", false)) {
                services.scope.launch { services.library.open(services.shared.list.first { it.isNotEmpty() }.first().shelf) }
            }
        }
        // A process the system restored keeps the ask it was started with in its saved state: not asked again.
        if (savedInstanceState == null && fromNowPlayingCard()) playerAsks.intValue += 1
        val screens: @androidx.compose.runtime.Composable (Window) -> Unit = { screensWindow ->
            CompositionLocalProvider(
                LocalFocusVisibility provides focusVisibility,
                LocalScreensWindow provides screensWindow,
                LocalKeyboardView provides window.decorView,
            ) {
                RpmApp(
                    services,
                    playerSettings,
                    controller.value,
                    screensaver,
                    playerAsks = playerAsks.intValue,
                    onExit = ::exitApp,
                    onRoomCovers = { covers ->
                        roomCovers = covers
                        showBackground()
                    },
                )
            }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                firstFrameDrawn = true
                showBackground()
            }
            // A resolution applied in the settings: the screens are made again at it. The settings outlive the
            // activity, so the new one reads the applied choice and does not see it as a change.
            LaunchedEffect(Unit) {
                snapshotFlow { playerSettings.appliedResolution }.drop(1).collect { recreate() }
            }
        }
        // The screens are drawn at the resolution chosen in the settings (Automatic: the output's own, 2160 on the
        // Shield), by SharpHost where that differs from the window's. A debuggable build can keep them in the
        // window for comparing: `--ez rpm.sharp false`.
        val keepInWindow = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && !intent.getBooleanExtra("rpm.sharp", true)
        val drawn = if (keepInWindow) playerSettings.windowHeight else playerSettings.drawnHeight()
        services.coverEdge.current = coverEdgeFor(drawn)
        sharp = if (drawn == playerSettings.windowHeight) null else SharpHost.at(this, drawn, screens)
        if (sharp == null) setContent { screens(window) }
    }

    override fun onDestroy() {
        sharp?.release()
        sharp = null
        super.onDestroy()
    }

    /** The activity is single-task, so a launch while it lives arrives here, the launcher's card's included. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (fromNowPlayingCard()) playerAsks.intValue += 1
    }

    /**
     * Whether the intent the activity was last handed was sent by the launcher's now playing card. The card opens the
     * app's launcher entry itself (`SessionOpening.LauncherEntry`), the same intent the app row sends, so it is known
     * only by its sender: the referrer the system records for the launch, read before `onNewIntent` runs. A launch from
     * the app row comes from the launcher itself and opens the app where it was.
     */
    private fun fromNowPlayingCard(): Boolean = referrer?.host == NOW_PLAYING_SERVICE

    override fun onStart() {
        super.onStart()
        // The plays counted since the last pass go up at launch and on coming back to the front, as the
        // phone's do (AppModel.syncPlays).
        services.trackPlaysSync.pass()
        // The collection read again from the cloud on the first start after Exit rpm, and on no other: the process
        // outlives the exit, and the library loads once per process. Quiet: the collection on
        // screen stays until the new one is in.
        services.library.refreshIfExited()
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .buildAsync()
        connecting = future
        future.addListener(
            {
                // A future from an earlier start that finishes late is not this start's controller.
                if (connecting === future && !future.isCancelled) controller.value = runCatching { future.get() }.getOrNull()
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    override fun onResume() {
        super.onResume()
        services.motion.attach(window)
        screensaver.resumed()
        wakingKey = null
    }

    override fun onPause() {
        services.motion.detach(window)
        wakingKey = null
        super.onPause()
    }

    /**
     * Exit rpm: the music stops and the playback service goes with the app. The service is told to pause and stop
     * itself ([PlaybackService.ACTION_EXIT]), which also takes its notification down. A stop asked from here would run
     * before the pause reached the service and leave it in the foreground. The queue is kept for the next start.
     */
    private fun exitApp() {
        services.library.exited()
        startService(android.content.Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_EXIT))
        finishAndRemoveTask()
    }

    override fun onStop() {
        controller.value = null
        connecting?.let(MediaController::releaseFuture)
        connecting = null
        super.onStop()
    }

    // In a debuggable build every key the activity receives is written to logcat under "rpm.keys",
    // so a remote can be read with `adb logcat -s rpm.keys`. The key is then handled as normal.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Diag.d(KEY_LOG_TAG, describe(event))
        }
        // A press that brings the screens back from the screensaver does nothing else, down to its release,
        // where Back acts. The volume and media keys still do their own work: a pause pressed over the room
        // pauses.
        if (event.action == KeyEvent.ACTION_DOWN && screensaver.keyDown()) wakingKey = event.keyCode
        if (event.keyCode == wakingKey) {
            if (event.action == KeyEvent.ACTION_UP) wakingKey = null
            if (event.keyCode !in WORK_THROUGH_SCREENSAVER) return true
        }
        // A held arrow's repeat that is already old when it reaches the app is dropped: the main thread was busy and
        // the repeats queued behind it, and handing each one over would move the focus on, and scroll, after the key
        // was let go. Judged by the event's own time, so a box that keeps up (the Shield) loses none of them, and the
        // held rules read the key's clock (SeekSteps, the held Left), not a count of repeats.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && event.keyCode in ARROWS &&
            SystemClock.uptimeMillis() - event.eventTime > STALE_REPEAT_MS
        ) {
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            services.motion.keyDown()
            focusVisibility.keyPressed()
        }
        // Android routes play, pause, stop, next, previous, rewind and fast-forward to the media
        // session, which handles them. MEDIA_CLOSE, the Amiko remote's Stop, is not one of the keys it
        // routes, so it is handled here, by the same rule, and only while the app is in front.
        // Back goes straight to the back handlers, on release as Android does. Passed through, Compose
        // spends the first Back clearing the focus from the focused key, so hiding the player's controls
        // and leaving the wall would each take two presses.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_CLOSE) {
            val player = controller.value
            if (player != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                KeyRules.forMediaKey(event.keyCode, player.meansToPlay)?.let(player::perform)
            }
            return true
        }
        sharp?.let { if (it.dispatchKey(event)) return true }
        return super.dispatchKeyEvent(event)
    }
}

private const val KEY_LOG_TAG = "rpm.keys"

/** The Android TV service that draws the launcher's now playing card and sends its Open. */
private const val NOW_PLAYING_SERVICE = "com.google.android.tvrecommendations"

/** The arrows, whose held repeats move the focus and scroll. */
private val ARROWS = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
)

/** How old a held arrow's repeat may be when it arrives and still be handled: about two repeats' time. Android
 * sends repeats 50 ms apart. */
private const val STALE_REPEAT_MS = 100L

/** The keys that do their own work when they also bring the screens back from the screensaver. */
private val WORK_THROUGH_SCREENSAVER = setOf(
    KeyEvent.KEYCODE_VOLUME_UP,
    KeyEvent.KEYCODE_VOLUME_DOWN,
    KeyEvent.KEYCODE_VOLUME_MUTE,
    KeyEvent.KEYCODE_MEDIA_PLAY,
    KeyEvent.KEYCODE_MEDIA_PAUSE,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    KeyEvent.KEYCODE_MEDIA_STOP,
    KeyEvent.KEYCODE_MEDIA_CLOSE,
    KeyEvent.KEYCODE_MEDIA_NEXT,
    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    KeyEvent.KEYCODE_MEDIA_REWIND,
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
)

private fun describe(event: KeyEvent): String {
    val action = when (event.action) {
        KeyEvent.ACTION_DOWN -> "down"
        KeyEvent.ACTION_UP -> "up"
        else -> "action ${event.action}"
    }
    val held = event.eventTime - event.downTime
    return "${KeyEvent.keyCodeToString(event.keyCode)} $action repeat=${event.repeatCount} " +
        "held=${held}ms scan=${event.scanCode}"
}
