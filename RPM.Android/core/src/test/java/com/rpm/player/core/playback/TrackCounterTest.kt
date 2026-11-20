package com.rpm.player.core.playback

import com.rpm.player.core.cloud.Shelf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrackCounterTest {
    private val a = TrackRef(Shelf.Own, "a")
    private val b = TrackRef(Shelf.Own, "b")

    /** Plays [ref] from [from] for [seconds] one-second readings; returns the plays handed out. */
    private fun TrackCounter.play(ref: TrackRef?, seconds: Int, duration: Double, from: Double = 0.0): List<TrackCounter.Counted> =
        (0..seconds).mapNotNull { read(ref, playing = true, position = from + it, duration = duration) }

    @Test
    fun `the listen rule is half the track or four minutes, and nothing under thirty seconds`() {
        assertTrue(Listen.counts(heard = 100.0, length = 200.0))
        assertFalse(Listen.counts(heard = 99.0, length = 200.0))
        assertTrue(Listen.counts(heard = 240.0, length = 1200.0))
        assertFalse(Listen.counts(heard = 29.0, length = 29.0))
        assertFalse(Listen.counts(heard = 100.0, length = 0.0))
    }

    @Test
    fun `a track heard past half counts when the next one starts`() {
        val counter = TrackCounter { 1000 }
        assertTrue(counter.play(a, 110, duration = 200.0).isEmpty())
        val ended = counter.read(b, playing = true, position = 0.0, duration = 180.0)
        assertEquals(TrackCounter.Counted(a, 1000), ended)
    }

    @Test
    fun `the last track of a queue counts when it ends, read as nothing playing`() {
        val counter = TrackCounter { 1000 }
        counter.play(a, 199, duration = 200.0)
        assertEquals(TrackCounter.Counted(a, 1000), counter.read(null, playing = false, position = 200.0, duration = 200.0))
        assertNull(counter.settle())
    }

    @Test
    fun `a track skipped early does not count`() {
        val counter = TrackCounter { 1000 }
        counter.play(a, 20, duration = 200.0)
        assertNull(counter.read(b, playing = true, position = 0.0, duration = 180.0))
    }

    @Test
    fun `a seek forward is not heard`() {
        val counter = TrackCounter { 1000 }
        counter.play(a, 10, duration = 200.0)
        // Jump to 150 s and play ten seconds more: twenty seconds heard, not 160.
        counter.play(a, 10, duration = 200.0, from = 150.0)
        assertNull(counter.settle())
    }

    @Test
    fun `playing the same track again from the start is a second listen`() {
        val counter = TrackCounter { 1000 }
        counter.play(a, 120, duration = 200.0)
        val first = counter.read(a, playing = true, position = 0.8, duration = 200.0)
        assertNotNull(first)
        counter.play(a, 120, duration = 200.0, from = 1.0)
        assertNotNull(counter.settle())
    }

    @Test
    fun `paused time is not heard, and a station is not a track`() {
        val counter = TrackCounter { 1000 }
        repeat(150) { counter.read(a, playing = false, position = 5.0, duration = 200.0) }
        assertNull(counter.settle())
        assertNull(counter.read(null, playing = true, position = 0.0, duration = 0.0))
    }

    @Test
    fun `the log keeps totals per account and starts over for another`() = runBlocking {
        val file = File.createTempFile("plays", ".json").apply { delete() }
        val job = Job()
        val log = TrackPlayLog(file, CoroutineScope(coroutineContext + job))
        log.note("u1", "a", 10)
        log.note("u1", "a", 20)
        log.note("u1", "b", 15)
        job.children.toList().joinAll()
        val first = log.forSync { deviceFor("u1") to unsent(200) }
        assertNotNull(first.first)
        assertEquals(listOf("a" to 2L, "b" to 1L), first.second.map { it.rpmId to it.plays })
        log.forSync { noteSent(first.second) }
        assertTrue(log.forSync { unsent(200) }.isEmpty())
        // Another account: nothing of the first is sent under it, and it gets its own device id.
        log.note("u2", "c", 30)
        job.children.toList().joinAll()
        val second = log.forSync { Triple(deviceFor("u1"), deviceFor("u2"), unsent(200)) }
        assertNull(second.first)
        assertNotNull(second.second)
        assertTrue(second.second != first.first)
        assertEquals(listOf("c"), second.third.map { it.rpmId })
        job.cancel()
        file.delete()
        Unit
    }
}

/** What goes into the listening log: every listen worth writing down, plays and skips, with the hour and the weekday
 * on this device's clock. The store cuts the lists that read the log from these. */
class HeardListenTest {
    private val a = TrackRef(Shelf.Own, "a")
    private val b = TrackRef(Shelf.Own, "b")

    /** Thursday 20 August 2026, 21:30 UTC. */
    private val instant = 1_787_261_400L

    private fun counter(zone: String) = TrackCounter(zone = { java.time.ZoneId.of(zone) }) { instant }

    private fun TrackCounter.hear(ref: TrackRef, seconds: Int, duration: Double = 300.0) {
        for (i in 0..seconds) readListen(ref, playing = true, position = i.toDouble(), duration = duration)
    }

    @Test
    fun `a play is a listen that counted`() {
        val counter = counter("UTC")
        counter.hear(a, 200)
        val heard = counter.readListen(b, playing = true, position = 0.0, duration = 300.0)
        assertNotNull(heard)
        assertEquals("a", heard!!.ref.rpmId)
        assertTrue(heard.counted)
        assertEquals(200.0, heard.heardSeconds, 0.001)
        assertEquals(300.0, heard.lengthSeconds, 0.001)
        assertEquals(instant, heard.at)
        assertEquals(TrackCounter.Counted(a, instant), heard.play)
    }

    @Test
    fun `a skip is a listen that did not count`() {
        val counter = counter("UTC")
        counter.hear(a, 20)
        val heard = counter.readListen(b, playing = true, position = 0.0, duration = 300.0)
        assertEquals(false, heard?.counted)
        assertNull(heard?.play)
    }

    @Test
    fun `a flick past is not written down`() {
        val counter = counter("UTC")
        counter.hear(a, 3)
        assertNull(counter.readListen(b, playing = true, position = 0.0, duration = 300.0))
    }

    @Test
    fun `the hour and the day are the listener's own`() {
        val counter = counter("Asia/Tokyo")
        counter.hear(a, 200)
        val heard = counter.settleListen()
        // 21:30 UTC on a Thursday is 06:30 on Friday in Tokyo.
        assertEquals(6, heard?.localHour)
        assertEquals(5, heard?.localDayOfWeek)
    }
}
