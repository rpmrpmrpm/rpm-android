package com.rpm.player.phone.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.request.ImageRequest

/**
 * A request for [data] at the pixels it is drawn at, [width] by [height].
 *
 * **The size is given, not left to the layout, so a picture already seen is there on the first frame.** A request
 * with no size waits for the layout to say how big the picture is, and only then asks the image loader, which
 * answers from its memory one or more frames later. So every picture on a page that is shown again, after another tab
 * or another view, popped in after the page. With the size known when the page is built, the loader answers from
 * its memory at once, and only a picture never loaded before arrives late.
 *
 * [extra] adds to the request (a transformation, a crossfade) before it is built.
 */
@Composable
fun rememberPicture(data: Any?, width: Dp, height: Dp = width, extra: (ImageRequest.Builder.() -> Unit)? = null): ImageRequest {
    val context = LocalContext.current
    val density = LocalDensity.current
    val wide = with(density) { width.roundToPx() }.coerceAtLeast(1)
    val high = with(density) { height.roundToPx() }.coerceAtLeast(1)
    return remember(data, wide, high, extra) {
        ImageRequest.Builder(context).data(data).size(wide, high).apply { extra?.invoke(this) }.build()
    }
}
