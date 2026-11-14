package com.rpm.player.core.cloud

import com.rpm.player.core.R
import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.auth.AuthWire
import com.rpm.player.core.auth.Session
import com.rpm.player.core.auth.SessionStore
import com.rpm.player.core.net.WireJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The artist wall's calls on [CommunityClient]: what is asked for, what is sent, and how the lines read. It is the
 * iPhone's `ArtistWallClientTests`. Nothing goes to the network: every request is answered by an interceptor here.
 */
class ArtistWallClientTest {

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
    }

    /** The requests the client sent, in order. */
    private val asked = mutableListOf<Request>()

    /** A client whose every request is answered with [status] and [json], and kept in [asked]. */
    private fun community(status: Int = 200, json: String = "{}"): CommunityClient {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                asked += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val auth = AuthClient(NoWire, Signed(), clock = { 0L }).apply { load() }
        return CommunityClient(http, auth)
    }

    private fun sent(request: Request): JsonObject {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return WireJson.parseToJsonElement(buffer.readUtf8()).jsonObject
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name.json")!!.readText()

    // ── the lines ──

    @Test fun theWallFixtureReadsBothKindsOfLine() {
        val wall = WireJson.decodeFromString(ListSerializer(WallEntry.serializer()), fixture("artist-wall"))
        assertEquals(4, wall.size)

        val post = wall[0]
        assertTrue(post.isPost && !post.isReview)
        assertNull(post.lineKey)
        assertEquals("Saw them in 1980.", post.body)
        assertEquals(1_790_000_000_003L, post.createdAt)

        val review = wall[1]
        assertTrue(review.isReview && !review.isPost)
        assertEquals(R.string.Artists_WallWroteReview, review.lineKey)
        assertEquals(4.5, review.stars, 0.0)
        assertEquals("Remain in Light", review.albumTitle)

        val rating = wall[2]
        assertEquals(R.string.Artists_WallRated, rating.lineKey)
        assertTrue(rating.authorDeleted)
    }

    /** A kind this build does not know is neither a post nor a review, so the page does not draw it as a post with no words. */
    @Test fun aKindFromANewerServerIsNeitherPostNorReview() {
        val unknown = WireJson.decodeFromString(ListSerializer(WallEntry.serializer()), fixture("artist-wall"))[3]
        assertFalse(unknown.isPost)
        assertFalse(unknown.isReview)
        assertNull(unknown.lineKey)
        assertEquals(0, unknown.rating)
        assertNull(unknown.albumTitle)
    }

    // ── finding the artist ──

    @Test fun anArtistIsLookedUpByNameWithoutCreatingOne() = runTest {
        val client = community(json = """{"artistId":"ar_7f3c","name":"Talking Heads","createdAt":1790000000000}""")

        val artist = client.lookupArtist("Talking Heads")

        assertEquals("ar_7f3c", artist?.artistId)
        val request = asked.single()
        assertEquals("GET", request.method)
        assertEquals("/artists/lookup", request.url.encodedPath)
        assertEquals("name=Talking%20Heads", request.url.encodedQuery)
    }

    /** Nobody has written about most artists. That is a wall with nothing on it, not a failure. */
    @Test fun anArtistNobodyWroteAboutIsNull() = runTest {
        assertNull(community(404, "").lookupArtist("Talking Heads"))
    }

    /** A failure throws, so the page can say the wall is not available instead of saying it is empty. */
    @Test fun aWallThatCannotBeReadThrows() = runTest {
        try {
            community(500, "").lookupArtist("Talking Heads")
            fail("a 500 was taken for an answer")
        } catch (e: ApiException.Status) {
            assertEquals(500, e.code)
        }
    }

    @Test fun aBlankNameIsNotAsked() = runTest {
        assertNull(community().lookupArtist("  "))
        assertTrue(asked.isEmpty())
    }

    @Test fun theWallIsReadNewestFirstAsTheServerSendsIt() = runTest {
        val client = community(json = fixture("artist-wall"))

        val wall = client.wall("ar_7f3c")

        assertEquals(listOf("0001790000000003_p1", "0001790000000002_r1", "0001790000000001_r2", "0001790000000000_x1"), wall.map { it.entryId })
        assertEquals("https://api.rpmrpmrpm.com/artists/ar_7f3c/wall?limit=50", asked.single().url.toString())
    }

    // ── writing ──

    @Test fun aPostGoesToTheArtistsWall() = runTest {
        val client = community(json = """{"artistId":"ar 1","entryId":"e1","kind":"post","userId":"u","body":"Hi"}""")

        val entry = client.postToWall("ar 1", "Hi")

        val request = asked.single()
        assertTrue(entry.isPost)
        assertEquals("POST", request.method)
        assertEquals("https://api.rpmrpmrpm.com/artists/ar%201/wall", request.url.toString())
        assertEquals("Hi", sent(request).text("body"))
    }

    @Test fun resolvingAnArtistSendsTheName() = runTest {
        val client = community(json = """{"artistId":"ar_7f3c","name":"Talking Heads"}""")

        client.resolveArtist("Talking Heads")

        val request = asked.single()
        assertEquals("POST", request.method)
        assertEquals("/artists/resolve", request.url.encodedPath)
        assertEquals("Talking Heads", sent(request).text("name"))
    }

    @Test fun deletingAPostNamesTheArtistAndTheLine() = runTest {
        community(204, "").deleteWallEntry("ar_7f3c", "0001790000000003_p1")

        val request = asked.single()
        assertEquals("DELETE", request.method)
        assertEquals("/artists/ar_7f3c/wall/0001790000000003_p1", request.url.encodedPath)
    }

    // ── reporting ──

    /** The server reads `targetType`, and a wall post report must name the artist. Without either it answers 400 and the report is lost. */
    @Test fun aWallPostReportNamesTheArtistAndTheLine() = runTest {
        community(json = """{"reportId":"r1"}""").report(ReportTarget.WallPost(artistId = "ar_7f3c", entryId = "e1"), reason = "wallpost", note = "Spam")

        val body = sent(asked.single())
        assertEquals("wallpost", body.text("targetType"))
        assertEquals("ar_7f3c", body.text("artistId"))
        assertEquals("e1", body.text("targetId"))
        assertEquals("Spam", body.text("note"))
        assertFalse(body.containsKey("albumId"))
    }

    @Test fun aCommentReportNamesTheAlbum() = runTest {
        community(json = """{"reportId":"r1"}""").report(ReportTarget.Comment(albumId = "al_1", commentId = "c9"), reason = null, note = null)

        val body = sent(asked.single())
        assertEquals("comment", body.text("targetType"))
        assertEquals("al_1", body.text("albumId"))
        assertEquals("c9", body.text("targetId"))
        assertFalse(body.containsKey("artistId"))
        assertFalse(body.containsKey("reason"))
    }
}
