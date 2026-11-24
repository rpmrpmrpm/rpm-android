package com.rpm.player.phone

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import com.rpm.player.core.cast.CastState
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.compose.runtime.mutableIntStateOf
import com.rpm.player.core.playback.Sitting
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.rpm.player.core.playback.PlaybackService
import com.rpm.player.core.services
import com.rpm.player.phone.shell.Root
import com.rpm.player.phone.ui.Ground
import com.rpm.player.phone.ui.LocalGround
import com.rpm.player.phone.ui.LocalNightMode
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.rpm.player.core.power.LocalMotionPlan
import com.rpm.player.core.power.MotionPlan
import com.rpm.player.core.power.PowerSaving

class MainActivity : ComponentActivity() {
    private val app get() = application as PhoneApplication

    /** The playback service's controller while the activity is started, null until it has connected. The
     * TV app works the same way: a tap before it connects plays nothing. */
    private val controller = mutableStateOf<MediaController?>(null)
    private var connecting: ListenableFuture<MediaController>? = null

    /** Where the session says this sitting begins in the queue ([Sitting]), from its extras: read when the controller
     * connects and each time the session publishes them again. */
    private val sittingFrom = mutableIntStateOf(0)

    /** The app's language on Android 9 to 12, which have no per-app language of their own ([AppLanguages]). */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        com.rpm.player.phone.settings.AppLanguages.overrideFor(newBase)?.let(::applyOverrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge to edge with light icons on clear bars: the app is dark throughout, as the iPhone app is, and
        // every screen pads itself for the bars (targetSdk 36 allows nothing else).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // The volume keys move the music's volume whenever the app is in front, playing or not, as an iPhone's do in a
        // music app. Without this a paused app's keys move the ringer.
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent {
            // The plan for the power state, followed here once for the whole app, so turning battery saver on or off
            // reaches every screen at once.
            val saving by PowerSaving.on.collectAsState()
            // The backdrop every page's ground draws, and night mode, both chosen in the profile. Read here, once for
            // the whole app, so a choice shows on every page at once.
            CompositionLocalProvider(
                LocalMotionPlan provides MotionPlan(saving),
                LocalGround provides Ground.standing(app.settings.backdrop, app.settings.isGold),
                LocalNightMode provides app.settings.nightMode,
                com.rpm.player.phone.ui.LocalDiscStandIn provides app.settings.discStandIn,
            ) {
                Root(services, app.settings, app.signIn, controller.value, sittingFrom.intValue)
            }
        }
        // The window's own background is only for the moment before Compose's first frame. Every screen paints its
        // own ground over the whole window, so after that it is one more full-screen fill on every frame that
        // nobody sees.
        window.decorView.post { window.setBackgroundDrawable(null) }
        if (savedInstanceState == null) takeSignInLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeSignInLink(intent)
    }

    /** The browser's way back after a sign-in with Apple or Google, `rpm://auth?sessionId=…&code=…` (the intent
     * filter in the manifest). [SignIn.returned] checks that it is for the sign-in that is waiting. */
    private fun takeSignInLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW && data.scheme == "rpm" && data.host == "auth") {
            app.signIn.returned(data.toString())
        }
    }

    override fun onStart() {
        super.onStart()
        // Back in the front: the plays and station listens counted here go up, as the iPhone's `syncPlays` sends them
        // each time the app becomes active. Other devices' counts are read with the library.
        services.trackPlaysSync.pass()
        services.radio.listensSync.pass()
        // Home built again for this hour and what was played while the app was away, if either moved on.
        services.library.freshenHome()
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .setListener(
                object : MediaController.Listener {
                    override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                        // Only the connected controller's news: one from an earlier start says nothing about now.
                        if (controller === this@MainActivity.controller.value) sittingFrom.intValue = extras.getInt(Sitting.EXTRA_FROM)
                    }
                },
            )
            .buildAsync()
        connecting = future
        future.addListener(
            {
                // A future from an earlier start that finishes late is not this start's controller.
                if (connecting === future && !future.isCancelled) {
                    controller.value = runCatching { future.get() }.getOrNull()
                    sittingFrom.intValue = controller.value?.sessionExtras?.getInt(Sitting.EXTRA_FROM) ?: 0
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    override fun onStop() {
        controller.value = null
        connecting?.let(MediaController::releaseFuture)
        connecting = null
        super.onStop()
    }

    /**
     * The phone's volume keys move the speaker while the sound is going to one: the iPhone's `VolumeKeys`. No sound comes
     * out of the phone then, so its own volume does nothing, and the keys are the one control that works without looking.
     * Each press is one of the speaker's steps; the phone's own level is left where it is.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val up = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            else -> return super.dispatchKeyEvent(event)
        }
        val casting = services.casting
        if (casting.state.value !is CastState.On || casting.roomVolume.value == null) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) casting.stepRoomVolume(up)
        return true
    }

    override fun onResume() {
        super.onResume()
        // Back from the browser a sign-in opened, closed or finished: see SignIn.backFromBrowser. A finished
        // sign-in's link arrives in onNewIntent, before this.
        app.signIn.backFromBrowser()
        // A renewal, a refund or a purchase on another device can happen while the app is away.
        if (app.services.auth.session.value != null) app.supporters.sync()
    }

    companion object {
        /** Set on the intent the media session opens: show the player (`RpmApplication.sessionOpening`). */
        const val EXTRA_SHOW_PLAYER = "rpm.showPlayer"
    }
}
