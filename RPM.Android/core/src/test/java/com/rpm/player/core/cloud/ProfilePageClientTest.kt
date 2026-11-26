package com.rpm.player.core.cloud

import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.auth.AuthWire
import com.rpm.player.core.auth.Session
import com.rpm.player.core.auth.SessionStore
import com.rpm.player.core.net.WireJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profile page's calls and wire types. It is the iPhone's `ProfilePageClientTests`, on the same fixtures. Nothing
 * goes to the network: every request is answered by an interceptor here.
 */
class ProfilePageClientTest {

    private class Signed : SessionStore {
        override fun read() = Session("u", "e", "n", accessToken = "t", refreshToken = "r", expiresAtMillis = Long.MAX_VALUE)
        override fun write(session: Session) {}
        override fun clear() {}
    }

    private object NoWire : AuthWire {
        override suspend fun login(email: String, password: String) = error("not used")
        override suspend fun startSocial(provider: com.rpm.player.core.auth.Provider, returnTo: com.rpm.player.core.auth.SocialReturn) = error("not used")
        override suspend fun poll(sessionId: String) = error("not used")
        override suspend fun collect(sessionId: String, code: String) = error("not used")
        override suspend fun refresh(refreshToken: String) = error("not used")
        override suspend fun revoke(refreshToken: String) = error("not used")
        override suspend fun deleteAccount(accessToken: String, confirmEmail: String) = error("not used")
        override suspend fun register(name: String, email: String, password: String) = error("not used")
        override suspend fun confirm(email: String, code: String) = error("not used")
        override suspend fun resendCode(email: String) = error("not used")
        override suspend fun forgotPassword(email: String) = error("not used")
        override suspend fun resetPassword(email: String, code: String, password: String) = error("not used")
    }

    private val asked = mutableListOf<Request>()

    private fun community(json: String = "{}"): CommunityClient {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                asked += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val auth = AuthClient(NoWire, Signed(), clock = { 0L }).apply { load() }
        return CommunityClient(http, auth)
    }

    private fun bytes(request: Request): ByteArray {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return buffer.readByteArray()
    }

    private fun sent(request: Request): JsonObject = WireJson.parseToJsonElement(String(bytes(request))).jsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun fixture(name: String): String = javaClass.getResource("/fixtures/$name.json")!!.readText()

    @Serializable
    private data class RepliesFixture(val items: List<Reply>)

    // ── reading ──

    @Test fun aProfileReadsThePageFields() {
        val profile = WireJson.decodeFromString(PersonProfile.serializer(), fixture("profile-page"))
        assertTrue(profile.hasBanner)
        assertEquals(1_790_000_000_456L, profile.bannerUpdatedAt)
        assertEquals("Once in a Lifetime", profile.listening?.song?.title)
        assertEquals(1610, profile.counts?.albums)
        assertEquals(Friendship.Incoming, profile.friendship)
        assertFalse(profile.blocked)
        assertTrue(profile.sharesListening)
    }

    @Test fun aFriendshipWordFromANewerServerReadsAsNone() {
        val profile = WireJson.decodeFromString(PersonProfile.serializer(), """{"userId":"u1","friendship":"following"}""")
        assertEquals(Friendship.None, profile.friendship)
        val own = WireJson.decodeFromString(PersonProfile.serializer(), """{"userId":"u1","friendship":"self"}""")
        assertEquals(Friendship.Own, own.friendship)
    }

    @Test fun theFeedReadsPostsAndReviewsAndSkipsUnknownKinds() = runTest {
        val page = community(fixture("profile-feed")).feed("u-ana", before = 42)
        assertEquals(2, page.items.size)
        assertEquals(1_790_000_100_000L, page.next)
        assertEquals("limit=20&before=42", asked.single().url.query)

        val post = (page.items[0] as FeedItem.OfPost).post
        assertEquals("New speakers today.", post.body)
        assertEquals("Talking Heads", post.song?.artist)
        assertEquals(1, post.myVote)
        assertEquals(4, post.replyCount)

        val review = (page.items[1] as FeedItem.OfReview).review
        assertEquals(9, review.rating)
        assertEquals("Remain in Light", review.albumTitle)
        assertEquals(1980, review.albumYear)
        assertEquals(9, review.review.rating)
    }

    @Test fun repliesBecomeATreeInReadingOrder() {
        val replies = WireJson.decodeFromString(RepliesFixture.serializer(), fixture("post-replies")).items
        val tree = ReplyTree("p1", replies)
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5"), tree.lines.map { it.reply.replyId })
        assertEquals(listOf(0, 1, 2, 0, 0), tree.lines.map { it.depth })
        assertTrue(tree.lines[1].reply.deleted)
    }

    @Test fun notificationsKeepUnknownKindsApart() {
        val page = WireJson.decodeFromString(NoticePage.serializer(), fixture("notifications"))
        assertEquals(2, page.unread)
        assertNull(page.next)
        assertEquals(5, page.items.count { it.known })
        assertFalse(page.items.last().known)
        assertEquals(3, page.items[0].count)
    }

    // ── writing ──

    @Test fun aPostGoesToTheReadersOwnPage() = runTest {
        community("""{"ownerId":"u1","postId":"p9","body":"Hi"}""").post("Hi", Song("A", "T", "L"))
        val request = asked.single()
        assertEquals("POST", request.method)
        assertEquals("/profile/posts", request.url.encodedPath)
        val body = sent(request)
        assertEquals("Hi", body.text("body"))
        assertEquals("T", (body["song"] as JsonObject).text("title"))
    }

    @Test fun aReplyNamesItsPostAndParent() = runTest {
        community("""{"postId":"p1","replyId":"r9","parentId":"r1"}""").reply("u 1", "p1", "r1", "Yes")
        val request = asked.single()
        assertEquals("/profiles/u%201/posts/p1/replies", request.url.encodedPath)
        assertEquals("r1", sent(request).text("parentId"))
    }

    @Test fun aBannerIsSentAsBytesWithItsType() = runTest {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val profile = community("""{"userId":"u1","hasBanner":true,"bannerUpdatedAt":5}""").setBanner(jpeg, "image/jpeg")
        assertTrue(profile.hasBanner)
        val request = asked.single()
        assertEquals("/profile/banner", request.url.encodedPath)
        assertEquals("image/jpeg", request.body!!.contentType().toString())
        assertArrayEquals(jpeg, bytes(request))
    }

    @Test fun theBannerAddressCarriesItsStamp() {
        val profile = WireJson.decodeFromString(PersonProfile.serializer(), fixture("profile-page"))
        assertEquals("https://api.rpmrpmrpm.com/profiles/u-ana/banner?v=1790000000456", bannerAddress(profile))
    }
}
