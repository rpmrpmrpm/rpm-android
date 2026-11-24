package com.rpm.player.core.cloud

import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.auth.AuthWire
import com.rpm.player.core.auth.Provider
import com.rpm.player.core.auth.Session
import com.rpm.player.core.auth.SessionStore
import com.rpm.player.core.auth.SocialReturn
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Google Play purchase, as the phone hands it to the accounts service. The service checks it with Google and
 * acknowledges it; the phone only sends it, and sends it again on the next start when it did not arrive. */
class SupporterClientTest {
    private fun body(request: Request): String = Buffer().also { request.body?.writeTo(it) }.readUtf8()

    private val standing = """{"grSupporter":true,"grSupporterUntil":1800000000,"grSupporterStore":"google","grUnlimitedStorage":true,"grTester":false}"""

    @Test fun thePurchaseTokenGoesToTheAccountsServiceWithTheAccountsToken() = runTest {
        val asked = mutableListOf<Request>()
        val said = client(asked) { 200 to standing }.postGoogle("tok1")

        assertEquals(SupporterPost.Confirmed(SupporterAnswer(true, 1_800_000_000, "google", true, false)), said)
        val request = asked.single()
        assertEquals("https://accounts.rpmrpmrpm.com/supporter/google", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("Bearer token", request.header("Authorization"))
        assertEquals("""{"purchaseToken":"tok1"}""", body(request))
    }

    @Test fun eachAnswerIsRead() = runTest {
        assertEquals(SupporterPost.OtherAccount, client { 409 to """{"error":"otherAccount"}""" }.postGoogle("t"))
        assertEquals(SupporterPost.Unavailable, client { 409 to """{"error":"noAccount"}""" }.postGoogle("t"))
        assertEquals(SupporterPost.Refused, client { 400 to """{"error":"notOurs"}""" }.postGoogle("t"))
        assertEquals(SupporterPost.Unavailable, client { 503 to """{"error":"unavailable"}""" }.postGoogle("t"))
        assertEquals(SupporterPost.Unavailable, client { 401 to "" }.postGoogle("t"))
        assertEquals(SupporterPost.Unavailable, client { 200 to "not json" }.postGoogle("t"))
    }

    @Test fun noNetworkIsUnavailable() = runTest {
        assertEquals(SupporterPost.Unavailable, client { throw IOException("offline") }.postGoogle("t"))
    }

    @Test fun onlyBoughtSupporterPurchasesAreSent() = runTest {
        val sent = mutableListOf<String>()
        val poster = SupporterPosting { token -> sent += token; SupporterPost.Confirmed(SupporterAnswer(true, 1, "google", true, false)) }

        val said = SupporterPurchases.send(listOf(
            HeldPurchase("bought", purchased = true, products = listOf("rpm_supporter")),
            HeldPurchase("pending", purchased = false, products = listOf("rpm_supporter")),
            HeldPurchase("other", purchased = true, products = listOf("something_else")),
        ), poster)

        assertEquals(listOf("bought"), sent)
        assertEquals(1, said.size)
    }

    @Test fun aPurchaseTheServiceConfirmedEndsConfirmed() {
        val confirmed = SupporterPost.Confirmed(SupporterAnswer(true, 1, "google", true, false))
        assertEquals(PurchaseOutcome.CONFIRMED, SupporterPurchases.outcomeOf(listOf(SupporterPost.Unavailable, confirmed), pending = false))
    }

    @Test fun anotherAccountsSubscriptionIsSaid() {
        assertEquals(PurchaseOutcome.OTHER_ACCOUNT, SupporterPurchases.outcomeOf(listOf(SupporterPost.OtherAccount), pending = false))
    }

    @Test fun aPendingPurchaseWithNothingSentIsPending() {
        assertEquals(PurchaseOutcome.PENDING, SupporterPurchases.outcomeOf(emptyList(), pending = true))
    }

    @Test fun anythingElseIsNotConfirmedYet() {
        assertEquals(PurchaseOutcome.FAILED, SupporterPurchases.outcomeOf(listOf(SupporterPost.Unavailable), pending = false))
        assertEquals(PurchaseOutcome.FAILED, SupporterPurchases.outcomeOf(emptyList(), pending = false))
    }

    private fun client(asked: MutableList<Request> = mutableListOf(), answer: (Request) -> Pair<Int, String>): SupporterClient {
        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                asked += request
                val (code, body) = answer(request)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("answer")
                    .body(body.toResponseBody()).build()
            })
            .build()
        val session = Session("u", "e", "n", accessToken = "token", refreshToken = "", expiresAtMillis = Long.MAX_VALUE)
        val auth = AuthClient(NoWire, Holding(session), clock = { 0L }).apply { load() }
        return SupporterClient(http, auth)
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
    }
}
