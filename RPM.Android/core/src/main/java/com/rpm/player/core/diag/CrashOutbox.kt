package com.rpm.player.core.diag

import android.content.SharedPreferences
import com.rpm.player.core.net.WireJson
import java.io.File

/**
 * Crashed runs waiting on disk to be sent, one JSON file each, named by the crash id.
 *
 * [put] is synchronous because the crash handler calls it while the process is ending. A file is removed when the
 * server took the report or refused it; it is kept when the device was offline or the server failed, and the next
 * launch tries again.
 */
class CrashOutbox(private val dir: File) {
    fun put(crash: CrashDraft) {
        dir.mkdirs()
        val partial = File(dir, "${crash.crashId}.partial")
        partial.writeText(WireJson.encodeToString(CrashDraft.serializer(), crash))
        partial.renameTo(file(crash.crashId))
        pending().dropLast(MOST_KEPT).forEach { remove(it.crashId) }
    }

    /** The waiting crashes, oldest first. A file that does not read is removed: it never will. */
    fun pending(): List<CrashDraft> = (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
        .mapNotNull { f ->
            runCatching { WireJson.decodeFromString(CrashDraft.serializer(), f.readText()) }
                .onFailure { f.delete() }
                .getOrNull()
        }
        .sortedBy { it.crashedAtMillis }

    fun removeAll() = pending().forEach { remove(it.crashId) }

    /** Sends every waiting crash, oldest first. Stops at the first one to try again later: the rest would fail the
     * same way. */
    suspend fun send(send: suspend (CrashDraft) -> SendOutcome) {
        for (crash in pending()) {
            when (send(crash)) {
                SendOutcome.Sent, SendOutcome.Refused -> remove(crash.crashId)
                SendOutcome.Later -> return
            }
        }
    }

    private fun remove(crashId: String) { file(crashId).delete() }

    private fun file(crashId: String) = File(dir, "$crashId.json")

    companion object {
        /** More than this many waiting files means a crash at every launch; the oldest are not kept. */
        const val MOST_KEPT = 10
    }
}

/** The answer to "Send crash reports automatically?", asked once with the desktop's own prompt. */
enum class CrashConsent {
    Unasked, Send, DontSend;

    enum class Step { Nothing, Ask, Send }

    companion object {
        private const val KEY = "crashConsent"

        fun read(prefs: SharedPreferences): CrashConsent =
            prefs.getString(KEY, null)?.let { name -> entries.firstOrNull { it.name == name } } ?: Unasked

        fun write(prefs: SharedPreferences, consent: CrashConsent) {
            prefs.edit().putString(KEY, consent.name).apply()
        }

        fun next(consent: CrashConsent, waiting: Int): Step = when {
            waiting == 0 -> Step.Nothing
            consent == Unasked -> Step.Ask
            consent == Send -> Step.Send
            else -> Step.Nothing
        }
    }
}
