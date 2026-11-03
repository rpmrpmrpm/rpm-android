package com.rpm.player.phone.design

import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.animate
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.rpm.player.phone.ui.RecordSpinner

/** How far a pull has to go before letting go reads the collection again, and where the spinner rests while it does. */
private val PULL_TRIGGER = 72.dp
private val PULL_REST = 52.dp

/** A pull moves the page by this share of the finger's travel, so it feels held back, as a pull on iPhone does. */
private const val PULL_RESISTANCE = 0.5f

/**
 * Pull down from the top to read the collection again: SwiftUI's `.refreshable` on the iPhone's walls. The content
 * comes down with the finger, a spinning record shows in the space it leaves, and letting go past the trigger calls
 * [onRefresh]. The spinner stays while [refreshing] is true, then the content goes back up.
 *
 * Only the overscroll at the top is taken, through nested scrolling, so an ordinary scroll of the wall is untouched.
 * The content is moved in its layer, so a pull redraws nothing but the layer's position. [top] is how much of a header
 * stands over the content's top, for content that runs up under one ([UnderHeader]): the spinner shows below it.
 */
@Composable
fun PullToRefresh(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, top: Dp = 0.dp, content: @Composable BoxScope.() -> Unit) {
    val density = LocalDensity.current
    val trigger = with(density) { PULL_TRIGGER.toPx() }
    val rest = with(density) { PULL_REST.toPx() }
    var pulled by remember { mutableFloatStateOf(0f) }
    suspend fun settle(to: Float) = animate(pulled, to) { value, _ -> pulled = value }
    val refresh by rememberUpdatedState(onRefresh)
    val busy by rememberUpdatedState(refreshing)

    // When the read is done, the content goes back up.
    LaunchedEffect(refreshing) {
        if (!refreshing && pulled > 0f) settle(0f)
    }

    val connection = remember(trigger, rest) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Pushing back up while pulled takes the pull away first.
                if (source != NestedScrollSource.UserInput || available.y >= 0f || pulled <= 0f) return Offset.Zero
                val next = (pulled + available.y).coerceAtLeast(0f)
                val used = next - pulled
                pulled = next
                return Offset(0f, used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                pulled += available.y * PULL_RESISTANCE
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pulled <= 0f) return Velocity.Zero
                if (pulled >= trigger && !busy) {
                    refresh()
                    settle(rest)
                } else {
                    settle(if (busy) rest else 0f)
                }
                return available
            }
        }
    }

    Box(modifier.nestedScroll(connection)) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = top)
                .fillMaxWidth()
                .height(PULL_REST)
                .graphicsLayer { alpha = (pulled / trigger).coerceIn(0f, 1f) },
            contentAlignment = Alignment.Center,
        ) {
            if (pulled > 0f || refreshing) RecordSpinner(26.dp)
        }
        Box(Modifier.graphicsLayer { translationY = pulled }) { content() }
    }
}

/**
 * A two-finger pinch over a wall, reported as the change in spread since the pinch began ([onZoom], 1 at the start) and
 * its end ([onEnd]). It is watched before the wall's own scroll sees the fingers, and only a second finger takes the
 * gesture from the scroll, so one finger still scrolls (`MagnifyGesture` as a `simultaneousGesture` on iPhone).
 */
fun Modifier.pinch(onZoom: (Float) -> Unit, onEnd: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoom = 1f
        var pinching = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.count { it.pressed }
            if (down == 0) break
            if (down >= 2) {
                pinching = true
                zoom *= event.calculateZoom()
                onZoom(zoom)
                event.changes.forEach { it.consume() }
            } else if (pinching) {
                // One finger left of a pinch does not turn into a scroll.
                event.changes.forEach { it.consume() }
            }
        }
        if (pinching) onEnd()
    }
}
