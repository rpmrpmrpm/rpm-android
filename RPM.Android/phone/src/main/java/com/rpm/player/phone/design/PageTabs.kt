package com.rpm.player.phone.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The row of tabs under a page's header that switches between the parts of the page. It matches the iPhone's
 * `PageTabs`. The library's views and the settings page's sections both use it.
 *
 * It is the tab capsule at a smaller size. Only the chosen tab shows its name beside its mark, so that five tabs fit
 * in every language. The pill slides between the tabs with the same spring as the tab bar, the chosen key opens to
 * show its name as it fades in, and the one left closes as its name fades out.
 *
 * @param motion where the row stands while it moves. A page that draws a new row for each tab passes one that it
 *   keeps, so the new row starts where the old one was ([PageTabsMotion]). Null: the row keeps its own.
 * @param label the string of a tab's name.
 * @param mark draws a tab's mark in the given colour.
 */
@Composable
internal fun <T> PageTabs(
    choices: List<T>,
    chosen: T,
    onPick: (T) -> Unit,
    backdrop: Backdrop,
    label: (T) -> Int,
    motion: PageTabsMotion? = null,
    mark: @Composable (T, Color) -> Unit,
) {
    val at = choices.indexOf(chosen).coerceAtLeast(0)
    val moving = motion ?: remember { PageTabsMotion(choices.size, at) }
    LaunchedEffect(at) { moving.go(at) }
    val capsule = RoundedCornerShape(percent = 50)
    val unlit = 52.dp
    // A tick under the thumb as the tab changes (`Haptics.pick()` on iPhone).
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
            .fillMaxWidth()
            .chromeGlass(backdrop, capsule)
            .padding(3.dp),
    ) {
        val lit = maxWidth - unlit * (choices.size - 1)
        Box(Modifier.offset(x = unlit * moving.pill.value).width(lit).height(32.dp).clip(capsule).background(Ink.white(0x24)))
        Row {
            choices.forEachIndexed { index, choice ->
                val isChosen = index == at
                // How far this key is open: 1 for the chosen tab, 0 for the others, and between while they change.
                val open = moving.open[index].value
                Row(
                    Modifier
                        .width(unlit + (lit - unlit) * open)
                        .height(32.dp)
                        .clipToBounds()
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) {
                            if (!isChosen) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onPick(choice)
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    mark(choice, lerp(Ink.white(0x99), Color.White, open))
                    if (open > 0.02f) {
                        Spacer(Modifier.width(6.dp * open))
                        BasicText(
                            stringResource(label(choice)),
                            Modifier.graphicsLayer { alpha = open * open },
                            maxLines = 1,
                            softWrap = false,
                            style = system(12f, FontWeight.SemiBold).copy(color = Color.White),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Where a row of tabs stands while it moves: the pill's place, in keys from the left, and how far each key is open.
 * A page that draws a new row for each tab keeps one of these for all of them, because a row that started fresh with
 * each tab would appear already finished.
 */
internal class PageTabsMotion(count: Int, at: Int) {
    val pill = Animatable(at.toFloat())
    val open = List(count) { Animatable(if (it == at) 1f else 0f) }

    /** Moves the row to the tab at [to]: the pill slides, that key opens, the others close. A later move takes over at once. */
    suspend fun go(to: Int) = coroutineScope {
        launch { pill.animateTo(to.toFloat(), MOVE) }
        open.forEachIndexed { index, key ->
            launch { key.animateTo(if (index == to) 1f else 0f, MOVE) }
        }
    }

    private companion object {
        /** The tab bar's spring. */
        val MOVE = spring<Float>(dampingRatio = 0.8f, stiffness = 322f)
    }
}
