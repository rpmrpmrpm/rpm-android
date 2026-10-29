package com.rpm.player.core.diag

/**
 * Records a Kotlin or Java crash in the [CrashOutbox] before the process ends: the stack, the thread, and this run's
 * log. Native crashes (the FFmpeg decoders, JNI) and ANRs do not pass through here; [ExitReasons] reads them from the
 * system at the next launch.
 */
object CrashCatcher {
    /** Call once in `Application.onCreate`, after [Diag.start]. The handler that was there before still runs after
     * this one, so the system's own crash dialog and its exit record are unchanged. */
    fun install(outbox: CrashOutbox, appVersion: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                Diag.e("rpm.crash", "uncaught on ${thread.name}", error)
                Diag.flushNow()
                outbox.put(record(thread.name, error, appVersion, System.currentTimeMillis(), Diag.tail()))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(thread: String, error: Throwable, appVersion: String, atMillis: Long, recent: String?) = CrashDraft(
        crashId = CrashDraft.newId(),
        crashedAtMillis = atMillis,
        appVersion = appVersion,
        exception = "${error.stackTraceToString().trimEnd()}\n(thread $thread)",
        systemReportKind = "android-java",
        recent = recent,
    )
}
