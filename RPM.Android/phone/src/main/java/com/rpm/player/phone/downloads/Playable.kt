package com.rpm.player.phone.downloads

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.rpm.player.core.library.StarterTracks

/**
 * Which records can be played here and now, as the iPhone's `PlayableHere` (RPMUI/RPMTheme.swift): everything while
 * online, and offline only the records with a track on the phone.
 *
 * It is one value handed down the tree ([LocalPlayable]) and not a question each tile works out, so every tile and
 * every row greys out by the same rule. The rule reads two facts that hold whenever they are asked, the settled
 * network reading and what the download book holds, so a tile drawn at any moment shows the right answer.
 */
@Immutable
data class PlayableHere(val isOnline: Boolean, val onPhone: Set<String>) {
    /** A starter record ([StarterTracks]) is inside the app, so it always plays. */
    fun canPlay(albumKey: String): Boolean = isOnline || albumKey in onPhone || StarterTracks.isStarterKey(albumKey)

    companion object {
        /** Everything plays. A screen drawn before the app has the real answer must assume this, or it greys out a whole
         * wall that works. */
        val Everything = PlayableHere(isOnline = true, onPhone = emptySet())
    }
}

/** What plays here, for every record tile. Set once, in the shell. */
val LocalPlayable = compositionLocalOf { PlayableHere.Everything }

/** The grey matrix, made once. */
private val GREY = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * A record that cannot be played now: still there to look at, not there to play. It is drawn without colour at 35%,
 * as the iPhone's tile is (`saturation(0)`, `opacity(0.35)`). Nothing is added to a tile that can play, so a wall
 * online costs nothing for this.
 */
fun Modifier.greyedOut(greyed: Boolean): Modifier = if (!greyed) this else drawWithCache {
    val paint = Paint().apply {
        colorFilter = GREY
        alpha = 0.35f
    }
    onDrawWithContent {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
            drawContent()
            canvas.restore()
        }
    }
}
