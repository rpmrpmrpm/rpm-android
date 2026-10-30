package com.rpm.player.phone.signin

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rpm.player.core.R
import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.auth.LoginAnswer
import com.rpm.player.core.auth.Provider
import com.rpm.player.core.auth.SocialPoll
import com.rpm.player.core.auth.SocialReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

/** A message for the screen: a string resource, and the values that fill its placeholders. */
data class Said(@StringRes val id: Int, val args: List<Any> = emptyList())

/**
 * Signing in on the phone. There are two ways, as on the iPhone (`AppModel.signIn`): an email address and a
 * password, or Apple or Google in the browser.
 *
 * **Owned by the application, not by the screen.** The browser covers the app while the person signs in. A
 * poll held by a screen would stop when the system recreates that activity behind the browser. So this object
 * is made once, next to the services, and uses their scope.
 *
 * The browser sign-in: the accounts service returns a sign-in address and a session id. When the person is done,
 * the website sends the browser to `rpm://auth?sessionId=…&code=…`, which `MainActivity` hands to [returned]. The
 * app then collects the tokens with that one-time code and its poll secret, so a sign-in finished in someone
 * else's browser never reaches this app. A return to the app without the link is handled by [backFromBrowser].
 *
 * @param bringBack puts the app back in front of the browser once the browser's sign-in has finished.
 * @param online whether the phone has a network at all. Without one no request is made, and the form says the phone
 * is offline at once, as the iPhone's network gate does (`Gated`, `RPMError.offline`).
 */
class SignIn(
    private val auth: AuthClient,
    private val scope: CoroutineScope,
    private val bringBack: () -> Unit,
    private val online: () -> Boolean = { true },
) {
    /** Making an account, on the same screen. A sign-in of an account that was never confirmed opens its code step. */
    val signUp = SignUp(auth, scope, online)

    /** A sign-in is under way; the form is disabled. */
    var busy by mutableStateOf(false)
        private set

    /** Waiting on the browser: the line under the fields says so. */
    var waitingForBrowser by mutableStateOf(false)
        private set

    /** What went wrong, in the place the form's errors go. */
    var trouble by mutableStateOf<Said?>(null)
        private set

    /**
     * A message that is not an error, in the same place: the account has been deleted (the iPhone's `model.notice`).
     * The next sign-in attempt clears it.
     */
    var notice by mutableStateOf<Said?>(null)

    /**
     * The address to open in the browser, until an activity has opened it ([opened]). It is held as state,
     * so whichever activity is on screen when the address arrives can open it.
     */
    var toOpen by mutableStateOf<String?>(null)
        private set

    /**
     * Set when the person returns to the app after the browser was opened. There is one per attempt. It is null between
     * attempts.
     */
    private var back: MutableStateFlow<Boolean>? = null

    /** The `rpm://auth` address the browser came back with, for this attempt. Null between attempts. */
    private var link: MutableStateFlow<String?>? = null

    /**
     * Whether this attempt's address has been handed to the browser. A return to the app before that, such as
     * a dialog over the activity while the address is fetched, says nothing about the browser.
     */
    private var browserShown = false

    /** Shows a message from the screen itself. The iPhone app has the same `model.report`. */
    fun report(said: Said) {
        trouble = said
    }

    fun withPassword(email: String, password: String) {
        if (busy) return
        if (email.isBlank() || password.isEmpty()) {
            trouble = Said(R.string.Login_NeedFields)
            return
        }
        trouble = null
        notice = null
        if (!online()) {
            trouble = Said(R.string.Rpm_Net_Offline)
            return
        }
        busy = true
        scope.launch {
            trouble = try {
                when (val answer = auth.login(email, password)) {
                    is LoginAnswer.In -> null
                    LoginAnswer.WrongPassword -> Said(R.string.Login_WrongPassword)
                    // The mail carries only a code, so the code step opens rather than a sentence.
                    LoginAnswer.NotConfirmed -> null.also { signUp.atCode(email, password) }
                    // A 2xx answer without a token means the sign-in did not finish. A 5xx answer means the service
                    // failed.
                    is LoginAnswer.Refused -> when (answer.code) {
                        in 200..299 -> Said(R.string.Login_Unable)
                        in 500..599 -> Said(R.string.Rpm_Error_Status, listOf(answer.code))
                        else -> Said(R.string.Rpm_SignIn_Failed)
                    }
                }
            } catch (e: IOException) {
                Said(R.string.Rpm_Net_Failed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An answer that could not be read. This is caught here, because the scope belongs to the
                // application. An exception that escapes would end the app.
                Said(R.string.Rpm_SignIn_Failed)
            }
            busy = false
        }
    }

    fun withProvider(provider: Provider) {
        if (busy) return
        trouble = null
        notice = null
        if (!online()) {
            trouble = Said(R.string.Rpm_Net_Offline)
            return
        }
        busy = true
        waitingForBrowser = true
        browserShown = false
        val back = MutableStateFlow(false).also { this.back = it }
        val link = MutableStateFlow<String?>(null).also { this.link = it }
        scope.launch {
            try {
                val start = auth.startSocial(provider, SocialReturn.APP)
                toOpen = start.loginUrl
                val code = awaitCode(start.sessionId, back, link)
                if (code != null) {
                    auth.adopt(collect(start.sessionId, code).asLogin())
                    bringBack()
                }
            } catch (e: IOException) {
                trouble = Said(R.string.Rpm_Net_Failed)
            } catch (e: Incomplete) {
                trouble = Said(R.string.Login_Unable)
            } catch (e: TimedOut) {
                trouble = Said(R.string.Login_BrowserTimedOut)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trouble = Said(R.string.Login_Unable)
            } finally {
                toOpen = null
                waitingForBrowser = false
                busy = false
                browserShown = false
                if (this@SignIn.back === back) this@SignIn.back = null
                if (this@SignIn.link === link) this@SignIn.link = null
            }
        }
    }

    /** The browser came back to the app with [address], an `rpm://auth` link (`MainActivity`). An address that
     * arrives when no sign-in is waiting is ignored. */
    fun returned(address: String) {
        link?.value = address
    }

    /** The activity has opened [toOpen] in the browser. */
    fun opened() {
        toOpen = null
        browserShown = true
    }

    /**
     * The activity came back to the front. While the browser sign-in waits, and its address is already open,
     * this means the person closed the browser, or the browser finished. The wait then gives the browser's link
     * [GRACE_MS] to arrive and stops. Closing the browser is not a failure, so no message is shown. The iPhone app
     * does the same.
     */
    fun backFromBrowser() {
        if (browserShown) back?.value = true
    }

    /**
     * Waits up to [DEADLINE_MS] for the browser to come back with the one-time code. Returns null when the person
     * came back to the app without it (they closed the browser). The link and the return to the app arrive close
     * together, the link first on a running activity, so a return waits [GRACE_MS] more for the link.
     */
    private suspend fun awaitCode(sessionId: String, back: MutableStateFlow<Boolean>, link: MutableStateFlow<String?>): String? {
        val first = withTimeoutOrNull(DEADLINE_MS) {
            merge(link.filterNotNull(), back.filter { it }.map { null }).first()
        } ?: if (back.value) null else throw TimedOut()
        val address = first ?: withTimeoutOrNull(GRACE_MS) { link.filterNotNull().first() } ?: return null
        return codeIn(address, sessionId) ?: throw Incomplete()
    }

    /** Collects the tokens with the code. The website writes them before it sends the browser back, so the first
     * ask normally finds them. Two more asks a second apart cover a slow write. */
    private suspend fun collect(sessionId: String, code: String): SocialPoll {
        repeat(3) { attempt ->
            if (attempt > 0) delay(1_000)
            val said = auth.collect(sessionId, code)
            if (said.isComplete) return said
            if (said.status == "complete") throw Incomplete()
        }
        throw Incomplete()
    }

    private class Incomplete : Exception()
    private class TimedOut : Exception()

    companion object {
        private const val DEADLINE_MS = 180_000L
        private const val GRACE_MS = 1_500L

        /** The code in an `rpm://auth?sessionId=…&code=…` address, if it is for [sessionId]. */
        internal fun codeIn(address: String, sessionId: String): String? {
            val uri = runCatching { URI(address) }.getOrNull() ?: return null
            if (uri.scheme != "rpm" || uri.host != "auth") return null
            val query = uri.rawQuery?.split('&').orEmpty().mapNotNull { pair ->
                val equals = pair.indexOf('=')
                if (equals <= 0) null else pair.substring(0, equals) to URLDecoder.decode(pair.substring(equals + 1), "UTF-8")
            }.toMap()
            if (query["sessionId"] != sessionId) return null
            return query["code"]?.takeIf { it.isNotEmpty() }
        }
    }
}
