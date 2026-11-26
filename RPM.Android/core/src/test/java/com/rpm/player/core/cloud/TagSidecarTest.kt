package com.rpm.player.core.cloud

import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.auth.AuthWire
import com.rpm.player.core.auth.Provider
import com.rpm.player.core.auth.Session
import com.rpm.player.core.auth.SessionStore
import com.rpm.player.core.auth.SocialReturn
import com.rpm.player.core.net.WireJson
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sidecar, which is two hops and a different casing. The iOS app checks the same (CloudClientTests.swift). */
class TagSidecarTest {

    /** The iPhone's Fixtures/sidecar.json, without the cover, which this app does not read. */
    private val fixture = """
        {
          "RelativePath": "Talk Talk/Laughing Stock/01 Myrrhman.flac",
          "Extension": ".flac",
          "RpmId": "rpm_0000000000000001",
          "DurationSeconds": 335.4,
          "Title": "Myrrhman",
          "Artist": "Talk Talk",
          "AlbumArtist": "Talk Talk",
          "Album": "Laughing Stock",
          "Genre": "Art Rock",
          "TrackNumber": 1,
          "TrackCount": 6,
          "DiscNumber": 1,
          "DiscCount": 1,
          "Copyright": "℗ 1991 Polydor Ltd. (UK)",
          "Publisher": "Verve Records",
          "Art": "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQ==",
          "ArtMime": "image/jpeg"
        }
    """.trimIndent()

    /** The sidecar is a blob the uploader wrote, not a Lambda response, so it is PascalCase where the whole rest of
     *  this API is camelCase. */
    @Test fun theSidecarDecodesItsPascalCaseFields() {
        val sidecar = WireJson.decodeFromString(TagSidecar.serializer(), fixture)
        assertEquals("Myrrhman", sidecar.title)
        assertEquals("Laughing Stock", sidecar.album)
        assertTrue(sidecar.relativePath.endsWith("Myrrhman.flac"))
        assertEquals("image/jpeg", sidecar.artMime)
        assertEquals(6, sidecar.trackCount)
        assertEquals("℗ 1991 Polydor Ltd. (UK)", sidecar.copyright)
        assertEquals("Verve Records", sidecar.publisher)
    }

    /** Two hops: the API signs a URL, and the bytes come from the store. The second hop must carry no bearer: a
     *  presigned URL is signed for exactly the request it describes, and an extra header is a different request. */
    @Test fun theSidecarSecondHopSendsNoBearer() = runTest {
        val asked = mutableListOf<Request>()
        val cloud = client(asked) { request ->
            if (request.url.host == "s3.example.com") 200 to fixture else 200 to """{"url":"https://s3.example.com/sidecar.json"}"""
        }

        val sidecar = cloud.sidecar("rpm_1")

        assertEquals("Myrrhman", sidecar?.title)
        assertEquals(2, asked.size)
        assertTrue(asked[0].url.encodedPath.endsWith("/cloud/sidecar/rpm_1"))
        assertEquals("Bearer token", asked[0].header("Authorization"))
        assertEquals("s3.example.com", asked[1].url.host)
        assertNull(asked[1].header("Authorization"))
    }

    /** A track with no sidecar is a track with no sidecar, not an error. */
    @Test fun aMissingSidecarIsNilRatherThanAThrow() = runTest {
        assertNull(client(mutableListOf()) { 404 to "{}" }.sidecar("rpm_1"))
    }

    /** A signed URL whose object is gone is the same: nothing to read. */
    @Test fun aSidecarTheStoreRefusesIsNil() = runTest {
        val cloud = client(mutableListOf()) { request ->
            if (request.url.host == "s3.example.com") 403 to "" else 200 to """{"url":"https://s3.example.com/sidecar.json"}"""
        }
        assertNull(cloud.sidecar("rpm_1"))
    }

    /** A cloud client whose every request is answered by [answer], with a signed-in session whose token is fresh. */
    private fun client(asked: MutableList<Request>, answer: (Request) -> Pair<Int, String>): CloudClient {
        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                asked += request
                val (code, body) = answer(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("answer")
                    .body(body.toResponseBody())
                    .build()
            })
            .build()
        val session = Session("u", "e", "n", accessToken = "token", refreshToken = "", expiresAtMillis = Long.MAX_VALUE)
        val auth = AuthClient(NoWire, Holding(session), clock = { 0L }).apply { load() }
        return CloudClient(http, auth)
    }

    private class Holding(private val session: Session) : SessionStore {
        override fun read() = session
        override fun write(session: Session) {}
        override fun clear() {}
    }

    /** The token is fresh, so the accounts service is never asked. */
    private object NoWire : AuthWire {
        override suspend fun login(email: String, password: String) = error("not used")
        override suspend fun startSocial(provider: Provider, returnTo: SocialReturn) = error("not used")
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
}
