package com.rpm.player.phone.radio

import android.content.res.Resources
import androidx.compose.runtime.staticCompositionLocalOf
import com.rpm.player.core.R
import com.rpm.player.core.radio.RadioRepository
import com.rpm.player.core.radio.RadioStation
import com.rpm.player.phone.PhoneSettings
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.MenuItem

/**
 * What a long press on a station offers, on the radio home and in the tuner: pin it to the radio home's Quick selection
 * or unpin it, and save it or take it out of the saved stations. Saving is offered only for the person's own saved
 * stations: on a shared collection they are the owner's ([com.rpm.player.core.radio.SavedStations.mine]).
 *
 * The words are read from [resources] at the moment of the press, so the rows build nothing while they scroll.
 */
class StationHolds(
    private val resources: Resources,
    private val settings: PhoneSettings,
    private val radio: RadioRepository,
) {
    fun of(station: RadioStation): List<MenuItem> {
        val pinned = settings.isPinned(station)
        val pin = MenuItem(resources.getString(if (pinned) R.string.Radio_Unpin else R.string.Radio_Pin), Glyph.pin) {
            settings.pinStation(station, !pinned)
        }
        val saved = radio.saved.value
        if (!saved.mine) return listOf(pin)
        val isSaved = saved.stations.any { station.answersTo(it.uuid) }
        val save = MenuItem(resources.getString(if (isSaved) R.string.Radio_Unsave else R.string.Radio_Save), Glyph.heart) {
            radio.favorites.toggle(station)
        }
        return listOf(pin, save)
    }
}

/** The app's [StationHolds], or null where stations have no long-press menu. Set at the root. */
val LocalStationHolds = staticCompositionLocalOf<StationHolds?> { null }
