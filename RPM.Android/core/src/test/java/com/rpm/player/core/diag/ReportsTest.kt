package com.rpm.player.core.diag

import com.rpm.player.core.net.WireJson
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportsTest {
    private val facts = DeviceFacts("androidtv", "0.1.0 (2)", "Android 14 (API 34); Google Chromecast", "arm64-v8a")
    private val draft = FeedbackDraft("problem", "playback", "The record keeps spinning.", byteArrayOf(0x89.toByte(), 0x50), "tester@example.com")
    private val crash = CrashDraft("crash_0123456789abcdef01234567", 1_800_000_000_000, "0.1.0 (1)",
        exception = "java.lang.IllegalStateException: boom", systemReportKind = "android-java", recent = "the log")

    private fun json(text: String): JsonObject = WireJson.parseToJsonElement(text).jsonObject

    @Test
    fun anonymous_feedback_has_no_log_picture_or_reply_address() {
        val wire = ReportsClient.feedbackWire(draft, "the log", ReportScope.Anonymous, 0, facts, "install-1")

        assertNull(wire.recent)
        assertNull(wire.screenshotPng)
        assertNull(wire.replyTo)
        assertNull(wire.scope)
        assertEquals("The record keeps spinning.", wire.message)
        assertEquals("install-1", wire.installId)
    }

    @Test
    fun full_feedback_has_all_of_it_and_says_so() {
        val wire = ReportsClient.feedbackWire(draft, "the log", ReportScope.Full, 0, facts, "install-1")

        assertEquals("the log", wire.recent)
        assertEquals("iVA=", wire.screenshotPng)
        assertEquals("tester@example.com", wire.replyTo)
        assertEquals("full", wire.scope)
        assertEquals("androidtv", wire.head)
    }

    /** The names are the server's CrashReportRequest. A name that does not match is dropped without an error. */
    @Test
    fun a_crash_uses_the_servers_names() {
        val body = json(WireJson.encodeToString(CrashWire.serializer(), ReportsClient.crashWire(crash, ReportScope.Full, facts, "install-1")))

        assertEquals(setOf("crashId", "head", "appVersion", "platform", "architecture", "crashedAtUtcTicks", "installId",
            "exception", "systemReportKind", "recent", "scope"), body.keys)
        assertEquals("639355968000000000", body["crashedAtUtcTicks"].toString())
    }

    @Test
    fun an_anonymous_crash_keeps_the_stack_but_not_the_log() {
        val wire = ReportsClient.crashWire(crash, ReportScope.Anonymous, facts, "install-1")

        assertNull(wire.recent)
        assertNull(wire.scope)
        assertEquals("java.lang.IllegalStateException: boom", wire.exception)
    }

    @Test
    fun a_crash_id_has_the_servers_shape() {
        val id = CrashDraft.newId()

        assertTrue(Regex("crash_[0-9a-f]{24}").matches(id))
        assertFalse(id == CrashDraft.newId())
    }

    @Test
    fun a_status_400_is_refused_and_500_or_no_network_is_later() = runTest {
        suspend fun outcome(poster: Poster) = ReportsClient(poster, facts, "i").sendCrash(crash, ReportScope.Full)

        assertEquals(SendOutcome.Sent, outcome { _, _ -> 200 })
        assertEquals(SendOutcome.Refused, outcome { _, _ -> 400 })
        assertEquals(SendOutcome.Later, outcome { _, _ -> 503 })
        assertEquals(SendOutcome.Later, outcome { _, _ -> throw IOException("offline") })
    }

    /** A second press of Send while the first is on its way must not file the same words twice. */
    @Test
    fun a_second_send_while_the_first_is_on_its_way_is_busy() = runTest {
        val gate = CompletableDeferred<Unit>()
        var posts = 0
        val client = ReportsClient({ _, _ -> posts++; gate.await(); 200 }, facts, "i")

        val first = async { client.sendFeedback(draft, null, ReportScope.Full, 0) }
        testScheduler.runCurrent()
        assertEquals(FeedbackSent.Busy, client.sendFeedback(draft, null, ReportScope.Full, 0))
        gate.complete(Unit)

        assertEquals(FeedbackSent.Sent, first.await())
        assertEquals(1, posts)
    }

    @Test
    fun the_outbox_keeps_a_crash_until_it_is_sent_or_refused() = runTest {
        val box = CrashOutbox(Files.createTempDirectory("crashes").toFile())
        box.put(crash)
        box.put(crash.copy(crashId = CrashDraft.newId(), crashedAtMillis = 2))

        box.send { SendOutcome.Later }
        assertEquals(2, box.pending().size)

        box.send { SendOutcome.Refused }
        assertTrue(box.pending().isEmpty())
    }

    @Test
    fun a_crash_at_every_launch_keeps_only_the_newest() {
        val box = CrashOutbox(Files.createTempDirectory("crashes").toFile())
        repeat(CrashOutbox.MOST_KEPT + 3) { box.put(crash.copy(crashId = CrashDraft.newId(), crashedAtMillis = it.toLong())) }

        val kept = box.pending()
        assertEquals(CrashOutbox.MOST_KEPT, kept.size)
        assertEquals(3L, kept.first().crashedAtMillis)
    }

    @Test
    fun consent_is_asked_once_then_followed() {
        assertEquals(CrashConsent.Step.Ask, CrashConsent.next(CrashConsent.Unasked, 1))
        assertEquals(CrashConsent.Step.Nothing, CrashConsent.next(CrashConsent.Unasked, 0))
        assertEquals(CrashConsent.Step.Send, CrashConsent.next(CrashConsent.Send, 2))
        assertEquals(CrashConsent.Step.Nothing, CrashConsent.next(CrashConsent.DontSend, 2))
    }

    @Test
    fun an_empty_message_cannot_be_sent() {
        assertFalse(FeedbackDraft("problem", "other", " \n ", null, null).isSendable)
        assertTrue(draft.isSendable)
    }
}
