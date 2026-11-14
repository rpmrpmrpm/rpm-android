package com.rpm.player.phone.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.GlassCard
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.Weight
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.ui.RecordSpinner
import kotlinx.coroutines.launch

/** The longest note the server takes, in characters. */
private const val LONGEST_NOTE = 2_000

/**
 * A card over the page where the reader says why something should be looked at, and sends the report. After a report is
 * sent, the card says so and offers one key to close it. It is the iPhone's `ReportSheet` (RPMUI/ReportSheet.swift).
 *
 * It is drawn over the page like [WriteReviewSheet], and not as a system dialog, so it has the app's own look and a box
 * with room for a few lines. A tap on the dim around it, or Back, closes it, except while the report is on its way.
 *
 * @param prompt the sentence over the box: why a person reads the report.
 * @param onSend sends the report with the reader's note. Null when it was sent, or why it failed.
 */
@Composable
fun ReportSheet(prompt: String, backdrop: Backdrop, bottomRoom: Dp, onSend: suspend (String) -> Said?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<Said?>(null) }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { opened = true }
    val shown by animateFloatAsState(if (opened) 1f else 0f, tween(180), label = "report sheet")
    BackHandler { if (!busy) onClose() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .alpha(shown)
            .background(Color.Black.copy(alpha = 0xB3 / 255f))
            .clickable(remember { MutableInteractionSource() }, indication = null) { if (!busy) onClose() }
            .statusBarsPadding()
            .padding(bottom = bottomRoom)
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(
            backdrop,
            Modifier
                .width(minOf(330.dp, maxWidth - 32.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
            radius = 18.dp,
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BasicText(stringResource(R.string.Album_ReportTitle), style = displayBold(19f).copy(color = Color.White))
                if (sent) {
                    BasicText(stringResource(R.string.Album_ReportSent), style = display(14f).copy(color = Ink.white(0xCC)))
                    GlassButton(onClick = onClose, weight = Weight.Primary, height = 42.dp, radius = 10.dp, size = 14f) { style ->
                        BasicText(stringResource(R.string.Common_OK), style = style)
                    }
                } else {
                    BasicText(prompt, style = display(13f).copy(color = Ink.white(0xCC)))
                    val well = RoundedCornerShape(10.dp)
                    BasicTextField(
                        note,
                        onValueChange = { note = it.take(LONGEST_NOTE) },
                        textStyle = display(15f).copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp + 16.dp)
                            .clip(well)
                            .background(Color.Black.copy(alpha = 0x22 / 255f))
                            .border(1.dp, Ink.cardBorder, well)
                            .padding(13.dp),
                        decorationBox = { field ->
                            Box {
                                if (note.isEmpty()) BasicText(stringResource(R.string.Album_ReportPlaceholder), style = display(15f).copy(color = Ink.white(0x66)))
                                field()
                            }
                        },
                    )
                    said?.let { BasicText(said(it), style = display(12f).copy(color = Color(0xFFFFB4A0))) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton(onClick = onClose, modifier = Modifier.weight(1f), enabled = !busy, height = 42.dp, radius = 10.dp, size = 14f) { style ->
                            BasicText(stringResource(R.string.Common_Cancel), style = style)
                        }
                        GlassButton(
                            onClick = {
                                if (busy) return@GlassButton
                                busy = true
                                said = null
                                focus.clearFocus()
                                scope.launch {
                                    val trouble = onSend(note.trim())
                                    busy = false
                                    if (trouble == null) sent = true else said = trouble
                                }
                            },
                            modifier = Modifier.weight(1f),
                            weight = Weight.Primary,
                            enabled = !busy,
                            height = 42.dp,
                            radius = 10.dp,
                            size = 14f,
                        ) { style ->
                            if (busy) RecordSpinner(18.dp) else BasicText(stringResource(R.string.Album_ReportSubmit), style = style)
                        }
                    }
                }
            }
        }
    }
}
