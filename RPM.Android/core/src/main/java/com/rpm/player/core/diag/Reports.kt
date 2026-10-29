package com.rpm.player.core.diag

import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.net.AppKey
import com.rpm.player.core.net.Hosts
import com.rpm.player.core.net.WireJson
import com.rpm.player.core.net.await
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What a person wrote in the Send feedback screen, before it is sent. */
class FeedbackDraft(
    /** "problem" or "idea" ([FeedbackChoices.kinds]). */
    val kind: String,
    val area: String,
    val message: String,
    /** The screen as it was when the screen opened, PNG. Null when the person turned it off. */
    val screenshotPng: ByteArray?,
    /** The address a reply goes to, when the person is signed out and typed one. */
    val replyTo: String?,
) {
    /** Only a report with words in it can be sent: the server refuses an empty one. */
    val isSendable: Boolean get() = message.isNotBlank()
}

/** One crashed run, kept on disk by [CrashOutbox] until it is sent. */
@Serializable
data class CrashDraft(
    /** "crash_" and 24 hex digits, the server's shape, made when the crash is recorded, so a second send of the same
     * crash is filed as a duplicate. */
    val crashId: String,
    val crashedAtMillis: Long,
    /** The build that crashed, which may not be the build that sends the report. */
    val appVersion: String,
    val exception: String? = null,
    val systemReport: String? = null,
    /** "android-java", "android-tombstone" (base64) or "android-anr". */
    val systemReportKind: String? = null,
    /** The crashed run's log. */
    val recent: String? = null,
) {
    companion object {
        private val random = SecureRandom()

        fun newId(): String {
            val bytes = ByteArray(12).also(random::nextBytes)
            return "crash_" + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** The server's `FeedbackReportRequest`. A null field is left out of the JSON ([WireJson]). */
@Serializable
data class FeedbackWire(
    val kind: String, val area: String, val message: String, val head: String, val appVersion: String,
    val platform: String, val architecture: String, val installId: String,
    val recent: String? = null, val screenshotPng: String? = null, val atUtcTicks: Long,
    val replyTo: String? = null, val scope: String? = null,
)

/** The server's `CrashReportRequest`. */
@Serializable
data class CrashWire(
    val crashId: String, val head: String, val appVersion: String, val platform: String, val architecture: String,
    val crashedAtUtcTicks: Long, val installId: String,
    val exception: String? = null, val systemReport: String? = null, val systemReportKind: String? = null,
    val recent: String? = null, val scope: String? = null,
)

/** Posts JSON to the API and answers the status. Throws [IOException] when there is no answer at all. */
fun interface Poster {
    suspend fun post(path: String, json: String): Int
}

/** The production [Poster]: the app key always, the token when signed in, so a tester whose sign-in is the broken
 * thing can still report it. */
fun apiPoster(http: OkHttpClient, auth: AuthClient) = Poster { path, json ->
    val token = auth.accessToken()
    val request = Request.Builder()
        .url(Hosts.API + path)
        .header(AppKey.HEADER, AppKey.VALUE)
        .apply { if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token") }
        .post(json.toRequestBody("application/json".toMediaType()))
        .build()
    http.newCall(request).await().use { it.code }
}

/** How a send ended, grouped by what the caller does next. */
enum class SendOutcome {
    Sent,
    /** 4xx: the server would refuse it again. A crash file is removed. */
    Refused,
    /** No answer, or a 5xx: try again at the next launch. */
    Later,
}

enum class FeedbackSent { Sent, Failed, Busy }

/**
 * `POST /errors/feedback` and `POST /errors/crash`. Every send takes the [ReportScope]: an anonymous report has
 * its log, screenshot and reply address removed here, before anything is encoded.
 */
class ReportsClient(private val poster: Poster, private val facts: DeviceFacts, private val installId: String) {
    private val sendingFeedback = AtomicBoolean(false)

    /** Sends one feedback report. [FeedbackSent.Busy] while another is on its way, so a second press of Send does not
     * file the same words twice. */
    suspend fun sendFeedback(draft: FeedbackDraft, recent: String?, scope: ReportScope, atMillis: Long): FeedbackSent {
        if (!sendingFeedback.compareAndSet(false, true)) return FeedbackSent.Busy
        try {
            val json = WireJson.encodeToString(FeedbackWire.serializer(), feedbackWire(draft, recent, scope, atMillis, facts, installId))
            val status = try { poster.post("errors/feedback", json) } catch (_: IOException) { return FeedbackSent.Failed }
            return if (status in 200..299) FeedbackSent.Sent else FeedbackSent.Failed
        } finally {
            sendingFeedback.set(false)
        }
    }

    suspend fun sendCrash(crash: CrashDraft, scope: ReportScope): SendOutcome {
        val json = WireJson.encodeToString(CrashWire.serializer(), crashWire(crash, scope, facts, installId))
        val status = try { poster.post("errors/crash", json) } catch (_: IOException) { return SendOutcome.Later }
        return when (status) {
            in 200..299 -> SendOutcome.Sent
            in 400..499 -> SendOutcome.Refused
            else -> SendOutcome.Later
        }
    }

    companion object {
        fun feedbackWire(d: FeedbackDraft, recent: String?, scope: ReportScope, atMillis: Long, facts: DeviceFacts, installId: String): FeedbackWire {
            val full = scope.isFull
            return FeedbackWire(
                kind = d.kind, area = d.area, message = d.message, head = facts.head, appVersion = facts.appVersion,
                platform = facts.platform, architecture = facts.architecture, installId = installId,
                recent = if (full) recent else null,
                screenshotPng = if (full) d.screenshotPng?.let { Base64.getEncoder().encodeToString(it) } else null,
                atUtcTicks = ticks(atMillis),
                replyTo = if (full) d.replyTo else null,
                scope = scope.wire,
            )
        }

        fun crashWire(c: CrashDraft, scope: ReportScope, facts: DeviceFacts, installId: String) = CrashWire(
            crashId = c.crashId, head = facts.head, appVersion = c.appVersion, platform = facts.platform,
            architecture = facts.architecture, crashedAtUtcTicks = ticks(c.crashedAtMillis), installId = installId,
            exception = c.exception, systemReport = c.systemReport, systemReportKind = c.systemReportKind,
            recent = if (scope.isFull) c.recent else null, scope = scope.wire,
        )

        /** .NET ticks (100 ns since 0001-01-01 UTC), which is how the server reads times from clients. */
        fun ticks(millis: Long): Long = millis * 10_000 + 621_355_968_000_000_000
    }
}
