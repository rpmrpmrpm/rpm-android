package com.rpm.player.phone.profile

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.rpm.player.core.R
import com.rpm.player.core.cloud.ApiException
import com.rpm.player.core.cloud.CommunityClient
import com.rpm.player.core.cloud.FeedItem
import com.rpm.player.core.cloud.FeedReview
import com.rpm.player.core.cloud.Post
import com.rpm.player.core.cloud.ProfileSample
import com.rpm.player.core.cloud.avatarAddress
import com.rpm.player.core.cloud.everyone
import com.rpm.player.core.cloud.vote

/**
 * Everyone's posts and album reviews, newest first: the Feed tab. It is the iPhone's `FeedBook`.
 *
 * While the server has no feed (404, or the 401 it answers for a route it does not have), a test build shows the
 * sample's posts, with the reader's own sample posts among them ([ProfileSample.everyone]).
 */
class FeedBook(
    private val community: CommunityClient,
    private val sample: (() -> ProfileSample?)? = null,
    /** The reader as their own page shows them, for their sample posts: read before the sample is built. */
    private val me: suspend () -> Me?,
) {
    /** The reader's name, id, photo and supporter's mark, as their sample posts carry them. */
    class Me(val name: String, val userId: String, val hasAvatar: Boolean, val supporter: Boolean)

    var items by mutableStateOf<List<FeedItem>>(emptyList())
        private set
    var next by mutableStateOf<Long?>(null)
        private set
    var answered by mutableStateOf(false)
        private set

    @get:StringRes
    var trouble by mutableStateOf<Int?>(null)
        private set
    var isSample by mutableStateOf(false)
        private set

    private var newest = 0
    private var readingMore = false

    suspend fun read() {
        val ticket = ++newest
        try {
            val page = community.everyone()
            if (ticket != newest) return
            isSample = false
            items = page.items
            next = page.next
            answered = true
            trouble = null
        } catch (missing: ApiException) {
            if (ticket != newest) return
            val shown = sample?.invoke()
            if ((missing is ApiException.NotFound || missing is ApiException.Unauthorized) && shown != null) {
                isSample = true
                val reader = me()
                val own = shown.feed.items.filterIsInstance<FeedItem.OfPost>().map {
                    FeedItem.OfPost(
                        it.post.copy(
                            ownerId = reader?.userId.orEmpty(),
                            authorName = reader?.name.orEmpty(),
                            hasAvatar = reader?.hasAvatar == true,
                            authorSupporter = reader?.supporter == true,
                        ),
                    )
                }
                items = (shown.everyone + own).sortedByDescending { it.createdAt }
                next = null
                answered = true
                trouble = null
            } else {
                trouble = R.string.Rpm_Profile_Offline
            }
        } catch (_: Exception) {
            if (ticket == newest) trouble = R.string.Rpm_Profile_Offline
        }
    }

    suspend fun more() {
        val before = next ?: return
        if (readingMore) return
        readingMore = true
        try {
            val ticket = newest
            val page = runCatching { community.everyone(before) }.getOrNull() ?: return
            if (ticket != newest) return
            val known = items.mapTo(HashSet()) { it.key }
            items = items + page.items.filter { it.key !in known }
            next = page.next
        } finally {
            readingMore = false
        }
    }

    /** Votes on a review. On the sample feed it counts here only, so the keys can be tried. */
    suspend fun voteReview(shown: FeedReview, value: Int) {
        val key = FeedItem.OfReview(shown).key
        val changed = if (isSample) {
            val ups = shown.ups + (if (value == 1) 1 else 0) - (if (shown.myVote == 1) 1 else 0)
            val downs = shown.downs + (if (value == -1) 1 else 0) - (if (shown.myVote == -1) 1 else 0)
            shown.copy(ups = ups, downs = downs, score = ups - downs, myVote = value)
        } else {
            val review = runCatching { community.vote(shown.albumId, shown.userId, value) }.getOrNull() ?: return
            shown.with(review)
        }
        items = items.map { if (it.key == key) FeedItem.OfReview(changed) else it }
    }

    /** Votes on a post. On the sample feed it counts here only, so the keys can be tried. */
    suspend fun vote(post: Post, value: Int) {
        val changed = if (isSample) {
            val ups = post.ups + (if (value == 1) 1 else 0) - (if (post.myVote == 1) 1 else 0)
            val downs = post.downs + (if (value == -1) 1 else 0) - (if (post.myVote == -1) 1 else 0)
            post.copy(ups = ups, downs = downs, score = ups - downs, myVote = value)
        } else {
            runCatching { community.vote(post, value) }.getOrNull() ?: return
        }
        items = items.map { if (it is FeedItem.OfPost && it.post.postId == changed.postId) FeedItem.OfPost(changed) else it }
    }
}

/**
 * The photos the sample people borrow, by user id: an artist's photo each ([ProfileSample.faces]), found once the
 * sample is shown. Empty outside a sample, so a real person's photo is always their own.
 */
val LocalSampleFaces = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/** A person's photo: their own, or on the sample page the photo they borrow ([LocalSampleFaces]). */
@Composable
internal fun rememberFace(userId: String, hasAvatar: Boolean, stamp: Long = 0): String? =
    avatarAddress(userId, hasAvatar, stamp) ?: LocalSampleFaces.current[userId]
