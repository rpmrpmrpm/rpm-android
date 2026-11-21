package com.rpm.player.phone.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
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
 * A title on one line or more, with the E after it when [explicit]. The title gives way to the mark: a long title is cut
 * short and the mark stays in view. Where [explicit] is false this is the plain text.
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
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(text, Modifier.weight(1f, fill = false), maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = style)
        ExplicitMark(edge)
    }
}
