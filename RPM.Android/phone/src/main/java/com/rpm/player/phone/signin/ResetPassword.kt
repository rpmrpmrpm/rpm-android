package com.rpm.player.phone.signin

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rpm.player.core.R
import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.auth.LoginAnswer
import com.rpm.player.core.auth.SignUpAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Choosing a new password for an rpm account, in place of the sign-in card. The address goes to the accounts service
 * (`auth/forgot-password`), which mails a six-digit code. The code and the new password go back
 * (`auth/reset-password`), and the account is then signed in with the new password. The session changes, and the app
 * replaces the sign-in screen.
 *
 * Owned by [SignIn], for the same reason as [SignUp]: a request held by a screen would stop when the system recreates
 * the activity.
 *
 * @param signInLater called when the password was changed but the sign-in after it did not succeed. It gets the
 * address, so that the sign-in card can show it with a note that the new password works.
 */
class ResetPassword(
    private val auth: AuthClient,
    private val scope: CoroutineScope,
    private val online: () -> Boolean,
    private val signInLater: (email: String) -> Unit,
) {
    /** Which card of the reset is up. */
    sealed interface Step {
        /** The address step. Its field starts with [email], the address typed on the sign-in card. */
        data class Email(val email: String) : Step

        /** The code was mailed to [email]. The card asks for it and for the new password. */
        data class Code(val email: String) : Step
    }

    /** The step on screen, or null when the sign-in card is up. */
    var step by mutableStateOf<Step?>(null)
        private set

    /** A request is out; the card is disabled, Cancel and Back too. */
    var busy by mutableStateOf(false)
        private set

    /** What went wrong, under the fields. */
    var trouble by mutableStateOf<Said?>(null)
        private set

    /** News that is not an error: a new code was sent. */
    var notice by mutableStateOf<Said?>(null)
        private set

    /** Shows the address step, its field filled with [email]. */
    fun open(email: String) = go(Step.Email(email.trim()))

    /** Back to the sign-in card. Not while a request is out: the answer would arrive with nothing to show it. */
    fun close() {
        if (!busy) go(null)
    }

    fun send(email: String) {
        if (email.isBlank()) {
            trouble = Said(R.string.Reset_NeedEmail)
            return
        }
        ask(R.string.Reset_SendFailed) {
            when (val answer = auth.forgotPassword(email)) {
                SignUpAnswer.Done -> go(Step.Code(email.trim()))
                else -> trouble = Said(said(answer, R.string.Reset_SendFailed))
            }
        }
    }

    fun resend() {
        val at = step as? Step.Code ?: return
        ask(R.string.Reset_SendFailed) {
            when (val answer = auth.forgotPassword(at.email)) {
                SignUpAnswer.Done -> notice = Said(R.string.SignUp_CodeResent)
                else -> trouble = Said(said(answer, R.string.Reset_SendFailed))
            }
        }
    }

    fun save(code: String, password: String) {
        val at = step as? Step.Code ?: return
        if (code.isBlank() || password.isEmpty()) {
            trouble = Said(R.string.Reset_NeedFields)
            return
        }
        ask(R.string.Reset_Failed) {
            when (val answer = auth.resetPassword(at.email, code, password)) {
                SignUpAnswer.Done -> signIn(at.email, password)
                else -> trouble = Said(said(answer, R.string.Reset_Failed))
            }
        }
    }

    /**
     * Signs in with the new password. The code is used up by now, so a failure here must not stay on the code step:
     * a second Save would only be refused. The sign-in card comes back with the address instead, and a note that
     * the new password works.
     */
    private suspend fun signIn(email: String, password: String) {
        val signedIn = try {
            auth.login(email, password) is LoginAnswer.In
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        go(null)
        if (!signedIn) signInLater(email)
    }

    private fun go(next: Step?) {
        step = next
        trouble = null
        notice = null
    }

    /** Runs one request. Without a network nothing is sent, as on the sign-in card. [failed] is the sentence for an
     * answer that could not be read. */
    private fun ask(@StringRes failed: Int, work: suspend () -> Unit) {
        if (busy) return
        trouble = null
        notice = null
        if (!online()) {
            trouble = Said(R.string.Rpm_Net_Offline)
            return
        }
        busy = true
        scope.launch {
            try {
                work()
            } catch (e: IOException) {
                trouble = Said(R.string.Rpm_Net_Failed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Caught here, because the scope belongs to the application, and an exception that escapes would end
                // the app.
                trouble = Said(failed)
            } finally {
                busy = false
            }
        }
    }

    companion object {
        /** The sentence for an answer that is not [SignUpAnswer.Done]. [failed] is the sentence for a code this
         * screen does not know, and for a failed service. */
        @StringRes
        internal fun said(answer: SignUpAnswer, @StringRes failed: Int): Int = when (answer) {
            is SignUpAnswer.Refused -> when (answer.code) {
                "too_many" -> R.string.SignUp_TooMany
                "invalid_code" -> R.string.SignUp_InvalidCode
                "code_expired" -> R.string.Reset_CodeExpired
                "weak_password" -> R.string.SignUp_WeakPassword
                "invalid_email" -> R.string.SignUp_InvalidEmail
                else -> failed
            }
            else -> failed
        }
    }
}
