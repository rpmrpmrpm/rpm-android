package com.rpm.player.tv.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rpm.player.core.R
import com.rpm.player.core.diag.FeedbackChoices
import com.rpm.player.core.diag.FeedbackDraft
import com.rpm.player.core.diag.FeedbackSent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Send feedback on the TV: the phone's fields as rows the remote moves through. OK on the kind or the area moves it to
 * its next value, as on a settings row; OK on the message opens the keyboard. Back closes it without sending.
 *
 * The screenshot is the one taken when the settings opened, so it shows the screen the tester came from. The TV app is
 * always signed in, so replies go to the account's address and no box asks for one.
 */
@Composable
fun FeedbackScreen(screenshot: ByteArray?, email: String, send: suspend (FeedbackDraft) -> FeedbackSent, onClose: () -> Unit) {
    var kind by remember { mutableStateOf(FeedbackChoices.kinds[0].value) }
    var area by remember { mutableStateOf(FeedbackChoices.tvAreas[0].value) }
    var message by remember { mutableStateOf("") }
    var includesShot by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Int?>(null) }
    val picture = remember(screenshot) { screenshot?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }

    fun press() {
        val draft = FeedbackDraft(kind, area, message.trim(), screenshot.takeIf { includesShot }, replyTo = null)
        if (!draft.isSendable) { note = R.string.Feedback_NeedWords; return }
        if (sending) return
        sending = true
        scope.launch {
            when (send(draft)) {
                FeedbackSent.Sent -> {
                    // The thanks stays on screen long enough to read, then the screen closes itself.
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

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onClose)
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        Box(Modifier.fillMaxSize().background(Color(0xF20E1520)), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.width(900.s).verticalScroll(rememberScrollState()).padding(vertical = 60.s),
                verticalArrangement = Arrangement.spacedBy(10.s),
            ) {
                Text(stringResource(R.string.Feedback_Title), style = ui(32.ts, FontWeight.SemiBold), color = Ink.Words)
                Text(stringResource(R.string.Feedback_Body), style = ui(18.ts), color = Ink.Artist)
                Spacer(Modifier.height(10.s))

                ChoiceRow(stringResource(R.string.Feedback_KindLabel), label(kind, FeedbackChoices.kinds), Modifier.focusRequester(first)) {
                    kind = next(kind, FeedbackChoices.kinds)
                }
                ChoiceRow(stringResource(R.string.Feedback_AreaLabel), label(area, FeedbackChoices.tvAreas)) {
                    area = next(area, FeedbackChoices.tvAreas)
                }
                MessageField(message, { message = it }, stringResource(FeedbackChoices.placeholder(kind)))
                if (picture != null) {
                    ChoiceRow(stringResource(R.string.Feedback_Screenshot), stringResource(if (includesShot) R.string.Viz_SettingOn else R.string.Viz_SettingOff)) {
                        includesShot = !includesShot
                    }
                    Image(picture, null, Modifier.heightIn(max = 220.s).alpha(if (includesShot) 1f else 0.3f), contentScale = ContentScale.Fit)
                }

                Text(stringResource(R.string.Feedback_Sends), style = ui(16.ts), color = Ink.Artist)
                if (email.isNotEmpty()) Text(stringResource(R.string.Feedback_RepliesTo, email), style = ui(16.ts), color = Ink.Artist)
                note?.let { Text(stringResource(it), style = ui(20.ts, FontWeight.SemiBold), color = Ink.Words) }

                Row(Modifier.padding(top = 10.s), horizontalArrangement = Arrangement.spacedBy(16.s)) {
                    Key(stringResource(R.string.Feedback_Send), ::press)
                    Key(stringResource(R.string.Common_Cancel), onClose)
                }
            }
        }
    }
}

/** The name of the chosen value. */
@Composable
private fun label(value: String, choices: List<FeedbackChoices.Choice>): String =
    stringResource((choices.firstOrNull { it.value == value } ?: choices[0]).label)

private fun next(value: String, choices: List<FeedbackChoices.Choice>): String {
    val at = choices.indexOfFirst { it.value == value }.coerceAtLeast(0)
    return choices[(at + 1) % choices.size].value
}

/** A row as in the settings: its name on the left and its value on the right, lit under the focus. */
@Composable
private fun ChoiceRow(name: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var lit by remember { mutableStateOf(false) }
    DeskButton(
        onClick = onClick,
        corner = 7.s,
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .onFocusChanged { lit = it.isFocused }
            .fillMaxWidth()
            .height(60.s)
            .background(if (lit) LIT else Color.Transparent, RoundedCornerShape(7.s))
            .padding(horizontal = 14.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = ui(22.ts, FontWeight.SemiBold), color = Ink.Words, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(40.s))
            Text(value, style = ui(22.ts), color = Color(0xCCFFFFFF), maxLines = 1)
        }
    }
}

/** The message, in a well like the radio's search box. */
@Composable
private fun MessageField(text: String, onText: (String) -> Unit, placeholder: String) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(7.s)
    BasicTextField(
        value = text,
        onValueChange = onText,
        textStyle = ui(22.ts).copy(color = Ink.Words),
        cursorBrush = SolidColor(Color.White),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.s)
            .deskFocus(7.s)
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) LIT else Color(0x14FFFFFF), shape)
            .border(1.px, Color(0x33FFFFFF), shape)
            .padding(14.s),
        decorationBox = { field ->
            Box {
                if (text.isEmpty()) Text(placeholder, style = ui(22.ts), color = Color(0x66FFFFFF))
                field()
            }
        },
    )
}

/** A boxed key, as the side menu's. */
@Composable
private fun Key(title: String, onClick: () -> Unit) {
    var lit by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(7.s)
    DeskButton(
        onClick = onClick,
        corner = 7.s,
        modifier = Modifier
            .onFocusChanged { lit = it.isFocused }
            .background(if (lit) LIT else Color(0x14FFFFFF), shape)
            .border(1.px, if (lit) Color(0xE6FFFFFF) else Color(0xB3FFFFFF), shape)
            .padding(horizontal = 28.s, vertical = 14.s),
    ) {
        Text(title, style = ui(22.ts, FontWeight.SemiBold), color = Ink.Words, maxLines = 1)
    }
}

private val LIT = Color(0x33FFFFFF)

/** "rpm closed unexpectedly": send this and later crash reports, or none, with the desktop's own words. Back counts as
 * no: a question that keeps coming back is worse than one crash unsent. */
@Composable
fun CrashPrompt(onAnswer: (Boolean) -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = { onAnswer(false) }) {
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        Column(
            Modifier.width(760.s).background(Color(0xF20E1520), RoundedCornerShape(14.s)).padding(36.s),
            verticalArrangement = Arrangement.spacedBy(16.s),
        ) {
            Text(stringResource(R.string.Crash_PromptTitle), style = ui(28.ts, FontWeight.SemiBold), color = Ink.Words)
            Text(stringResource(R.string.Crash_PromptBody), style = ui(18.ts), color = Ink.Artist)
            Row(horizontalArrangement = Arrangement.spacedBy(16.s)) {
                Box(Modifier.focusRequester(first)) { Key(stringResource(R.string.Crash_PromptSend)) { onAnswer(true) } }
                Key(stringResource(R.string.Crash_PromptDontSend)) { onAnswer(false) }
            }
        }
    }
}

/** The activity behind a composition's context, for the screenshot. */
tailrec fun android.content.Context.activity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.activity()
    else -> null
}
