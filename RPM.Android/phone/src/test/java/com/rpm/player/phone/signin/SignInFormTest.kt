package com.rpm.player.phone.signin

import com.rpm.player.core.R
import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.auth.AuthWire
import com.rpm.player.core.auth.LoginAnswer
import com.rpm.player.core.auth.LoginResponse
import com.rpm.player.core.auth.Provider
import com.rpm.player.core.auth.RefreshAnswer
import com.rpm.player.core.auth.Session
import com.rpm.player.core.auth.SessionStore
import com.rpm.player.core.auth.SignUpAnswer
import com.rpm.player.core.auth.SocialPoll
import com.rpm.player.core.auth.SocialReturn
import com.rpm.player.core.auth.SocialStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * What the sign-in form says for each answer of `auth/login`, as the iPhone's `AppModel.signIn(email:password:)` and
 * `RPMError.userMessage` say it. The real account cannot be signed out on the test phone to look at these, so they are
 * checked here against a wire that answers as told.
 */
class SignInFormTest {
    /** Answers `auth/login` with [answer], or throws it; counts the calls. Nothing else is asked in these tests. */
    private class Wire(private val answer: () -> LoginAnswer) : AuthWire {
        var logins = 0
        override suspend fun login(email: String, password: String): LoginAnswer {
            logins++
            return answer()
        }
        override suspend fun startSocial(provider: Provider, returnTo: SocialReturn): SocialStart = throw IOException("not asked")
        override suspend fun poll(sessionId: String): SocialPoll = throw IOException("not asked")
        override suspend fun collect(sessionId: String, code: String): SocialPoll = throw IOException("not asked")
        override suspend fun refresh(refreshToken: String): RefreshAnswer = RefreshAnswer.Unavailable
        override suspend fun revoke(refreshToken: String) = Unit
        override suspend fun deleteAccount(accessToken: String, confirmEmail: String): Int = throw IOException("not asked")
        override suspend fun register(name: String, email: String, password: String): SignUpAnswer = throw IOException("not asked")
        override suspend fun confirm(email: String, code: String): SignUpAnswer = throw IOException("not asked")
        override suspend fun resendCode(email: String): SignUpAnswer = throw IOException("not asked")
    }

    private class Store : SessionStore {
        var held: Session? = null
        override fun read(): Session? = held
        override fun write(session: Session) { held = session }
        override fun clear() { held = null }
    }

    /** A form whose work runs at once on the calling thread: the fake wire never suspends. */
    private fun form(online: Boolean = true, answer: () -> LoginAnswer): Pair<SignIn, Wire> {
        val wire = Wire(answer)
        val auth = AuthClient(wire, Store()) { 0L }
        return SignIn(auth, CoroutineScope(Dispatchers.Unconfined), bringBack = {}, online = { online }) to wire
    }

    @Test fun aWrongPasswordSaysSo() {
        val (signIn, _) = form { LoginAnswer.WrongPassword }
        signIn.withPassword("a@b.c", "x")
        assertEquals(Said(R.string.Login_WrongPassword), signIn.trouble)
        assertFalse(signIn.busy)
    }

    /** The mail carries only a code, so an account that was never confirmed goes to the code step, not to a sentence. */
    @Test fun anUnconfirmedAddressOpensTheCodeStep() {
        val (signIn, _) = form { LoginAnswer.NotConfirmed }
        signIn.withPassword(" a@b.c ", "x")
        assertNull(signIn.trouble)
        assertEquals(SignUp.Step.Code("a@b.c", "x"), signIn.signUp.step)
        assertFalse(signIn.busy)
    }

    @Test fun aServerFailureGivesItsStatus() {
        val (signIn, _) = form { LoginAnswer.Refused(503) }
        signIn.withPassword("a@b.c", "x")
        assertEquals(Said(R.string.Rpm_Error_Status, listOf(503)), signIn.trouble)
    }

    @Test fun anAnswerWithoutATokenIsUnableToSignIn() {
        val (signIn, _) = form { LoginAnswer.Refused(200) }
        signIn.withPassword("a@b.c", "x")
        assertEquals(Said(R.string.Login_Unable), signIn.trouble)
    }

    @Test fun anotherRefusalIsTheGeneralFailure() {
        val (signIn, _) = form { LoginAnswer.Refused(429) }
        signIn.withPassword("a@b.c", "x")
        assertEquals(Said(R.string.Rpm_SignIn_Failed), signIn.trouble)
    }

    @Test fun aServiceThatCannotBeReachedSaysTheNetworkFailed() {
        val (signIn, _) = form { throw IOException("down") }
        signIn.withPassword("a@b.c", "x")
        assertEquals(Said(R.string.Rpm_Net_Failed), signIn.trouble)
        assertFalse(signIn.busy)
    }

    @Test fun anOfflinePhoneSaysSoWithoutAsking() {
        val (signIn, wire) = form(online = false) { LoginAnswer.WrongPassword }
        signIn.withPassword("a@b.c", "x")
        assertEquals(Said(R.string.Rpm_Net_Offline), signIn.trouble)
        assertEquals(0, wire.logins)
        signIn.withProvider(Provider.GOOGLE)
        assertEquals(Said(R.string.Rpm_Net_Offline), signIn.trouble)
        assertFalse(signIn.busy)
        assertFalse(signIn.waitingForBrowser)
    }

    @Test fun emptyFieldsAskForBothWithoutAsking() {
        val (signIn, wire) = form { LoginAnswer.WrongPassword }
        signIn.withPassword("  ", "x")
        assertEquals(Said(R.string.Login_NeedFields), signIn.trouble)
        signIn.withPassword("a@b.c", "")
        assertEquals(Said(R.string.Login_NeedFields), signIn.trouble)
        assertEquals(0, wire.logins)
    }

    @Test fun aSignInClearsTheTroubleAndTheNotice() {
        val (signIn, _) = form { LoginAnswer.In(LoginResponse(userId = "u", accessToken = "t", refreshToken = "r", expiresIn = 3600)) }
        signIn.notice = Said(R.string.Account_DeletedBody)
        signIn.report(Said(R.string.Login_NeedFields))
        signIn.withPassword("a@b.c", "x")
        assertNull(signIn.trouble)
        assertNull(signIn.notice)
    }
}
