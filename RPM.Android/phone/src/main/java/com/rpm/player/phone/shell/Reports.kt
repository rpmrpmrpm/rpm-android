package com.rpm.player.phone.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rpm.player.core.R
import com.rpm.player.core.Services
import com.rpm.player.core.diag.FeedbackShot
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.Weight
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.profile.FeedbackPage

/**
 * What the reports draw over the app: the Send feedback page while [shot] is set, and the question the first time
 * crashes are waiting to be sent, with the desktop's own words.
 */
@Composable
fun Reports(services: Services, shot: FeedbackShot?, onFeedbackClosed: () -> Unit) {
    val session by services.auth.session.collectAsStateWithLifecycle()
    val asks by services.diagnostics.asksAboutCrashes.collectAsStateWithLifecycle()

    if (shot != null) {
        FeedbackPage(shot.png, session?.email.orEmpty(), send = services.diagnostics::sendFeedback, onClose = onFeedbackClosed)
    }
    if (asks) CrashPrompt(onAnswer = services.diagnostics::answer)
}

/** "rpm closed unexpectedly": send this and later crash reports, or none. The answer is kept, and the profile's switch
 * changes it. */
@Composable
private fun CrashPrompt(onAnswer: (Boolean) -> Unit) {
    // Dismissing without an answer counts as no: a question that keeps coming back is worse than one crash unsent.
    Dialog(onDismissRequest = { onAnswer(false) }) {
        Column(
            Modifier.clip(RoundedCornerShape(22.dp)).background(Ink.ground).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(stringResource(R.string.Crash_PromptTitle), style = displayBold(17f).copy(color = Ink.white(0xFF)))
            BasicText(stringResource(R.string.Crash_PromptBody), style = display(13f).copy(color = Ink.white(0xB3)))
            GlassButton(onClick = { onAnswer(true) }, weight = Weight.Primary) { style ->
                BasicText(stringResource(R.string.Crash_PromptSend), style = style)
            }
            GlassButton(onClick = { onAnswer(false) }) { style ->
                BasicText(stringResource(R.string.Crash_PromptDontSend), style = style)
            }
        }
    }
}
