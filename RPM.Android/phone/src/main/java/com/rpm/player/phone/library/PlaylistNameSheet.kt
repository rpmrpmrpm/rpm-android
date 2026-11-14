package com.rpm.player.phone.library

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
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

/**
 * The card over a playlist page where the playlist's name is changed: Edit playlist in the page's menu. It matches the
 * iPhone's `PlaylistNameSheet` (RPMUI/PlaylistNameSheet.swift), and is drawn over the page as the review sheet
 * ([WriteReviewSheet]) is, with the app's own look: a dimmed screen, a glass card 330 dp wide, the name in a well,
 * Cancel and Save under it.
 *
 * The box starts with the playlist's name, the cursor after it, and the keyboard up. Save is offered only while the
 * box holds more than spaces. While the store answers, Save turns, and nothing else can be pressed; a failure stays on
 * the card in a line over the keys, and a name that was written closes it.
 *
 * @param name the name the box starts with: the playlist's name now.
 * @param backdrop what the card's glass blurs: the page under it.
 * @param bottomRoom how much of the foot of the screen the chrome covers. The card is centred above it.
 * @param onSave writes the new name. It answers why it failed, or null when it is done.
 */
@Composable
fun PlaylistNameSheet(
    name: String,
    backdrop: Backdrop,
    bottomRoom: Dp,
    onSave: suspend (String) -> String?,
    onClose: () -> Unit,
) {
    var typed by remember { mutableStateOf(TextFieldValue(name, TextRange(name.length))) }
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    val blank = typed.text.isBlank()
    val focus = LocalFocusManager.current
    val typing = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    BackHandler { if (!busy) onClose() }
    LaunchedEffect(Unit) { typing.requestFocus() }

    fun save() {
        if (busy || blank) return
        busy = true
        said = null
        focus.clearFocus()
        scope.launch {
            val trouble = onSave(typed.text)
            busy = false
            if (trouble == null) onClose() else said = trouble
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
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
                // The card takes its own taps, so a tap on it does not reach the shade and close it.
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
            radius = 18.dp,
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BasicText(stringResource(R.string.Rpm_PlaylistMenu_Edit), style = displayBold(19f).copy(color = Color.White))
                val well = RoundedCornerShape(10.dp)
                BasicTextField(
                    typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    textStyle = display(16f).copy(color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(well)
                        .background(Color.Black.copy(alpha = 0x22 / 255f))
                        .border(1.dp, Ink.cardBorder, well)
                        .focusRequester(typing),
                    decorationBox = { field ->
                        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                            if (typed.text.isEmpty()) BasicText(stringResource(R.string.Rpm_NewPlaylist_Name), style = display(16f).copy(color = Ink.white(0x66)))
                            field()
                        }
                    },
                )
                said?.let { BasicText(it, style = display(12f).copy(color = Color(0xFFFFB4A0))) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(onClick = onClose, modifier = Modifier.weight(1f), enabled = !busy, height = 42.dp, radius = 10.dp, size = 14f) { style ->
                        BasicText(stringResource(R.string.Common_Cancel), style = style)
                    }
                    GlassButton(
                        onClick = ::save,
                        modifier = Modifier.weight(1f),
                        weight = Weight.Primary,
                        enabled = !busy && !blank,
                        height = 42.dp,
                        radius = 10.dp,
                        size = 14f,
                    ) { style ->
                        if (busy) RecordSpinner(18.dp) else BasicText(stringResource(R.string.ArtPlace_Save), style = style)
                    }
                }
            }
        }
    }
}
