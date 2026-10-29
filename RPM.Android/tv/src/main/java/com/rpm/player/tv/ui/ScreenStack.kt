package com.rpm.player.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import com.rpm.player.core.motion.LocalMotion
import com.rpm.player.core.motion.Motion

/** One screen on the stack: what it shows, and an id that is new each time a screen is opened. Two openings of
 * the same route are two screens, each with its own state. Two entries are equal only if route and id are equal. */
data class StackEntry(val route: Route, val id: Long) {
    /** The item of this screen that last had the focus. */
    val focus = FocusMemory()
}

/**
 * The item of one screen that last had the focus, so the screen can give it back when it is shown again: after
 * Back, after the player, after the side menu, and on Down from the mini player. Every focusable item made with
 * [deskFocus] or [CardButton] tells it when it takes the focus ([remembersFocus]).
 *
 * Compose's own `focusRestorer` keeps only the child of its group that had the focus, one level down. On home
 * that is the whole list, not the card in a row of the list. Asking the list for the focus then gives it to
 * whatever the list chooses (for example the first quick entry, after a page was opened from the banner).
 */
@Stable
class FocusMemory {
    var last: FocusRequester? = null
        private set

    internal fun took(item: FocusRequester) {
        last = item
    }

    /** Gives the focus back to the item. False when there is none, or it has left the screen since. */
    fun giveBack(): Boolean = last?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
}

/** The memory of the stacked screen this is read in; null outside one. */
val LocalFocusMemory = staticCompositionLocalOf<FocusMemory?> { null }

/** Tells the screen's [FocusMemory] when this item takes the focus. Put it before the item's `clickable` or
 * `focusable`. Does nothing outside a stacked screen. */
fun Modifier.remembersFocus(): Modifier = composed {
    val memory = LocalFocusMemory.current
    if (memory == null) {
        this
    } else {
        val me = remember { FocusRequester() }
        this.focusRequester(me).onFocusChanged { if (it.isFocused) memory.took(me) }
    }
}

/**
 * The screens below the player, home at the bottom. Every entry stays composed while it is on the stack, and
 * only the top one is placed and drawn ([StackedScreen]). Back, and closing the player, therefore show the
 * screen as it was left: its scroll, the scroll of its rows, its pictures and the item that had the focus.
 * If a screen left composition when another opened over it, it would be built again from nothing on the way
 * back: home at its top on the banner's first card, the wall on its first card, every picture loaded again.
 *
 * The only ways to change the stack are the four below, so a screen opened from the side menu cannot keep a
 * stale screen of the same kind underneath, and a new collection cannot keep the old one's home.
 */
@Stable
class ScreenStack {
    private var nextId = 0L
    private val stack = mutableStateListOf(StackEntry(Route.Home, nextId++))

    /** Bottom first. */
    val entries: List<StackEntry> get() = stack
    val top: StackEntry get() = stack.last()
    val size: Int get() = stack.size

    /** Opens [route] over what is up. */
    fun push(route: Route) {
        stack.add(StackEntry(route, nextId++))
    }

    /** Takes the top screen away. Home is never taken away. Leaving the app from home is the caller's job. */
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Home as it was, with [route] opened over it, or home alone. This is what the side menu picks. */
    fun overHome(route: Route) {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        if (route != Route.Home) push(route)
    }

    /** A new home with nothing over it. Used when another collection is opened in place of this one. */
    fun newHome() {
        stack.clear()
        stack.add(StackEntry(Route.Home, nextId++))
    }
}

/**
 * Whether the screen this is read in is the one on top and shown. False for a screen kept under another, or
 * under the player. A screen reads it before doing anything that only the screen in front may do: give the
 * mini player its scroll answer ([MiniPlayerState.follow]), take Back, step its banner, or drift its cards.
 * True outside a stacked screen.
 */
val LocalOnTop = compositionLocalOf { true }

/**
 * One screen of the stack. On top it is placed, drawn and fades in over [SCREEN_FADE_MS]. Under another screen
 * it stays composed, measured and unplaced, which draws nothing and takes it out of the remote's search (Compose
 * skips a target that is not placed). When it comes back on top it gives the focus back to the item that had
 * it ([FocusMemory]). If there is none, it gives the focus to its group, whose [focusRestorer] picks the child
 * that had it. The first time the screen is on top, it takes its own first focus.
 *
 * A screen underneath cannot take the focus either: its outer group cancels any focus that would enter it while
 * it is not on top. Without that, a screen's own "focus my first key" effect, run again under another screen
 * (for example a page whose album is read again), would pull the focus out of the screen in front.
 */
@Composable
fun StackedScreen(onTop: Boolean, memory: FocusMemory, content: @Composable () -> Unit) {
    val focus = remember { FocusRequester() }
    val onTopNow by rememberUpdatedState(onTop)
    var hidden by remember { mutableStateOf(false) }
    val shown = remember { Animatable(0f) }
    val fades = LocalMotion.current < Motion.Still
    LaunchedEffect(onTop) {
        if (!onTop) {
            hidden = true
            shown.snapTo(0f)
            return@LaunchedEffect
        }
        if (hidden) {
            hidden = false
            val gaveBack = memory.giveBack()
            val group = !gaveBack && runCatching { focus.requestFocus() }.getOrDefault(false)
            com.rpm.player.core.diag.Diag.d("rpm.focus", "screen back on top: item ${if (gaveBack) "given back" else "not given back"}, group ${if (gaveBack) "not asked" else if (group) "took it" else "refused"}")
        }
        if (fades) shown.animateTo(1f, tween(SCREEN_FADE_MS)) else shown.snapTo(1f)
    }
    Box(
        Modifier
            .fillMaxSize()
            .then(if (onTop) Modifier else Modifier.unplacedScreen())
            .graphicsLayer {
                alpha = shown.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .focusProperties { onEnter = { if (!onTopNow) cancelFocusChange() } }
            .focusGroup()
            .focusRequester(focus)
            .focusRestorer()
            .focusGroup(),
    ) {
        CompositionLocalProvider(LocalOnTop provides onTop, LocalFocusMemory provides memory) { content() }
    }
}

/** Measures what it wraps and does not place it: it stays composed and draws nothing. */
private fun Modifier.unplacedScreen(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {}
}

/** How long a screen takes to come up when it opens, or when the screen over it closes. Only the incoming
 * screen fades, with nothing crossfading out under it, because the Amiko's GPU cannot keep up with two whole
 * screens drawn at once. It was 180 ms, which read as a flash rather than a fade; half as long again reads as
 * smooth. */
internal const val SCREEN_FADE_MS = 270
