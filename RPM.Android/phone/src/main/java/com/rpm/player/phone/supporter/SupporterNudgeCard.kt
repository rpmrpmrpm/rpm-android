package com.rpm.player.phone.supporter

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.core.Services
import com.rpm.player.phone.PhoneSettings
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.GlassCard
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.Weight
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** How long the app must be up with nothing over it before the pop-up shows, in milliseconds. */
private const val NUDGE_SETTLES_MS = 2_500L

/**
 * How long the pop-up waits for the account's plan once it is due, in milliseconds. A plan that is still not known after
 * this (a failed read, no network) means no pop-up on this launch.
 */
private const val NUDGE_WAITS_FOR_PLAN_MS = 15_000L

/**
 * Whether the pop-up has had its turn in this process: shown, or passed over. Held outside the composition, so a shell
 * made again in the same process (another collection, a new activity) does not ask a second time.
 */
private var nudgeSettled = false

/**
 * Decides once per process whether the supporter pop-up shows, and calls [onDue] when it does. It counts the launch as
 * soon as the shell is up ([PhoneSettings.countLaunch]). On a due launch ([SupporterNudge.onLaunch]) it waits until the
 * app has been [idle] for a moment, then for the account's plan, and asks [SupporterNudge.due].
 *
 * @param idle whether the app is up with nothing over it: no welcome, wait cover, player, picker or sheet. When it turns
 * false, the wait starts again the next time it is true.
 */
@Composable
fun SupporterNudgeWatch(services: Services, settings: PhoneSettings, idle: Boolean, onDue: () -> Unit) {
    LaunchedEffect(Unit) { settings.countLaunch() }
    LaunchedEffect(idle) {
        if (!idle || nudgeSettled) return@LaunchedEffect
        val launch = settings.countLaunch()
        if (!SupporterNudge.onLaunch(launch)) {
            nudgeSettled = true
            return@LaunchedEffect
        }
        delay(NUDGE_SETTLES_MS)
        // Null while the plan is not read, and after a read that failed. Null is "not known", never "free".
        val quota = withTimeoutOrNull(NUDGE_WAITS_FOR_PLAN_MS) { services.ownFace.quota.first { it != null } }
        nudgeSettled = true
        if (SupporterNudge.due(launch, quota?.wearsMark)) onDue()
    }
}

/**
 * The supporter pop-up: a small glass card in the middle of the dimmed screen that asks a listener who does not pay to
 * consider becoming a supporter. It is the iPhone's `SupporterNudgeCard` (RPMUI/SupporterNudgeCard.swift), drawn as
 * [com.rpm.player.phone.library.ReportSheet] is, in the app's own look and not as a system dialog.
 *
 * A tap on the dim around the card, Back, or Not now closes it ([onClose]).
 *
 * @param onSupport the key Become a supporter: closes the card and opens the subscription sheet ([SupporterSheet]).
 */
@Composable
fun SupporterNudgeCard(backdrop: Backdrop, onSupport: () -> Unit, onClose: () -> Unit) {
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { opened = true }
    val shown by animateFloatAsState(if (opened) 1f else 0f, tween(180), label = "supporter nudge")
    BackHandler(onBack = onClose)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .alpha(shown)
            .background(Color.Black.copy(alpha = 0xB3 / 255f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(
            backdrop,
            Modifier
                .width(minOf(330.dp, maxWidth - 32.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
            radius = 18.dp,
        ) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    stringResource(R.string.Supporter_NudgeTitle),
                    Modifier.fillMaxWidth(),
                    style = displayBold(19f).copy(color = Color.White, textAlign = TextAlign.Center),
                )
                BasicText(
                    stringResource(R.string.Supporter_NudgeBody),
                    Modifier.fillMaxWidth(),
                    style = display(14f).copy(color = Ink.white(0xCC), textAlign = TextAlign.Center),
                )
                // One key under the other: in some languages Become a supporter is too long to share a row.
                GlassButton(onClick = onSupport, weight = Weight.Primary) { style ->
                    BasicText(stringResource(R.string.Cloud_BecomeSupporter), style = style.copy(textAlign = TextAlign.Center))
                }
                GlassButton(onClick = onClose) { style ->
                    BasicText(stringResource(R.string.Supporter_Later), style = style.copy(textAlign = TextAlign.Center))
                }
            }
        }
    }
}
