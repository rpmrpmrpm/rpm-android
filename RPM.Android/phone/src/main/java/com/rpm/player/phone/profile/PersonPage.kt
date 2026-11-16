package com.rpm.player.phone.profile

import kotlinx.coroutines.CancellationException
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rpm.player.core.R
import com.rpm.player.core.cloud.ApiException
import com.rpm.player.core.cloud.CommunityClient
import com.rpm.player.core.cloud.PageGround
import com.rpm.player.core.cloud.ProfileSample
import com.rpm.player.core.cloud.setPageGround
import com.rpm.player.core.cloud.setBadges
import com.rpm.player.core.cloud.setBannerSource
import com.rpm.player.core.cloud.FeedItem
import com.rpm.player.core.cloud.Friendship
import com.rpm.player.core.cloud.Notice
import com.rpm.player.core.cloud.PersonProfile
import com.rpm.player.core.cloud.Post
import com.rpm.player.core.cloud.Reply
import com.rpm.player.core.cloud.ReplyTree
import com.rpm.player.core.cloud.ReportTarget
import com.rpm.player.core.cloud.Review
import com.rpm.player.core.cloud.Song
import com.rpm.player.core.cloud.acceptFriend
import com.rpm.player.core.cloud.avatarAddress
import com.rpm.player.core.cloud.bannerAddress
import com.rpm.player.core.cloud.block
import com.rpm.player.core.cloud.clearListening
import com.rpm.player.core.cloud.deleteAvatar
import com.rpm.player.core.cloud.deleteBanner
import com.rpm.player.core.cloud.deletePost
import com.rpm.player.core.cloud.deleteReply
import com.rpm.player.core.cloud.feed
import com.rpm.player.core.cloud.markNoticesRead
import com.rpm.player.core.cloud.notices
import com.rpm.player.core.cloud.ownPage
import com.rpm.player.core.cloud.personPage
import com.rpm.player.core.cloud.post
import com.rpm.player.core.cloud.removeFriend
import com.rpm.player.core.cloud.replies
import com.rpm.player.core.cloud.reply
import com.rpm.player.core.cloud.requestFriend
import com.rpm.player.core.cloud.setAvatar
import com.rpm.player.core.cloud.setBanner
import com.rpm.player.core.cloud.setDisplayName
import com.rpm.player.core.cloud.setListening
import com.rpm.player.core.cloud.unblock
import com.rpm.player.core.cloud.unreadNotices
import com.rpm.player.core.cloud.vote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a key on someone else's page does to the friendship. */
enum class FriendStep { Add, Cancel, Accept, Decline, Remove }

/**
 * One person's page: their profile, their feed, and the thread that is open on it. It is the iPhone's `PersonPage`.
 * The profile tab holds one for the reader; a name tapped on a review opens one for that person.
 *
 * **The newest read wins, not the last one to answer.** Each read takes a number, and its answer is used only while no
 * newer read has started. Every call runs on the main thread, from the page's scope.
 */
class PersonPage(
    private val community: CommunityClient,
    val userId: String,
    val isOwn: Boolean,
    /** The sample page for a server that has no profile page yet. Null: no sample. See [ProfileSample]. */
    private val sample: (() -> ProfileSample?)? = null,
) {
    /**
     * Whether the page shows the sample: the reader's own page, read from a server that has no feed route yet (404).
     * The page says so above the feed. Writes then fail as they would, except the page ground, which is kept here so
     * it can be tried.
     */
    var isSample by mutableStateOf(false)
        private set

    /** A post to open with its replies once the page has been read: a post opened from the Feed tab. */
    var pendingPost: Post? = null
    private var sampled: ProfileSample? = null

    var profile by mutableStateOf<PersonProfile?>(null)
        private set
    var feed by mutableStateOf<List<FeedItem>>(emptyList())
        private set

    /** The `before` of the next feed page; null when there is no more. */
    var next by mutableStateOf<Long?>(null)
        private set

    /** Whether the feed has been read. False while it is read and after a failed read. */
    var answered by mutableStateOf(false)
        private set

    @get:StringRes
    var trouble by mutableStateOf<Int?>(null)
        private set
    var sending by mutableStateOf(false)
        private set
    var draft by mutableStateOf("")
    var withSong by mutableStateOf(false)

    /**
     * Why the last change failed: a post, a delete, a friend step, a block, the photo, the banner or the name. The page
     * shows it in a panel. [trouble] is only for a page that could not be read, and takes the place of the feed.
     */
    @get:StringRes
    var editTrouble by mutableStateOf<Int?>(null)
    var saving by mutableStateOf(false)
        private set

    private var newest = 0
    private var readingMore = false

    val photo: String? get() = profile?.let { avatarAddress(it.userId, it.hasAvatar, it.avatarUpdatedAt) }
    val banner: String? get() = profile?.let(::bannerAddress)

    // ── reading ──

    suspend fun read() {
        val ticket = ++newest
        try {
            val found = if (isOwn) community.ownPage() else community.personPage(userId)
            val page = try {
                community.feed(userId)
            } catch (missing: ApiException) {
                // The live server answers a route it does not have with 404, or with 401 even for a caller whose own
                // profile was just read with the same session. Either means: no profile pages yet.
                if (missing !is ApiException.NotFound && missing !is ApiException.Unauthorized) throw missing
                val shown = sampled ?: sample?.invoke()?.also { sampled = it } ?: throw missing
                if (ticket != newest) return
                if (!isOwn) {
                    // A sample person's page: their sample profile, and their posts from the sample feed.
                    val person = shown.people[userId] ?: throw missing
                    isSample = true
                    profile = person
                    feed = shown.everyone.filter {
                        when (it) {
                            is FeedItem.OfPost -> it.post.ownerId == userId
                            is FeedItem.OfReview -> it.review.userId == userId
                        }
                    }
                    next = null
                    answered = true
                    trouble = null
                    return
                }
                isSample = true
                profile = found?.let { own ->
                    own.copy(
                        counts = own.counts ?: shown.counts,
                        listening = own.listening ?: shown.listening,
                        pageGround = profile?.pageGround?.takeIf { it.isNotEmpty() } ?: own.pageGround,
                        // What was chosen on the sample page stays; until then the sample's badge.
                        badge = profile?.badge?.takeIf { profile?.badgeColour?.isNotEmpty() == true } ?: own.badge.ifEmpty { shown.badge },
                        badgeColour = profile?.badgeColour?.takeIf { it.isNotEmpty() } ?: own.badgeColour.ifEmpty { shown.badgeColour },
                        bannerSource = profile?.bannerSource?.takeIf { it.isNotEmpty() } ?: own.bannerSource,
                    )
                }
                feed = shown.feed.items.map { it.ownedBy(found) }
                next = null
                answered = true
                trouble = null
                return
            }
            if (ticket != newest) return
            isSample = false
            profile = found
            feed = page.items
            next = page.next
            answered = true
            trouble = null
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
            val page = runCatching { community.feed(userId, before) }.getOrNull() ?: return
            if (ticket != newest) return
            // A post written while the page was read can appear on two pages; keep the first.
            val known = feed.mapTo(HashSet()) { it.key }
            feed = feed + page.items.filter { it.key !in known }
            next = page.next
        } finally {
            readingMore = false
        }
    }

    // ── the owner's posts ──

    suspend fun post(song: Song?) {
        val body = draft.trim()
        if (!isOwn || body.isEmpty() || sending) return
        sending = true
        try {
            community.post(body, if (withSong) song else null)
            // Only the text that was sent is removed. Anything typed while it was on its way stays.
            if (draft.trim() == body) draft = ""
            withSong = false
        } catch (_: Exception) {
            editTrouble = R.string.Rpm_Profile_Offline
            return
        } finally {
            sending = false
        }
        read()
    }

    suspend fun delete(post: Post) {
        runCatching { community.deletePost(post.postId) }.onFailure { editTrouble = R.string.Rpm_Profile_Offline; return }
        feed = feed.filterNot { it.key == FeedItem.OfPost(post).key }
        if (openPost?.postId == post.postId) closeThread()
    }

    suspend fun vote(post: Post, value: Int) {
        val changed = runCatching { community.vote(post, value) }.getOrNull() ?: return
        feed = feed.map { if (it is FeedItem.OfPost && it.post.postId == changed.postId) FeedItem.OfPost(changed) else it }
        if (openPost?.postId == changed.postId) openPost = changed
    }

    suspend fun voteReview(review: Review, value: Int) {
        val changed = community.vote(review.albumId, review.userId, value) ?: return
        feed = feed.map { if (it is FeedItem.OfReview && it.review.albumId == changed.albumId) FeedItem.OfReview(it.review.with(changed)) else it }
    }

    /** Returns null when the report was sent, or why it failed. */
    suspend fun report(post: Post, note: String): Int? =
        runCatching { community.report(ReportTarget.Post(post.ownerId, post.postId), "post", note.ifEmpty { null }) }
            .fold({ null }, { R.string.Rpm_Profile_Offline })

    // ── the person ──

    suspend fun friend(step: FriendStep) {
        runCatching {
            when (step) {
                FriendStep.Add -> community.requestFriend(userId)
                FriendStep.Accept -> community.acceptFriend(userId)
                FriendStep.Cancel, FriendStep.Decline, FriendStep.Remove -> community.removeFriend(userId)
            }
        }.onFailure { editTrouble = R.string.Rpm_Profile_Offline; return }
        read()
    }

    suspend fun block() {
        runCatching { community.block(userId) }.onFailure { editTrouble = R.string.Rpm_Profile_Offline; return }
        read()
    }

    suspend fun unblock() {
        runCatching { community.unblock(userId) }.onFailure { editTrouble = R.string.Rpm_Profile_Offline; return }
        read()
    }

    // ── the owner's page ──

    /**
     * Sends the badges this phone worked out when the server does not hold them all yet. The server notifies the reader
     * of each new one. Only on the reader's own page, once it is read: the badge shown and its colour go with them.
     * Returns true when the server took new badges, so the caller reads the notification count again.
     */
    suspend fun reportBadges(unlocked: List<String>): Boolean {
        val shown = profile ?: return false
        if (!isOwn || isSample || unlocked.all { it in shown.badges }) return false
        val stored = runCatching { community.setBadges(unlocked, shown.badge, shown.badgeColour) }.getOrNull() ?: return false
        profile = profile?.copy(badges = stored.badges)
        return true
    }

    /**
     * Saves everything changed on the edit page: the name, the photo, the banner and where it comes from, the
     * background, and the badge with its colour. Each part is sent only when it changed. Returns true when all of it was
     * saved; otherwise [editTrouble] says why, and the edit page stays open with the changes, to try again.
     *
     * On the sample page the banner's source, the background and the badge are kept here only, as the server cannot keep
     * them yet; [read] keeps them over the sample.
     *
     * @param ownBadges the badges this phone worked out, sent with the badge so one unlocked since the last send can be
     *   chosen at once.
     */
    internal suspend fun save(edit: PageEdit, ownBadges: List<String>): Boolean {
        val shown = profile ?: return false
        if (saving) return false
        saving = true
        try {
            val name = edit.name.trim()
            if (name != edit.nameAtStart) community.setDisplayName(name)
            val photo = edit.photo
            if (photo != null) community.setAvatar(photo, "image/jpeg") else if (edit.dropPhoto) community.deleteAvatar()
            val banner = edit.banner
            if (banner != null) community.setBanner(banner, "image/jpeg") else if (edit.dropBanner) community.deleteBanner()
            val badges = (shown.badges + ownBadges).distinct()
            if (isSample) {
                profile = profile?.copy(
                    bannerSource = if (edit.follows) "listening" else "photo",
                    pageGround = edit.ground.wire,
                    badges = badges,
                    badge = edit.badge,
                    badgeColour = edit.colour,
                )
            } else {
                if (edit.follows != edit.followsAtStart) community.setBannerSource(edit.follows)
                if (edit.ground != edit.groundAtStart) community.setPageGround(edit.ground)
                if (edit.badge != edit.badgeAtStart || edit.colour != edit.colourAtStart) community.setBadges(badges, edit.badge, edit.colour)
            }
            editTrouble = null
        } catch (stop: CancellationException) {
            throw stop
        } catch (_: Exception) {
            editTrouble = R.string.Rpm_Profile_Offline
            return false
        } finally {
            saving = false
        }
        read()
        return true
    }

    // ── the open thread ──

    var openPost by mutableStateOf<Post?>(null)
        private set
    var replies by mutableStateOf<List<Reply>>(emptyList())
        private set
    var threadAnswered by mutableStateOf(false)
        private set

    @get:StringRes
    var threadTrouble by mutableStateOf<Int?>(null)
        private set
    var replying by mutableStateOf(false)
        private set
    var replyDraft by mutableStateOf("")

    /** The reply the box answers; null for the post itself. */
    var replyingTo by mutableStateOf<Reply?>(null)

    private var threadTicket = 0

    val replyLines: List<ReplyTree.Line>
        get() = openPost?.let { ReplyTree(it.postId, replies).lines }.orEmpty()

    suspend fun open(post: Post) {
        openPost = post
        replies = emptyList()
        threadAnswered = false
        threadTrouble = null
        replyingTo = null
        readThread()
    }

    fun closeThread() {
        openPost = null
        replies = emptyList()
        replyingTo = null
    }

    private suspend fun readThread() {
        val post = openPost ?: return
        val ticket = ++threadTicket
        if (isSample) {
            replies = sampled?.replies?.get(post.postId).orEmpty()
            threadAnswered = true
            return
        }
        try {
            val said = community.replies(post.ownerId, post.postId)
            if (ticket != threadTicket || openPost?.postId != post.postId) return
            replies = said
            threadAnswered = true
            threadTrouble = null
        } catch (_: Exception) {
            if (ticket == threadTicket) threadTrouble = R.string.Rpm_Profile_Offline
        }
    }

    suspend fun sendReply() {
        val post = openPost ?: return
        val body = replyDraft.trim()
        if (body.isEmpty() || replying) return
        replying = true
        try {
            community.reply(post.ownerId, post.postId, replyingTo?.replyId, body)
            if (replyDraft.trim() == body) replyDraft = ""
            replyingTo = null
        } catch (_: Exception) {
            threadTrouble = R.string.Rpm_Profile_Offline
            return
        } finally {
            replying = false
        }
        readThread()
        // The feed has no read of a single post, so the first page is read again for the post's reply count.
        read()
    }

    suspend fun voteReply(reply: Reply, value: Int) {
        val post = openPost ?: return
        val changed = runCatching { community.vote(post.ownerId, reply, value) }.getOrNull() ?: return
        replies = replies.map { if (it.replyId == changed.replyId) changed else it }
    }

    suspend fun deleteReply(reply: Reply) {
        val post = openPost ?: return
        runCatching { community.deleteReply(post.ownerId, post.postId, reply.replyId) }
            .onFailure { threadTrouble = R.string.Rpm_Profile_Offline; return }
        readThread()
        read()
    }

    suspend fun reportReply(reply: Reply, note: String): Int? {
        val post = openPost ?: return null
        return runCatching { community.report(ReportTarget.Reply(post.ownerId, post.postId, reply.replyId), "reply", note.ifEmpty { null }) }
            .fold({ null }, { R.string.Rpm_Profile_Offline })
    }

    val friendship: Friendship get() = profile?.friendship ?: Friendship.None
}

/** A sample entry shown as written by [owner]: the reader's own name, on their own page. */
private fun FeedItem.ownedBy(owner: PersonProfile?): FeedItem {
    val name = owner?.displayName.orEmpty()
    val id = owner?.userId.orEmpty()
    return when (this) {
        is FeedItem.OfPost -> FeedItem.OfPost(post.copy(ownerId = id, authorName = name))
        is FeedItem.OfReview -> FeedItem.OfReview(review.copy(userId = id, authorName = name))
    }
}

/** The reader's notifications, and the count on the badge. It is the iPhone's `Notices`. */
class Notices(private val community: CommunityClient, private val sample: (() -> ProfileSample?)? = null) {
    var unread by mutableIntStateOf(0)
        private set
    var items by mutableStateOf<List<Notice>>(emptyList())
        private set
    var next by mutableStateOf<String?>(null)
        private set
    var answered by mutableStateOf(false)
        private set

    @get:StringRes
    var trouble by mutableStateOf<Int?>(null)
        private set

    /** Whether the sample's notifications were shown: its count then stays read, as real ones do. */
    private var sampleSeen = false

    /** Reads the count for the badge. A failure keeps the last count. */
    suspend fun readUnread() {
        try {
            unread = community.unreadNotices()
        } catch (missing: ApiException) {
            // A server with no notifications yet (404, or 401 for a route it does not have): the sample's count, so the
            // badge can be seen.
            if (missing is ApiException.NotFound || missing is ApiException.Unauthorized) {
                sample?.invoke()?.let { unread = if (sampleSeen) 0 else it.notices.unread }
            }
        } catch (_: Exception) {
            // The last count stays.
        }
    }

    /** Reads the first page, then marks everything on it as read: the list is on screen. */
    suspend fun read() {
        try {
            val page = try {
                community.notices()
            } catch (missing: ApiException) {
                if (missing !is ApiException.NotFound && missing !is ApiException.Unauthorized) throw missing
                sample?.invoke()?.notices?.also { items = it.items; next = null; answered = true; trouble = null; unread = 0; sampleSeen = true } ?: throw missing
                return
            }
            items = page.items
            next = page.next
            answered = true
            trouble = null
            val newest = page.items.firstOrNull()
            if (newest != null && page.unread > 0) {
                runCatching { community.markNoticesRead(newest.id) }
                unread = 0
            }
        } catch (_: Exception) {
            trouble = R.string.Rpm_Profile_Offline
        }
    }

    private var readingMore = false

    suspend fun more() {
        val before = next ?: return
        if (readingMore) return
        readingMore = true
        try {
            val page = runCatching { community.notices(before) }.getOrNull() ?: return
            val known = items.mapTo(HashSet()) { it.id }
            items = items + page.items.filter { it.id !in known }
            next = page.next
        } finally {
            readingMore = false
        }
    }

    suspend fun answer(userId: String, accept: Boolean) {
        runCatching { if (accept) community.acceptFriend(userId) else community.removeFriend(userId) }
        items = items.filterNot { it.kind == Notice.FRIEND_REQUEST && it.fromUserId == userId }
    }
}

/**
 * Sends what plays to the reader's profile, as "listening to". It is the iPhone's `ListeningShare`.
 *
 * At most one send a minute: a song that changes faster waits, and only the newest one is sent. The same song noted
 * again within 20 minutes is not sent again. After that it is, because the server stops showing a song 30 minutes
 * after it was sent.
 */
class ListeningShare(private val community: CommunityClient, private val scope: CoroutineScope) {
    private var sentKey: String? = null
    private var sentAt = 0L
    private var waiting: Job? = null

    fun note(song: Song?) {
        waiting?.cancel()
        waiting = null
        if (song == null || song.title.isEmpty()) {
            if (sentKey == null) return
            sentKey = null
            sentAt = 0
            scope.launch { runCatching { community.clearListening() } }
            return
        }
        val key = song.artist + "\u001F" + song.title
        val now = System.currentTimeMillis()
        if (key == sentKey && now - sentAt < 20 * 60_000L) return
        val wait = if (sentAt == 0L) 0L else (60_000L - (now - sentAt)).coerceAtLeast(0)
        waiting = scope.launch {
            if (wait > 0) delay(wait)
            sentKey = key
            sentAt = System.currentTimeMillis()
            runCatching { community.setListening(song) }
        }
    }
}
