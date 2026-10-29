package com.rpm.player.core.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.rpm.player.core.diag.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import kotlin.coroutines.resume

/**
 * Finds Cast devices on the network from `_googlecast._tcp`, using the system's own mDNS (`NsdManager`), in the
 * same way as the computers are found in `home/Discovery.kt`. It browses only while someone is looking
 * ([look]). Each look starts from an empty list, because a device that left while nobody was browsing is never
 * reported as lost.
 *
 * Android 9's `NsdManager` resolves one service at a time, and the computers' discovery resolves through the
 * same system service. So if a resolve fails with "already active" or "too many", it is tried again after a
 * pause.
 */
class CastDiscovery(context: Context, private val scope: CoroutineScope) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val lock = Any()
    private val devices = CastDevices()
    private val _found = MutableStateFlow<List<CastDevice>>(emptyList())

    /** The devices found in this look, sorted by name. */
    val found: StateFlow<List<CastDevice>> = _found.asStateFlow()

    private var lookers = 0
    private var browsing: NsdManager.DiscoveryListener? = null
    private var resolving: Job? = null

    /** Starts browsing, or joins the browse that is already running. Every call needs one matching [stopLooking]. */
    fun look() {
        synchronized(lock) {
            lookers += 1
            if (lookers == 1) start()
        }
    }

    fun stopLooking() {
        synchronized(lock) {
            if (lookers == 0) return
            lookers -= 1
            if (lookers == 0) stop()
        }
    }

    /** Browses again from an empty list. The menu's "Look again" uses this. */
    fun again() {
        synchronized(lock) {
            if (lookers == 0) return
            stop()
            start()
        }
    }

    private fun start() {
        devices.clear()
        _found.value = emptyList()
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                queue.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(lock) {
                    if (browsing !== this) return
                    devices.lost(info.serviceName)
                    _found.value = devices.list()
                }
            }

            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Diag.w(TAG, "browse did not start: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        browsing = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Diag.w(TAG, "browse did not start: $it") }
        resolving = scope.launch(Dispatchers.IO) {
            for (info in queue) {
                val device = resolve(info) ?: continue
                synchronized(lock) {
                    // A resolve that finishes after its browse was stopped belongs to no look.
                    if (browsing !== listener) return@launch
                    devices.found(info.serviceName, device)
                    _found.value = devices.list()
                }
            }
        }
    }

    private fun stop() {
        browsing?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        browsing = null
        resolving?.cancel()
        resolving = null
    }

    private suspend fun resolve(info: NsdServiceInfo): CastDevice? {
        repeat(RESOLVE_TRIES) {
            var busy = false
            val resolved = suspendCancellableCoroutine<NsdServiceInfo?> { done ->
                @Suppress("DEPRECATION")
                nsd.resolveService(
                    info,
                    object : NsdManager.ResolveListener {
                        override fun onServiceResolved(service: NsdServiceInfo) = done.resume(service)
                        override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                            busy = errorCode == NsdManager.FAILURE_ALREADY_ACTIVE || errorCode == NsdManager.FAILURE_MAX_LIMIT
                            done.resume(null)
                        }
                    },
                )
            }
            if (resolved != null) return deviceOf(resolved)
            if (!busy) return null
            delay(RESOLVE_PAUSE_MS)
        }
        return null
    }

    private fun deviceOf(resolved: NsdServiceInfo): CastDevice? {
        val txt = resolved.attributes.entries.associate { (k, v) -> k.lowercase() to v?.toString(Charsets.UTF_8).orEmpty() }
        @Suppress("DEPRECATION")
        val host = (resolved.host as? Inet4Address)?.hostAddress
        return CastDevice.of(txt, host, resolved.port)
    }

    companion object {
        const val SERVICE_TYPE = "_googlecast._tcp"
        private const val TAG = "rpm.cast"
        private const val RESOLVE_TRIES = 8
        private const val RESOLVE_PAUSE_MS = 300L
    }
}
