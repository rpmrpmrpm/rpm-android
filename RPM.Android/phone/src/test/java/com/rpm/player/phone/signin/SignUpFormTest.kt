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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * Signing up on the phone, as the iPhone's `SignUpCard` does it: the form, the mailed code, then a sign-in with the
 * password just chosen. Checked against a wire that answers as told.
 */
class SignUpFormTest {
    private class Wire : AuthWire {
        var register: () -> SignUpAnswer = { SignUpAnswer.Done }
        var confirm: () -> SignUpAnswer = { SignUpAnswer.Done }
        var resend: () -> SignUpAnswer = { SignUpAnswer.Done }
        var login: () -> LoginAnswer = {
            LoginAnswer.In(LoginResponse(userId = "u", accessToken = "t", refreshToken = "r", expiresIn = 3600))
        }
        val asked = mutableListOf<String>()

        override suspend fun register(name: String, email: String, password: String): SignUpAnswer {
            asked += "register $name $email $password"
            return register()
        }
        override suspend fun confirm(email: String, code: String): SignUpAnswer {
            asked += "confirm $email $code"
            return confirm()
        }
        override suspend fun resendCode(email: String): SignUpAnswer {
            asked += "resend $email"
            return resend()
        }
        override suspend fun login(email: String, password: String): LoginAnswer {
            asked += "login $email $password"
            return login()
        }
        override suspend fun startSocial(provider: Provider, returnTo: SocialReturn): SocialStart = throw IOException("not asked")
        override suspend fun poll(sessionId: String): SocialPoll = throw IOException("not asked")
        override suspend fun collect(sessionId: String, code: String): SocialPoll = throw IOException("not asked")
        override suspend fun refresh(refreshToken: String): RefreshAnswer = RefreshAnswer.Unavailable
        override suspend fun revoke(refreshToken: String) = Unit
        override suspend fun deleteAccount(accessToken: String, confirmEmail: String): Int = throw IOException("not asked")
    }

    private class Store : SessionStore {
        var held: Session? = null
        override fun read(): Session? = held
        override fun write(session: Session) { held = session }
        override fun clear() { held = null }
    }

    private class Form(online: Boolean = true) {
        val wire = Wire()
        val auth = AuthClient(wire, Store()) { 0L }
        val signUp = SignUp(auth, CoroutineScope(Dispatchers.Unconfined), online = { online }).apply { open() }
    }

    /** The wire trims what it sends, as it does for `auth/login`. The code step names the trimmed address. */
    @Test fun aMadeAccountGoesToTheCodeStepForTheTrimmedAddress() {
        val form = Form()
        form.signUp.create(" Hélène ", " a@b.c ", "Passw0rd")
        assertEquals(SignUp.Step.Code("a@b.c", "Passw0rd"), form.signUp.step)
        assertEquals(listOf("register  Hélène   a@b.c  Passw0rd"), form.wire.asked)
        assertNull(form.signUp.trouble)
        assertFalse(form.signUp.busy)
    }

    @Test fun aRefusalSaysItsOwnSentenceAndStaysOnTheForm() {
        val form = Form()
        form.wire.register = { SignUpAnswer.Refused("social_account") }
        form.signUp.create("Jan", "a@b.c", "Passw0rd")
        assertEquals(Said(R.string.SignUp_SocialAccount), form.signUp.trouble)
        assertEquals(SignUp.Step.Form, form.signUp.step)
    }

    @Test fun eachRefusalHasItsOwnSentence() {
        val expected = mapOf(
            "already_registered" to R.string.SignUp_AlreadyRegistered,
            "social_account" to R.string.SignUp_SocialAccount,
            "weak_password" to R.string.SignUp_WeakPassword,
            "invalid_email" to R.string.SignUp_InvalidEmail,
            "invalid_name" to R.string.SignUp_InvalidName,
            "invalid_code" to R.string.SignUp_InvalidCode,
            "code_expired" to R.string.SignUp_CodeResent,
            "code_resent" to R.string.SignUp_CodeResent,
            "too_many" to R.string.SignUp_TooMany,
            "something_new" to R.string.SignUp_Failed,
        )
        for ((code, id) in expected) assertEquals(code, id, SignUp.said(SignUpAnswer.Refused(code)))
        assertEquals(R.string.SignUp_Failed, SignUp.said(SignUpAnswer.Failed(502)))
    }

    @Test fun emptyFieldsAskForAllThreeWithoutAsking() {
        val form = Form()
        form.signUp.create(" ", "a@b.c", "Passw0rd")
        assertEquals(Said(R.string.SignUp_NeedFields), form.signUp.trouble)
        form.signUp.create("Jan", "", "Passw0rd")
        form.signUp.create("Jan", "a@b.c", "")
        assertEquals(Said(R.string.SignUp_NeedFields), form.signUp.trouble)
        assertEquals(emptyList<String>(), form.wire.asked)
    }

    @Test fun anOfflinePhoneSaysSoWithoutAsking() {
        val form = Form(online = false)
        form.signUp.create("Jan", "a@b.c", "Passw0rd")
        assertEquals(Said(R.string.Rpm_Net_Offline), form.signUp.trouble)
        assertEquals(emptyList<String>(), form.wire.asked)
    }

    @Test fun aServiceThatCannotBeReachedSaysTheNetworkFailed() {
        val form = Form()
        form.wire.register = { throw IOException("down") }
        form.signUp.create("Jan", "a@b.c", "Passw0rd")
        assertEquals(Said(R.string.Rpm_Net_Failed), form.signUp.trouble)
        assertFalse(form.signUp.busy)
    }

    @Test fun aConfirmedCodeSignsInWithThePasswordAndCloses() {
        val form = Form()
        form.signUp.create("Jan", "a@b.c", "Passw0rd")
        form.signUp.confirm("123456")
        assertEquals(listOf("register Jan a@b.c Passw0rd", "confirm a@b.c 123456", "login a@b.c Passw0rd"), form.wire.asked)
        assertNotNull(form.auth.session.value)
        assertNull(form.signUp.step)
    }

    @Test fun aWrongCodeStaysOnTheCodeStepAndDoesNotSignIn() {
        val form = Form()
        form.wire.confirm = { SignUpAnswer.Refused("invalid_code") }
        form.signUp.atCode("a@b.c", "Passw0rd")
        form.signUp.confirm("111111")
        assertEquals(Said(R.string.SignUp_InvalidCode), form.signUp.trouble)
        assertEquals(SignUp.Step.Code("a@b.c", "Passw0rd"), form.signUp.step)
        assertNull(form.auth.session.value)
    }

    @Test fun anEmptyCodeAsksForItWithoutAsking() {
        val form = Form()
        form.signUp.atCode("a@b.c", "Passw0rd")
        form.signUp.confirm("  ")
        assertEquals(Said(R.string.SignUp_NeedCode), form.signUp.trouble)
        assertEquals(emptyList<String>(), form.wire.asked)
    }

    @Test fun aSignInThatFailsAfterTheCodeSaysWhyAndStays() {
        val form = Form()
        form.wire.login = { LoginAnswer.WrongPassword }
        form.signUp.atCode("a@b.c", "Passw0rd")
        form.signUp.confirm("123456")
        assertEquals(Said(R.string.Login_WrongPassword), form.signUp.trouble)
        assertEquals(SignUp.Step.Code("a@b.c", "Passw0rd"), form.signUp.step)
    }

    @Test fun aResentCodeSaysSo() {
        val form = Form()
        form.signUp.atCode("a@b.c", "Passw0rd")
        form.signUp.resend()
        assertEquals(Said(R.string.SignUp_CodeResent), form.signUp.notice)
        assertEquals(listOf("resend a@b.c"), form.wire.asked)
        form.wire.resend = { SignUpAnswer.Refused("too_many") }
        form.signUp.resend()
        assertEquals(Said(R.string.SignUp_TooMany), form.signUp.trouble)
        assertNull(form.signUp.notice)
    }

    @Test fun closingGoesBackToSignIn() {
        val form = Form()
        form.signUp.close()
        assertNull(form.signUp.step)
    }
}
