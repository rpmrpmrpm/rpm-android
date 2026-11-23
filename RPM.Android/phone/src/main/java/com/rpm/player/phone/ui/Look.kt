package com.rpm.player.phone.ui

import android.content.Context
import com.rpm.player.phone.settings.AppLanguages

/**
 * The look settings kept outside [com.rpm.player.phone.PhoneSettings]: how the background is dimmed and blurred
 * ([BackdropVeil]), how round the covers are ([CoverCorner]) and the app's language ([AppLanguages]). Each is read
 * wherever it is drawn, as the iPhone reads its `AppStorage`, so none is passed through the pages. They live in the
 * phone's settings file, under the iPhone's keys.
 */
object Look {
    /** The phone's settings file, the one [com.rpm.player.phone.PhoneSettings] keeps. */
    const val FILE = "rpm.phone"

    /** Reads the stored settings. Called once as the app starts, before any screen. */
    fun open(context: Context) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        BackdropVeil.open(prefs)
        CoverCorner.open(prefs)
        AppLanguages.open(prefs)
    }
}
