package com.rpm.player.core.home

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One computer's catalogue as held in memory: its version, and its tracks by `rpmId`. */
data class HeldCatalog(
    val computer: String,
    val version: String,
    val tracks: Map<String, PeerTrack>,
    /** True when the catalogue came from a NAS rather than a computer. A NAS is tried first. */
    val fromNas: Boolean,
) {
    companion object {
        /** Catalogues from a NAS before catalogues from computers, then by key: the files are on the NAS, and the
         * computer would read them from it anyway. */
        fun nasFirst(catalogs: Collection<HeldCatalog>): List<HeldCatalog> =
            catalogs.sortedWith(compareBy<HeldCatalog>({ !it.fromNas }, { it.computer }))
    }
}

/**
 * The catalogues of the paired computers on the network. Each one is read when its computer is found and
 * every 5 minutes after that. The request sends `have` (the version held), so an unchanged library answers
 * 204. Requests run one at a time, and a catalogue is replaced as a whole when a new version arrives, so
 * a reader never sees a mix of two versions. [here] holds only the computers that are on the network now.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeCatalogs(private val computers: Computers, scope: CoroutineScope) {

    private val held = MutableStateFlow<Map<String, HeldCatalog>>(emptyMap())

    /** The paired computers on the network, by key, with their current address. */
    private val reachable = combine(computers.discovery.found, computers.paired, computers.trustedNas) { found, paired, nas ->
        val keys = paired.map { it.deviceId.uppercase() }.toSet()
        found.filterKeys { it in keys } + nas.filterKeys { it !in keys }
    }

    val here: StateFlow<Map<String, HeldCatalog>> = combine(held, reachable) { held, reachable -> held.filterKeys { it in reachable } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch(Dispatchers.IO) {
            // Restart only when the set of computers or an address changes, not on every beacon.
            reachable.map { it.values.toList() }.distinctUntilChanged().collectLatest { found ->
                while (true) {
                    for (computer in found) read(computer)
                    delay(POLL_MS)
                }
            }
        }
    }

    private suspend fun read(computer: FoundComputer) {
        try {
            val catalog = computers.catalog(computer, have = held.value[computer.key]?.version) ?: return
            val tracks = catalog.tracks.associateBy { it.rpmId }
            val fromNas = computers.trustedNas.value.containsKey(computer.key)
            held.update { it + (computer.key to HeldCatalog(computer.key, catalog.version, tracks, fromNas)) }
            // Only a paired computer names NAS machines. A NAS's copy of the catalogue carries the same list.
            if (!fromNas) computers.learnHosts(computer.key, catalog.hosts)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The catalogue that is held stays. The next poll tries again.
        }
    }

    private companion object {
        /** Same interval as the desktop's `CatalogPoll`. */
        const val POLL_MS = 5 * 60_000L
    }
}
