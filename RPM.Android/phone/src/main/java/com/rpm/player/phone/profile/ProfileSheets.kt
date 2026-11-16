@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.rpm.player.phone.profile

import androidx.compose.foundation.layout.FlowRow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CommunityClient
import com.rpm.player.core.cloud.Notice
import com.rpm.player.core.cloud.Post
import com.rpm.player.core.cloud.Reply
import com.rpm.player.core.cloud.avatarAddress
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.OverChrome
import com.rpm.player.phone.design.PageTitle
import com.rpm.player.phone.design.PullDownMenu
import com.rpm.player.phone.design.PullItem
import com.rpm.player.phone.design.Weight
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.design.system
import com.rpm.player.phone.library.ReportSheet
import com.rpm.player.phone.library.Said
import com.rpm.player.phone.ui.VinylGround
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import com.rpm.player.core.cloud.ProfileBadges
import com.rpm.player.phone.ui.GroundTile
import com.rpm.player.phone.ui.Ground
import com.rpm.player.core.cloud.PageGround
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll

private val MARGIN = 20.dp

/** Replies deeper than this are drawn at this depth, so a long thread still has room for its text. */
private const val DEEPEST = 5
private val STEP = 16.dp

/** A close key: a cross at the top right of a sheet. */
@Composable
internal fun CloseKey(onClose: () -> Unit) {
    val label = stringResource(R.string.Common_Cancel)
    Box(
        Modifier
            .size(40.dp)
            .semantics { contentDescription = label }
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(Glyph.close, style = icon(15f).copy(color = Ink.white(0xCC)))
    }
}

/**
 * One post and every reply under it, risen over the page: the iPhone's `ThreadPage`. Replies are indented by how deep
 * they are, up to [DEEPEST] steps.
 *
 * @param me the reader's user id, to tell their own replies from other people's.
 */
@Composable
internal fun ThreadPage(page: PersonPage, post: Post, images: ImageLoader, me: String?, bottomRoom: Dp, ownerSupporter: Boolean, onOpenPerson: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val backdrop = rememberBackdrop()
    var menu by remember { mutableStateOf<Pair<Reply, Rect>?>(null) }
    var reporting by remember { mutableStateOf<Reply?>(null) }
    BackHandler { page.closeThread() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
            // The page owner's chosen background, as on the page the thread belongs to.
            PageGroundView(page.profile?.pageGround.orEmpty())
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Spacer(Modifier.weight(1f))
                    CloseKey { page.closeThread() }
                }
                LazyColumn(Modifier.weight(1f).padding(horizontal = MARGIN)) {
                    item(key = "post") { Head(post, page, images, me, ownerSupporter, onOpenPerson) { value -> scope.launch { page.vote(post, value) } } }
                    page.threadTrouble?.let { said -> item(key = "trouble") { Quiet(stringResource(said)) } }
                    items(page.replyLines, key = { it.reply.replyId }) { line ->
                        ReplyRow(
                            line.reply,
                            depth = line.depth,
                            mine = line.reply.userId == me,
                            images = images,
                            onVote = { value -> scope.launch { page.voteReply(line.reply, value) } },
                            onReply = { page.replyingTo = line.reply },
                            onPerson = onOpenPerson,
                            onMenu = { at -> menu = line.reply to at },
                        )
                    }
                    item(key = "foot") { Spacer(Modifier.height(16.dp)) }
                }
                ReplyBox(page) { scope.launch { page.sendReply() } }
            }
        }
        menu?.let { (reply, at) ->
            val items = buildList {
                if (reply.userId == me || page.isOwn) {
                    add(PullItem(stringResource(R.string.Rpm_Profile_DeleteReply), destructive = true) { scope.launch { page.deleteReply(reply) } })
                }
                if (reply.userId != me) add(PullItem(stringResource(R.string.Rpm_Profile_ReportReply)) { reporting = reply })
            }
            OverChrome { PullDownMenu(backdrop, at, items, onDismiss = { menu = null }) }
        }
        reporting?.let { reply ->
            OverChrome {
                ReportSheet(
                    stringResource(R.string.Album_ReportCommentPrompt),
                    backdrop,
                    bottomRoom,
                    onSend = { note -> page.reportReply(reply, note)?.let { Said(it) } },
                    onClose = { reporting = null },
                )
            }
        }
    }
}

@Composable
private fun Head(post: Post, page: PersonPage, images: ImageLoader, me: String?, supporter: Boolean, onOpenPerson: (String) -> Unit, onVote: (Int) -> Unit) {
    val name = if (post.authorDeleted) stringResource(R.string.Community_DeletedUser) else post.authorName
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { onOpenPerson(post.ownerId) },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PersonPicture(page.photo, name, 40.dp, images)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(name, style = system(15f, FontWeight.SemiBold).copy(color = if (supporter) Ink.gold else Color.White))
                BasicText(written(post.createdAt), style = display(11f).copy(color = Ink.white(0x77)))
            }
        }
        BasicText(post.body, style = display(15f).copy(color = Ink.white(0xEE)))
        post.song?.let { song ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(Glyph.play, style = icon(9f).copy(color = Ink.white(0xAA)))
                BasicText(songLine(song), maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(12f).copy(color = Ink.white(0xAA)))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            VoteKeys(post.ups, post.downs, post.myVote, enabled = post.ownerId != me, onVote = onVote)
            Spacer(Modifier.weight(1f))
            ReplyKey(0) { page.replyingTo = null }
        }
    }
}

@Composable
private fun ReplyRow(
    reply: Reply,
    depth: Int,
    mine: Boolean,
    images: ImageLoader,
    onVote: (Int) -> Unit,
    onReply: () -> Unit,
    onPerson: (String) -> Unit,
    onMenu: (Rect) -> Unit,
) {
    val name = if (reply.authorDeleted) stringResource(R.string.Community_DeletedUser) else reply.authorName
    var corner by remember { mutableStateOf(Rect.Zero) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = STEP * minOf(depth, DEEPEST), top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (depth > 0) Box(Modifier.width(2.dp).height(40.dp).background(Ink.white(0x22)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (reply.deleted) {
                BasicText(stringResource(R.string.Rpm_Profile_ReplyDeleted), style = display(13f).copy(color = Ink.white(0x77), fontStyle = FontStyle.Italic))
            } else {
                Row(
                    Modifier.clickable(remember { MutableInteractionSource() }, indication = null, enabled = !reply.authorDeleted) { onPerson(reply.userId) },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PersonPicture(if (reply.authorDeleted) null else rememberFace(reply.userId, reply.hasAvatar), name, 26.dp, images)
                    Byline(name, reply.createdAt, gold = reply.authorSupporter)
                }
                BasicText(reply.body, style = display(14f).copy(color = Ink.white(0xE6)))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    VoteKeys(reply.ups, reply.downs, reply.myVote, enabled = !mine, onVote = onVote)
                    Spacer(Modifier.weight(1f))
                    ReplyKey(0, onReply)
                }
            }
        }
        if (!reply.deleted) {
            val more = stringResource(R.string.Common_MoreOptions)
            Box(
                Modifier
                    .size(34.dp, 28.dp)
                    .onGloballyPositioned { corner = it.boundsInRoot() }
                    .semantics { contentDescription = more }
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onMenu(corner) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(Glyph.more, style = icon(13f).copy(color = Ink.white(0xAA)))
            }
        }
    }
}

@Composable
private fun ReplyBox(page: PersonPage, onSend: () -> Unit) {
    val card = RoundedCornerShape(14.dp)
    val canSend = !page.replying && page.replyDraft.isNotBlank()
    Column(
        Modifier
            .padding(horizontal = MARGIN, vertical = 8.dp)
            .fillMaxWidth()
            .clip(card)
            .background(Ink.white(0x14))
            .border(1.dp, Ink.white(0x22), card)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Which reply the box answers, with a cross to answer the post instead.
        page.replyingTo?.let { to ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val who = if (to.authorDeleted) stringResource(R.string.Community_DeletedUser) else to.authorName
                BasicText(stringResource(R.string.Rpm_Profile_Reply) + ": " + who, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(12f).copy(color = Ink.white(0x99)))
                val cancel = stringResource(R.string.Common_Cancel)
                Box(
                    Modifier
                        .size(24.dp)
                        .semantics { contentDescription = cancel }
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { page.replyingTo = null },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(Glyph.close, style = icon(11f).copy(color = Ink.white(0x99)))
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicTextField(
                page.replyDraft,
                onValueChange = { page.replyDraft = it.take(CommunityClient.LONGEST_WALL_POST) },
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                textStyle = display(15f).copy(color = Color.White),
                cursorBrush = SolidColor(Color.White),
                minLines = 1,
                maxLines = 5,
                decorationBox = { field ->
                    Box {
                        if (page.replyDraft.isEmpty()) BasicText(stringResource(R.string.Rpm_Profile_ReplyPrompt), style = display(15f).copy(color = Ink.white(0x66)))
                        field()
                    }
                },
            )
            GlassButton(onClick = onSend, weight = Weight.Primary, enabled = canSend, stretches = false, height = 32.dp, radius = 15.dp, inset = 14.dp, size = 13f) { style ->
                BasicText(stringResource(R.string.Album_Send), style = style)
            }
        }
    }
}

// ── notifications ──

/**
 * The reader's notifications, newest first, risen over the page: the iPhone's `NotificationsPage`. Unread ones have a
 * dot. A kind this build does not know is left out.
 */
@Composable
internal fun NoticesPage(notices: Notices, images: ImageLoader, onClose: () -> Unit, onOpen: (Notice) -> Unit) {
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { notices.read() }
    val shown = notices.items.filter { it.known }

    Box(Modifier.fillMaxSize()) {
        VinylGround()
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = MARGIN, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PageTitle(stringResource(R.string.Rpm_Notify_Title), Modifier.weight(1f))
                CloseKey(onClose)
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal = MARGIN), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val trouble = notices.trouble
                when {
                    trouble != null -> item(key = "trouble") { Quiet(stringResource(trouble)) }
                    notices.answered && shown.isEmpty() -> item(key = "empty") { Quiet(stringResource(R.string.Rpm_Notify_Empty)) }
                }
                items(shown, key = { it.id }) { note ->
                    NoticeRow(
                        note,
                        images,
                        onOpen = { onOpen(note) },
                        onAnswer = { accept -> scope.launch { notices.answer(note.fromUserId, accept) } },
                    )
                }
                if (notices.next != null) {
                    item(key = "more") {
                        LaunchedEffect(notices.next) { notices.more() }
                        Spacer(Modifier.height(40.dp))
                    }
                }
                item(key = "foot") { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun NoticeRow(note: Notice, images: ImageLoader, onOpen: () -> Unit, onAnswer: (Boolean) -> Unit) {
    val who = note.fromName.ifEmpty { stringResource(R.string.Community_DeletedUser) }
    val said = when (note.kind) {
        Notice.REPLY -> stringResource(R.string.Rpm_Notify_Reply, who)
        Notice.VOTE -> if (note.count > 1) stringResource(R.string.Rpm_Notify_Votes, note.count) else stringResource(R.string.Rpm_Notify_Vote, who)
        Notice.FRIEND_REQUEST -> stringResource(R.string.Rpm_Notify_FriendRequest, who)
        Notice.FRIEND_ACCEPTED -> stringResource(R.string.Rpm_Notify_FriendAccepted, who)
        Notice.FRIEND_REVIEW -> stringResource(R.string.Rpm_Notify_FriendReview, who, note.albumTitle.orEmpty())
        Notice.BADGE -> note.badgeId?.let(ProfileBadges::byId)?.let { stringResource(R.string.Rpm_Notify_Badge, stringResource(it.name)) }.orEmpty()
        else -> ""
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.white(if (note.read) 0x10 else 0x1C))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onOpen)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PersonPicture(rememberFace(note.fromUserId, note.fromHasAvatar), who, 36.dp, images)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(said, style = display(14f).copy(color = Color.White))
            BasicText(written(note.createdAt), style = display(11f).copy(color = Ink.white(0x77)))
            if (note.kind == Notice.FRIEND_REQUEST) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallKey(stringResource(R.string.Rpm_Profile_Accept), Weight.Primary) { onAnswer(true) }
                    SmallKey(stringResource(R.string.Rpm_Profile_Decline), Weight.Ghost) { onAnswer(false) }
                }
            }
        }
        if (!note.read) Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(Ink.red))
    }
}
