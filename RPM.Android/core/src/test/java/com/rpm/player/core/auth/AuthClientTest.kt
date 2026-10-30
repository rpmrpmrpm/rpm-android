package com.rpm.player.core.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Two callers that need a token at once, and a sign-out that arrives while a refresh is running. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthClientTest {

    private class MemoryStore(var saved: Session? = null) : SessionStore {
        override fun read() = saved
        override fun write(session: Session) { saved = session }
        override fun clear() { saved = null }
    }

    /** A wire whose refresh answers only when the test says so. */
    private class HeldWire : AuthWire {
        var refreshes = 0
        val answer = CompletableDeferred<RefreshAnswer>()
        val revoked = mutableListOf<String>()
        override suspend fun login(email: String, password: String) = error("not used")
        override suspend fun startSocial(provider: Provider, returnTo: SocialReturn) = error("not used")
        override suspend fun poll(sessionId: String) = error("not used")
        override suspend fun collect(sessionId: String, code: String) = error("not used")
        override suspend fun refresh(refreshToken: String): RefreshAnswer {
            refreshes += 1
            return answer.await()
        }
        override suspend fun revoke(refreshToken: String) { revoked += refreshToken }
        override suspend fun deleteAccount(accessToken: String, confirmEmail: String) = error("not used")
        override suspend fun register(name: String, email: String, password: String) = error("not used")
        override suspend fun confirm(email: String, code: String) = error("not used")
        override suspend fun resendCode(email: String) = error("not used")
    }

    private val now = 10_000_000L

    private fun expiring() = Session("u", "e", "n", accessToken = "old", refreshToken = "r1", expiresAtMillis = now + 1_000)

    @Test fun twoCallersNeedingATokenAtOnceRefreshOnce() = runTest {
        val wire = HeldWire()
        val auth = AuthClient(wire, MemoryStore(expiring()), clock = { now }).apply { load() }

        val first = async { auth.accessToken() }
        val second = async { auth.accessToken() }
        runCurrent()
        wire.answer.complete(RefreshAnswer.Fresh(LoginResponse(accessToken = "new", expiresIn = 3600)))

        assertEquals("new", first.await())
        assertEquals("new", second.await())
        assertEquals(1, wire.refreshes)
    }

    @Test fun aRefreshAnsweredWithoutARefreshTokenKeepsTheOldOne() = runTest {
        val wire = HeldWire().apply { answer.complete(RefreshAnswer.Fresh(LoginResponse(accessToken = "new", expiresIn = 3600))) }
        val store = MemoryStore(expiring())
        val auth = AuthClient(wire, store, clock = { now }).apply { load() }

        auth.accessToken()

        assertEquals("r1", auth.session.value?.refreshToken)
        assertEquals("r1", store.saved?.refreshToken)
    }

    @Test fun aRefusedRefreshEndsTheSession() = runTest {
        val wire = HeldWire().apply { answer.complete(RefreshAnswer.Refused) }
        val store = MemoryStore(expiring())
        val auth = AuthClient(wire, store, clock = { now }).apply { load() }

        assertNull(auth.accessToken())
        assertNull(auth.session.value)
        assertNull(store.saved)
    }

    @Test fun anUnjudgedRefreshKeepsTheSessionAndHandsBackTheOldToken() = runTest {
        val wire = HeldWire().apply { answer.complete(RefreshAnswer.Unavailable) }
        val auth = AuthClient(wire, MemoryStore(expiring()), clock = { now }).apply { load() }

        assertEquals("old", auth.accessToken())
        assertEquals("r1", auth.session.value?.refreshToken)
    }

    @Test fun aSignOutDuringARefreshIsNotUndoneByTheRefreshAnswer() = runTest {
        val wire = HeldWire()
        val store = MemoryStore(expiring())
        val auth = AuthClient(wire, store, clock = { now }).apply { load() }

        val token = async { auth.accessToken() }
        runCurrent()
        val signOut = launch { auth.signOut() }
        runCurrent()
        wire.answer.complete(RefreshAnswer.Fresh(LoginResponse(accessToken = "new", refreshToken = "r2", expiresIn = 3600)))
        token.await()
        signOut.join()

        assertNull(auth.session.value)
        assertNull(store.saved)
        // The sign-out ran after the refresh, so it revoked the refresh token that the refresh handed back.
        assertEquals(listOf("r2"), wire.revoked)
    }

    @Test fun aFreshTokenIsReturnedWithoutAskingTheServer() = runTest {
        val wire = HeldWire()
        val session = Session("u", "e", "n", "fresh", "r1", expiresAtMillis = now + AuthClient.REFRESH_MARGIN_MS + 60_000)
        val auth = AuthClient(wire, MemoryStore(session), clock = { now }).apply { load() }

        assertEquals("fresh", auth.accessToken())
        assertEquals(0, wire.refreshes)
    }
}
