package com.rpm.player.core.diag

import android.util.Log
import java.io.File
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The app's log: every line goes to `android.util.Log`, and the last [MOST_LINES] lines are also kept,
 * so a feedback or crash report can carry what the app did just before.
 *
 * The lines are written to `run.log` in [start]'s folder a few seconds after they arrive, on one background
 * thread, so the next launch can send a crashed run's log. [start] moves the last run's copy to `previous.log`
 * first, synchronously, before anything writes this run's copy. The crash handler calls [flushNow] to write
 * the very last lines before the process ends.
 *
 * The calls have the same shapes as `Log`'s, so every log call in the apps goes through here.
 */
object Diag {
    const val MOST_LINES = 2_000

    /** The server's limit for a report's recent log (`MaxRecent`). */
    const val MAX_RECENT = 64_000

    /** How long after a line arrives the file is written. Lines that arrive meanwhile are written with it. */
    private const val WRITE_AFTER_MS = 5_000L

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private var writeScheduled = false
    @Volatile private var folder: File? = null
    private val writer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "rpm-diag").apply { isDaemon = true }
    }

    /** Moves the last run's copy aside and starts this run's. Call once, first thing in `Application.onCreate`. */
    fun start(dir: File) {
        dir.mkdirs()
        val current = File(dir, "run.log")
        val previous = File(dir, "previous.log")
        if (current.exists()) {
            previous.delete()
            current.renameTo(previous)
        }
        folder = dir
    }

    fun v(tag: String, msg: String, tr: Throwable? = null): Int { note('V', tag, msg, tr); return Log.v(tag, msg, tr) }
    fun d(tag: String, msg: String, tr: Throwable? = null): Int { note('D', tag, msg, tr); return Log.d(tag, msg, tr) }
    fun i(tag: String, msg: String, tr: Throwable? = null): Int { note('I', tag, msg, tr); return Log.i(tag, msg, tr) }
    fun w(tag: String, msg: String, tr: Throwable? = null): Int { note('W', tag, msg, tr); return Log.w(tag, msg, tr) }
    fun w(tag: String, tr: Throwable): Int { note('W', tag, "", tr); return Log.w(tag, tr) }
    fun e(tag: String, msg: String, tr: Throwable? = null): Int { note('E', tag, msg, tr); return Log.e(tag, msg, tr) }

    /** This run's last lines, at most [maxChars], starting at a line. Null when nothing was logged. */
    fun tail(maxChars: Int = MAX_RECENT): String? {
        val text = synchronized(lock) { lines.joinToString("\n") }
        return if (text.isEmpty()) null else trim(text, maxChars)
    }

    /** The last run's log, from `previous.log`, or null when there is none. */
    fun previousRun(): String? = folder?.let { File(it, "previous.log") }
        ?.takeIf { it.exists() }?.readText()?.takeIf { it.isNotEmpty() }

    /** Writes the lines now, on the calling thread. For the crash handler: the process ends right after. */
    fun flushNow() = write()

    /** The end of [text], at most [maxChars] long, from the start of a line, so no line is cut in half. */
    fun trim(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val tail = text.takeLast(maxChars)
        val newline = tail.indexOf('\n')
        return if (newline < 0) tail else tail.substring(newline + 1)
    }

    /** One line: UTC time, level letter, tag, message, and the stack when there is one. */
    fun line(level: Char, tag: String, msg: String, tr: Throwable?, at: LocalTime): String {
        val time = "%02d:%02d:%02d.%03d".format(at.hour, at.minute, at.second, at.nano / 1_000_000)
        val stack = tr?.let { "\n" + it.stackTraceToString().trimEnd() } ?: ""
        return "$time $level $tag: $msg$stack"
    }

    private fun note(level: Char, tag: String, msg: String, tr: Throwable?) {
        val text = line(level, tag, msg, tr, LocalTime.now(ZoneOffset.UTC))
        val schedule = synchronized(lock) {
            lines.addLast(text)
            while (lines.size > MOST_LINES) lines.removeFirst()
            val first = !writeScheduled && folder != null
            if (first) writeScheduled = true
            first
        }
        if (schedule) writer.schedule(::write, WRITE_AFTER_MS, TimeUnit.MILLISECONDS)
    }

    /** The whole ring over the last copy: a reader gets the old file or the new one, never half of one. */
    private fun write() {
        val dir = folder ?: return
        val text = synchronized(lock) {
            writeScheduled = false
            lines.joinToString("\n")
        }
        runCatching {
            val partial = File(dir, "run.log.partial")
            partial.writeText(text)
            partial.renameTo(File(dir, "run.log"))
        }
    }
}
