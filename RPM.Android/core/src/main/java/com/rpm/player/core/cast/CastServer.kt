package com.rpm.player.core.cast

import com.rpm.player.core.diag.Diag
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.withLock

/**
 * Lets a Cast device fetch sound from the TV. A receiver pulls the audio: it is only given an address. So
 * for as long as a casting session lasts, the app answers HTTP on a port of its own. It serves the live stream
 * as an endless WAV, and the record's cover. Ported from the phone's `RPMCast/CastServer.swift`.
 *
 * - **Only one stream is served**: the one that [serve] named last. A request for any other name gets a 404. A
 *   receiver that still uses the last stream's address is refused, and does not get the next track's audio
 *   attached to the end of the old stream's buffer.
 * - **Only one connection is streamed to.** A new stream or a reconnect closes the previous connection. The ring
 *   has one reader, and two connections would take turns reading it. A connection also stops when the ring holds
 *   another stream ([PcmRing.read] returns -1).
 * - **A `HEAD` is not a fetch.** Only a `GET` of the live stream sets [fetchedGeneration], which the fetch
 *   check reads.
 * - Every answer has `Access-Control-Allow-Origin: *`. RPM's receiver reads the cover's pixels to colour its
 *   room, and a picture from another origin without permission taints the canvas.
 */
class CastServer(private val ring: PcmRing) {
    private val socket = ServerSocket(0)
    private val lock = Any()
    @Volatile private var stopped = false

    // Guarded by [lock].
    private var live: CastStream? = null
    private var streaming: Socket? = null
    private val open = HashSet<Socket>()

    /** The port that the system assigned, open on every network interface. */
    val port: Int get() = socket.localPort

    /** The generation of the last stream a receiver came for with a `GET`. */
    @Volatile var fetchedGeneration = 0
        private set

    /** The cover of the record that is playing. */
    val cover = CoverCell()

    private val accepting = Thread(::accept, "rpm-cast-server").apply { isDaemon = true }

    fun start() = accepting.start()

    /** Serves [stream] from now on, or nothing, and closes the connection that streams the previous stream. */
    fun serve(stream: CastStream?) {
        val cut = synchronized(lock) {
            live = stream
            streaming.also { streaming = null }
        }
        cut?.let { runCatching { it.close() } }
    }

    /** The address where a receiver on the network fetches [stream]. [host] is this device's address on that
     * network. */
    fun address(host: String, stream: CastStream): String = "http://${hostPart(host)}:$port/${stream.id}.wav"

    /** The address where a receiver fetches a cover named [key]. The route only checks the `/art/` prefix, so
     * the caller chooses the name. If the name comes from the picture's contents, a receiver can keep one
     * cover for a whole album. */
    fun coverAddress(host: String, key: String): String = "http://${hostPart(host)}:$port/art/$key.jpg"

    fun stop() {
        stopped = true
        runCatching { socket.close() }
        val all = synchronized(lock) {
            live = null
            streaming = null
            open.toList().also { open.clear() }
        }
        all.forEach { runCatching { it.close() } }
        // Nothing should wait for a cover that the server will not serve.
        cover.show(null)
    }

    private fun accept() {
        while (!stopped) {
            val connection = try {
                socket.accept()
            } catch (e: IOException) {
                if (!stopped) Diag.i(TAG, "accept ended: $e")
                return
            }
            synchronized(lock) { open += connection }
            Thread({ answer(connection) }, "rpm-cast-serve").apply { isDaemon = true }.start()
        }
    }

    private fun answer(connection: Socket) {
        try {
            connection.soTimeout = REQUEST_MS
            connection.tcpNoDelay = true
            val head = readHead(connection.getInputStream()) ?: return
            val request = Request.of(head) ?: return
            val out = connection.getOutputStream()
            when {
                request.method == "OPTIONS" -> out.send(headers("204 No Content", null))
                request.method != "GET" && request.method != "HEAD" -> out.send(headers("405 Method Not Allowed", null, length = 0))
                request.path.startsWith("/art/") -> answerCover(out, request.method == "HEAD")
                else -> answerStream(connection, out, request)
            }
        } catch (e: IOException) {
            // A receiver that closes a connection is normal, and so is a stream cut by [serve].
        } finally {
            synchronized(lock) {
                open -= connection
                if (streaming === connection) streaming = null
            }
            runCatching { connection.close() }
        }
    }

    private fun answerStream(connection: Socket, out: OutputStream, request: Request) {
        val stream = synchronized(lock) { live }
        if (stream == null || request.path != "/${stream.id}.wav") {
            out.send(headers("404 Not Found", null, length = 0))
            return
        }
        out.send(headers("200 OK", "audio/wav"))
        // Some receiver firmware sends HEAD before GET. It only wants the content type.
        if (request.method == "HEAD") return

        // From here this connection is the stream's reader, and any earlier one is closed. The live stream is
        // checked again under the lock: if a new stream started since the check above, the new stream wins.
        val previous = synchronized(lock) {
            if (live !== stream || stopped) return
            streaming.also { streaming = connection }
        }
        previous?.let { runCatching { it.close() } }
        fetchedGeneration = stream.generation
        Diag.i(TAG, "stream ${stream.generation} fetched by ${connection.inetAddress?.hostAddress}")

        connection.soTimeout = 0
        out.write(WavHeader.of(stream.rate, PcmConvert.OUT_CHANNELS))
        val pail = ByteArray(stream.rate * PcmConvert.OUT_FRAME_BYTES / 5)
        while (!stopped) {
            val took = ring.read(pail, WAIT_MS, stream.generation)
            if (took < 0) return
            // Nothing read means the decoder is between buffers, not that the stream ended. Closing here would
            // disconnect the receiver at every pause.
            if (took > 0) out.write(pail, 0, took)
        }
    }

    private fun answerCover(out: OutputStream, headOnly: Boolean) {
        val picture = cover.await(COVER_WAIT_MS)
        if (picture == null || picture.isEmpty()) {
            // A record with no cover gets a 404. The receiver then draws the record without a cover.
            out.send(headers("404 Not Found", null, length = 0))
            return
        }
        out.send(headers("200 OK", "image/jpeg", length = picture.size, cache = "public, max-age=31536000, immutable"))
        if (!headOnly) out.write(picture)
        out.flush()
    }

    /** The request line and headers, up to the blank line. Returns null if they do not arrive or are too long. */
    private fun readHead(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        var tail = 0
        while (bytes.size() < HEAD_LONGEST) {
            val b = input.read()
            if (b < 0) return null
            bytes.write(b)
            tail = (tail shl 8) or b
            if (tail == 0x0D0A0D0A) return bytes.toString(Charsets.ISO_8859_1.name())
        }
        return null
    }

    private fun OutputStream.send(text: String) {
        write(text.toByteArray(Charsets.ISO_8859_1))
        flush()
    }

    /** The cover of the record that is playing. A receiver fetches it when it chooses. A cover can be announced
     * before it is read ([expect]). A fetch that arrives first waits for the cover and is not told that the
     * record has none. Null is also a valid answer, and it ends the wait. */
    class CoverCell {
        private val lock = java.util.concurrent.locks.ReentrantLock()
        private val shown = lock.newCondition()
        private var bytes: ByteArray? = null
        private var promised = false

        /** A cover is coming. Call it before an address is published. */
        fun expect() = lock.withLock {
            promised = true
            bytes = null
        }

        /** Sets the cover, or null for a record with none. */
        fun show(jpeg: ByteArray?) = lock.withLock {
            bytes = jpeg
            promised = false
            shown.signalAll()
        }

        /** The cover now. If a cover is announced, it waits at most [ms] for it. */
        fun await(ms: Long): ByteArray? = lock.withLock {
            var left = ms * 1_000_000
            while (promised && left > 0) left = shown.awaitNanos(left)
            bytes
        }

        /** The current cover, without waiting. */
        fun now(): ByteArray? = lock.withLock { bytes }
    }

    /** The request line, which is all this server reads. */
    internal data class Request(val method: String, val path: String) {
        companion object {
            fun of(head: String): Request? {
                val line = head.substringBefore("\r\n").split(' ')
                if (line.size < 2) return null
                return Request(line[0].uppercase(), line[1].substringBefore('?'))
            }
        }
    }

    companion object {
        private const val TAG = "rpm.cast"
        private const val REQUEST_MS = 5_000
        private const val HEAD_LONGEST = 8_192
        private const val WAIT_MS = 100L
        private const val COVER_WAIT_MS = 10_000L

        /** A response head, with the cross-origin headers that the receiver needs to read a cover's pixels. */
        internal fun headers(status: String, type: String?, length: Int? = null, cache: String = "no-cache, no-store"): String = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: ").append(cache).append("\r\n")
            append("Accept-Ranges: none\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Accept-Encoding, Range\r\n")
            if (type != null) append("Content-Type: ").append(type).append("\r\n")
            if (length != null) append("Content-Length: ").append(length).append("\r\n")
            append("\r\n")
        }

        /** An address as it is written in a URL: an IPv6 address is put in brackets, without its interface. */
        internal fun hostPart(host: String): String =
            if (':' in host) "[" + host.substringBefore('%') + "]" else host
    }
}
