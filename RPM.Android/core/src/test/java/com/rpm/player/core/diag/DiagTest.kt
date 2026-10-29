package com.rpm.player.core.diag

import java.io.File
import java.nio.file.Files
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagTest {
    @Test
    fun trim_keeps_the_end_and_starts_on_a_line() {
        val text = "first line\nsecond line\nthird line"

        assertEquals(text, Diag.trim(text, 100))
        assertEquals("third line", Diag.trim(text, 15))
    }

    @Test
    fun a_line_has_the_time_level_tag_and_message() {
        assertEquals("09:05:03.042 W rpm.cast: no route", Diag.line('W', "rpm.cast", "no route", null, LocalTime.of(9, 5, 3, 42_000_000)))
    }

    @Test
    fun a_line_with_a_throwable_carries_its_stack() {
        val line = Diag.line('E', "rpm.net", "failed", IllegalStateException("boom"), LocalTime.NOON)

        assertTrue(line.startsWith("12:00:00.000 E rpm.net: failed\njava.lang.IllegalStateException: boom"))
    }

    @Test
    fun the_ring_keeps_the_last_lines_and_hands_the_run_to_the_next_launch() {
        val dir = Files.createTempDirectory("diag").toFile()
        Diag.start(dir)
        repeat(Diag.MOST_LINES + 10) { Diag.i("rpm.test", "line $it") }

        val tail = Diag.tail(Int.MAX_VALUE)!!
        assertEquals(Diag.MOST_LINES, tail.lines().size)
        assertTrue(tail.endsWith("line ${Diag.MOST_LINES + 9}"))

        Diag.flushNow()
        assertNull(Diag.previousRun())
        Diag.start(dir)
        assertTrue(Diag.previousRun()!!.endsWith("line ${Diag.MOST_LINES + 9}"))
        assertTrue(!File(dir, "run.log").exists())
    }
}
