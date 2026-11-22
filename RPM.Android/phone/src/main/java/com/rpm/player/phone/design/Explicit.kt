package com.rpm.player.phone.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R

/**
 * The E mark for explicit lyrics: a small light square with the letter cut out. The letter uses the background colour,
 * so it looks cut out. [edge] is the square's side; the letter and the corner follow it. 14 dp sits beside a song's
 * title in a list.
 */
@Composable
fun ExplicitMark(edge: Dp = 14.dp, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.Library_Explicit)
    Box(
        modifier
            .size(edge)
            .clip(RoundedCornerShape(edge * 3 / 14))
            .background(Ink.white(0xAA))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        BasicText("E", style = system(edge.value * 9f / 14f, FontWeight.Bold).copy(color = Ink.ground))
    }
}

/**
 * A title on one line or more, with the E after it when [explicit]. Where [explicit] is false this is the plain text.
 *
 * On one line the title gives way to the mark: a long title is cut short and the mark stays in view. On more lines the
 * mark is part of the text, after the last word, joined to it by a no-break space, so it wraps with that word and never
 * stands alone at the edge of the block. A title too long for its lines is cut short with the mark beside it instead, so
 * the mark is never cut off with the text.
 *
 * @param edge the mark's side; by default a little smaller than the text, so it sits inside the line.
 */
@Composable
fun MarkedTitle(
    text: String,
    explicit: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    edge: Dp = (style.fontSize.value * 0.88f).coerceAtLeast(10f).dp,
) {
    if (!explicit) {
        BasicText(text, modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = style)
        return
    }
    // Whether the title with the mark in it did not fit its lines. Then the mark stands beside the cut title.
    var overflowed by remember(text, maxLines) { mutableStateOf(false) }
    if (maxLines == 1 || overflowed) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(text, Modifier.weight(1f, fill = false), maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = style)
            ExplicitMark(edge)
        }
        return
    }
    val side = with(LocalDensity.current) { edge.toSp() }
    val marked = remember(text) {
        buildAnnotatedString {
            append(text)
            append(NO_BREAK_SPACE)
            appendInlineContent(MARK_ID, "E")
        }
    }
    val inline = remember(side, edge) {
        mapOf(MARK_ID to InlineTextContent(Placeholder(side, side, PlaceholderVerticalAlign.TextCenter)) { ExplicitMark(edge) })
    }
    BasicText(
        marked,
        modifier,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Clip,
        inlineContent = inline,
        onTextLayout = { if (it.hasVisualOverflow) overflowed = true },
    )
}

/** The id of the mark inside a title's text. */
private const val MARK_ID = "explicit"

/** Keeps the mark on the line of the title's last word. */
private const val NO_BREAK_SPACE = " "
