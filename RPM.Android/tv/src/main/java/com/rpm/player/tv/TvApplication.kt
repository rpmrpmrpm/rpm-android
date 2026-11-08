package com.rpm.player.tv

import android.content.Context
import com.rpm.player.core.RpmApplication
import com.rpm.player.core.Services
import com.rpm.player.core.SessionOpening
import com.rpm.player.core.playback.AudioLadder
import com.rpm.player.tv.ui.PlayerSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class TvApplication : RpmApplication() {
    /** The TV's settings: the player's layout, the stats, the screensaver and volume levelling. Made in
     * [makeServices], before the services, which are handed its levelling. */
    lateinit var settings: PlayerSettings
        private set

    override fun makeServices(): Services {
        settings = PlayerSettings(this)
        return Services(this, "androidtv", settings.levelling, com.rpm.player.core.radio.TunerOpensOn.ListenersCountry)
    }

    override fun onCreate() {
        super.onCreate()
        settleTheRate()
    }

    /**
     * The rate the TV asks the store for, from the account's plan: the original file for a supporter or a closed beta
     * tester (the quota marks both as supporters), from the moment the plan says so, and the one rate a free account
     * is served. The original while the plan is not known yet, as on the phones: the store caps what it serves
     * either way. The TV has no setting for it, because a television streams over a home network.
     */
    private fun settleTheRate() {
        services.scope.launch {
            services.ownFace.quota
                .map { quota -> AudioLadder.RateOffer.forPlan(quota?.supporter) }
                .distinctUntilChanged()
                .collect { kilobits -> services.streamRate.kilobits = kilobits }
        }
    }

    /** The launcher's now playing card opens the app's launcher entry itself; [MainActivity] knows that launch by who
     * sent it and opens the player ([MainActivity.fromNowPlayingCard]). */
    override fun sessionOpening(): SessionOpening = SessionOpening.LauncherEntry
}

val Context.playerSettings: PlayerSettings get() = (applicationContext as TvApplication).settings
