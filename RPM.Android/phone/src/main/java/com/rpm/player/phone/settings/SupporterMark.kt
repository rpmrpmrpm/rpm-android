package com.rpm.player.phone.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.system

/**
 * The supporter's mark: a heart and a word, in gold, as the iPhone's `SupporterMark` and the desktop's
 * `SupporterBadge`. It has no "not a supporter" state: the caller shows it or does not.
 *
 * @param word the word beside the heart. Empty draws the heart alone, for a line with no room for the word.
 */
@Composable
fun SupporterMark(modifier: Modifier = Modifier, word: String = stringResource(R.string.Rpm_Profile_PlanSupporter)) {
    val label = stringResource(R.string.Rpm_Profile_PlanSupporter)
    Row(
        modifier
            .semantics { contentDescription = label }
            .drawBehind {
                val corner = CornerRadius(9.dp.toPx())
                // Both run diagonally, from the bottom leading corner to the top trailing one: stamped metal catches
                // the light on a diagonal.
                val start = Offset(0f, size.height)
                val end = Offset(size.width, 0f)
                drawRoundRect(Brush.linearGradient(*SupporterMetal.face, start = start, end = end), cornerRadius = corner)
                val edge = 1.dp.toPx()
                drawRoundRect(
                    Brush.linearGradient(*SupporterMetal.rim, start = start, end = end),
                    topLeft = Offset(edge / 2, edge / 2),
                    size = size.copy(width = size.width - edge, height = size.height - edge),
                    cornerRadius = CornerRadius(corner.x - edge / 2),
                    style = Stroke(edge),
                )
            }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(Glyph.heart, style = icon(9f).copy(color = SupporterMetal.ink))
        if (word.isNotEmpty()) BasicText(word, style = system(10f).copy(color = SupporterMetal.ink))
    }
}

/** The supporter's gold, the iPhone's `SupporterMetal` (RPMSharedUI), value for value. */
private object SupporterMetal {
    private fun gold(alpha: Int, r: Int, g: Int, b: Int) = Color(r, g, b, alpha)

    /** The lettering and the heart: the lightest gold, not white, so the mark looks stamped from the same piece. */
    val ink = gold(0xFF, 0xF2, 0xDC, 0x9A)

    /** The face: bronze in the corner away from the light, then gold, a narrow band of champagne, and gold again. */
    val face = arrayOf(
        0f to gold(0x3A, 0x9A, 0x6A, 0x18),
        0.30f to gold(0x46, 0xEF, 0xAF, 0x12),
        0.42f to gold(0x72, 0xEB, 0xD0, 0x7A),
        0.52f to gold(0x46, 0xEF, 0xAF, 0x12),
        1f to gold(0x38, 0x9A, 0x6A, 0x18),
    )

    /** The rim, one step brighter than the face, so the pill does not look flat. */
    val rim = arrayOf(
        0f to gold(0x66, 0xB9, 0x8B, 0x1E),
        0.45f to gold(0xCC, 0xEB, 0xD0, 0x7A),
        1f to gold(0x66, 0xB9, 0x8B, 0x1E),
    )
}
