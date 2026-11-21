package com.rpm.player.phone.design

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role

/**
 * The long-press menu on a thing that is not a song: a record or a playlist, wherever it is shown. The song menu has its
 * own ([com.rpm.player.phone.library.SongMenu]); this one carries whatever items the thing offers. There is one for the
 * app, drawn over everything at the root ([HoldMenuLayer]), so it can reach past the page and over the tab bar.
 */
@Stable
class HoldMenus {
    var open by mutableStateOf<Open?>(null)
        internal set

    /** A menu of [items], opened on the thing at [source] in the window. */
    class Open(val source: Rect, val items: List<MenuItem>)
}

/** The app's [HoldMenus], or null where nothing is held for a menu. Set at the root. */
val LocalHoldMenus = staticCompositionLocalOf<HoldMenus?> { null }

/**
 * A tap runs [onClick]; a long press opens the menu of [items] on this thing. [items] is asked only at the long press,
 * so a long list of rows builds no menus while it scrolls. Where there are no items, or no [HoldMenus] is set, this is a
 * plain tap.
 */
@Composable
fun Modifier.holdPress(presses: MutableInteractionSource, onClick: () -> Unit, items: (() -> List<MenuItem>)?): Modifier {
    val menus = LocalHoldMenus.current
    if (menus == null || items == null) {
        return combinedClickable(interactionSource = presses, indication = null, role = Role.Button, onClick = onClick)
    }
    val haptics = LocalHapticFeedback.current
    val placed = remember { HeldPlace() }
    return this
        .onPlaced { placed.at = it }
        .combinedClickable(
            interactionSource = presses,
            indication = null,
            role = Role.Button,
            onLongClick = {
                val at = placed.at?.takeIf { it.isAttached } ?: return@combinedClickable
                val shown = items().takeIf { it.isNotEmpty() } ?: return@combinedClickable
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                menus.open = HoldMenus.Open(at.boundsInRoot(), shown)
            },
            onClick = onClick,
        )
}

/** Where a held thing was last placed. Held outside the state system on purpose: nothing draws from it. */
private class HeldPlace {
    var at: LayoutCoordinates? = null
}

/** The open menu, over everything, frosting [backdrop]. The root draws it once, next to the song menu. */
@Composable
fun HoldMenuLayer(menus: HoldMenus, backdrop: Backdrop) {
    val open = menus.open ?: return
    // Keyed by the opening, so a second long press while the first menu fades starts a fresh menu.
    key(open) {
        ContextMenu(backdrop, open.source, open.items, onDismiss = { if (menus.open === open) menus.open = null })
    }
}
