package com.rpm.player.phone.ui

import android.content.SharedPreferences
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.min

/**
 * How round the corners of a cover are: the same share of its side on every cover, so a record, a playlist, a folder,
 * a station and the player's cover look alike at any size (`CoverCorner` in the iPhone's AlbumTile.swift).
 *
 * The person chooses the share on the Appearance settings and in the welcome, from square corners to quite round ones.
 * The usual share is the one of the album covers on home: 10 dp on a 132 dp cover, the desktop card's corner. A small
 * cover in a row gets small corners and a large one on a pinched wall gets large ones, and they all look equally round.
 *
 * [share] is snapshot state, so every composable that calls [radius] or [rememberCoverShape] reads the setting and is
 * drawn again when it changes. It is kept on this phone only, under the iPhone's key.
 */
object CoverCorner {
    /** Ten dp on home's 132 dp covers. */
    const val USUAL: Float = 10f / 132f

    /** The roundest corner offered: a little under a quarter of the side. */
    const val ROUNDEST: Float = 0.22f

    /** Where the share is kept, as on iPhone (`CoverRounding.key`). */
    const val KEY = "look.coverCorner"

    private var prefs: SharedPreferences? = null

    /** The share chosen, from 0 (square) to [ROUNDEST]. */
    var share by mutableFloatStateOf(USUAL)
        private set

    /** The corner radius for a cover [edge] wide. */
    fun radius(edge: Dp): Dp = edge * share

    /** Reads the stored share. Called once as the app starts ([Look.open]). */
    fun open(prefs: SharedPreferences) {
        this.prefs = prefs
        share = stored(if (prefs.contains(KEY)) prefs.getFloat(KEY, USUAL) else null)
    }

    /** Keeps [chosen], within the range offered, and every cover follows. */
    fun change(chosen: Float) {
        val kept = stored(chosen)
        if (kept == share) return
        share = kept
        prefs?.edit()?.putFloat(KEY, kept)?.apply()
    }

    /** The share for what is stored: [USUAL] when nothing is, and a stored value kept within 0 and [ROUNDEST]. */
    internal fun stored(value: Float?): Float = value?.takeUnless { it.isNaN() }?.coerceIn(0f, ROUNDEST) ?: USUAL
}

/**
 * A cover's rounded rectangle for a card whose size the layout decides: a genre's portrait card, an artist's wide tile
 * (`CoverShape` on iPhone). The corner follows [CoverCorner] from the card's shorter side, so the card is as round as a
 * cover of that size. [share] is fixed when the shape is made; [rememberCoverShape] makes a new one when it changes.
 */
class CoverShape(private val share: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = min(size.width, size.height) * share
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
    }

    override fun equals(other: Any?): Boolean = other is CoverShape && other.share == share

    override fun hashCode(): Int = share.hashCode()
}

/** The [CoverShape] for the share chosen now. Reading it here is what draws the caller again when the share changes. */
@Composable
fun rememberCoverShape(): Shape {
    val share = CoverCorner.share
    return remember(share) { CoverShape(share) }
}

/** The rounded square of a cover [edge] wide, with the corner chosen now ([CoverCorner.radius]). */
@Composable
fun coverSquare(edge: Dp): RoundedCornerShape {
    val radius = CoverCorner.radius(edge)
    return remember(radius) { RoundedCornerShape(radius) }
}
