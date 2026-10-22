package com.rpm.player.core.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale

/** One connection to a live encode at a time: opened from a second of the song, read, and let go. */
interface LiveLine {
    /** Opens the encode from [fromSecond] (0 is the start) and answers the response's headers. */
    fun open(fromSecond: Double): Map<String, List<String>>

    /** As [DataSource.read]: [C.RESULT_END_OF_INPUT] when the server closed the connection cleanly. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun close()
}

/**
 * A live encode read to its end through drops, as the iPhone's `StreamedPcm` reads one. The cloud makes a rate it has
 * not stored while it sends it: ADTS with no length, which Media3 takes for a radio stream. Left to Media3, a drop
 * part way through starts the song again from its first second (a stream with no length cannot be resumed at a byte),
 * and a server that stops early and closes cleanly ends the song there and moves on to the next one without a word.
 *
 * - **Only whole frames are handed on.** What has been read is counted in AAC frames, so where the player stands in
 *   the song is known to the sample, and a broken frame never reaches the decoder.
 * - **A drop is taken up again where it happened**, by asking the encode `?from=` the second after the last whole
 *   frame, after a short pause, up to [RETRIES] times in a row. The new connection's frames follow the old ones
 *   with nothing for the player to notice. A retry that brings frames starts the count again.
 * - **A clean close is the end only when it can be** ([reachedTheEnd]): with the length the server states
 *   (`X-RPM-Duration`), the song must have been read to within a second of it; without it, a clean close counts.
 *   A close in the middle of a frame never does.
 *
 * Runs on a Media3 loader thread, which may block. [pause] waits between attempts.
 */
class LiveEncode(private val line: LiveLine, private val pause: (Long) -> Unit = Thread::sleep) {

    /** What has been read and not yet handed on: whole frames first ([ready] bytes), then a frame still arriving. */
    private var held = ByteArray(BUFFER)
    private var length = 0
    private var ready = 0

    /** Samples handed on or ready to be, and their rate, read from the frames themselves. */
    private var samples = 0L
    private var rate = 0

    /** The song's length as the server states it, or null when it did not. */
    private var duration: Double? = null

    private var retries = 0
    private var ended = false
    private val scratch = ByteArray(BUFFER)

    fun open() = connect(0.0)

    fun read(buffer: ByteArray, offset: Int, wanted: Int): Int {
        if (wanted == 0) return 0
        while (true) {
            if (ready > 0) {
                val n = minOf(wanted, ready)
                System.arraycopy(held, 0, buffer, offset, n)
                System.arraycopy(held, n, held, 0, length - n)
                length -= n
                ready -= n
                return n
            }
            if (ended) return C.RESULT_END_OF_INPUT
            val got = try {
                line.read(scratch, 0, scratch.size)
            } catch (e: IOException) {
                // Media3 stops a load by interrupting its thread: that is not a drop. A read that timed out is.
                if (Thread.currentThread().isInterrupted) throw e
                retry(e)
                continue
            }
            if (got == C.RESULT_END_OF_INPUT) {
                if (length == ready && reachedTheEnd(cleanly = true, seconds = seconds(), duration = duration)) {
                    ended = true
                } else {
                    retry(IOException("the live encode closed at ${seconds()} s of ${duration ?: "?"} s"))
                }
                continue
            }
            keep(scratch, got)
        }
    }

    fun close() = line.close()

    private fun seconds(): Double = if (rate == 0) 0.0 else samples.toDouble() / rate

    private fun connect(from: Double) {
        val headers = line.open(from)
        durationOf(headers)?.let { duration = it }
    }

    /** Lets the broken connection go, drops the frame it was in the middle of, and asks again from where the whole
     * frames end. Gives the failure to the player once the retries are spent. */
    private fun retry(failure: IOException) {
        try {
            line.close()
        } catch (_: IOException) {
        }
        length = ready
        while (true) {
            if (retries == RETRIES) throw failure
            try {
                pause(BACKOFF_MS[retries.coerceAtMost(BACKOFF_MS.lastIndex)])
            } catch (_: InterruptedException) {
                throw InterruptedIOException("stopped while waiting to retry")
            }
            retries++
            try {
                connect(seconds())
                return
            } catch (e: IOException) {
                if (Thread.currentThread().isInterrupted) throw e
                // Counted; the next attempt waits longer.
            }
        }
    }

    /** Adds what arrived, and moves every frame it completes into the part that can be handed on. */
    private fun keep(bytes: ByteArray, count: Int) {
        if (length + count > held.size) held = held.copyOf(maxOf(held.size * 2, length + count))
        System.arraycopy(bytes, 0, held, length, count)
        length += count
        while (length - ready >= HEADER) {
            val at = ready
            if (!isSync(at)) {
                // Not a frame (a tag before the first one): handed on as it is, up to the next frame.
                ready = nextSync(at + 1)
                continue
            }
            val size = ((held[at + 3].toInt() and 0x03) shl 11) or
                ((held[at + 4].toInt() and 0xFF) shl 3) or
                ((held[at + 5].toInt() and 0xE0) ushr 5)
            if (size < HEADER) {
                ready = nextSync(at + 1)
                continue
            }
            if (length - at < size) break
            val frameRate = RATES.getOrElse((held[at + 2].toInt() ushr 2) and 0x0F) { 0 }
            if (frameRate > 0) {
                rate = frameRate
                samples += SAMPLES_PER_BLOCK * ((held[at + 6].toInt() and 0x03) + 1)
            }
            ready = at + size
            retries = 0
        }
    }

    private fun isSync(at: Int): Boolean =
        held[at] == 0xFF.toByte() && (held[at + 1].toInt() and 0xF6) == 0xF0

    /** Where the next frame header may start after [from]; the last byte is kept back, as it may be one's first. */
    private fun nextSync(from: Int): Int {
        var i = from
        while (i < length - 1) {
            if (isSync(i)) return i
            i++
        }
        return maxOf(from, length - 1)
    }

    companion object {
        /** How many attempts in a row a drop is given before the player is told. */
        const val RETRIES = 3

        private val BACKOFF_MS = longArrayOf(500, 1_000, 2_000)

        /** How close to the stated length a clean close has to come to be the end. */
        private const val END_SLACK_S = 1.0

        private const val BUFFER = 16 * 1024
        private const val HEADER = 7
        private const val SAMPLES_PER_BLOCK = 1024

        /** ADTS's sampling frequency index. */
        private val RATES = intArrayOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350)

        /** The response header that states the song's whole length, in seconds; absent when the server only estimated it. */
        const val DURATION_HEADER = "X-RPM-Duration"

        /** The length [DURATION_HEADER] states, or null. HTTP header names are not case-sensitive, and OkHttp hands
         * them over in lower case. */
        fun durationOf(headers: Map<String, List<String>>): Double? = headers.entries
            .firstOrNull { it.key.equals(DURATION_HEADER, ignoreCase = true) }
            ?.value?.firstOrNull()?.trim()
            ?.takeIf { text -> text.all { it.isDigit() || it == '.' } }
            ?.toDoubleOrNull()
            ?.takeIf { it > 0 }

        /**
         * Whether a connection that ended had read the song to its end. A connection error never had. With the
         * stated length, the song must have been read to within [END_SLACK_S] of it; without it, a clean close counts.
         */
        fun reachedTheEnd(cleanly: Boolean, seconds: Double, duration: Double?): Boolean {
            if (!cleanly) return false
            return duration == null || seconds >= duration - END_SLACK_S
        }

        /** The encode's address asked from [second]: seconds with a decimal point, whatever the phone's language. */
        fun addressFrom(url: String, second: Double): String {
            val joiner = if ('?' in url) '&' else '?'
            return url + joiner + "from=" + String.format(Locale.ROOT, "%.3f", second)
        }
    }
}

/**
 * Media3's side of [LiveEncode]: opens a live encode's address through [upstream], and again `?from=` a second when
 * the encode is taken up after a drop. A live encode is always read from its start: it has no length, so Media3
 * never resumes one at a byte.
 */
@OptIn(UnstableApi::class)
class LiveEncodeSource(private val upstream: DataSource, private val url: String) : DataSource {

    private var uri: Uri? = null
    private var headers: Map<String, List<String>> = emptyMap()
    private var spec: DataSpec? = null

    private val encode = LiveEncode(
        object : LiveLine {
            override fun open(fromSecond: Double): Map<String, List<String>> {
                val address = if (fromSecond > 0) LiveEncode.addressFrom(url, fromSecond) else url
                val asked = checkNotNull(spec).withUri(Uri.parse(address))
                upstream.open(asked)
                uri = upstream.uri
                headers = upstream.responseHeaders
                return headers
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

            override fun close() = upstream.close()
        },
    )

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        encode.open()
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = encode.read(buffer, offset, length)

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = headers

    override fun close() {
        uri = null
        encode.close()
    }
}
