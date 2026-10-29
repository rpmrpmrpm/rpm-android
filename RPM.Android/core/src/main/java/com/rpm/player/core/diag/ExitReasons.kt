package com.rpm.player.core.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.security.MessageDigest
import java.util.Base64

/**
 * The crashes the app cannot catch itself, read from the system at the next launch (Android 11, API 30, and later;
 * nothing below): a native crash, with its tombstone on Android 12 and later, and an ANR, with its trace.
 *
 * A Kotlin or Java crash is skipped here: [CrashCatcher] has already recorded it with the app's log. Each exit is
 * read once: the newest exit time seen is kept, and only newer exits are taken. On the first launch with this code
 * nothing is taken, so crashes from before it are not sent.
 */
object ExitReasons {
    /** One exit as the system describes it, so [pick] can be tested without a device. */
    class Exit(val reason: Int, val timestampMillis: Long, val description: String?, val trace: ByteArray?)

    private const val SEEN = "exitSeen"

    /** The largest system report kept; the server takes about a million characters. */
    const val MAX_TRACE_CHARS = 900_000

    fun collect(context: Context, prefs: SharedPreferences, appVersion: String, recent: String?): List<CrashDraft> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val infos = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 8) }.getOrDefault(emptyList())
        val exits = infos.map { info ->
            val wanted = info.reason == ApplicationExitInfo.REASON_ANR ||
                (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            val trace = if (wanted) runCatching { info.traceInputStream?.use { it.readBytes() } }.getOrNull() else null
            Exit(info.reason, info.timestamp, info.description, trace)
        }

        val seen = prefs.getLong(SEEN, -1)
        val newest = exits.maxOfOrNull { it.timestampMillis } ?: 0
        prefs.edit().putLong(SEEN, maxOf(seen, newest)).apply()
        return if (seen < 0) emptyList() else pick(exits, seen, appVersion, recent)
    }

    /** The exits after [seen] that the app could not record itself, as crash drafts. */
    fun pick(exits: List<Exit>, seen: Long, appVersion: String, recent: String?): List<CrashDraft> =
        exits.filter { it.timestampMillis > seen }.mapNotNull { exit ->
            when (exit.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> draft(exit, "Native crash", appVersion, recent,
                    exit.trace?.let { Base64.getEncoder().encodeToString(it) }, "android-tombstone")
                ApplicationExitInfo.REASON_ANR -> draft(exit, "Not responding (ANR)", appVersion, recent,
                    exit.trace?.toString(Charsets.UTF_8), "android-anr")
                else -> null
            }
        }

    private fun draft(exit: Exit, what: String, appVersion: String, recent: String?, report: String?, kind: String) =
        CrashDraft(
            crashId = idOf(exit),
            crashedAtMillis = exit.timestampMillis,
            appVersion = appVersion,
            exception = listOfNotNull(what, exit.description).joinToString(": "),
            systemReport = report?.take(MAX_TRACE_CHARS),
            systemReportKind = report?.let { kind },
            recent = recent,
        )

    /** The same exit gives the same id, so a second read of it is filed as a duplicate. */
    private fun idOf(exit: Exit): String {
        val hash = MessageDigest.getInstance("SHA-256").digest("exit:${exit.reason}:${exit.timestampMillis}".toByteArray())
        return "crash_" + hash.take(12).joinToString("") { "%02x".format(it) }
    }
}
