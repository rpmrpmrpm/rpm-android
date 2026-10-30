package com.rpm.player.core.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The signed-in session, and the token every API call carries.
 *
 * Every change to the session happens inside [lock]: adopting a sign-in, refreshing and ending. This
 * keeps two changes from running at the same time, for example a refresh and a sign-out. A refresh
 * runs inside the lock too, so a second caller waits for the first caller's answer and does not refresh
 * with the same refresh token.
 */
class AuthClient(
    private val wire: AuthWire,
    private val store: SessionStore,
    private val clock: () -> Long,
) {
    private val lock = Mutex()
    private val current = MutableStateFlow<Session?>(null)

    /** The session, or null when signed out. Screens choose what to show from this. */
    val session: StateFlow<Session?> = current.asStateFlow()

    /** Restores the stored session. It does not use the network: the token is checked when it is first used,
     * so the app opens with no connection. */
    fun load() {
        current.value = store.read()
    }

    suspend fun startSocial(provider: Provider, returnTo: SocialReturn): SocialStart = wire.startSocial(provider, returnTo)

    suspend fun poll(sessionId: String): SocialPoll = wire.poll(sessionId)

    suspend fun collect(sessionId: String, code: String): SocialPoll = wire.collect(sessionId, code)

    /** Signs in with an address and a password. On [LoginAnswer.In] the session is already set. Throws
     * [java.io.IOException] if the accounts service cannot be reached. */
    suspend fun login(email: String, password: String): LoginAnswer =
        wire.login(email, password).also { if (it is LoginAnswer.In) adopt(it.said) }

    /** Signing up: see [AuthWire.register], [AuthWire.confirm] and [AuthWire.resendCode]. None of them signs in. After
     * the code, [login] with the new password does. */
    suspend fun register(name: String, email: String, password: String): SignUpAnswer = wire.register(name, email, password)

    suspend fun confirm(email: String, code: String): SignUpAnswer = wire.confirm(email, code)

    suspend fun resendCode(email: String): SignUpAnswer = wire.resendCode(email)

    /** Takes a finished sign-in as the session. */
    suspend fun adopt(said: LoginResponse) {
        lock.withLock { replace(merge(previous = null, said)) }
    }

    /**
     * A valid access token, refreshed when it is within [REFRESH_MARGIN_MS] of expiring. Null when
     * signed out. A refused refresh also ends the session and returns null, so a caller that gets
     * null has nothing to retry with.
     */
    suspend fun accessToken(): String? = lock.withLock {
        // Read inside the lock: a caller that waited here while another caller refreshed finds the new
        // token and returns it.
        val session = current.value ?: return@withLock null
        if (session.expiresAtMillis - clock() > REFRESH_MARGIN_MS) return@withLock session.accessToken
        // With no refresh token the session is kept. The call that uses the token fails by itself.
        if (session.refreshToken.isEmpty()) return@withLock session.accessToken

        when (val answer = wire.refresh(session.refreshToken)) {
            is RefreshAnswer.Fresh -> {
                val next = merge(previous = session, answer.said)
                replace(next)
                next.accessToken
            }
            RefreshAnswer.Refused -> {
                // The server checked the refresh token and refused it. Asking again with the same
                // token would loop, and only a new sign-in ends it.
                replace(null)
                null
            }
            // The token was not checked. Signing out here would sign a person out whenever the network is down.
            RefreshAnswer.Unavailable -> session.accessToken
        }
    }

    /** Ends the session on this device and asks the server to revoke its refresh token. */
    suspend fun signOut() {
        val ended = lock.withLock {
            val session = current.value
            replace(null)
            session
        }
        if (ended != null && ended.refreshToken.isNotEmpty()) {
            try {
                wire.revoke(ended.refreshToken)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best effort: the session has already ended on this device.
            }
        }
    }

    /**
     * Asks the accounts service to delete the signed-in account (the iPhone's `AuthClient.deleteAccount`). This is only
     * the server's part: on [Deletion.Deleted] the caller signs out, as the desktop and the iPhone do.
     *
     * @param confirmEmail the address the person typed. The server compares it with the account's verified address,
     * ignoring case.
     */
    suspend fun deleteAccount(confirmEmail: String): Deletion {
        val token = accessToken() ?: return Deletion.Failed
        val status = try {
            wire.deleteAccount(token, confirmEmail)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Deletion.Failed
        }
        return Deletion.of(status)
    }

    private fun replace(next: Session?) {
        current.value = next
        if (next == null) store.clear() else store.write(next)
    }

    private fun merge(previous: Session?, said: LoginResponse) = Session(
        userId = said.userId ?: previous?.userId ?: "",
        email = said.email ?: previous?.email ?: "",
        displayName = said.displayName ?: previous?.displayName ?: "",
        accessToken = said.accessToken ?: "",
        // A refresh answer has no refresh token. If it were dropped here, the session would end an hour later.
        refreshToken = said.refreshToken ?: previous?.refreshToken ?: "",
        expiresAtMillis = clock() + (said.expiresIn ?: 3600) * 1000L,
    )

    /** What asking the accounts service to delete the account came to. */
    enum class Deletion {
        /** Deleted. The server erases the cloud library within two hours. The caller then signs out. */
        Deleted,

        /** The typed address is not the account's verified email, or the account has none. Nothing was deleted. */
        EmailRefused,

        /** The request did not get through, or the server failed. Nothing was deleted. */
        Failed;

        companion object {
            /** 2xx is deleted, 400 is a refused address, and any other answer deleted nothing. */
            fun of(status: Int): Deletion = when (status) {
                in 200..299 -> Deleted
                400 -> EmailRefused
                else -> Failed
            }
        }
    }

    companion object {
        /** A token this close to expiring is treated as expired, because a request started now would finish
         * after it. The iPhone app uses the same three minutes. */
        const val REFRESH_MARGIN_MS = 180_000L
    }
}
