package com.rpm.player.phone.design

import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.rpm.player.phone.R
import java.text.Normalizer

/**
 * A title in the title face (`header()`), on one line, with the letters the face lacks drawn at a size of their own. The
 * iPhone app has the same `HeaderText` (RPMUI/PageTitle.swift).
 *
 * Thiamine RPM has Latin letters only. For a letter it lacks, such as Chinese, Android falls back to a system font. At the
 * title's size that font draws an ideograph about three times taller than Thiamine's capitals, because Thiamine's
 * capitals are only a quarter of the em. So those letters are drawn smaller, until their ink is as tall as the title
 * face's letters (see [TitleText]).
 *
 * **The line box is the title face's own.** A title with a run in another font is laid out in the box that a Latin title
 * has, with the same baseline, so a Chinese page title is as tall as an English one and the page under it does not move.
 */
@Composable
fun HeaderText(title: String, modifier: Modifier = Modifier, size: Float = 96f, color: Color = Color.White) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.dp.toPx() }
    val shown = remember(title, sizePx) { TitleText.of(context, title, sizePx) }
    HeaderText(shown, modifier, size, color)
}

/** [HeaderText] for a title that the caller has already cut into runs, to measure it as well. */
@Composable
internal fun HeaderText(shown: TitleText, modifier: Modifier, size: Float, color: Color) {
    val style = header(size).copy(color = color)
    if (shown.inTitleFace) {
        BasicText(shown.text, modifier, maxLines = 1, style = style)
    } else {
        val context = LocalContext.current
        val density = LocalDensity.current
        val text = remember(shown, density) {
            // The lift is a share of the ascent of the paint the shift is applied to. Compose sets a span's baseline
            // shift before its font size, so that paint still has the title's size and face.
            val ascent = -titlePaint(context, shown.sizePx).ascent()
            buildAnnotatedString {
                append(shown.text)
                for (run in shown.runs) {
                    if (run.inTitleFace) continue
                    addStyle(
                        SpanStyle(fontSize = with(density) { run.sizePx.toSp() }, baselineShift = BaselineShift(run.liftPx / ascent)),
                        run.start,
                        run.end,
                    )
                }
            }
        }
        Layout(
            content = {
                // The title face's line box, measured from a letter of its own. It is measured and never placed, so it is
                // not drawn.
                BasicText("x", maxLines = 1, style = style)
                BasicText(text, maxLines = 1, style = style)
            },
            modifier,
        ) { (box, line), constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val shownBox = box.measure(loose)
            val shownLine = line.measure(loose)
            val boxBase = shownBox[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: shownBox.height
            val lineBase = shownLine[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: shownLine.height
            val width = shownLine.width.coerceIn(constraints.minWidth, constraints.maxWidth)
            val height = shownBox.height.coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height, mapOf<AlignmentLine, Int>(FirstBaseline to boxBase, LastBaseline to boxBase)) {
                shownLine.place(0, boxBase - lineBase)
            }
        }
    }
}

/**
 * One part of a title, by its place in [TitleText.text]: in the title face, or in the font that stands in for it at
 * [sizePx], raised by [liftPx] so an ideograph stands on the baseline.
 */
internal class TitleRun(val start: Int, val end: Int, val sizePx: Float, val liftPx: Float, val inTitleFace: Boolean)

/** A title cut into the letters the title face has and the letters it lacks. A title the face draws whole is one run. */
internal class TitleText(val text: String, val sizePx: Float, val runs: List<TitleRun>) {
    val inTitleFace: Boolean get() = runs.all { it.inTitleFace }

    companion object {
        /**
         * Cuts [said] into runs. A space stays in the run it is in. The text is composed first, so an accent typed as a
         * separate mark is looked up as the one letter the face has.
         *
         * Ideographs are sized by the ink of a plain one, so it stands [IDEOGRAPH_OVER_CAPITAL] times as tall as the
         * title face's capitals, and lifted so it stands on the baseline. Other scripts are sized so the system font's
         * capitals are as tall as the title face's.
         */
        fun of(context: Context, said: String, sizePx: Float): TitleText {
            val whole = TitleText(said, sizePx, listOf(TitleRun(0, said.length, sizePx, 0f, true)))
            if (said.isEmpty()) return whole

            val letters = ThiamineLetters.of(context)
            val text = Normalizer.normalize(said, Normalizer.Form.NFC)
            val parts = ArrayList<Triple<Int, Int, Boolean>>()
            var partStart = 0
            var partHas = true
            var i = 0
            while (i < text.length) {
                val code = text.codePointAt(i)
                val space = Character.isWhitespace(code) || Character.isSpaceChar(code)
                val has = space || code in letters
                if (i == 0) {
                    partHas = has
                } else if (has != partHas && !space) {
                    parts += Triple(partStart, i, partHas)
                    partStart = i
                    partHas = has
                }
                i += Character.charCount(code)
            }
            parts += Triple(partStart, text.length, partHas)
            if (parts.all { it.third }) return whole

            val paint = titlePaint(context, sizePx)
            val capital = -inkOf(paint, "H").top
            val runs = parts.map { (start, end, has) ->
                if (has) TitleRun(start, end, sizePx, 0f, true) else standIn(text, start, end, paint, sizePx, capital)
            }
            return TitleText(text, sizePx, runs)
        }

        private fun standIn(text: String, start: Int, end: Int, paint: Paint, sizePx: Float, capital: Float): TitleRun {
            if ((start until end).any { isIdeographic(text.codePointAt(it)) }) {
                // The ideograph for "country", a plain square one, drawn by the font the title face falls back to.
                val ink = inkOf(paint, "国")
                if (ink.height() > 0f) {
                    val scale = capital * IDEOGRAPH_OVER_CAPITAL / ink.height()
                    return TitleRun(start, end, sizePx * scale, ink.bottom * scale, false)
                }
            }
            val theirs = -inkOf(Paint(paint).apply { typeface = Typeface.DEFAULT }, "H").top
            val scale = if (theirs > 0f) capital / theirs else 1f
            return TitleRun(start, end, sizePx * scale, 0f, false)
        }

        /** Han, kana, Hangul and the full-width forms: the scripts drawn as square ideographs. */
        private fun isIdeographic(code: Int): Boolean =
            code in 0x2E80..0x9FFF || code in 0xAC00..0xD7AF || code in 0xF900..0xFAFF || code in 0xFF00..0xFFEF ||
                code in 0x20000..0x3FFFF
    }
}

/**
 * How tall the ink of an ideograph stands, as a share of the title face's capital height. The iPhone's
 * `RPMFont.ideographOverCapital`.
 *
 * A square ideograph looks larger than a capital of the same height, but a lowercase title such as "settings" also reaches
 * below the baseline, so the ideograph is made a little taller than the capitals. It still fits in the title's line box.
 */
private const val IDEOGRAPH_OVER_CAPITAL = 1.15f

/** A paint in the title face at this size, for measuring. */
internal fun titlePaint(context: Context, sizePx: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = ResourcesCompat.getFont(context, R.font.thiamine_rpm)
    textSize = sizePx
}

/** The ink of [said] in this paint, from the glyphs' outlines, with the baseline at 0 and y growing downwards. */
private fun inkOf(paint: Paint, said: String): RectF {
    val outline = Path()
    paint.getTextPath(said, 0, said.length, 0f, 0f, outline)
    return RectF().also { outline.computeBounds(it, true) }
}

/** The characters Thiamine RPM has, read from the font file once. */
private object ThiamineLetters {
    @Volatile
    private var known: Set<Int>? = null

    fun of(context: Context): Set<Int> =
        known ?: context.resources.openRawResource(R.font.thiamine_rpm).use { fontLetters(it.readBytes()) }.also { known = it }
}
