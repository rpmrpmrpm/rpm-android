package com.rpm.player.phone.design

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The app's colours and surfaces, taken from the iPhone app's `RPMInk` (RPMUI/RPMTheme.swift). The
 * values are the same. Do not choose new values here: the two phone apps must look the same.
 */
object Ink {
    /** The background of the whole app. `#0E1520`. */
    val ground = Color(0xFF0E1520)

    /** `#FF6B6B`. The only colour in the app that means something: an error. */
    val red = Color(0xFFFF6B6B)

    /** The supporter's gold: a supporter's name on their page, beside the gold badge ([com.rpm.player.phone.settings.SupporterMark]). */
    val gold = Color(0xFFEBC65A)

    /** The colour of the supporter mark. It is used for nothing else. */
    val cyan = Color(0xFF04C8FF)

    /** What the iPhone draws as `.secondary` on a dark screen: iOS's secondary label, (235, 235, 245) at 60%. */
    val secondary = Color(0x99EBEBF5)

    /** White at [alpha] out of 255. The alpha is the first byte of the ARGB values in the theme files. */
    fun white(alpha: Int): Color = Color.White.copy(alpha = alpha / 255f)

    /** The dark wash laid over the frost on every card and on the chrome. */
    val glassWash = Color.Black.copy(alpha = 0x22 / 255f)

    /** The thin edge on every glass surface. */
    val glassEdge = white(0x22)

    /** The fill of the one main key on a screen. */
    val primaryFace = white(0x33)

    /** The fill of every other key and of a text field. */
    val ghostFace = white(0x14)

    /** The veil on a key while it is pressed. */
    val pressedVeil = white(0x28)

    /** The fill of the chosen key in a switch. Same as `RPMInk.selectedWash` on iPhone. */
    val selectedWash = Color(0x3A0371D0)

    /** The edge of a card. Nearly transparent at the top, and brighter towards the bottom. */
    val cardBorder = Brush.verticalGradient(0f to white(0x05), 0.5f to white(0x12), 1f to white(0x24))

    /** The edge of the chrome's glass: the mini player and the tab capsule. */
    val chromeEdge = white(0x1A)

    /**
     * The fill of a glass surface on Android 9 to 11, which cannot blur what is behind a view. It is the
     * background colour at 80% opacity, the same flat fill the Uno phone head uses.
     */
    val frostless = ground.copy(alpha = 0xCC / 255f)
}
