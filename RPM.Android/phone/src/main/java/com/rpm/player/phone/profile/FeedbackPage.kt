package com.rpm.player.phone.profile

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rpm.player.core.R
import com.rpm.player.core.diag.FeedbackChoices
import com.rpm.player.core.diag.FeedbackDraft
import com.rpm.player.core.diag.FeedbackSent
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.Weight
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.kicker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Send feedback: something that went wrong, or something that could be better, with a screenshot of the screen it was
 * opened over and the app's recent log. The same fields and rules as the desktop window and the iPhone's sheet.
 *
 * The message is the report, so an empty box is refused here. A failed send says so and keeps what was written. A
 * full-screen dialog like [AboutPage].
 *
 * @param screenshot the screen as it was before this opened, or null when it could not be read.
 * @param email the signed-in account's address, where replies go; empty when signed out, and a box asks for one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackPage(screenshot: ByteArray?, email: String, send: suspend (FeedbackDraft) -> FeedbackSent, onClose: () -> Unit) {
    var kind by remember { mutableStateOf(FeedbackChoices.kinds[0].value) }
    var area by remember { mutableStateOf(FeedbackChoices.phoneAreas[0].value) }
    var message by remember { mutableStateOf("") }
    var includesShot by remember { mutableStateOf(true) }
    var replyTo by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Int?>(null) }
    val picture = remember(screenshot) { screenshot?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    val scope = rememberCoroutineScope()

    fun press() {
        val draft = FeedbackDraft(kind, area, message.trim(), screenshot.takeIf { includesShot }, replyTo.trim().ifEmpty { null })
        if (!draft.isSendable) { note = R.string.Feedback_NeedWords; return }
        if (sending) return
        sending = true
        scope.launch {
            when (send(draft)) {
                FeedbackSent.Sent -> {
                    // The thanks stays on screen long enough to read, then the page closes itself.
                    note = R.string.Feedback_Sent
                    delay(1_500)
                    onClose()
                }
                FeedbackSent.Failed -> note = R.string.Feedback_Failed
                FeedbackSent.Busy -> Unit
            }
            sending = false
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Ink.ground)) {
            Row(
                Modifier.fillMaxWidth().background(Ink.white(0x0A)).statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassButton(onClick = onClose, stretches = false, height = 34.dp, radius = 9.dp, inset = 16.dp, size = 13f) { style ->
                    BasicText(stringResource(R.string.Common_Cancel), style = style)
                }
                Spacer(Modifier.weight(1f))
                BasicText(stringResource(R.string.Feedback_Title), style = kicker())
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Words(stringResource(R.string.Feedback_Body), 13f, 0xB3)

                BasicText(stringResource(R.string.Feedback_KindLabel), style = kicker())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeedbackChoices.kinds.forEach { choice -> Chip(stringResource(choice.label), choice.value == kind) { kind = choice.value } }
                }
                BasicText(stringResource(R.string.Feedback_AreaLabel), style = kicker())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeedbackChoices.phoneAreas.forEach { choice -> Chip(stringResource(choice.label), choice.value == area) { area = choice.value } }
                }

                Field(message, { message = it }, stringResource(FeedbackChoices.placeholder(kind)), minHeight = 130, keyboard = KeyboardOptions.Default)

                if (picture != null) {
                    Row(
                        Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { includesShot = !includesShot },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Words(stringResource(R.string.Feedback_Screenshot), 13f, 0xB3, Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        Switch(includesShot)
                    }
                    Image(picture, null, Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp)).alpha(if (includesShot) 1f else 0.3f), contentScale = ContentScale.Fit)
                }

                Words(stringResource(R.string.Feedback_Sends), 11f, 0x88)
                if (email.isNotEmpty()) {
                    Words(stringResource(R.string.Feedback_RepliesTo, email), 11f, 0x88)
                } else {
                    Field(replyTo, { replyTo = it }, stringResource(R.string.Feedback_ReplyEmail), minHeight = 0, keyboard = KeyboardOptions(keyboardType = KeyboardType.Email))
                }
                note?.let { Words(stringResource(it), 13f, 0xE0) }

                GlassButton(onClick = ::press, weight = Weight.Primary, enabled = !sending) { style ->
                    BasicText(stringResource(R.string.Feedback_Send), style = style)
                }
            }
        }
    }
}

@Composable
private fun Words(text: String, size: Float, white: Int, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = display(size).copy(color = Ink.white(white)))
}

/** One choice of a kind or an area: lit while chosen. */
@Composable
private fun Chip(text: String, chosen: Boolean, onPick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (chosen) Ink.white(0x88) else Ink.white(0x14))
            .border(1.dp, Ink.white(0x1A), shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        BasicText(text, style = display(13f).copy(color = if (chosen) Color.Black else Ink.white(0xF0)))
    }
}

/** A text box in the well the record pages' review box uses. */
@Composable
private fun Field(text: String, onChange: (String) -> Unit, placeholder: String, minHeight: Int, keyboard: KeyboardOptions) {
    val well = RoundedCornerShape(10.dp)
    BasicTextField(
        text,
        onValueChange = onChange,
        textStyle = display(15f).copy(color = Color.White),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = keyboard,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight.dp)
            .clip(well)
            .background(Color.Black.copy(alpha = 0x22 / 255f))
            .border(1.dp, Ink.white(0x1A), well)
            .padding(13.dp),
        decorationBox = { field ->
            Box {
                if (text.isEmpty()) BasicText(placeholder, style = display(15f).copy(color = Ink.white(0x66)))
                field()
            }
        },
    )
}
