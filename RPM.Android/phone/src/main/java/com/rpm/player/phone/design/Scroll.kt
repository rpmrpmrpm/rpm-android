package com.rpm.player.phone.design

import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A scrolling list fades out at its top edge instead of being cut off there: the iPhone's `DissolvingScroll` and its
 * `TopBand`. [under] is what stands over the list's top: 30 dp under a header (`EdgeFade.depth`), or the status bar and
 * the title or back key's row on a page that runs up behind them. The band is that and [EDGE] more, as the iPhone's
 * band continues past the chrome.
 *
 * **The grading is the iPhone's**: nearly everything is taken away behind the chrome (all of it at the very top, 95 %
 * at 45 % of the band, 60 % at 75 %), easing to nothing at the band's foot. A gentler, even ramp leaves the rows behind
 * a title looking unfaded.
 *
 * **The strength grows with the scroll**, from nothing at rest to full after [EDGE] of scrolling (`EdgeFade.head`), so a
 * list at its top, or a picture that runs up behind the clock, is whole, and the band appears as the person scrolls.
 * It is drawn as one gradient taken out of the content at that strength, so a scroll changes only the alpha of one
 * rectangle; the gradient itself is made once per size.
 *
 * [scrolled] is read during drawing, so a scroll redraws the band without recomposing anything.
 */
fun Modifier.dissolvesAtTop(under: Dp, frosted: Boolean = false, scrolled: () -> Float): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithCache {
        val edge = EDGE.toPx()
        val band = minOf(under.toPx() + edge, size.height)
        val taken = Brush.verticalGradient(
            *(if (frosted) FROSTED_TAKEN else TAKEN),
            startY = 0f,
            endY = band,
        )
        onDrawWithContent {
            drawContent()
            val strength = (minOf(scrolled(), edge) / edge).coerceIn(0f, 1f)
            if (strength > 0f && band > 0f) {
                drawRect(taken, size = Size(size.width, band), alpha = strength, blendMode = BlendMode.DstOut)
            }
        }
    }

/**
 * The frosted edge under a header that stands on a picture (home's): what scrolls up under the title is shown blurred
 * as it goes, and fades out towards the top, see-through, so the page's own colours show through and no band of colour
 * stands behind the title (Chivan, 2026-11-04: as the collection's header, "a transparent blur", not a colour). Laid
 * over a list that [dissolvesAtTop] under the same [under], `frosted`: the list's sharp rows fade out, and their blurred
 * copy takes their place and fades out in turn towards the top. [content] is the list's own drawing, recorded without the band's fade.
 *
 * Its strength grows with the scroll as the band's does, so a page at rest is untouched. One blur, at the chrome's
 * strength and at a quarter size ([frost]).
 */
@Composable
fun Modifier.frostsAtTop(content: Backdrop, under: Dp, scrolled: () -> Float): Modifier = this
    .graphicsLayer {
        val edge = EDGE.toPx()
        alpha = (minOf(scrolled(), edge) / edge).coerceIn(0f, 1f)
        compositingStrategy = CompositingStrategy.Offscreen
    }
    .drawWithCache {
        val shown = Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.3f),
            0.6f to Color.Black,
            1f to Color.Transparent,
            startY = 0f,
            endY = minOf(under.toPx() + EDGE.toPx(), size.height),
        )
        onDrawWithContent {
            drawContent()
            drawRect(shown, blendMode = BlendMode.DstIn)
        }
    }
    .frost(content, RectangleShape, CHROME_BLUR)

/** How much of the list the band takes away, down the band: the iPhone's grading. */
private val TAKEN = arrayOf(0f to Color.Black, 0.45f to Color.Black.copy(alpha = 0.95f), 0.75f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent)

/** The same under a frosted edge ([frostsAtTop]): the sharp rows are gone by the band's middle, where their blurred copy
 * stands in full. Left at the iPhone's grading, they showed through the frost behind the title, still readable. */
private val FROSTED_TAKEN = arrayOf(0f to Color.Black, 0.6f to Color.Black, 1f to Color.Transparent)

/** How deep the band reaches past the chrome, and how far a list scrolls before it is at full strength (`EdgeFade.depth`). */
private val EDGE = 30.dp

/**
 * How far a grid has scrolled off its top. The fade band only needs to know this. Once the first row is
 * off screen, the result is `Float.MAX_VALUE`.
 */
fun LazyGridState.scrolled(): Float =
    if (firstVisibleItemIndex > 0) Float.MAX_VALUE else firstVisibleItemScrollOffset.toFloat()

/** The same for a list. */
fun LazyListState.scrolled(): Float =
    if (firstVisibleItemIndex > 0) Float.MAX_VALUE else firstVisibleItemScrollOffset.toFloat()
