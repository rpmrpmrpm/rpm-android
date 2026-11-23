package com.rpm.player.phone.design

import android.content.Context
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The space between the title and the line under it. It is the same on every page, so the headings line up across tabs.
 */
private val SUBTITLE_GAP = 6.dp

/** The space between a descender and the line set next to it. */
private val BESIDE_DESCENDER = 6.dp

/**
 * The title of a library view, the line under it, and the keys on the title's line. The iPhone app has the
 * same `PageHeading` (RPMUI/PageTitle.swift). The title is laid out as if it had no descender, and the line sits
 * level with the descenders. A line short enough to end before the first letter that goes below the baseline is set
 * at the left, under the title. A longer line starts just to the right of the last such letter, so it never runs into
 * one. So every header is the same height, whatever the title. The keys are centred on the title's line.
 */
@Composable
fun PageHeading(title: String, line: String, keys: @Composable () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val size = 96f
    val sizePx = with(density) { size.dp.toPx() }
    val shown = remember(title, sizePx) { TitleText.of(context, title, sizePx) }
    val metrics = remember(shown) { TitleMetrics.of(context, shown) }

    // Placed by baselines, not stacked: the line's baseline is SUBTITLE_GAP plus the iPhone's ascent for the line's
    // 11 points under the title's baseline, which is where the iPhone's stack puts it (the title trimmed to its
    // baseline, the gap, then the line). Stacked boxes stood the line 11 dp lower than that here, because Android's
    // line boxes for both texts are not the iPhone's.
    Layout(
        content = {
            HeaderText(shown, Modifier, size, Color.White)
            Box { keys() }
            BasicText(line, maxLines = 1, style = kicker())
        },
    ) { (titleText, keyRow, lineText), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val shownTitle = titleText.measure(loose)
        val shownKeys = keyRow.measure(loose)
        // Where the line starts: at the left when its whole width, and the gap, ends before the first descender,
        // else the gap past the last one. Its width is asked first, without measuring it, because a measure can be
        // made only once and the room it is measured in depends on where it starts.
        val gap = BESIDE_DESCENDER.toPx()
        val natural = lineText.maxIntrinsicWidth(Constraints.Infinity)
        val lineStart = if (metrics.descenderEnd > 0 && natural + gap > metrics.descenderStart) (metrics.descenderEnd + gap).roundToInt() else 0
        val room = if (loose.hasBoundedWidth) (loose.maxWidth - lineStart).coerceAtLeast(0) else Constraints.Infinity
        val shownLine = lineText.measure(loose.copy(maxWidth = room))
        val titleBase = shownTitle[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: shownTitle.height
        val lineBase = shownLine[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: 0
        val lineTop = titleBase + (SUBTITLE_GAP + LINE_ASCENT).roundToPx() - lineBase
        // The keys are centred on the title's line, from its top to its baseline (the title is laid out as if it had no
        // descender), not hung from its top. They are taller than the letters, and hung from the top they reached down
        // into the line under the title, which on a borrowed collection is long enough to run under them.
        val keysTop = (titleBase - shownKeys.height) / 2
        val width = constraints.maxWidth
        val height = max(lineTop + shownLine.height, keysTop + shownKeys.height)
        layout(width, height) {
            shownTitle.place(0, 0)
            shownKeys.place(width - shownKeys.width, keysTop)
            shownLine.place(lineStart, lineTop)
        }
    }
}

/** How far the iPhone's 11-point line rises above its baseline: SF Pro's ascent, 0.952 of the em. */
private val LINE_ASCENT = 10.5.dp

/**
 * The name at the top of a page, in the title font. The iPhone app has the same `PageTitle`. The part of
 * the font's descent that this word does not use is removed from the bottom. So the line under the title is
 * the same distance below the letters on every page.
 */
@Composable
fun PageTitle(title: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val size = 96f
    val sizePx = with(density) { size.dp.toPx() }
    val shown = remember(title, sizePx) { TitleText.of(context, title, sizePx) }
    val metrics = remember(shown) { TitleMetrics.of(context, shown) }
    HeaderText(shown, modifier.trimBottom(metrics.slack), size, Color.White)
}

/** Takes [px] off the foot of what this lays out, so what follows starts that much higher. */
private fun Modifier.trimBottom(px: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val height = max(0, placeable.height - px.roundToInt())
    layout(placeable.width, height) { placeable.place(0, 0) }
}

/**
 * How far the title font goes under the baseline for one word. The values are measured from the word's own
 * glyphs, as the iPhone app does (`RPMFont.headerDescentSlack`, `headerDescenderEnd`).
 */
private class TitleMetrics(
    /** The font's whole descent, in pixels. It is removed so that the title is laid out as if it had no descender. */
    val descent: Float,
    /** Where the last letter that reaches below the baseline ends, in pixels from the word's start; 0 for none. */
    val descenderEnd: Float,
    /** Where the first letter that reaches below the baseline starts, in pixels from the word's start; 0 for none. */
    val descenderStart: Float,
    /** The part of the descent that this word does not use. It is all of the descent when the word has no descender. */
    val slack: Float,
) {
    companion object {
        fun of(context: Context, shown: TitleText): TitleMetrics {
            val said = shown.text
            val paint = titlePaint(context, shown.sizePx)
            val descent = paint.fontMetrics.descent
            // A letter counts as a descender only if it goes more than half a dp below the baseline.
            // So a round letter that dips slightly under it is not counted. The ink is measured from the
            // glyphs' outlines, not getTextBounds: that rounds out to whole pixels, and at 450 dpi it turns
            // the 0.38 dp that c, o and e dip into 2 px, past the half dp, so "collection" counted as having
            // a descender and its line was set beside the n.
            val dip = context.resources.displayMetrics.density * 0.5f
            val outline = Path()
            val ink = RectF()
            fun inkOf(start: Int, end: Int): RectF {
                outline.reset()
                paint.getTextPath(said, start, end, 0f, 0f, outline)
                outline.computeBounds(ink, true)
                return ink
            }
            // Each run is measured at its own size, and a lifted run where it is drawn (see [TitleText]).
            var depth = if (shown.inTitleFace) inkOf(0, said.length).bottom else 0f
            var start = Float.POSITIVE_INFINITY
            var end = 0f
            var x = 0f
            for (run in shown.runs) {
                paint.textSize = run.sizePx
                for (i in run.start until run.end) {
                    val letter = inkOf(i, i + 1)
                    val bottom = letter.bottom - run.liftPx
                    if (!shown.inTitleFace && !letter.isEmpty) depth = max(depth, bottom)
                    if (bottom > dip) {
                        val at = x + paint.measureText(said, run.start, i)
                        start = min(start, at + letter.left)
                        end = max(end, at + letter.right)
                    }
                }
                x += paint.measureText(said, run.start, run.end)
            }
            val slack = max(0f, descent - max(0f, depth))
            return TitleMetrics(
                descent = descent,
                descenderEnd = end,
                descenderStart = if (start.isFinite()) max(0f, start) else 0f,
                slack = slack,
            )
        }
    }
}
