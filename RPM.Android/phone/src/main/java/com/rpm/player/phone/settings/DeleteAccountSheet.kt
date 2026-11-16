package com.rpm.player.phone.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.GlassCard
import com.rpm.player.phone.design.GlassField
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import kotlinx.coroutines.launch

/**
 * The sheet for deleting the account, as the iPhone's `DeleteAccountSheet`: it says what will be deleted and asks for
 * the account's email to be typed to confirm. A glass panel over a scrim, with Cancel and the Delete key in its header,
 * because the keyboard covers the bottom of the panel. The Delete key is red and stays dim until the field holds the
 * address; the comparison ignores case and surrounding spaces, as the accounts service does.
 *
 * The request runs while the panel is shown, so an error appears where the person is looking and they can try again.
 * On success the app signs out and the panel goes with the profile page.
 *
 * @param commit does the deleting. Returns null when done and an error sentence when it fails.
 * @param bottomRoom how much of the screen the chrome covers; the panel stays above it.
 */
@Composable
fun DeleteAccountSheet(email: String, backdrop: Backdrop, bottomRoom: Dp, commit: suspend () -> String?, onClose: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var trouble by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val matches = typed.trim().equals(email.trim(), ignoreCase = true)

    fun delete() {
        if (!matches || busy) return
        busy = true
        trouble = null
        focusManager.clearFocus()
        scope.launch {
            val failed = commit()
            busy = false
            if (failed != null) {
                trouble = failed
                focus.requestFocus()
            }
        }
    }

    // Back cancels, except while the request runs: the person is waiting for its answer.
    BackHandler { if (!busy) onClose() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // The keyboard covers the chrome, so the panel stands above whichever of the two is taller, not above both.
    val keyboard = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0xB3 / 255f))
            .clickable(remember { MutableInteractionSource() }, indication = null) { if (!busy) onClose() }
            .statusBarsPadding()
            .padding(bottom = maxOf(bottomRoom, keyboard)),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(
            backdrop,
            Modifier
                .padding(horizontal = 8.dp)
                .padding(bottom = 8.dp)
                .fillMaxWidth()
                // The panel takes its own taps, so a tap on it does not close it.
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
            radius = 18.dp,
        ) {
            Column {
                Row(
                    Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GlassButton(onClick = onClose, enabled = !busy, stretches = false, height = 38.dp, radius = 10.dp, inset = 14.dp, size = 14f) { style ->
                        BasicText(stringResource(R.string.Common_Cancel), maxLines = 1, style = style)
                    }
                    BasicText(
                        stringResource(R.string.Account_DeleteTitle),
                        Modifier.weight(1f),
                        maxLines = 1,
                        style = displayBold(17f).copy(color = Color.White, textAlign = TextAlign.Center),
                    )
                    val label = stringResource(R.string.Account_Delete)
                    GlassButton(
                        onClick = ::delete,
                        modifier = Modifier.alpha(if (matches && !busy) 1f else 0.45f).semantics { contentDescription = label },
                        enabled = matches && !busy,
                        stretches = false,
                        height = 38.dp,
                        radius = 10.dp,
                        inset = 14.dp,
                        size = 14f,
                    ) { style ->
                        if (busy) Spinner() else BasicText(label, maxLines = 1, style = style.copy(color = Ink.red))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.white(0x1A)))
                // The body scrolls under the header when the keyboard leaves little room, as on the square Titan: the
                // keys stay in reach and the field scrolls into view as it takes the keyboard.
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BasicText(stringResource(R.string.Account_DeleteBody), style = display(13f).copy(color = Ink.white(0xD9)))
                    BasicText(stringResource(R.string.Account_DeleteTypeEmail, email), style = display(13f).copy(color = Ink.white(0xD9)))
                    GlassField(
                        typed,
                        { typed = it },
                        placeholder = email,
                        modifier = Modifier.focusRequester(focus),
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { delete() }),
                    )
                    trouble?.let { BasicText(it, style = display(12f).copy(color = Ink.red)) }
                }
            }
        }
    }
}

/** The small white spinner a key shows while its request runs, as iOS's `ProgressView` at 0.7. */
@Composable
private fun Spinner() {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "turn",
    )
    Canvas(Modifier.size(14.dp).rotate(turn)) {
        drawArc(Color.White, startAngle = 0f, sweepAngle = 270f, useCenter = false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}
