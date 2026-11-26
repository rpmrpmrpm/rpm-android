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
 * Choosing a new password on the phone: the address, the mailed code with the new password, then a sign-in with that
 * password. Checked against a wire that answers as told, through [SignIn], which owns the reset and the sign-in card it
 * goes back to.
 */
class ResetFormTest {
    private class Wire : AuthWire {
        var forgot: () -> SignUpAnswer = { SignUpAnswer.Done }
        var reset: () -> SignUpAnswer = { SignUpAnswer.Done }
        var login: () -> LoginAnswer = {
            LoginAnswer.In(LoginResponse(userId = "u", accessToken = "t", refreshToken = "r", expiresIn = 3600))
        }
        val asked = mutableListOf<String>()

        override suspend fun forgotPassword(email: String): SignUpAnswer {
            asked += "forgot $email"
            return forgot()
        }
        override suspend fun resetPassword(email: String, code: String, password: String): SignUpAnswer {
            asked += "reset $email $code $password"
            return reset()
        }
        override suspend fun login(email: String, password: String): LoginAnswer {
            asked += "login $email $password"
            return login()
        }
        override suspend fun register(name: String, email: String, password: String): SignUpAnswer = throw IOException("not asked")
        override suspend fun confirm(email: String, code: String): SignUpAnswer = throw IOException("not asked")
        override suspend fun resendCode(email: String): SignUpAnswer = throw IOException("not asked")
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

    /** A sign-in screen whose work runs at once on the calling thread: the fake wire never suspends. */
    private class Form(online: Boolean = true) {
        val wire = Wire()
        val auth = AuthClient(wire, Store()) { 0L }
        val signIn = SignIn(auth, CoroutineScope(Dispatchers.Unconfined), bringBack = {}, online = { online })
        val reset get() = signIn.reset
    }

    @Test fun forgotPasswordOpensTheResetWithTheTypedAddress() {
        val form = Form()
        form.signIn.email = " a@b.c "
        form.signIn.forgotPassword()
        assertEquals(ResetPassword.Step.Email("a@b.c"), form.reset.step)
        assertEquals(emptyList<String>(), form.wire.asked)
    }

    @Test fun aSentCodeGoesToTheCodeStepForTheTrimmedAddress() {
        val form = Form()
        form.signIn.forgotPassword()
        form.reset.send(" a@b.c ")
        assertEquals(ResetPassword.Step.Code("a@b.c"), form.reset.step)
        assertEquals(listOf("forgot  a@b.c "), form.wire.asked)
        assertNull(form.reset.trouble)
        assertFalse(form.reset.busy)
    }

    @Test fun anEmptyAddressAsksForItWithoutAsking() {
        val form = Form()
        form.signIn.forgotPassword()
        form.reset.send("  ")
        assertEquals(Said(R.string.Reset_NeedEmail), form.reset.trouble)
        assertEquals(ResetPassword.Step.Email(""), form.reset.step)
        assertEquals(emptyList<String>(), form.wire.asked)
    }

    @Test fun eachRefusalHasItsOwnSentence() {
        val expected = mapOf(
            "too_many" to R.string.SignUp_TooMany,
            "invalid_code" to R.string.SignUp_InvalidCode,
            "code_expired" to R.string.Reset_CodeExpired,
            "weak_password" to R.string.SignUp_WeakPassword,
            "invalid_email" to R.string.SignUp_InvalidEmail,
        )
        for (failed in listOf(R.string.Reset_SendFailed, R.string.Reset_Failed)) {
            for ((code, id) in expected) assertEquals(code, id, ResetPassword.said(SignUpAnswer.Refused(code), failed))
            assertEquals(failed, ResetPassword.said(SignUpAnswer.Refused("something_new"), failed))
            assertEquals(failed, ResetPassword.said(SignUpAnswer.Refused("refused"), failed))
            assertEquals(failed, ResetPassword.said(SignUpAnswer.Failed(502), failed))
        }
    }

    @Test fun aRefusedSendSaysWhyAndStaysOnTheAddress() {
        val form = Form()
        form.wire.forgot = { SignUpAnswer.Refused("too_many") }
        form.signIn.forgotPassword()
        form.reset.send("a@b.c")
        assertEquals(Said(R.string.SignUp_TooMany), form.reset.trouble)
        assertEquals(ResetPassword.Step.Email(""), form.reset.step)
        form.wire.forgot = { SignUpAnswer.Failed(500) }
        form.reset.send("a@b.c")
        assertEquals(Said(R.string.Reset_SendFailed), form.reset.trouble)
    }

    @Test fun anOfflinePhoneSaysSoWithoutAsking() {
        val form = Form(online = false)
        form.signIn.forgotPassword()
        form.reset.send("a@b.c")
        assertEquals(Said(R.string.Rpm_Net_Offline), form.reset.trouble)
        assertEquals(emptyList<String>(), form.wire.asked)
    }

    @Test fun aServiceThatCannotBeReachedSaysTheNetworkFailed() {
        val form = Form()
        form.wire.forgot = { throw IOException("down") }
        form.signIn.forgotPassword()
        form.reset.send("a@b.c")
        assertEquals(Said(R.string.Rpm_Net_Failed), form.reset.trouble)
        assertFalse(form.reset.busy)
    }

    @Test fun anEmptyCodeOrPasswordAsksForBothWithoutAsking() {
        val form = Form()
        form.signIn.forgotPassword()
        form.reset.send("a@b.c")
        form.wire.asked.clear()
        form.reset.save(" ", "Passw0rd")
        assertEquals(Said(R.string.Reset_NeedFields), form.reset.trouble)
        form.reset.save("123456", "")
        assertEquals(Said(R.string.Reset_NeedFields), form.reset.trouble)
        assertEquals(emptyList<String>(), form.wire.asked)
    }

    @Test fun aSavedPasswordSignsInWithItAndCloses() {
        val form = Form()
        form.signIn.forgotPassword()
        form.reset.send("a@b.c")
        form.reset.save("123456", "N3wPassword")
        assertEquals(listOf("forgot a@b.c", "reset a@b.c 123456 N3wPassword", "login a@b.c N3wPassword"), form.wire.asked)
        assertNotNull(form.auth.session.value)
        assertNull(form.reset.step)
        assertNull(form.signIn.notice)
    }

    /** The code is used up, so a failed sign-in goes back to the sign-in card with the address and a note, not to the
     * code step. */
    @Test fun aSignInThatFailsAfterTheResetGoesBackToSignInWithTheAddress() {
        val form = Form()
        form.wire.login = { LoginAnswer.Refused(503) }
        form.signIn.forgotPassword()
        form.reset.send("a@b.c")
        form.reset.save("123456", "N3wPassword")
        assertNull(form.reset.step)
        assertNull(form.auth.session.value)
        assertEquals("a@b.c", form.signIn.email)
        assertEquals(Said(R.string.Reset_Done), form.signIn.notice)
        assertNull(form.signIn.trouble)
        assertFalse(form.reset.busy)
    }

    @Test fun aSignInThatCannotReachTheServiceAfterTheResetAlsoGoesBack() {
        val form = Form()
        form.wire.login = { throw IOException("down") }
        form.signIn.forgotPassword()
        form.reset.send("a@b.c")
        form.reset.save("123456", "N3wPassword")
        assertNull(form.reset.step)
        assertEquals(Said(R.string.Reset_Done), form.signIn.notice)
    }

    @Test fun aRefusedResetSaysWhyAndStaysOnTheCodeStep() {
        val form = Form()
        form.signIn.forgotPassword()
        form.reset.send("a@b.c")
        form.wire.reset = { SignUpAnswer.Refused("code_expired") }
        form.reset.save("111111", "N3wPassword")
        assertEquals(Said(R.string.Reset_CodeExpired), form.reset.trouble)
        assertEquals(ResetPassword.Step.Code("a@b.c"), form.reset.step)
        assertNull(form.auth.session.value)
        form.wire.reset = { SignUpAnswer.Refused("refused") }
        form.reset.save("111111", "N3wPassword")
        assertEquals(Said(R.string.Reset_Failed), form.reset.trouble)
    }

    @Test fun aResentCodeSaysSo() {
        val form = Form()
        form.signIn.forgotPassword()
        form.reset.send("a@b.c")
        form.reset.resend()
        assertEquals(Said(R.string.SignUp_CodeResent), form.reset.notice)
        assertEquals(listOf("forgot a@b.c", "forgot a@b.c"), form.wire.asked)
        form.wire.forgot = { SignUpAnswer.Refused("too_many") }
        form.reset.resend()
        assertEquals(Said(R.string.SignUp_TooMany), form.reset.trouble)
        assertNull(form.reset.notice)
    }

    @Test fun closingGoesBackToSignInAndKeepsItsAddress() {
        val form = Form()
        form.signIn.email = "a@b.c"
        form.signIn.forgotPassword()
        form.reset.close()
        assertNull(form.reset.step)
        assertEquals("a@b.c", form.signIn.email)
    }
}
