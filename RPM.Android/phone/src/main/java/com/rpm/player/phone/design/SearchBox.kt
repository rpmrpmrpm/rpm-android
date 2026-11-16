package com.rpm.player.phone.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/** The height of the search box. The same as `RPMSearchBox.height` on iPhone. */
val SEARCH_HEIGHT = 38.dp

/**
 * A search field on the face of a text field, as the iPhone's `RPMSearchBox`. The search runs as the user
 * types, so the keyboard's search key only hides the keyboard. It has no clear key: the search row puts its close key
 * right beside the box, and a second cross there is one too many.
 *
 * @param focus gives the box focus, which opens the keyboard. The caller requests focus when the box appears.
 */
@Composable
fun SearchBox(text: String, onText: (String) -> Unit, prompt: String, focus: FocusRequester, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    val focusManager = LocalFocusManager.current
    val style = display(14f).copy(color = Color.White)
    Row(
        modifier
            .height(SEARCH_HEIGHT)
            .clip(shape)
            .background(Ink.ghostFace)
            .border(1.dp, Ink.glassEdge, shape)
            .padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = onText,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier.weight(1f).focusRequester(focus),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) BasicText(prompt, maxLines = 1, style = style.copy(color = Ink.white(0x77)))
                    field()
                }
            },
        )
    }
}
