package com.rpm.player.phone.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a finger must stay put before a row lights, how long the mark stays after it lifts, and its fade. */
private const val SETTLE_MS = 150L
private const val HOLD_MS = 140L
private const val FADE_MS = 250

/**
 * A tappable list row that lights as the iPhone's `RowPress` lights it: a veil of white at 0x24 behind the row, with a
 * light tap felt as it lights (`Haptics.pick`).
 *
 * - **A finger has to stay put for 150 ms before the row lights**, so the start of a scroll never paints a mark across
 *   the row. A tap lights it by itself, so taps are not slowed.
 * - The mark stays 140 ms after the finger lifts and then fades over 250 ms, long enough to be seen by a person whose
 *   thumb covered the row.
 *
 * The veil's strength is read while drawing, so a press redraws the row and recomposes nothing.
 *
 * @param held the items of the menu a long press opens on the row ([HoldMenus]). They are asked for only at the long
 * press. Null, or no [HoldMenus] set, and a long press does nothing.
 */
fun Modifier.rowPress(held: (() -> List<MenuItem>)? = null, onClick: () -> Unit): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val lit = remember { Animatable(0f) }
    val menus = LocalHoldMenus.current
    val placed = remember { RowPlace() }
    this
        .onPlaced { placed.at = it }
        .semantics {
            role = Role.Button
            onClick { onClick(); true }
        }
        .pointerInput(Unit) {
            // Felt only when the row goes from dark to lit: the press and the tap are two halves of one touch.
            fun light() {
                if (lit.value == 0f && !lit.isRunning) haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                scope.launch { lit.snapTo(1f) }
            }
            var lighting: Job? = null
            detectTapGestures(
                onPress = {
                    lighting = scope.launch {
                        delay(SETTLE_MS)
                        light()
                    }
                    tryAwaitRelease()
                    lighting.cancel()
                    if (lit.value > 0f) {
                        scope.launch {
                            delay(HOLD_MS)
                            lit.animateTo(0f, tween(FADE_MS))
                        }
                    }
                },
                onLongPress = if (menus != null && held != null) {
                    {
                        val at = placed.at?.takeIf { it.isAttached }
                        val items = held().takeIf { it.isNotEmpty() }
                        if (at != null && items != null) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menus.open = HoldMenus.Open(at.boundsInRoot(), items)
                        }
                    }
                } else {
                    null
                },
                onTap = {
                    light()
                    scope.launch {
                        delay(HOLD_MS)
                        lit.animateTo(0f, tween(FADE_MS))
                    }
                    onClick()
                },
            )
        }
        .drawBehind {
            val strength = lit.value
            if (strength > 0f) drawRect(Ink.white(0x24), alpha = strength)
        }
}

/** Where a row was last placed, for the menu a long press opens on it. Held outside the state system: nothing draws from it. */
private class RowPlace {
    var at: LayoutCoordinates? = null
}
