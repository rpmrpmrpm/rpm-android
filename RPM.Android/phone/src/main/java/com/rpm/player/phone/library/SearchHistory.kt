package com.rpm.player.phone.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.phone.PhoneSettings
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.system
import kotlinx.coroutines.delay

/** How long the box must be left alone before what is in it counts as a search to remember. */
private const val SETTLED_MS = 2_000L

/**
 * Remembers [asked] once it has stayed the same for [SETTLED_MS]: the person stopped typing and is reading the results.
 * See [PhoneSettings.rememberSearch] for how the steps of one search become one entry. It is the iPhone's
 * `SearchHistory.remember`.
 */
@Composable
internal fun RememberSearch(asked: String, settings: PhoneSettings) {
    LaunchedEffect(asked) {
        if (asked.trim().length < 2) return@LaunchedEffect
        delay(SETTLED_MS)
        settings.rememberSearch(asked)
    }
}

/**
 * The earlier searches, newest first, shown under the box while it is empty. A tap puts one back in the box; the cross
 * beside it removes it, and Clear removes them all. Nothing is drawn while there are none.
 */
@Composable
internal fun SearchHistoryList(history: List<String>, top: Dp, bottomRoom: Dp, onPick: (String) -> Unit, onForget: (String) -> Unit, onClear: () -> Unit) {
    if (history.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = top + 4.dp, start = 20.dp, end = 12.dp, bottom = bottomRoom + 40.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(stringResource(R.string.Rpm_Search_Recent), Modifier.weight(1f), style = kicker())
            BasicText(
                stringResource(R.string.Rpm_Search_ClearRecent),
                Modifier
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClear)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                style = system(13f, FontWeight.SemiBold).copy(color = Ink.white(0xB8)),
            )
        }
        val forget = stringResource(R.string.Rpm_Search_Forget)
        for (asked in history) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onPick(asked) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(Glyph.search, style = icon(12f).copy(color = Ink.white(0x77)))
                Spacer(Modifier.width(12.dp))
                BasicText(asked, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(15f).copy(color = Color.White))
                Box(
                    Modifier
                        .size(36.dp)
                        .semantics { contentDescription = forget }
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onForget(asked) },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(Glyph.close, style = icon(11f).copy(color = Ink.white(0x77)))
                }
            }
        }
    }
}
