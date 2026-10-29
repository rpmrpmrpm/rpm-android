package com.rpm.player.phone.shell

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sqrt

/**
 * Tells a shake of the phone from walking with it: [PEAKS] readings over [THRESHOLD_G] within [WINDOW_MS], then
 * [QUIET_MS] before another shake counts. Pure, so it is tested without a device.
 */
class ShakeCounter {
    private val peaks = ArrayDeque<Long>()
    private var lastShake = Long.MIN_VALUE / 2

    /** One accelerometer reading in m/s². True when it completes a shake. */
    fun feed(x: Float, y: Float, z: Float, atMillis: Long): Boolean {
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < THRESHOLD_G || atMillis - lastShake < QUIET_MS) return false
        peaks.addLast(atMillis)
        while (peaks.isNotEmpty() && atMillis - peaks.first() > WINDOW_MS) peaks.removeFirst()
        if (peaks.size < PEAKS) return false
        peaks.clear()
        lastShake = atMillis
        return true
    }

    companion object {
        const val THRESHOLD_G = 2.7f
        const val PEAKS = 3
        const val WINDOW_MS = 1_000L
        const val QUIET_MS = 2_000L
    }
}

/** Calls [onShake] when the phone is shaken, while this is composed. */
@Composable
fun ShakeWatch(onShake: () -> Unit) {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onShake)
    DisposableEffect(context) {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val counter = ShakeCounter()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (counter.feed(event.values[0], event.values[1], event.values[2], event.timestamp / 1_000_000)) latest()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (accelerometer != null) sensors.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensors?.unregisterListener(listener) }
    }
}
