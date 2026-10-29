package com.rpm.player.core.cast

import android.annotation.SuppressLint
import com.rpm.player.core.diag.Diag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * The conversation with one Cast device: a TLS socket that carries length-prefixed [CastMessage]s, multiplexed
 * by namespace. It owns the socket, the virtual connections that every other namespace needs, and the
 * heartbeat. Ported from the phone's `RPMCast/CastLink.swift`.
 *
 * A frame that the device sends on its own (a status change, or a message from RPM's receiver) goes to
 * [events]. An answer to [ask] goes to the caller that asked. It is matched by its `requestId` only, because
 * two questions can be open at the same time and their answers can arrive in either order.
 */
class CastLink private constructor(private val socket: SSLSocket, val deviceName: String) {
    private val input = DataInputStream(socket.inputStream.buffered())
    private val output: OutputStream = socket.outputStream
    private val writeLock = Any()
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<CastMessage>>()
    private val asked = AtomicInteger()
    private val shut = AtomicBoolean()
    private val _events = Channel<CastMessage>(Channel.UNLIMITED)

    /** Everything the device sends that is not an answer to a question. Closed when the link ends. */
    val events: ReceiveChannel<CastMessage> get() = _events

    /** Completed when the socket is gone, whatever the reason. */
    val gone = CompletableDeferred<Unit>()

    /** This device's own address on the network of the speaker. It is the address the socket connects from,
     * so the speaker can reach it to fetch the stream. */
    val ownAddress: InetAddress? get() = socket.localAddress

    private val reader = Thread(::read, "rpm-cast-link").apply { isDaemon = true }

    /** Sends one message. Blocking, so call it on `Dispatchers.IO`. Returns false if the socket is gone. */
    fun send(message: CastMessage): Boolean {
        if (shut.get()) return false
        val frame = message.framed()
        return try {
            synchronized(writeLock) {
                output.write(frame)
                output.flush()
            }
            true
        } catch (e: IOException) {
            Diag.i(TAG, "$deviceName: write failed: $e")
            close()
            false
        }
    }

    /** Sends [payload] on [namespace] to [destination] and does not wait for an answer. */
    suspend fun tell(namespace: String, payload: String, destination: String): Boolean =
        withContext(Dispatchers.IO) { send(CastMessage(namespace, payload, destination = destination)) }

    /**
     * Sends a question and waits for its answer. Returns null after [withinMs] or when the socket ends. The
     * waiter is registered before the frame is sent, because a device on the same network can answer before
     * the write has returned.
     */
    suspend fun ask(payload: JsonObject, namespace: String, destination: String = CastMessage.RECEIVER, withinMs: Long = ASK_MS): CastMessage? {
        val id = asked.incrementAndGet()
        val answer = CompletableDeferred<CastMessage>()
        waiting[id] = answer
        try {
            val sent = withContext(Dispatchers.IO) { send(CastMessage(namespace, CastPayload.stamp(payload, id), destination = destination)) }
            if (!sent) return null
            return withTimeoutOrNull(withinMs) { runCatching { answer.await() }.getOrNull() }
        } finally {
            waiting.remove(id)
        }
    }

    /** Opens a virtual connection to [destination]. A device silently drops every message to an endpoint that it
     * has not been connected to. The running app's transport is an endpoint of its own. */
    suspend fun connect(destination: String): Boolean =
        tell(CastNamespace.CONNECTION, CastPayload.connect.toString(), destination)

    /** Closes the socket. It sends CLOSE first if it can, so the device does not have to wait for a timeout. */
    fun close() {
        if (!shut.compareAndSet(false, true)) return
        runCatching {
            synchronized(writeLock) {
                output.write(CastMessage(CastNamespace.CONNECTION, CastPayload.close.toString()).framed())
                output.flush()
            }
        }
        runCatching { socket.close() }
    }

    private fun start() = reader.start()

    /**
     * Reads frames until the socket ends. The data is a stream and does not arrive in whole messages: the length
     * is read first, then exactly that many bytes, in as many reads as the network needs. A `PING` is answered
     * here, so no other code can forget it and lose the device. A read that waits [SILENT_MS] with nothing
     * arriving means the socket is dead, because the device sends a ping every five seconds.
     */
    private fun read() {
        val header = ByteArray(4)
        try {
            while (!shut.get()) {
                input.readFully(header)
                val length = CastMessage.lengthOf(header)
                if (length <= 0 || length > CastMessage.LONGEST) break
                val body = ByteArray(length)
                input.readFully(body)
                val message = CastMessage.decode(body) ?: continue
                if (message.namespace == CastNamespace.HEARTBEAT) {
                    if (CastPayload.typeOf(message.payload) == "PING") {
                        send(CastMessage(CastNamespace.HEARTBEAT, CastPayload.pong.toString(), destination = message.source.ifEmpty { CastMessage.RECEIVER }))
                    }
                    continue
                }
                val id = CastPayload.requestIdOf(message.payload)
                val waiter = id?.let { waiting.remove(it) }
                if (waiter != null) {
                    waiter.complete(message)
                    continue
                }
                _events.trySend(message)
            }
        } catch (e: SocketTimeoutException) {
            Diag.i(TAG, "$deviceName: nothing heard for ${SILENT_MS / 1000} s")
        } catch (e: IOException) {
            if (!shut.get()) Diag.i(TAG, "$deviceName: read ended: $e")
        } finally {
            close()
            // Everything still waiting is waiting on a socket that has ended.
            waiting.values.forEach { it.cancel() }
            waiting.clear()
            _events.close()
            gone.complete(Unit)
        }
    }

    companion object {
        private const val TAG = "rpm.cast"
        private const val CONNECT_MS = 5_000
        private const val ASK_MS = 8_000L
        private const val SILENT_MS = 30_000

        /** Opens the socket and sends CONNECT to the device. Returns null if the device cannot be reached. Blocking. */
        fun open(device: CastDevice): CastLink? {
            val socket = context().socketFactory.createSocket() as SSLSocket
            try {
                socket.connect(InetSocketAddress(device.host, device.port), CONNECT_MS)
                socket.soTimeout = SILENT_MS
                socket.tcpNoDelay = true
                socket.startHandshake()
            } catch (e: IOException) {
                Diag.i(TAG, "${device.name}: could not open: $e")
                runCatching { socket.close() }
                return null
            }
            val link = CastLink(socket, device.name)
            link.start()
            // Send CONNECT before anything else: a device drops every other message until it has received one.
            return link.takeIf { it.send(CastMessage(CastNamespace.CONNECTION, CastPayload.connect.toString())) }
        }

        /**
         * Accepts any certificate on this socket, and only on this one, as every Cast sender does (the phone's
         * `CastLink.tls`, the desktop's Sharpcaster). A device presents a certificate that it made itself, which
         * no trust store contains. The protocol's own check of which device this is (the device-auth challenge)
         * is not made. Nothing sent here is secret: only a track's name and the address of a stream on the home
         * network.
         */
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        private fun context(): SSLContext {
            val anyCertificate = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            return SSLContext.getInstance("TLS").apply { init(null, arrayOf(anyCertificate), null) }
        }
    }
}
