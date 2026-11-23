package com.rpm.player.core.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether the phone saves power (battery saver), for the whole app: the one place this is known. The screens follow it
 * through the plan it gives ([MotionPlan], [LocalMotionPlan]); the rooms' painter ([com.rpm.player.core.room.RoomPainter])
 * follows it on its own thread.
 *
 * It is false until [watch] is called. An app calls [watch] once as it starts.
 */
object PowerSaving {
    private val state = MutableStateFlow(false)
    private val watching = AtomicBoolean(false)

    /** Whether the phone saves power now. It changes when battery saver is turned on or off. */
    val on: StateFlow<Boolean> = state.asStateFlow()

    /** The same as `on.value`. */
    val isOn: Boolean get() = state.value

    /**
     * Reads battery saver now and follows the system's broadcast whenever it is turned on or off, for the life of the
     * process. Calling it again does nothing.
     */
    fun watch(context: Context) {
        if (!watching.compareAndSet(false, true)) return
        val app = context.applicationContext
        val power = app.getSystemService(PowerManager::class.java)
        state.value = power?.isPowerSaveMode == true
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    state.value = power?.isPowerSaveMode == true
                }
            },
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
