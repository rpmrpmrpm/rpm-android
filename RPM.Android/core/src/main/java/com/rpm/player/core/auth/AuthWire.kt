package com.rpm.player.core.auth

import com.rpm.player.core.net.Hosts
import com.rpm.player.core.net.WireJson
import com.rpm.player.core.net.await
import com.rpm.player.core.net.escapeSegment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** The result of a refresh. Only [Refused] ends the session, as in the iPhone app. */
sealed interface RefreshAnswer {
    data class Fresh(val said: LoginResponse) : RefreshAnswer
    /** The server checked the refresh token and refused it. */
    data object Refused : RefreshAnswer
    /** The token was not checked: no network, a 5xx status, or a page that is not from the accounts service. */
    data object Unavailable : RefreshAnswer
}

/** The result of `auth/login`. The accounts service sends no reason code, so the HTTP status is the reason.
 * It is read as the iPhone app reads it (`AuthClient.signIn`, `Refusal`). */
sealed interface LoginAnswer {
    data class In(val said: LoginResponse) : LoginAnswer
    /** 401: the address and the password do not match an account. */
    data object WrongPassword : LoginAnswer
    /** 400: the address has not been confirmed. */
    data object NotConfirmed : LoginAnswer
    /** Any other status, or a 2xx without a token. */
    data class Refused(val code: Int) : LoginAnswer
}

/** How a step of signing up ended (`auth/register`, `auth/confirm`, `auth/resend-code`), as the iPhone's
 * `SignUpAnswer`. No case carries the service's English sentence: the screen says its own. */
sealed interface SignUpAnswer {
    data object Done : SignUpAnswer

    /** The service said no, with its own code: "already_registered", "social_account", "weak_password",
     * "invalid_email", "invalid_name", "invalid_code", "code_expired", "code_resent", "too_many", or "refused"
     * when the answer had no code. */
    data class Refused(val code: String) : SignUpAnswer

    /** Any other status: a 5xx, or a page that is not from the accounts service. */
    data class Failed(val status: Int) : SignUpAnswer

    companion object {
        /** Reads an answer. A 4xx answer's code is taken, and the sentence beside it is left out. */
        fun read(status: Int, body: String): SignUpAnswer = when (status) {
            in 200..299 -> Done
            in 400..499 -> Refused(
                runCatching { (WireJson.parseToJsonElement(body) as? JsonObject)?.get("code") as? JsonPrimitive }
                    .getOrNull()?.contentOrNull?.takeIf { it.isNotEmpty() } ?: "refused",
            )
            else -> Failed(status)
        }
    }
}

/** The accounts service's routes. This is an interface so that the ordering rules of [AuthClient] can be
 * tested without a network. */
interface AuthWire {
    /** Throws [IOException] if the service cannot be reached. */
    suspend fun login(email: String, password: String): LoginAnswer
    suspend fun startSocial(provider: Provider, returnTo: SocialReturn): SocialStart

    /** A TV's ask for a sign-in it started, with its poll secret. */
    suspend fun poll(sessionId: String): SocialPoll

    /** The phone's ask for a sign-in it started, with its poll secret and the one-time [code] the browser
     * brought back. */
    suspend fun collect(sessionId: String, code: String): SocialPoll
    suspend fun refresh(refreshToken: String): RefreshAnswer
    suspend fun revoke(refreshToken: String)

    /** `DELETE auth/account` with the address the person typed, under [accessToken]. Returns the HTTP status. Throws
     * [IOException] if the service cannot be reached. */
    suspend fun deleteAccount(accessToken: String, confirmEmail: String): Int

    /** `auth/register`: makes an account that starts unconfirmed. The service mails a six-digit code. Throws
     * [IOException] if the service cannot be reached. */
    suspend fun register(name: String, email: String, password: String): SignUpAnswer

    /** `auth/confirm`: takes the mailed code. The service answers an account that is already confirmed as done. */
    suspend fun confirm(email: String, code: String): SignUpAnswer

    /** `auth/resend-code`: mails a new code, which replaces the one sent before. */
    suspend fun resendCode(email: String): SignUpAnswer
}

/** [AuthWire] over HTTP. None of these calls sends the app key, which the accounts service does not read. Only
 * [deleteAccount] sends a bearer token: the others are the calls that get one. */
class HttpAuthWire(private val http: OkHttpClient) : AuthWire {

    /** The poll secret of each social sign-in this wire started, by session id. See [PollSecret]. */
    private val pollSecrets = ConcurrentHashMap<String, String>()

    override suspend fun login(email: String, password: String): LoginAnswer {
        val answer = post("auth/login", mapOf("email" to email.trim(), "password" to password))
        return when (answer.code) {
            in 200..299 -> runCatching { WireJson.decodeFromString(LoginResponse.serializer(), answer.body) }.getOrNull()
                ?.takeUnless { it.accessToken.isNullOrEmpty() }
                ?.let { LoginAnswer.In(it) }
                ?: LoginAnswer.Refused(answer.code)
            401 -> LoginAnswer.WrongPassword
            400 -> LoginAnswer.NotConfirmed
            else -> LoginAnswer.Refused(answer.code)
        }
    }

    override suspend fun startSocial(provider: Provider, returnTo: SocialReturn): SocialStart {
        val secret = PollSecret.make()
        val fields = mapOf(
            "provider" to provider.wireName, "appId" to APP_ID, "pollHash" to PollSecret.hashOf(secret),
            "returnTo" to returnTo.wireName,
        )
        return post("auth/social-login", fields).let {
            if (it.code !in 200..299) throw IOException("social-login answered ${it.code}")
            WireJson.decodeFromString(SocialStart.serializer(), it.body)
        }.also { pollSecrets[it.sessionId] = secret }
    }

    override suspend fun poll(sessionId: String): SocialPoll = ask(sessionId, code = null)

    override suspend fun collect(sessionId: String, code: String): SocialPoll = ask(sessionId, code)

    /** One ask of `auth/poll-session`. The server answers 403 when the secret or the code does not match. */
    private suspend fun ask(sessionId: String, code: String?): SocialPoll {
        val request = Request.Builder().url(Hosts.ACCOUNTS + "auth/poll-session?sessionId=" + escapeSegment(sessionId))
            .apply { pollSecrets[sessionId]?.let { header(PollSecret.HEADER, it) } }
            .apply { code?.let { header(PollSecret.CODE_HEADER, it) } }
            .build()
        http.newCall(request).await().use { response ->
            val body = response.body.string()
            if (response.code !in 200..299) throw IOException("poll-session answered ${response.code}")
            return WireJson.decodeFromString(SocialPoll.serializer(), body)
                .also { if (it.status == "complete") pollSecrets.remove(sessionId) }
        }
    }

    override suspend fun refresh(refreshToken: String): RefreshAnswer {
        val answer = try {
            post("auth/refresh", mapOf("refreshToken" to refreshToken))
        } catch (e: IOException) {
            return RefreshAnswer.Unavailable
        }
        if (answer.code !in 200..299) {
            // 401 means a used-up token and 400 means a missing one. A 429, a 5xx or a proxy's 404 is a
            // problem with the service or the connection, and says nothing about the account.
            return if (answer.code in setOf(400, 401, 403)) RefreshAnswer.Refused else RefreshAnswer.Unavailable
        }
        val said = runCatching { WireJson.decodeFromString(LoginResponse.serializer(), answer.body) }
            .getOrElse { return RefreshAnswer.Unavailable }
        return if (said.accessToken.isNullOrEmpty()) RefreshAnswer.Refused else RefreshAnswer.Fresh(said)
    }

    override suspend fun revoke(refreshToken: String) {
        post("auth/revoke", mapOf("refreshToken" to refreshToken))
    }

    override suspend fun deleteAccount(accessToken: String, confirmEmail: String): Int {
        val json = WireJson.encodeToString(JsonObject(mapOf("confirmEmail" to JsonPrimitive(confirmEmail.trim()))))
        val request = Request.Builder()
            .url(Hosts.ACCOUNTS + "auth/account")
            .header("Authorization", "Bearer $accessToken")
            .delete(json.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).await().use { response -> return response.code }
    }

    override suspend fun register(name: String, email: String, password: String): SignUpAnswer =
        post("auth/register", mapOf("name" to name.trim(), "email" to email.trim(), "password" to password))
            .let { SignUpAnswer.read(it.code, it.body) }

    override suspend fun confirm(email: String, code: String): SignUpAnswer =
        post("auth/confirm", mapOf("email" to email.trim(), "code" to code.trim())).let { SignUpAnswer.read(it.code, it.body) }

    override suspend fun resendCode(email: String): SignUpAnswer =
        post("auth/resend-code", mapOf("email" to email.trim())).let { SignUpAnswer.read(it.code, it.body) }

    private class Answer(val code: Int, val body: String)

    private suspend fun post(path: String, fields: Map<String, String>): Answer {
        val json = WireJson.encodeToString(JsonObject(fields.mapValues { JsonPrimitive(it.value) }))
        val request = Request.Builder()
            .url(Hosts.ACCOUNTS + path)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).await().use { response ->
            return Answer(response.code, response.body.string())
        }
    }

    private companion object {
        /** The name this app gives itself to the accounts service. The iPhone app sends the same name. */
        const val APP_ID = "RPM"
    }
}
