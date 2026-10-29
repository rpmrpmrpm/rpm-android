package com.rpm.player.tv

import android.content.Context
import com.rpm.player.core.RpmApplication
import com.rpm.player.core.Services
import com.rpm.player.core.SessionOpening
import com.rpm.player.tv.ui.PlayerSettings

class TvApplication : RpmApplication() {
    /** The TV's settings: the player's layout, the stats, the screensaver and volume levelling. Made in
     * [makeServices], before the services, which are handed its levelling. */
    lateinit var settings: PlayerSettings
        private set

    override fun makeServices(): Services {
        settings = PlayerSettings(this)
        return Services(this, "androidtv", settings.levelling, com.rpm.player.core.radio.TunerOpensOn.ListenersCountry)
    }

    /** The launcher's now playing card opens the app's launcher entry itself; [MainActivity] knows that launch by who
     * sent it and opens the player ([MainActivity.fromNowPlayingCard]). */
    override fun sessionOpening(): SessionOpening = SessionOpening.LauncherEntry
}

val Context.playerSettings: PlayerSettings get() = (applicationContext as TvApplication).settings
