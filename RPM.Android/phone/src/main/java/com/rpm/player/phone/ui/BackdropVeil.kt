package com.rpm.player.phone.ui

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * How much the person dims and blurs the app's background, for text that reads more easily on it (`BackdropVeil` in
 * the iPhone's VinylGround.swift). Two sliders on the Appearance settings and in the welcome's look step move the
 * values, from 0 (off) to 1 (the most). [VinylGround] reads them, so every page gets them.
 *
 * The values are snapshot state: the background follows a slider as it moves. They are written to the phone's settings
 * when the finger lifts ([save]), under the iPhone's keys, and kept on this phone only.
 */
object BackdropVeil {
    const val DIM_KEY = "backdrop.dim"
    const val BLUR_KEY = "backdrop.blur"

    /** The blur at the top of the slider, as a standard deviation in dp. Enough to turn any picture into soft light. */
    const val WIDEST_BLUR: Float = 30f

    /** The extra black at the top of the slider. The picture still shows a little through it. */
    const val DEEPEST_DIM: Float = 0.75f

    private var prefs: SharedPreferences? = null

    /** How much the picture is dimmed, from 0 to 1. */
    var dim by mutableFloatStateOf(0f)
        private set

    /** How much the picture is blurred, from 0 to 1. */
    var blur by mutableFloatStateOf(0f)
        private set

    /** Reads the stored values. Called once as the app starts ([Look.open]). */
    fun open(prefs: SharedPreferences) {
        this.prefs = prefs
        dim = prefs.getFloat(DIM_KEY, 0f).coerceIn(0f, 1f)
        blur = prefs.getFloat(BLUR_KEY, 0f).coerceIn(0f, 1f)
    }

    fun changeDim(value: Float) {
        dim = value.coerceIn(0f, 1f)
    }

    fun changeBlur(value: Float) {
        blur = value.coerceIn(0f, 1f)
    }

    /** Keeps both values. Called when a slider is let go, not on every step of it. */
    fun save() {
        prefs?.edit()?.putFloat(DIM_KEY, dim)?.putFloat(BLUR_KEY, blur)?.apply()
    }

    /** The blur for a slider at [blur], as a standard deviation in dp. */
    fun radius(blur: Float): Float = blur.coerceIn(0f, 1f) * WIDEST_BLUR

    /** The black laid over the picture: its [own] veil ([Ground.shade]), and the person's dimming over what it leaves. */
    fun shade(own: Float, dim: Float): Float {
        val extra = dim.coerceIn(0f, 1f) * DEEPEST_DIM
        return own + (1 - own) * extra
    }
}
