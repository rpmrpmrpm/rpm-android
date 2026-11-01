package com.rpm.player.phone.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.design.system

/**
 * What home shows a collection too small for it: what home is, where to add music, and how many records it has of the
 * [want] it needs. It is the iPhone's `HomeLock`. The bar shows the amount at a glance; the line under it gives the
 * numbers. There is no button: music is added in rpm on the computer, not on the phone.
 *
 * @param lent whether the collection belongs to someone else. Only the owner can add music, so a borrowed collection
 * does not say how.
 */
@Composable
fun HomeLock(have: Int, want: Int, lent: Boolean, modifier: Modifier = Modifier) {
    val fraction = if (want <= 0) 1f else (have.toFloat() / want).coerceIn(0f, 1f)
    Column(
        modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(stringResource(R.string.Home_Heading), style = displayBold(26f).copy(color = Color.White))
        BasicText(
            stringResource(lockLine(have, lent)),
            Modifier.widthIn(max = 320.dp),
            style = system(14f).copy(color = Ink.secondary, textAlign = TextAlign.Center),
        )
        // An empty collection has nothing to measure. Its line says how to fill it.
        if (have > 0) Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(220.dp).height(4.dp).clip(RoundedCornerShape(percent = 50)).background(Ink.glassWash)) {
                Box(Modifier.width(maxOf(2.dp, 220.dp * fraction)).fillMaxHeight().clip(RoundedCornerShape(percent = 50)).background(Ink.white(0xB8)))
            }
            BasicText(stringResource(R.string.HomeLock_Progress, have, want), style = system(12f).copy(color = Ink.secondary))
        }
    }
}

/**
 * The line under the heading. An empty collection says how to fill it; a small one says what home is and, for the
 * person's own collection, where to add music. Only the owner can add music, so a borrowed collection does not say how.
 */
internal fun lockLine(have: Int, lent: Boolean): Int = when {
    have == 0 && lent -> R.string.Rpm_Share_EmptyShelf
    have == 0 -> R.string.Rpm_Library_Empty
    lent -> R.string.HomeLock_Body
    else -> R.string.Rpm_HomeLock_Body
}
