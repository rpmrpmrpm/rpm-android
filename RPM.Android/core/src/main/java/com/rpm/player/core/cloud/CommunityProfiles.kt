package com.rpm.player.core.cloud

import com.rpm.player.core.net.Hosts
import com.rpm.player.core.net.WireJson
import com.rpm.player.core.net.escapeSegment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The profile page's wire types and calls: a person's page, what they play, their posts and the replies under them,
// friends, notifications and blocks. They match the iPhone's `CommunityProfiles.swift` and
// `RPMCommunityClient+Profiles.swift`. Times are Unix milliseconds, as everywhere in the community API.
//
// [WireJson] ignores unknown fields and reads a missing field as its default, so a page from an older or newer server
// still reads.

/** A song, as a post carries it and as "listening to" shows it. [albumId] is the album's rpm id when the app knew it. */
@Serializable
data class Song(val artist: String = "", val title: String = "", val album: String = "", val albumId: String? = null)

/** What a person is playing now, and since when. */
@Serializable
data class Listening(
    val artist: String = "",
    val title: String = "",
    val album: String = "",
    val albumId: String? = null,
    val at: Long = 0,
) {
    val song: Song get() = Song(artist, title, album, albumId)
}

/** The size of a person's collection, as their app last reported it. */
@Serializable
data class Counts(val albums: Int = 0, val tracks: Int = 0, val updatedAt: Long = 0)

/** Where the reader stands with another person. A word this build does not know reads as [None]. */
@Serializable
enum class Friendship {
    /** The reader's own page. */
    @SerialName("self") Own,
    @SerialName("none") None,

    /** The reader asked; the other person has not answered. */
    @SerialName("requested") Requested,

    /** The other person asked the reader. */
    @SerialName("incoming") Incoming,
    @SerialName("friends") Friends,
}

/** A person's profile as their page reads it: `profiles/{id}`, or `profile` for the reader's own. */
@Serializable
data class PersonProfile(
    val userId: String = "",
    val displayName: String = "",
    val hasAvatar: Boolean = false,
    val avatarUpdatedAt: Long = 0,
    /** Milliseconds, as the rest of the community API. */
    val createdAt: Long = 0,
    val supporter: Boolean = false,
    val hasBanner: Boolean = false,
    val bannerUpdatedAt: Long = 0,
    /** Null when nothing plays, when it is old, or when the person turned sharing off. */
    val listening: Listening? = null,
    val counts: Counts? = null,
    val friendship: Friendship = Friendship.None,
    /** The reader has blocked this person. */
    val blocked: Boolean = false,
    /** Only on the reader's own profile; true elsewhere. */
    val sharesListening: Boolean = true,
    /**
     * What the owner's page is drawn on: empty for the app's standard background, `color:#RRGGBB` for a solid colour,
     * or `backdrop:{id}` for one of the app's backgrounds. See [PageGround].
     */
    val pageGround: String = "",
    /** `photo` (the uploaded banner; also what an empty value means) or `listening` (the playing artist's photo). */
    val bannerSource: String = "",
    /** The badges unlocked, by id, oldest first. See [ProfileBadges]. */
    val badges: List<String> = emptyList(),
    /** The badge shown under the name, or empty. */
    val badge: String = "",
    /** The badge's colour, `#RRGGBB`, or empty for the first of [ProfileBadges.colours]. */
    val badgeColour: String = "",
) {
    /** The banner shows the photo of the artist playing, and not an uploaded picture. */
    val bannerFollowsListening: Boolean get() = bannerSource == "listening"
}

/** What a profile page is drawn on, read from [PersonProfile.pageGround]. A value this build cannot read is [Standard]. */
sealed interface PageGround {
    data object Standard : PageGround
    data class Colour(val argb: Long) : PageGround
    data class Backdrop(val id: String) : PageGround

    /** The value as the server stores it. */
    val wire: String
        get() = when (this) {
            Standard -> ""
            is Colour -> "color:#%06X".format(argb and 0xFFFFFF)
            is Backdrop -> "backdrop:$id"
        }

    companion object {
        fun of(wire: String): PageGround = when {
            wire.startsWith("color:#") && wire.length == 13 ->
                wire.substring(7).toLongOrNull(16)?.let { Colour(0xFF000000 or it) } ?: Standard
            wire.startsWith("backdrop:") && wire.length > 9 -> Backdrop(wire.substring(9))
            else -> Standard
        }
    }
}

/** A post on a person's page. The author is always the page's owner. [myVote] is the reader's: 1, -1 or 0. */
@Serializable
data class Post(
    val ownerId: String = "",
    val postId: String = "",
    val authorName: String = "",
    val hasAvatar: Boolean = false,
    val body: String = "",
    val song: Song? = null,
    val createdAt: Long = 0,
    val ups: Int = 0,
    val downs: Int = 0,
    val score: Int = 0,
    val myVote: Int = 0,
    val replyCount: Int = 0,
    val authorDeleted: Boolean = false,
    val authorSupporter: Boolean = false,
)

/**
 * A reply under a post or under another reply. [parentId] is the post id for a reply to the post itself. [deleted]:
 * the reply was deleted but has replies under it, so it keeps its place in the thread with no text.
 */
@Serializable
data class Reply(
    val postId: String = "",
    val replyId: String = "",
    val parentId: String = "",
    val userId: String = "",
    val authorName: String = "",
    val hasAvatar: Boolean = false,
    val body: String = "",
    val createdAt: Long = 0,
    val ups: Int = 0,
    val downs: Int = 0,
    val score: Int = 0,
    val myVote: Int = 0,
    val authorDeleted: Boolean = false,
    val deleted: Boolean = false,
    val authorSupporter: Boolean = false,
)

/** A review as a feed carries it: the review's own fields and the album's, side by side in one object. */
@Serializable
data class FeedReview(
    val albumId: String = "",
    val userId: String = "",
    val authorName: String = "",
    val hasAvatar: Boolean = false,
    val rating: Int = 0,
    val body: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val ups: Int = 0,
    val downs: Int = 0,
    val score: Int = 0,
    val myVote: Int = 0,
    val authorDeleted: Boolean = false,
    val albumArtist: String = "",
    val albumTitle: String = "",
    val albumYear: Int = 0,
    val authorSupporter: Boolean = false,
) {
    val review: Review
        get() = Review(albumId, userId, authorName, hasAvatar, rating, body, createdAt, updatedAt, ups, downs, score, myVote, authorDeleted, authorSupporter)

    /** The same entry with the review's counts and vote from [changed]. */
    fun with(changed: Review) = copy(ups = changed.ups, downs = changed.downs, score = changed.score, myVote = changed.myVote)
}

/** One entry of a feed. */
sealed interface FeedItem {
    val key: String
    val createdAt: Long

    data class OfPost(val post: Post) : FeedItem {
        override val key get() = "post:" + post.postId
        override val createdAt get() = post.createdAt
    }

    data class OfReview(val review: FeedReview) : FeedItem {
        // The author is part of the key: on the Feed tab two people's reviews of one record stand side by side.
        override val key get() = "review:" + review.userId + ":" + review.albumId
        override val createdAt get() = review.createdAt
    }
}

/** A page of a feed. [next] is the `before` of the next page; null at the end. Entries of an unknown kind are left out. */
data class FeedPage(val items: List<FeedItem>, val next: Long?)

@Serializable
private data class FeedPageWire(val items: List<FeedItemWire> = emptyList(), val next: Long? = null)

@Serializable
private data class FeedItemWire(val kind: String = "", val post: Post? = null, val review: FeedReview? = null)

@Serializable
private data class RepliesWire(val items: List<Reply> = emptyList())

/** A person in a list: friends, requests, blocked people. [since] is when that began. */
@Serializable
data class PersonEntry(
    val userId: String = "",
    val displayName: String = "",
    val hasAvatar: Boolean = false,
    val avatarUpdatedAt: Long = 0,
    val since: Long = 0,
)

@Serializable
data class Friends(
    val friends: List<PersonEntry> = emptyList(),
    val incoming: List<PersonEntry> = emptyList(),
    val requested: List<PersonEntry> = emptyList(),
)

/**
 * A notification. [kind] is kept as the server's word, so a kind this build does not know can be left out of the
 * list rather than fail the page ([Notice.known]). [count] is how many votes a vote entry stands for; 1 otherwise.
 */
@Serializable
data class Notice(
    val id: String = "",
    val kind: String = "",
    val fromUserId: String = "",
    val fromName: String = "",
    val fromHasAvatar: Boolean = false,
    val ownerId: String? = null,
    val postId: String? = null,
    val replyId: String? = null,
    val albumId: String? = null,
    val albumTitle: String? = null,
    val count: Int = 1,
    val createdAt: Long = 0,
    val read: Boolean = false,
    /** The badge a `badge` notification is about. */
    val badgeId: String? = null,
) {
    val known: Boolean get() = kind in KINDS

    companion object {
        const val REPLY = "reply"
        const val VOTE = "vote"
        const val FRIEND_REQUEST = "friendRequest"
        const val FRIEND_ACCEPTED = "friendAccepted"
        const val FRIEND_REVIEW = "friendReview"
        const val BADGE = "badge"
        private val KINDS = setOf(REPLY, VOTE, FRIEND_REQUEST, FRIEND_ACCEPTED, FRIEND_REVIEW, BADGE)
    }
}

@Serializable
data class NoticePage(val items: List<Notice> = emptyList(), val unread: Int = 0, val next: String? = null)

@Serializable
private data class UnreadWire(val unread: Int = 0)

@Serializable
private data class PeopleWire(val items: List<PersonEntry> = emptyList())

@Serializable
private data class SharingBody(val sharesListening: Boolean)

@Serializable
private data class CountsBody(val albums: Int, val tracks: Int)

@Serializable
private data class NewPostBody(val body: String, val song: Song? = null)

@Serializable
private data class NewReplyBody(val body: String, val parentId: String? = null)

@Serializable
private data class ValueBody(val value: Int)

@Serializable
private data class UpToBody(val upTo: String)

@Serializable
private data class DisplayNameBody(val displayName: String)

// ── calls ──

/** The reader's own profile, with [PersonProfile.sharesListening]. */
suspend fun CommunityClient.ownPage(): PersonProfile = get("profile", PersonProfile.serializer())

/** Another person's profile, or null when they have none (404). */
suspend fun CommunityClient.personPage(userId: String): PersonProfile? = try {
    get("profiles/${escapeSegment(userId)}", PersonProfile.serializer())
} catch (_: ApiException.NotFound) {
    null
}

/** `profiles/{id}/avatar`, stamped when the stamp is known. Null when they have no photo. */
fun avatarAddress(userId: String, hasAvatar: Boolean, stamp: Long = 0): String? =
    if (!hasAvatar || userId.isEmpty()) null
    else Hosts.API + "profiles/" + escapeSegment(userId) + "/avatar" + (if (stamp > 0) "?v=$stamp" else "")

/** `profiles/{id}/banner`, stamped as the photo is. Null when they have no banner. */
fun bannerAddress(profile: PersonProfile): String? =
    if (!profile.hasBanner || profile.userId.isEmpty()) null
    else Hosts.API + "profiles/" + escapeSegment(profile.userId) + "/banner" +
        (if (profile.bannerUpdatedAt > 0) "?v=${profile.bannerUpdatedAt}" else "")

/** Sets the reader's photo: JPEG, PNG or WebP, at most 512 KB. */
suspend fun CommunityClient.setAvatar(bytes: ByteArray, type: String): PersonProfile =
    sendBytes("POST", "profile/avatar", bytes, type, PersonProfile.serializer())

suspend fun CommunityClient.deleteAvatar() {
    send<Unit>("DELETE", "profile/avatar", null, null)
}

/** Sets the reader's banner: JPEG, PNG or WebP, at most 2 MB. */
suspend fun CommunityClient.setBanner(bytes: ByteArray, type: String): PersonProfile =
    sendBytes("POST", "profile/banner", bytes, type, PersonProfile.serializer())

suspend fun CommunityClient.deleteBanner() {
    send<Unit>("DELETE", "profile/banner", null, null)
}

suspend fun CommunityClient.setDisplayName(name: String): PersonProfile =
    send("PUT", "profile", WireJson.encodeToString(DisplayNameBody.serializer(), DisplayNameBody(name)), PersonProfile.serializer())!!

/** Turns sharing of what plays on or off, for every device of the account. Off also clears what is shown now. */
suspend fun CommunityClient.setSharesListening(on: Boolean) {
    send<Unit>("PUT", "profile/sharing", WireJson.encodeToString(SharingBody.serializer(), SharingBody(on)), null)
}

/** What the reader plays now. The server ignores it while sharing is off. */
suspend fun CommunityClient.setListening(song: Song) {
    send<Unit>("PUT", "profile/listening", WireJson.encodeToString(Song.serializer(), song), null)
}

suspend fun CommunityClient.clearListening() {
    send<Unit>("DELETE", "profile/listening", null, null)
}

@Serializable
private data class GroundBody(val ground: String)

@Serializable
private data class BannerSourceBody(val source: String)

@Serializable
private data class BadgesBody(val unlocked: List<String>, val badge: String, val badgeColour: String)

/** Where the banner comes from: [listening] true for the playing artist's photo, false for the uploaded picture. */
suspend fun CommunityClient.setBannerSource(listening: Boolean) {
    val body = BannerSourceBody(if (listening) "listening" else "photo")
    send<Unit>("PUT", "profile/banner-source", WireJson.encodeToString(BannerSourceBody.serializer(), body), null)
}

/**
 * Sends the badges this phone worked out ([ProfileBadges.unlocked]) and the one to show. The server adds [unlocked] to
 * what it holds, never removes one, and notifies the reader of each one it did not hold yet.
 */
suspend fun CommunityClient.setBadges(unlocked: List<String>, badge: String, badgeColour: String): PersonProfile =
    send("PUT", "profile/badges", WireJson.encodeToString(BadgesBody.serializer(), BadgesBody(unlocked, badge, badgeColour)), PersonProfile.serializer())!!

/** What the reader's page is drawn on. See [PageGround]. */
suspend fun CommunityClient.setPageGround(ground: PageGround) {
    send<Unit>("PUT", "profile/ground", WireJson.encodeToString(GroundBody.serializer(), GroundBody(ground.wire)), null)
}

suspend fun CommunityClient.setCounts(albums: Int, tracks: Int) {
    send<Unit>("PUT", "profile/counts", WireJson.encodeToString(CountsBody.serializer(), CountsBody(albums, tracks)), null)
}

/** A page of a person's feed, newest first. [before] is the [FeedPage.next] of the page before; null for the first. */
suspend fun CommunityClient.feed(userId: String, before: Long? = null, limit: Int = 20): FeedPage {
    val path = "profiles/${escapeSegment(userId)}/feed?limit=$limit" + (before?.let { "&before=$it" } ?: "")
    return get(path, FeedPageWire.serializer()).read()
}

/**
 * A page of everyone's posts and album reviews, newest first: the Feed tab. [before] is the [FeedPage.next] of the page
 * before. A page can come back empty while [FeedPage.next] is set, when everything on it was by someone blocked: keep
 * reading.
 */
suspend fun CommunityClient.everyone(before: Long? = null, limit: Int = 20): FeedPage =
    get("feed?limit=$limit" + (before?.let { "&before=$it" } ?: ""), FeedPageWire.serializer()).read()

/** The entries this build knows, in order. */
private fun FeedPageWire.read(): FeedPage = FeedPage(
    items.mapNotNull { entry ->
        when (entry.kind) {
            "post" -> entry.post?.let { FeedItem.OfPost(it) }
            "review" -> entry.review?.let { FeedItem.OfReview(it) }
            else -> null
        }
    },
    next,
)

/** Posts on the reader's own page. */
suspend fun CommunityClient.post(body: String, song: Song?): Post =
    send("POST", "profile/posts", WireJson.encodeToString(NewPostBody.serializer(), NewPostBody(body, song)), Post.serializer())!!

suspend fun CommunityClient.deletePost(postId: String) {
    send<Unit>("DELETE", "profile/posts/${escapeSegment(postId)}", null, null)
}

private fun postPath(ownerId: String, postId: String) = "profiles/${escapeSegment(ownerId)}/posts/${escapeSegment(postId)}"

/** [value]: 1 up, -1 down, 0 to take the vote back. */
suspend fun CommunityClient.vote(post: Post, value: Int): Post =
    send("POST", postPath(post.ownerId, post.postId) + "/vote", WireJson.encodeToString(ValueBody.serializer(), ValueBody(value)), Post.serializer())!!

/** Every reply under a post, oldest first. See [ReplyTree]. */
suspend fun CommunityClient.replies(ownerId: String, postId: String): List<Reply> =
    get(postPath(ownerId, postId) + "/replies", RepliesWire.serializer()).items

/** [parentId]: the reply this answers; null for a reply to the post itself. */
suspend fun CommunityClient.reply(ownerId: String, postId: String, parentId: String?, body: String): Reply =
    send("POST", postPath(ownerId, postId) + "/replies", WireJson.encodeToString(NewReplyBody.serializer(), NewReplyBody(body, parentId)), Reply.serializer())!!

suspend fun CommunityClient.deleteReply(ownerId: String, postId: String, replyId: String) {
    send<Unit>("DELETE", postPath(ownerId, postId) + "/replies/${escapeSegment(replyId)}", null, null)
}

suspend fun CommunityClient.vote(ownerId: String, reply: Reply, value: Int): Reply =
    send(
        "POST",
        postPath(ownerId, reply.postId) + "/replies/${escapeSegment(reply.replyId)}/vote",
        WireJson.encodeToString(ValueBody.serializer(), ValueBody(value)),
        Reply.serializer(),
    )!!

/** Asks to be friends. If that person already asked the reader, this accepts instead. */
suspend fun CommunityClient.requestFriend(userId: String) {
    send<Unit>("POST", "friends/${escapeSegment(userId)}", null, null)
}

suspend fun CommunityClient.acceptFriend(userId: String) {
    send<Unit>("POST", "friends/${escapeSegment(userId)}/accept", null, null)
}

/** Cancels the reader's request, declines theirs, or ends a friendship. */
suspend fun CommunityClient.removeFriend(userId: String) {
    send<Unit>("DELETE", "friends/${escapeSegment(userId)}", null, null)
}

suspend fun CommunityClient.friends(): Friends = get("friends", Friends.serializer())

/** A page of the reader's notifications, newest first. */
suspend fun CommunityClient.notices(before: String? = null): NoticePage =
    get("notifications" + (before?.let { "?before=" + escapeSegment(it) } ?: ""), NoticePage.serializer())

suspend fun CommunityClient.unreadNotices(): Int = get("notifications/unread", UnreadWire.serializer()).unread

/** Marks this notification and every older one as read. */
suspend fun CommunityClient.markNoticesRead(upTo: String) {
    send<Unit>("POST", "notifications/read", WireJson.encodeToString(UpToBody.serializer(), UpToBody(upTo)), null)
}

/** Blocks a person. It also ends a friendship or a request between the two. */
suspend fun CommunityClient.block(userId: String) {
    send<Unit>("POST", "blocks/${escapeSegment(userId)}", null, null)
}

suspend fun CommunityClient.unblock(userId: String) {
    send<Unit>("DELETE", "blocks/${escapeSegment(userId)}", null, null)
}

suspend fun CommunityClient.blocked(): List<PersonEntry> = get("blocks", PeopleWire.serializer()).items

/**
 * The replies of one post as a tree, built from each reply's [Reply.parentId]: every reply in reading order, each one
 * followed by the replies under it, with its depth (0 for a reply to the post). A reply whose parent is not in the list
 * is shown at the top level, so no reply is lost.
 */
class ReplyTree(postId: String, replies: List<Reply>) {
    data class Line(val reply: Reply, val depth: Int)

    val lines: List<Line>

    init {
        val known = replies.mapTo(HashSet()) { it.replyId }
        val children = replies.groupBy { if (it.parentId in known) it.parentId else postId }
        val out = ArrayList<Line>(replies.size)
        val seen = HashSet<String>()
        // An explicit stack and not recursion: a thread can be deep.
        val stack = ArrayDeque<Line>()
        children[postId].orEmpty().asReversed().forEach { stack.addLast(Line(it, 0)) }
        while (stack.isNotEmpty()) {
            val line = stack.removeLast()
            if (!seen.add(line.reply.replyId)) continue
            out += line
            children[line.reply.replyId].orEmpty().asReversed().forEach { stack.addLast(Line(it, line.depth + 1)) }
        }
        lines = out
    }
}

/**
 * The sample page that a test build shows while the server has no profile page yet (`profile_sample.json`, the same
 * file as the iPhone's `profile-sample.json`). It is read with the real wire types. Every time in it is moved so that
 * the newest entry is from a few minutes ago.
 */
class ProfileSample(
    val counts: Counts?,
    val listening: Listening?,
    /** The badge the sample shows while the reader has chosen none, and its colour. */
    val badge: String,
    val badgeColour: String,
    /** Other people's sample pages, by user id. */
    val people: Map<String, PersonProfile>,
    /** The sample's Feed tab: everyone's posts and reviews, newest first. */
    val everyone: List<FeedItem>,
    /** Whose photo each sample person borrows, by user id: an artist's, found as the artist page finds it. */
    val faces: Map<String, String>,
    val feed: FeedPage,
    val replies: Map<String, List<Reply>>,
    val notices: NoticePage,
) {
    companion object {
        fun parse(json: String, nowMillis: Long): ProfileSample {
            val wire = WireJson.decodeFromString(SampleWire.serializer(), json)
            val shift = nowMillis - wire.now
            fun shifted(entry: FeedItemWire): FeedItem? = when (entry.kind) {
                "post" -> entry.post?.let { FeedItem.OfPost(it.copy(createdAt = it.createdAt + shift)) }
                "review" -> entry.review?.let { FeedItem.OfReview(it.copy(createdAt = it.createdAt + shift, updatedAt = it.updatedAt + shift)) }
                else -> null
            }
            val items = wire.feed.items.mapNotNull(::shifted)
            return ProfileSample(
                counts = wire.profile.counts?.copy(updatedAt = nowMillis),
                listening = wire.profile.listening?.let { it.copy(at = it.at + shift) },
                badge = wire.profile.badge,
                badgeColour = wire.profile.badgeColour,
                people = wire.people.mapValues { (_, person) ->
                    person.copy(createdAt = person.createdAt, listening = person.listening?.let { it.copy(at = it.at + shift) })
                },
                everyone = wire.everyone.mapNotNull(::shifted),
                faces = wire.faces,
                feed = FeedPage(items, null),
                replies = wire.replies.mapValues { (_, list) -> list.map { it.copy(createdAt = it.createdAt + shift) } },
                notices = wire.notifications.copy(items = wire.notifications.items.map { it.copy(createdAt = it.createdAt + shift) }),
            )
        }
    }
}

@Serializable
private data class SampleWire(
    val now: Long = 0,
    val profile: SampleProfileWire = SampleProfileWire(),
    val feed: FeedPageWire = FeedPageWire(),
    val replies: Map<String, List<Reply>> = emptyMap(),
    val notifications: NoticePage = NoticePage(),
    val people: Map<String, PersonProfile> = emptyMap(),
    val everyone: List<FeedItemWire> = emptyList(),
    val faces: Map<String, String> = emptyMap(),
)

@Serializable
private data class SampleProfileWire(
    val counts: Counts? = null,
    val listening: Listening? = null,
    val badge: String = "",
    val badgeColour: String = "",
)
