package com.rpm.player.core.playback

import androidx.media3.common.C
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale

class LiveEncodeTest {

    /** One AAC frame in an ADTS header, 1024 samples at 44.1 kHz, its payload filled with [fill]. */
    private fun frame(fill: Int, payload: Int = 40): ByteArray {
        val length = 7 + payload
        val header = byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            ((1 shl 6) or (4 shl 2)).toByte(),
            ((2 shl 6) or ((length shr 11) and 0x03)).toByte(),
            ((length shr 3) and 0xFF).toByte(),
            (((length and 0x07) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
        return header + ByteArray(payload) { (fill and 0x7F).toByte() }
    }

    private fun frames(from: Int, count: Int): ByteArray = (from until from + count).fold(ByteArray(0)) { all, i -> all + frame(i) }

    private fun seconds(frames: Int) = frames * 1024 / 44_100.0

    /** One connection as the server sends it: its bytes, then a clean close, or a drop when [drop] is set. */
    private class Connection(val bytes: ByteArray, val drop: Boolean = false, val duration: String? = null, val refused: Boolean = false)

    /** The encode's connections, in the order they are opened. Records the second each one was asked from. */
    private class Line(vararg connections: Connection) : LiveLine {
        val left = ArrayDeque(connections.toList())
        val asked = ArrayList<Double>()
        private var now: Connection? = null
        private var at = 0

        override fun open(fromSecond: Double): Map<String, List<String>> {
            asked.add(fromSecond)
            val next = left.removeFirstOrNull() ?: throw IOException("no more connections")
            if (next.refused) throw IOException("refused")
            now = next
            at = 0
            // OkHttp hands the headers over with their names in lower case.
            return next.duration?.let { mapOf("x-rpm-duration" to listOf(it)) } ?: emptyMap()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val c = now ?: throw IOException("not open")
            if (at == c.bytes.size) {
                if (c.drop) throw IOException("connection reset")
                return C.RESULT_END_OF_INPUT
            }
            // A few bytes at a time, so frames arrive in pieces.
            val n = minOf(length, 13, c.bytes.size - at)
            System.arraycopy(c.bytes, at, buffer, offset, n)
            at += n
            return n
        }

        override fun close() {
            now = null
        }
    }

    private fun readAll(encode: LiveEncode): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64)
        encode.open()
        while (true) {
            val n = encode.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
        }
        encode.close()
        return out.toByteArray()
    }

    /** The connection drops in the middle of the third frame: the encode is asked again from the end of the second,
     * and the player reads two whole frames and then the new connection's, never the broken one's half. */
    @Test fun aDropCarriesOnFromTheLastWholeFrame() {
        val third = frame(2)
        val line = Line(
            Connection(frames(0, 2) + third.copyOf(third.size / 2), drop = true),
            Connection(frames(100, 3)),
        )
        val pauses = ArrayList<Long>()
        val read = readAll(LiveEncode(line) { pauses.add(it) })
        assertArrayEquals(frames(0, 2) + frames(100, 3), read)
        assertEquals(listOf(0.0, seconds(2)), line.asked)
        assertEquals(1, pauses.size)
    }

    /** A clean close well before the length the server stated is a failure on its side, not the end of the song. */
    @Test fun aCleanCloseBeforeTheStatedLengthIsRetried() {
        val line = Line(
            Connection(frames(0, 10), duration = "2.00"),
            Connection(frames(10, 40), duration = "2.00"),
        )
        val read = readAll(LiveEncode(line) {})
        assertArrayEquals(frames(0, 50), read)
        assertEquals(listOf(0.0, seconds(10)), line.asked)
    }

    /** Within a second of the stated length, a clean close is the end. */
    @Test fun aCleanCloseAtTheStatedLengthIsTheEnd() {
        val line = Line(Connection(frames(0, 50), duration = "1.90"))
        assertArrayEquals(frames(0, 50), readAll(LiveEncode(line) {}))
        assertEquals(listOf(0.0), line.asked)
    }

    /** Without the length (the server only estimated it), a clean close is the end. */
    @Test fun aCleanCloseWithoutALengthIsTheEnd() {
        val line = Line(Connection(frames(0, 10)))
        assertArrayEquals(frames(0, 10), readAll(LiveEncode(line) {}))
        assertEquals(listOf(0.0), line.asked)
    }

    /** A close in the middle of a frame did not end the song, whatever else is known. */
    @Test fun aCloseInTheMiddleOfAFrameIsRetried() {
        val fifth = frame(4)
        val line = Line(
            Connection(frames(0, 4) + fifth.copyOf(10)),
            Connection(frames(4, 2)),
        )
        assertArrayEquals(frames(0, 6), readAll(LiveEncode(line) {}))
        assertEquals(listOf(0.0, seconds(4)), line.asked)
    }

    /** A line that will not come back is given up on after the retries, and the player is told. */
    @Test fun aLineThatStaysDownFailsAfterTheRetries() {
        val line = Line(
            Connection(frames(0, 3), drop = true),
            *Array(LiveEncode.RETRIES) { Connection(ByteArray(0), refused = true) },
        )
        val pauses = ArrayList<Long>()
        try {
            readAll(LiveEncode(line) { pauses.add(it) })
            fail("a line that stays down must fail")
        } catch (_: IOException) {
        }
        assertEquals(1 + LiveEncode.RETRIES, line.asked.size)
        assertEquals(LiveEncode.RETRIES, pauses.size)
    }

    /** A retry that brings frames starts the count again, so a long song can survive more than one drop. */
    @Test fun eachRetryThatPlaysStartsTheCountAgain() {
        val connections = (0..LiveEncode.RETRIES + 1).map { Connection(frames(it * 2, 2), drop = it <= LiveEncode.RETRIES) }
        val line = Line(*connections.toTypedArray())
        assertArrayEquals(frames(0, 2 * (LiveEncode.RETRIES + 2)), readAll(LiveEncode(line) {}))
    }

    /** A seek opens the encode from the second asked for, and the song is read on from there. */
    @Test fun aSeekStartsAtItsSecond() {
        val line = Line(Connection(frames(0, 10)))
        val encode = LiveEncode(line) {}
        encode.open(12.5)
        val buffer = ByteArray(4096)
        while (encode.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) Unit
        assertEquals(listOf(12.5), line.asked)
        assertEquals(12.5 + seconds(10), encode.handedOnSeconds()!!, 1e-9)
    }

    /** A drop after a seek is taken up from the seek's second plus what was read since. */
    @Test fun aDropAfterASeekCarriesOnFromTheSeekPlusWhatWasRead() {
        val line = Line(
            Connection(frames(0, 4), drop = true),
            Connection(frames(4, 2)),
        )
        val encode = LiveEncode(line) {}
        encode.open(30.0)
        val buffer = ByteArray(4096)
        while (encode.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) Unit
        assertEquals(listOf(30.0, 30.0 + seconds(4)), line.asked)
    }

    /** The stated length is the whole record's, so the seek's second counts towards it: a clean close at 30.2 s of a
     * 40 s record is not the end, and one within a second of the end is. */
    @Test fun theEndRuleCountsFromTheSeek() {
        val short = Line(Connection(frames(0, 10), duration = "40.0"), Connection(frames(10, 2), duration = "40.0"))
        LiveEncode(short) {}.apply { open(30.0) }.also { encode ->
            val buffer = ByteArray(4096)
            try {
                while (encode.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) Unit
            } catch (_: IOException) {
                // The second connection closes short of the end as well, and the retries run out on "no more".
            }
        }
        assertEquals(30.0 + seconds(10), short.asked[1], 1e-9)

        val atTheEnd = Line(Connection(frames(0, 10), duration = "30.5"))
        val encode = LiveEncode(atTheEnd) {}
        encode.open(30.0)
        val buffer = ByteArray(4096)
        while (encode.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) Unit
        assertEquals(listOf(30.0), atTheEnd.asked)
    }

    @Test fun theLengthIsReadInAnyLanguage() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals(245.32, LiveEncode.durationOf(mapOf("X-RPM-Duration" to listOf("245.32")))!!, 1e-9)
            assertEquals(245.32, LiveEncode.durationOf(mapOf("x-rpm-duration" to listOf("245.32")))!!, 1e-9)
            assertNull(LiveEncode.durationOf(emptyMap()))
            assertNull(LiveEncode.durationOf(mapOf("x-rpm-duration" to listOf("245,32"))))
            assertNull(LiveEncode.durationOf(mapOf("x-rpm-duration" to listOf("-1"))))
            assertEquals("https://t.example/live/abc?from=12.346", LiveEncode.addressFrom("https://t.example/live/abc", 12.3456))
            assertEquals("https://t.example/live?k=1&from=0.500", LiveEncode.addressFrom("https://t.example/live?k=1", 0.5))
        } finally {
            Locale.setDefault(before)
        }
    }
}
