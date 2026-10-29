package com.rpm.player.core.diag

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitReasonsTest {
    private val native = ExitReasons.Exit(ApplicationExitInfo.REASON_CRASH_NATIVE, 2_000, "signal 11", byteArrayOf(1, 2, 3))
    private val anr = ExitReasons.Exit(ApplicationExitInfo.REASON_ANR, 3_000, "input dispatching timed out", "main".toByteArray())
    private val java = ExitReasons.Exit(ApplicationExitInfo.REASON_CRASH, 4_000, "crash", null)
    private val quit = ExitReasons.Exit(ApplicationExitInfo.REASON_USER_REQUESTED, 5_000, null, null)

    @Test
    fun a_native_crash_carries_its_tombstone_and_an_anr_its_trace() {
        val picked = ExitReasons.pick(listOf(native, anr), seen = 0, appVersion = "0.1.0 (2)", recent = "the log")

        assertEquals(listOf("android-tombstone", "android-anr"), picked.map { it.systemReportKind })
        assertEquals("AQID", picked[0].systemReport)
        assertEquals("main", picked[1].systemReport)
        assertEquals("Native crash: signal 11", picked[0].exception)
        assertEquals("the log", picked[0].recent)
    }

    /** CrashCatcher has already recorded a Kotlin or Java crash, with the app's own log. */
    @Test
    fun a_java_crash_and_a_quit_are_skipped() {
        assertTrue(ExitReasons.pick(listOf(java, quit), seen = 0, appVersion = "v", recent = null).isEmpty())
    }

    @Test
    fun exits_already_seen_are_skipped() {
        val picked = ExitReasons.pick(listOf(native, anr), seen = 2_000, appVersion = "v", recent = null)

        assertEquals(listOf("android-anr"), picked.map { it.systemReportKind })
    }

    /** The same exit read at two launches is one crash on the server. */
    @Test
    fun the_same_exit_has_the_same_id() {
        val first = ExitReasons.pick(listOf(native), seen = 0, appVersion = "v", recent = null).single()
        val again = ExitReasons.pick(listOf(native), seen = 0, appVersion = "v", recent = null).single()

        assertEquals(first.crashId, again.crashId)
        assertTrue(Regex("crash_[0-9a-f]{24}").matches(first.crashId))
    }

    @Test
    fun a_caught_crash_records_its_stack_and_thread() {
        val draft = CrashCatcher.record("main", IllegalStateException("boom"), "0.1.0 (2)", 7, "the log")

        assertTrue(draft.exception!!.startsWith("java.lang.IllegalStateException: boom"))
        assertTrue(draft.exception!!.endsWith("(thread main)"))
        assertEquals("android-java", draft.systemReportKind)
    }
}
