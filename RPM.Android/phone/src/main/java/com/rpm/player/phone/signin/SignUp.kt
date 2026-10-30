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
 * Making an rpm account with an email and password, as the iPhone's `SignUpCard` does it. The form goes to the
 * accounts service (`auth/register`), which mails a six-digit code; the code is typed in (`auth/confirm`); and the
 * new account is signed in with the password just chosen. The session then changes, and the app replaces the
 * sign-in screen.
 *
 * Owned by [SignIn], for the same reason: a request held by a screen would stop when the system recreates the
 * activity. It also lets a sign-in of an account that was never confirmed open the code step ([atCode]).
 */
class SignUp(
    private val auth: AuthClient,
    private val scope: CoroutineScope,
    private val online: () -> Boolean,
) {
    /** Which card of sign-up is up. */
    sealed interface Step {
        data object Form : Step

        /** The code was mailed to [email]. [password] signs the account in once the code is taken. */
        data class Code(val email: String, val password: String) : Step
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

    /** Shows the form. */
    fun open() = go(Step.Form)

    /**
     * Shows the code step for an account that was made and never confirmed. No new code is sent: it would replace the
     * one already in the person's inbox. "Send a new code" is there for a mail that was lost, and the service answers
     * an expired code with a new one.
     */
    fun atCode(email: String, password: String) = go(Step.Code(email.trim(), password))

    /** Back to the sign-in card. Not while a request is out: the answer would arrive with nothing to show it. */
    fun close() {
        if (!busy) go(null)
    }

    fun create(name: String, email: String, password: String) {
        if (name.isBlank() || email.isBlank() || password.isEmpty()) {
            trouble = Said(R.string.SignUp_NeedFields)
            return
        }
        ask {
            when (val answer = auth.register(name, email, password)) {
                SignUpAnswer.Done -> go(Step.Code(email.trim(), password))
                else -> trouble = Said(said(answer))
            }
        }
    }

    fun confirm(code: String) {
        val at = step as? Step.Code ?: return
        if (code.isBlank()) {
            trouble = Said(R.string.SignUp_NeedCode)
            return
        }
        ask {
            when (val answer = auth.confirm(at.email, code)) {
                // The account is live: sign it in with the password it was made with. A second Confirm after a failed
                // sign-in is safe, because the service answers a confirmed account as done.
                SignUpAnswer.Done -> when (auth.login(at.email, at.password)) {
                    is LoginAnswer.In -> go(null)
                    LoginAnswer.WrongPassword -> trouble = Said(R.string.Login_WrongPassword)
                    else -> trouble = Said(R.string.Login_Unable)
                }
                else -> trouble = Said(said(answer))
            }
        }
    }

    fun resend() {
        val at = step as? Step.Code ?: return
        ask {
            when (val answer = auth.resendCode(at.email)) {
                SignUpAnswer.Done -> notice = Said(R.string.SignUp_CodeResent)
                else -> trouble = Said(said(answer))
            }
        }
    }

    private fun go(next: Step?) {
        step = next
        trouble = null
        notice = null
    }

    /** Runs one request. Without a network nothing is sent, as on the sign-in card. */
    private fun ask(work: suspend () -> Unit) {
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
                // An answer that could not be read. Caught here, because the scope belongs to the application, and
                // an exception that escapes would end the app.
                trouble = Said(R.string.SignUp_Failed)
            } finally {
                busy = false
            }
        }
    }

    companion object {
        /** The sentence for an answer that is not [SignUpAnswer.Done]. */
        @StringRes
        internal fun said(answer: SignUpAnswer): Int = when (answer) {
            is SignUpAnswer.Refused -> when (answer.code) {
                "already_registered" -> R.string.SignUp_AlreadyRegistered
                "social_account" -> R.string.SignUp_SocialAccount
                "weak_password" -> R.string.SignUp_WeakPassword
                "invalid_email" -> R.string.SignUp_InvalidEmail
                "invalid_name" -> R.string.SignUp_InvalidName
                "invalid_code" -> R.string.SignUp_InvalidCode
                "code_expired", "code_resent" -> R.string.SignUp_CodeResent
                "too_many" -> R.string.SignUp_TooMany
                else -> R.string.SignUp_Failed
            }
            else -> R.string.SignUp_Failed
        }
    }
}
