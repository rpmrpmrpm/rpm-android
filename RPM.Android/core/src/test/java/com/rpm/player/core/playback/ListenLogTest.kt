package com.rpm.player.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The listens this device has not sent yet, kept on disk until the store has taken them. */
class ListenLogTest {
    private fun folder(): File = Files.createTempDirectory("rpm-listens").toFile()

    private fun log(dir: File, keepAtMost: Int = ListenLog.CEILING) =
        ListenLog(File(dir, "listens.json"), keepAtMost)

    private fun heard(id: String, at: Long = 1_790_000_000, counted: Boolean = true) =
        ListenLog.Heard(id, at, if (counted) 200.0 else 20.0, 240.0, counted, 22, 3)

    @Test
    fun `a listen is kept with an id of its own until it is sent`() = runBlocking {
        val log = log(folder())
        log.note("u1", heard("t1"))
        log.note("u1", heard("t1", counted = false))

        val unsent = log.unsent("u1", 50)
        assertEquals(2, unsent.size)
        assertEquals(2, unsent.map { it.listenId }.toSet().size)
        assertEquals("t1", unsent[0].rpmId)
        assertTrue(unsent[0].counted)
        assertEquals(false, unsent[1].counted)
        assertEquals(22, unsent[0].localHour)
        assertEquals(3, unsent[0].localDayOfWeek)

        log.noteSent(unsent)
        assertTrue(log.unsent("u1", 50).isEmpty())
    }

    @Test
    fun `a listen of a track with no cloud id is not kept`() = runBlocking {
        val log = log(folder())
        log.note("u1", heard(""))
        assertTrue(log.unsent("u1", 50).isEmpty())
    }

    @Test
    fun `oldest go first and the rest wait`() = runBlocking {
        val log = log(folder())
        for (i in 0 until 5) log.note("u1", heard("t$i", at = 1_790_000_000L + i))
        val first = log.unsent("u1", 3)
        assertEquals(listOf("t0", "t1", "t2"), first.map { it.rpmId })
        log.noteSent(first)
        assertEquals(listOf("t3", "t4"), log.unsent("u1", 3).map { it.rpmId })
    }

    @Test
    fun `listens survive a restart`() = runBlocking {
        val dir = folder()
        log(dir).note("u1", heard("t1"))
        assertEquals(listOf("t1"), log(dir).unsent("u1", 50).map { it.rpmId })
    }

    /** Another account signing in on this device must not send the last one's listens. */
    @Test
    fun `listens belong to the account that heard them`() = runBlocking {
        val log = log(folder())
        log.note("u1", heard("t1"))
        assertTrue(log.unsent("u2", 50).isEmpty())
        log.note("u2", heard("t2"))
        assertEquals(listOf("t2"), log.unsent("u2", 50).map { it.rpmId })
        assertTrue(log.unsent("u1", 50).isEmpty())
    }

    @Test
    fun `the log keeps at most its ceiling and the oldest go first`() = runBlocking {
        val log = log(folder(), keepAtMost = 3)
        for (i in 0 until 5) log.note("u1", heard("t$i", at = i.toLong()))
        assertEquals(listOf("t2", "t3", "t4"), log.unsent("u1", 50).map { it.rpmId })
    }
}
