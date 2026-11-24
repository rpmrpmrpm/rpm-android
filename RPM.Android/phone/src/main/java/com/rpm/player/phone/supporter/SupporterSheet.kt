package com.rpm.player.phone.supporter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rpm.player.core.R
import com.rpm.player.core.cloud.PurchaseOutcome
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.system
import com.rpm.player.phone.stage.findActivity

/**
 * The sheet for becoming a supporter, rising from the bottom edge as [com.rpm.player.phone.downloads.ConfirmSheet]
 * does: what supporting pays for and what supporters get, one row per plan with Google Play's price, how renewal and
 * cancelling work, and Cancel. A row opens Google Play's own purchase screen. The sheet closes when the accounts
 * service has confirmed the purchase.
 *
 * @param accountId the signed-in account's id (its Cognito `sub`), put on the purchase.
 */
@Composable
fun SupporterSheet(billing: SupporterBilling, accountId: String, onClose: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    // Null while Play is asked, an empty list when it could not be asked.
    var plans by remember { mutableStateOf<List<PlanOffer>?>(null) }
    val outcome by billing.outcome.collectAsStateWithLifecycle()
    val shown = remember { MutableTransitionState(false) }

    LaunchedEffect(Unit) {
        shown.targetState = true
        billing.forgetOutcome()
        plans = billing.plans().orEmpty()
    }
    LaunchedEffect(outcome) { if (outcome == PurchaseOutcome.CONFIRMED) onClose() }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(visibleState = shown, enter = slideInVertically(tween(300)) { it }) {
                Column(
                    Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 8.dp)
                        .padding(bottom = 8.dp)
                        // A tap on the sheet itself is not a tap outside it.
                        .clickable(remember { MutableInteractionSource() }, indication = null) {},
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.fillMaxWidth().clip(SHAPE).background(FACE)) {
                        Header(outcome)
                        val offered = plans
                        if (offered != null && offered.isEmpty()) {
                            Line(stringResource(R.string.Supporter_StoreUnavailable), Ink.gold)
                        }
                        offered?.forEach { plan ->
                            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.white(0x26)))
                            Choice(stringResource(planLabel(plan.basePlanId), plan.formattedPrice), system(20f).copy(color = Ink.cyan)) {
                                activity?.let { billing.buy(it, plan, accountId) }
                            }
                        }
                        if (!offered.isNullOrEmpty()) {
                            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.white(0x26)))
                            BasicText(
                                stringResource(R.string.Supporter_RenewsGooglePlay),
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                style = system(12f).copy(color = Ink.white(0x8C), textAlign = TextAlign.Center),
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().clip(SHAPE).background(FACE_CANCEL)) {
                        Choice(stringResource(R.string.Common_Cancel), system(20f, FontWeight.SemiBold).copy(color = Color.White), onClose)
                    }
                }
            }
        }
    }
}

/** The title, why to support, what supporters get, and the line about the last purchase. */
@Composable
private fun Header(outcome: PurchaseOutcome?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(stringResource(R.string.Cloud_BecomeSupporter), style = display(24f).copy(color = Color.White))
        BasicText(stringResource(R.string.Supporter_StoreBody), style = display(14f).copy(color = Ink.white(0xCC)))
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (perk in SupporterPerk.ALL) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(Glyph.check, Modifier.width(22.dp), style = icon(12f).copy(color = Ink.gold))
                    BasicText(stringResource(perk.words), style = display(14f).copy(color = Color.White))
                }
            }
        }
        val said = when (outcome) {
            PurchaseOutcome.PENDING -> R.string.Supporter_PurchasePending
            PurchaseOutcome.OTHER_ACCOUNT -> R.string.Supporter_OtherAccount
            PurchaseOutcome.FAILED -> R.string.Supporter_PurchaseFailed
            PurchaseOutcome.CONFIRMED, null -> null
        }
        if (said != null) BasicText(stringResource(said), Modifier.padding(top = 4.dp), style = display(14f).copy(color = Ink.gold))
    }
}

@Composable
private fun Line(text: String, color: Color) {
    BasicText(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), style = system(14f).copy(color = color, textAlign = TextAlign.Center))
}

@Composable
private fun Choice(label: String, style: androidx.compose.ui.text.TextStyle, onClick: () -> Unit) {
    val presses = remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .height(57.dp)
            .background(if (pressed) Ink.white(0x1A) else Color.Transparent)
            .clickable(presses, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = style)
    }
}

private fun planLabel(basePlanId: String): Int = when (basePlanId) {
    "week" -> R.string.Supporter_PlanWeek
    "year" -> R.string.Supporter_PlanYear
    else -> R.string.Supporter_PlanMonth
}

private val SHAPE = RoundedCornerShape(14.dp)

/** The sheet's dark faces, as [com.rpm.player.phone.downloads.ConfirmSheet] draws them. */
private val FACE = Color(0xF21C1C1E)
private val FACE_CANCEL = Color(0xFF2C2C2E)
