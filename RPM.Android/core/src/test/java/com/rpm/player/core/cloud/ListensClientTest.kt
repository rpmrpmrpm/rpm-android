package com.rpm.player.core.cloud

import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.auth.AuthWire
import com.rpm.player.core.auth.Provider
import com.rpm.player.core.auth.Session
import com.rpm.player.core.auth.SessionStore
import com.rpm.player.core.auth.SocialReturn
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The listening log and the generated lists, as this device asks the store for them. */
class ListensClientTest {
    private fun body(request: Request): String = Buffer().also { request.body?.writeTo(it) }.readUtf8()

    @Test fun listensArePutUnderTheDeviceWithEveryField() = runTest {
        val asked = mutableListOf<Request>()
        val cloud = client(asked) { 200 to """{"receivedAt":1790000000123}""" }

        val stamp = cloud.putListens(ListensReport("tv-1", listOf(
            CloudListen("l1", "t1", 1_790_000_000, 120.5, 241.0, true, 23, 5))))

        assertEquals(1_790_000_000_123, stamp)
        val request = asked.single()
        assertEquals("PUT", request.method)
        assertEquals("/cloud/listens", request.url.encodedPath)
        val json = Json.parseToJsonElement(body(request)).jsonObject
        assertEquals("tv-1", json["deviceId"]!!.jsonPrimitive.content)
        val listen = json["listens"]!!.jsonArray.single().jsonObject
        assertEquals("l1", listen["listenId"]!!.jsonPrimitive.content)
        assertEquals("t1", listen["rpmId"]!!.jsonPrimitive.content)
        assertEquals(1_790_000_000L, listen["at"]!!.jsonPrimitive.long)
        assertEquals(true, listen["counted"]!!.jsonPrimitive.boolean)
        assertEquals(23, listen["localHour"]!!.jsonPrimitive.int)
        assertEquals(5, listen["localDayOfWeek"]!!.jsonPrimitive.int)
    }

    @Test fun theListsAreAskedForInTheAppsLanguageAndZone() = runTest {
        val asked = mutableListOf<Request>()
        val cloud = client(asked) { 200 to """{"recipe":1,"computedAt":1,"lists":[]}""" }

        cloud.moodLists(Shelf.Own, "zh-Hans", "America/Argentina/Buenos_Aires")

        val url = asked.single().url
        assertEquals("zh-Hans", url.queryParameter("lang"))
        assertEquals("America/Argentina/Buenos_Aires", url.queryParameter("tz"))
    }

    @Test fun aBorrowedShelfsListsKeepTheirOwner() = runTest {
        val asked = mutableListOf<Request>()
        val cloud = client(asked) { 200 to """{"recipe":1,"computedAt":1,"lists":[]}""" }

        cloud.moodLists(Shelf.Borrowed("owner-1"), "en", "Europe/Paris")

        val url = asked.single().url
        assertEquals("owner-1", url.queryParameter("owner"))
        assertEquals("Europe/Paris", url.queryParameter("tz"))
    }

    @Test fun aPressIsAnsweredWithANewReading() = runTest {
        val asked = mutableListOf<Request>()
        val cloud = client(asked) { 200 to """{"id":"L","trackIds":["a","b"],"shows":50}""" }

        val reading = cloud.nextReading("L", 5, "fr", "Europe/Paris")

        assertEquals(ListReading("L", listOf("a", "b"), 50), reading)
        val request = asked.single()
        assertEquals("POST", request.method)
        assertEquals("/cloud/moodlists/next", request.url.encodedPath)
        val json = Json.parseToJsonElement(body(request)).jsonObject
        assertEquals("L", json["id"]!!.jsonPrimitive.content)
        assertEquals(5, json["reading"]!!.jsonPrimitive.int)
        assertEquals("fr", json["lang"]!!.jsonPrimitive.content)
        assertEquals("Europe/Paris", json["tz"]!!.jsonPrimitive.content)
    }

    /** Still building, or no longer offered: not a failure. The app shows the next slice it was sent. */
    @Test fun aPressTheStoreCannotAnswerYetIsNull() = runTest {
        assertNull(client(mutableListOf()) { 409 to """{"message":"building"}""" }.nextReading("L", 1, "en", "UTC"))
        assertNull(client(mutableListOf()) { 404 to "{}" }.nextReading("L", 1, "en", "UTC"))
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
