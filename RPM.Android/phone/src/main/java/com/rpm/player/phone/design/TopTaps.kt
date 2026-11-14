package com.rpm.player.phone.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Asks the page on screen in one tab to go back to its top. On iPhone a tap on the status bar does this, and the
 * system does it for the app. On Android the status bar belongs to the system and the app never gets that tap, so
 * two other taps ask instead: a tap on the tab that is already showing (`Chrome`), and a tap on an empty part of a
 * page's header ([tapsToTop]).
 *
 * There is one for each tab, given to the tab's pages through [LocalTopTaps]. Only the page on top of the tab's stack
 * is composed, so only that page scrolls. A tap while no page listens is dropped.
 */
class TopTaps {
    private val taps = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Asks the page that listens now to go to its top. */
    fun tap() {
        taps.tryEmit(Unit)
    }

    /** Runs [toTop] for each tap, for as long as the caller is composed. */
    suspend fun collect(toTop: suspend () -> Unit) {
        taps.collect { toTop() }
    }
}

/** The [TopTaps] of the tab this page is in. Null outside a tab: a sheet over the player has no top to go to. */
val LocalTopTaps = staticCompositionLocalOf<TopTaps?> { null }

/** Scrolls this list to its first row when the tab asks ([TopTaps]). */
@Composable
fun ScrollsToTop(state: LazyListState) {
    val taps = LocalTopTaps.current ?: return
    LaunchedEffect(taps, state) { taps.collect { state.animateScrollToItem(0) } }
}

/** Scrolls this grid to its first cell when the tab asks ([TopTaps]). */
@Composable
fun ScrollsToTop(state: LazyGridState) {
    val taps = LocalTopTaps.current ?: return
    LaunchedEffect(taps, state) { taps.collect { state.animateScrollToItem(0) } }
}

/** Scrolls this page to its top when the tab asks ([TopTaps]). */
@Composable
fun ScrollsToTop(state: ScrollState) {
    val taps = LocalTopTaps.current ?: return
    LaunchedEffect(taps, state) { taps.collect { state.animateScrollTo(0) } }
}

/**
 * A header: it takes every touch in its area, so nothing under it is opened or played through it, and a tap on an
 * empty part of it takes the page back to its top ([TopTaps]). The keys inside the header take their own taps first,
 * because a touch reaches the innermost element before this one, so a tap on a key does not also scroll. A drag
 * that starts here does not scroll the page under it.
 */
fun Modifier.tapsToTop(): Modifier = composed {
    val taps = LocalTopTaps.current
    pointerInput(taps) { detectTapGestures { taps?.tap() } }
}
