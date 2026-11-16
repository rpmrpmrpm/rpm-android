package com.rpm.player.phone.library

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CommunityClient
import com.rpm.player.core.cloud.WallEntry
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.Weight
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.system
import com.rpm.player.phone.ui.RecordSpinner

/**
 * What listeners wrote about an artist, at the foot of the artist's page: a box to write in, and the lines, newest
 * first. It is the iPhone's `ArtistWallSection` (RPMUI/ArtistWallSection.swift), the phone version of the desktop's wall
 * card. The lines are rows of the page's own list, not a list of their own, so one finger scrolls one thing.
 *
 * The box is there from the start, before the wall has been read and when it is empty, because the first post is what
 * creates the artist's entry. Under it is one of three things: nothing while the wall is being read, the empty line once
 * it is read with nothing on it, or the trouble when it could not be read. A wall that could not be read is never shown
 * as an empty one.
 *
 * @param me the signed-in account. The reader can delete their own post, and cannot report anything of their own.
 * @param top the space above the wall, where a new part of the page starts.
 * @param onMenu opens the menu of a line's corner key, which stands at that place on the screen.
 */
fun LazyListScope.artistWall(wall: ArtistPosts, me: String?, top: Dp, onMenu: (WallEntry, Rect) -> Unit) {
    item(key = "wall:head", contentType = "wall:head") {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = top),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(stringResource(R.string.Artists_Wall).uppercase(), style = kicker())
            WallBox(wall)
            val trouble = wall.trouble
            if (trouble != null) {
                BasicText(said(trouble), style = display(12f).copy(color = Ink.white(0x99)))
            } else if (wall.answered && wall.lines.isEmpty()) {
                BasicText(stringResource(R.string.Artists_WallEmpty), style = display(13f).copy(color = Ink.white(0x88)))
            }
        }
    }
    items(wall.lines, key = { "wall:" + it.entryId }, contentType = { "wall:line" }) { line ->
        WallLine(line, mine = me != null && line.userId == me, onMenu, Modifier.padding(horizontal = 20.dp).padding(top = 12.dp))
    }
}

/**
 * The box to write in, with Send beside it. It grows to six lines and then scrolls. It stops at the longest post the
 * server takes ([CommunityClient.LONGEST_WALL_POST]), so a long post is not refused after it was typed.
 */
@Composable
private fun WallBox(wall: ArtistPosts) {
    val card = RoundedCornerShape(14.dp)
    val canSend = !wall.sending && wall.draft.isNotBlank()
    // **The box scrolls itself above the keyboard.** The box is at the foot of the page, and the keyboard comes up after
    // the box takes focus, so the scroll that focus asks for happens before the list is made shorter. Each step of
    // the keyboard asks again while the box has focus. Only this small box reads the keyboard's height.
    val bringer = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, keyboard) { if (focused && keyboard > 0) bringer.bringIntoView() }
    Row(
        Modifier
            .bringIntoViewRequester(bringer)
            .fillMaxWidth()
            .clip(card)
            .background(Ink.white(0x14))
            .border(1.dp, Ink.glassEdge, card)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        BasicTextField(
            wall.draft,
            onValueChange = { wall.draft = it.take(CommunityClient.LONGEST_WALL_POST) },
            modifier = Modifier.weight(1f).padding(vertical = 6.dp).onFocusChanged { focused = it.isFocused },
            textStyle = display(15f).copy(color = Color.White),
            cursorBrush = SolidColor(Color.White),
            minLines = 1,
            maxLines = 6,
            decorationBox = { field ->
                Box {
                    if (wall.draft.isEmpty()) {
                        BasicText(stringResource(R.string.Artists_WallPlaceholder), style = display(15f).copy(color = Ink.white(0x66)))
                    }
                    field()
                }
            },
        )
        GlassButton(
            onClick = { wall.post() },
            weight = Weight.Primary,
            enabled = canSend,
            stretches = false,
            height = 32.dp,
            radius = 15.dp,
            inset = 14.dp,
            size = 13f,
        ) { style ->
            if (wall.sending) RecordSpinner(14.dp) else BasicText(stringResource(R.string.Album_Send), style = style)
        }
    }
}

/**
 * One line: the first letter of the author's name in a circle, as on the record page's reviews, the name and when, the
 * line, and the stars on a review line. The key in the corner opens Delete on the reader's own post, Report on anybody
 * else's line; the reader's own review line has neither. It goes when the review goes, and the record page is where a
 * review is deleted.
 */
@Composable
private fun WallLine(line: WallEntry, mine: Boolean, onMenu: (WallEntry, Rect) -> Unit, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.white(0x12))
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Ink.white(0x26)), contentAlignment = Alignment.Center) {
            BasicText(initial(line), style = system(14f, FontWeight.SemiBold).copy(color = Ink.white(0xDD)))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(
                    if (line.authorDeleted) stringResource(R.string.Community_DeletedUser) else line.authorName,
                    Modifier.weight(1f, fill = false).alignByBaseline(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // A supporter's name is in gold, as on their page.
                    style = system(14f, FontWeight.SemiBold).copy(color = if (line.authorSupporter) Ink.gold else Color.White),
                )
                BasicText(writtenWhen(line), Modifier.alignByBaseline(), maxLines = 1, style = display(11f).copy(color = Ink.white(0x77)))
            }
            val key = line.lineKey
            BasicText(
                if (key != null) stringResource(key, line.albumTitle.orEmpty()) else line.body,
                style = display(14f).copy(color = Ink.white(if (line.isPost) 0xE6 else 0xBB)),
            )
            // Only on a review line. A post has no rating, and empty stars under a post would read as a rating of zero.
            if (line.isReview && line.rating > 0) StarRating(line.stars, 11f)
        }
        val deletable = mine && line.isPost
        if (deletable || !mine) {
            val label = stringResource(R.string.Common_MoreOptions)
            val place = remember { KeyPlace() }
            Box(
                Modifier
                    .size(34.dp, 28.dp)
                    .onGloballyPositioned { place.bounds = it.boundsInRoot() }
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onMenu(line, place.bounds) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(Glyph.more, style = icon(13f).copy(color = Ink.white(0xAA)))
            }
        }
    }
}

private fun initial(line: WallEntry): String {
    val first = line.authorName.firstOrNull()
    return if (line.authorDeleted || first == null || !first.isLetterOrDigit()) "?" else first.uppercase()
}

/** When it was written, in words a person would use ("3 days ago"). */
private fun writtenWhen(line: WallEntry): String {
    if (line.createdAt <= 0) return ""
    return DateUtils.getRelativeTimeSpanString(line.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}

/** Where a corner key is on the screen, for the menu that drops from it. Read only when the key is tapped, so it is not state. */
private class KeyPlace {
    var bounds = Rect.Zero
}
