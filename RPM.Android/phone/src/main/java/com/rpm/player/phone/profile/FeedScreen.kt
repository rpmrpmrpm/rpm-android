package com.rpm.player.phone.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.rpm.player.core.R
import com.rpm.player.core.cloud.FeedItem
import com.rpm.player.core.cloud.FeedReview
import com.rpm.player.core.cloud.Post
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.Cover
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rpm.player.phone.design.OverChrome
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.PageHeading
import com.rpm.player.phone.design.UnderHeader
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.dissolvesAtTop
import com.rpm.player.phone.design.scrolled
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.library.HeaderKey
import com.rpm.player.phone.library.KeyGroup
import com.rpm.player.phone.ui.VinylGround
import kotlinx.coroutines.launch

private val MARGIN = 20.dp

/**
 * The Feed tab: everyone's posts and album reviews, newest first. Its header is the collection's: the title, a line under it, and its
 * keys on one glass capsule at the right; the one key here opens the reader's own profile page, with a dot while
 * notifications wait. It is the iPhone's `FeedPage`.
 *
 * A post's name or photo opens that person's page; the post itself opens on their page with its replies. A review opens
 * whole over the tab.
 *
 * @param me the reader's user id, so their own posts cannot be voted on.
 * @param unread how many notifications wait, for the dot on the profile key.
 */
@Composable
fun FeedScreen(
    feed: FeedBook,
    images: ImageLoader,
    me: String?,
    unread: Int,
    bottomRoom: Dp,
    onMyProfile: () -> Unit,
    onOpenPerson: (String) -> Unit,
    onOpenPost: (Post) -> Unit,
    /** The reader's own copy of a reviewed album; null when it is not in their collection. */
    albumOf: (FeedReview) -> CloudAlbum?,
    coverOf: (CloudAlbum) -> Cover?,
    albumCover: (suspend (artist: String, title: String) -> String?)?,
    onOpenAlbum: (CloudAlbum) -> Unit,
) {
    // The review open in full, by its key, so its votes stay current.
    var reading by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val page = rememberBackdrop()
    val ground = rememberBackdrop()
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { feed.read() }

    Box(Modifier.fillMaxSize().backdropSource(page)) {
        UnderHeader(ground, ground = { VinylGround() }, header = {
            Column(Modifier.statusBarsPadding()) {
                Box(Modifier.padding(start = MARGIN, end = 16.dp, bottom = 10.dp)) {
                    PageHeading(stringResource(R.string.Rpm_Tab_Feed), if (feed.isSample) stringResource(R.string.Rpm_Profile_SampleNote) else "") {
                        Box {
                            val label = stringResource(R.string.Rpm_Feed_MyProfile)
                            KeyGroup(ground) {
                                Box(Modifier.semantics { contentDescription = label }) { HeaderKey(Glyph.profile, onMyProfile) }
                            }
                            if (unread > 0) {
                                Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(10.dp).clip(CircleShape).background(Ink.red))
                            }
                        }
                    }
                }
            }
        }) { top ->
            // What scrolls up under the header fades out, as on the collection.
            LazyColumn(Modifier.fillMaxSize().dissolvesAtTop(top) { list.scrolled() }, state = list) {
                item(key = "top") { Spacer(Modifier.height(top + 4.dp)) }
                val trouble = feed.trouble
                when {
                    trouble != null -> item(key = "trouble") { Quiet(stringResource(trouble), Modifier.padding(horizontal = MARGIN)) }
                    feed.answered && feed.items.isEmpty() -> item(key = "empty") {
                        Quiet(stringResource(R.string.Rpm_Feed_Empty), Modifier.padding(horizontal = MARGIN))
                    }
                    else -> items(feed.items, key = { it.key }, contentType = { if (it is FeedItem.OfPost) "post" else "review" }) { item ->
                        val modifier = Modifier.padding(horizontal = MARGIN).padding(bottom = 12.dp)
                        when (item) {
                            is FeedItem.OfPost -> {
                                val post = item.post
                                PostRow(
                                    post,
                                    rememberFace(post.ownerId, post.hasAvatar),
                                    images,
                                    isOwn = post.ownerId == me,
                                    supporter = post.authorSupporter,
                                    modifier = modifier,
                                    onVote = { value -> scope.launch { feed.vote(post, value) } },
                                    onOpen = { onOpenPost(post) },
                                    onMenu = null,
                                    onPerson = { onOpenPerson(post.ownerId) },
                                )
                            }
                            is FeedItem.OfReview -> {
                                val review = item.review
                                ReviewRow(
                                    review,
                                    isOwn = review.userId == me,
                                    albumOf(review),
                                    coverOf,
                                    albumCover,
                                    images,
                                    modifier,
                                    onVote = { value -> scope.launch { feed.voteReview(review, value) } },
                                    onRead = { reading = item.key },
                                    author = { ReviewAuthor(review, images) { onOpenPerson(review.userId) } },
                                )
                            }
                        }
                    }
                }
                if (feed.next != null) {
                    item(key = "more") {
                        LaunchedEffect(feed.next) { feed.more() }
                        Spacer(Modifier.height(40.dp))
                    }
                }
                item(key = "foot") { Spacer(Modifier.height(40.dp + bottomRoom)) }
            }
        }
    }

    // The review read in full, over the tab and the tab bar. It is looked up in the feed each time, so a vote shows at once.
    val open = reading?.let { key -> feed.items.firstOrNull { it.key == key } as? FeedItem.OfReview }?.review
    OverChrome {
        AnimatedVisibility(open != null, enter = slideInVertically(tween(320)) { it }, exit = slideOutVertically(tween(260)) { it }) {
            val shown = remember { androidx.compose.runtime.mutableStateOf<FeedReview?>(null) }
            open?.let { shown.value = it }
            shown.value?.let { review ->
                ReviewPage(
                    review,
                    isOwn = review.userId == me,
                    album = albumOf(review),
                    coverOf = coverOf,
                    albumCover = albumCover,
                    images = images,
                    ground = "",
                    onVote = { value -> scope.launch { feed.voteReview(review, value) } },
                    onOpenAlbum = { album -> reading = null; onOpenAlbum(album) },
                    onClose = { reading = null },
                    author = { ReviewAuthor(review, images) { reading = null; onOpenPerson(review.userId) } },
                )
            }
        }
    }
}
