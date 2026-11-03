package com.rpm.player.phone.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A page whose content runs up under its header to the top of the screen, as the iPhone's library walls scroll under
 * their header bar (Chivan, 2026-11-03: the wall was cut off a short way under the view row, where it should fade
 * out towards the top of the screen). The header is drawn over the content; [content] is told how tall it is,
 * status bar included, to start its first row under it and to fade its top over it ([dissolvesAtTop]).
 *
 * [ground] (the page's background) and the content are recorded together into [backdrop], so the header's glass
 * frosts the wall passing behind it and not only the background, as the iPhone's glass does.
 *
 * The header lets touches it does not take through to the content, so a drag on the title scrolls the wall.
 */
@Composable
fun UnderHeader(
    backdrop: Backdrop,
    ground: @Composable () -> Unit,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (top: Dp) -> Unit,
) {
    val density = LocalDensity.current
    var top by remember { mutableStateOf(0.dp) }
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
            ground()
            content(top)
        }
        Box(Modifier.fillMaxWidth().onSizeChanged { top = with(density) { it.height.toDp() } }) { header() }
    }
}
