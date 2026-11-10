package com.rpm.player.core.home

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.net.WireJson
import com.rpm.player.core.net.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.json.decodeFromStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A computer that this TV has paired with. The pairing is stored on the TV. */
@Serializable
data class PairedComputer(val deviceId: String, val publicKey: String, val name: String)

/** A computer as the computers screen shows it: paired or not, and on the network or not. */
data class ComputerView(val key: String, val name: String, val paired: Boolean, val here: FoundComputer?)

/** The state of a pairing. The first ending wins: once a pairing has ended, later endings are ignored. */
sealed interface Pairing {
    val name: String

    data class Asking(override val name: String) : Pairing
    /** The computer shows the code. [wrong] is true after the computer refused a code. */
    data class ShowingCode(override val name: String, val triesLeft: Int, val wrong: Boolean) : Pairing
    data class Ended(override val name: String, val how: Ending) : Pairing
}

/** [Outdated]: the computer runs a build that sends the code in the clear (before pairing version 2), which the TV
 * does not pair with. */
enum class Ending { Accepted, Declined, Expired, TooManyTries, Full, Lost, Failed, Outdated }

/** The computer answered 401 (the token is gone because of a restart, expiry, or the computer forgot the TV) or 403. */
class PeerRefused(val status: Int) : IOException("computer answered $status")

/**
 * The computers that this TV pairs with and reads from. This is the requesting side of the protocol in
 * `RPM.Core/Sharing`. Pairing (version 2, [PeerWire.PAIRING]): the TV offers its key, the computer shows a six-digit
 * code, and the person types the code on the TV, which never sends it: see [submit]. Sessions: the TV signs a challenge with its key and gets a token for 30 minutes.
 */
class Computers(
    context: Context,
    /** Built off the main thread as the app starts (Services.peerHttp), and first used when a request is made. */
    private val http: Lazy<OkHttpClient>,
    val discovery: Discovery,
    /** The libraries on a NAS, from `_rpm-nas._tcp`. Used only when a paired computer names them. */
    val nasDiscovery: Discovery,
    private val auth: AuthClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val file = File(context.noBackupFilesDir, "computers.json")
    private val tvName: String = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private val _paired = MutableStateFlow(readPaired())
    val paired: StateFlow<List<PairedComputer>> = _paired.asStateFlow()

    private val vouchedFile = File(context.noBackupFilesDir, "nas-hosts.json")
    private val _vouched = MutableStateFlow(readVouched())

    /** The NAS machines each paired computer's catalogue names, by that computer's key. */
    val vouched: StateFlow<Map<String, List<PeerHost>>> = _vouched.asStateFlow()

    /** The NAS libraries to use now: named by a paired computer and on the network with the named key. */
    val trustedNas: StateFlow<Map<String, FoundComputer>> =
        combine(_vouched, _paired, nasDiscovery.found, nasDiscovery.libraryIds) { vouched, paired, found, ids ->
            trustedNas(vouched, paired, found, ids)
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Replaces the NAS machines [owner] names with the list from its latest catalogue. */
    @Synchronized
    fun learnHosts(owner: String, hosts: List<PeerHost>) {
        val key = owner.uppercase()
        if ((_vouched.value[key] ?: emptyList()) == hosts) return
        val next = if (hosts.isEmpty()) _vouched.value - key else _vouched.value + (key to hosts)
        _vouched.value = next
        try {
            val temp = File(vouchedFile.parentFile, vouchedFile.name + ".tmp")
            temp.writeText(WireJson.encodeToString(MapSerializer(String.serializer(), ListSerializer(PeerHost.serializer())), next))
            if (!temp.renameTo(vouchedFile)) {
                vouchedFile.delete()
                temp.renameTo(vouchedFile)
            }
        } catch (_: Exception) {
        }
    }

    private fun readVouched(): Map<String, List<PeerHost>> = try {
        if (vouchedFile.exists()) {
            WireJson.decodeFromString(MapSerializer(String.serializer(), ListSerializer(PeerHost.serializer())), vouchedFile.readText())
        } else {
            emptyMap()
        }
    } catch (_: Exception) {
        emptyMap()
    }

    /** Every computer that is on the network or paired, with paired computers first, then sorted by name. */
    val list: StateFlow<List<ComputerView>> = combine(_paired, discovery.found) { paired, found ->
        val byKey = paired.associateBy { it.deviceId.uppercase() }
        (byKey.keys + found.keys).map { key ->
            ComputerView(key, found[key]?.name ?: byKey[key]?.name.orEmpty(), key in byKey, found[key])
        }.sortedWith(compareBy<ComputerView>({ !it.paired }, { it.name.lowercase() }))
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _pairing = MutableStateFlow<Pairing?>(null)
    val pairing: StateFlow<Pairing?> = _pairing.asStateFlow()

    private var identity: PeerIdentity? = null
    private val identityLock = Mutex()

    private suspend fun identity(): PeerIdentity = identityLock.withLock { identity ?: PeerIdentity.load().also { identity = it } }

    // Pairing ----------------------------------------------------------------------------------------

    private var pairingJob: Job? = null
    private var pairingId = ""
    private var pairingWith: FoundComputer? = null
    /** Counts pairings, so a reply for a replaced pairing is ignored. */
    private var attempt = 0
    private var lastTries = 0

    /** Never sent: each round's nonce is derived from it ([PairingCode.askerNonce]). New for each pairing. */
    private var pairingSeed = PairingCode.nonce()

    /** The newest round seen, with the commitment it was first seen with, for the pairing [attempt]. */
    private class Round(val attempt: Int, val number: Int, val promised: ByteArray, val nonce: ByteArray)

    private var round: Round? = null
    private val roundLock = Any()

    /** Starts pairing with [computer], and replaces any pairing in progress. */
    fun pair(computer: FoundComputer) {
        pairingJob?.cancel()
        val mine = ++attempt
        pairingWith = computer
        pairingId = ""
        val seed = PairingCode.nonce().also { pairingSeed = it }
        synchronized(roundLock) { round = null }
        _pairing.value = Pairing.Asking(computer.name)
        pairingJob = scope.launch(Dispatchers.IO) {
            try {
                val me = identity()
                val offer = post(
                    computer.base + "/rpm/pair",
                    WireJson.encodeToString(PairRequest.serializer(), PairRequest(
                        version = PeerWire.VERSION,
                        publicKey = PeerWire.toBase64Url(me.publicKey),
                        name = tvName,
                        face = "",
                        // Only computers before pairing version 2 read this nonce; it is sent so that one answers
                        // with an offer (with no commitment, below) rather than refusing.
                        nonce = PeerWire.toBase64Url(PairingCode.nonce()),
                        pairing = PeerWire.PAIRING,
                    )),
                    PairOffer.serializer(),
                    token = null,
                ) ?: throw IOException("no offer")
                if (!PeerWire.fromBase64Url(offer.publicKey).contentEquals(computer.publicKey)) return@launch end(mine, Ending.Failed)
                if (offer.id.isEmpty()) return@launch onStatus(mine, offer.status)
                // A code with no commitment comes from an older build, which sends the code in the clear. The
                // request is withdrawn, so the code comes off that screen.
                if (offer.status.state == "ShowingCode" && offer.status.commit.isEmpty()) {
                    end(mine, Ending.Outdated)
                    post<Unit>(computer.base + "/rpm/pair/withdraw", WireJson.encodeToString(PairId.serializer(), PairId(offer.id)), null, token = null)
                    return@launch
                }
                pairingId = offer.id
                lastTries = offer.status.triesLeft
                var status = offer.status
                onStatus(mine, status)
                join(mine, seed, computer, offer.id, status)
                // Ask again at once after each answer, because the computer ends a pairing that nobody follows for
                // 10 s. Failures are retried every second and treated as lost after 15 s.
                var failingSince = 0L
                while (_pairing.value !is Pairing.Ended && attempt == mine) {
                    status = try {
                        get(computer.base + "/rpm/pair/status?id=${offer.id}&rev=${status.rev}", PairStatus.serializer(), token = null)
                            .also { failingSince = 0L } ?: status
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (failingSince == 0L) failingSince = clock()
                        if (clock() - failingSince > 15_000) return@launch end(mine, Ending.Lost)
                        delay(1_000)
                        continue
                    }
                    onStatus(mine, status)
                    join(mine, seed, computer, offer.id, status)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                end(mine, Ending.Failed)
            }
        }
    }

    /**
     * The code the person typed, in three steps (the desktop's `PeerClient.PairingAsker.TypeAsync`): commit to it,
     * bound to both keys, and the computer reveals its nonce for the round; check that nonce against the commitment
     * pinned at the round's first status and work out the digits here; open the commitment only if they agree, or
     * send an empty opening, which the computer counts as a wrong code. The code itself never crosses the network.
     */
    fun submit(code: String) {
        val computer = pairingWith ?: return
        val id = pairingId.takeIf { it.isNotEmpty() } ?: return
        val mine = attempt
        val seed = pairingSeed
        val round = synchronized(roundLock) { round }?.takeIf { it.attempt == mine } ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val askerKey = identity().publicKey
                val ownerKey = computer.publicKey
                val opening = PairingCode.nonce()
                val commitment = PairingCode.commitCode(askerKey, ownerKey, opening, code)
                val committed = post(
                    computer.base + "/rpm/pair/commit",
                    WireJson.encodeToString(PairCommit.serializer(), PairCommit(id, round.number, PeerWire.toBase64Url(commitment))),
                    PairStatus.serializer(),
                    token = null,
                ) ?: return@launch
                val nonce = PeerWire.fromBase64Url(committed.reveal)
                // The pairing ended or moved on, or the digits were not on that screen yet: nothing is opened.
                if (committed.round != round.number || nonce == null || nonce.isEmpty()) {
                    onStatus(mine, committed)
                    join(mine, seed, computer, id, committed)
                    return@launch
                }
                val agrees = PairingCode.same(PairingCode.commitNonce(nonce), round.promised) &&
                    PairingCode.same(PairingCode.of(askerKey, ownerKey, round.nonce, nonce), code)
                post(
                    computer.base + "/rpm/pair/open",
                    WireJson.encodeToString(PairOpen.serializer(), PairOpen(id, round.number, if (agrees) PeerWire.toBase64Url(opening) else "")),
                    PairStatus.serializer(),
                    token = null,
                )?.let { opened ->
                    onStatus(mine, opened)
                    join(mine, seed, computer, id, opened)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The status loop still decides. A lost answer to the code does not end the pairing.
            }
        }
    }

    /** Withdraws a pairing in progress (Back on the code screen), or clears one that has ended. */
    fun cancel() {
        val computer = pairingWith
        val id = pairingId
        attempt++
        pairingJob?.cancel()
        _pairing.value = null
        if (computer != null && id.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                try {
                    post(computer.base + "/rpm/pair/withdraw", WireJson.encodeToString(PairId.serializer(), PairId(id)), null, token = null)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Sends this TV's nonce when [status] shows a round whose digits are not on the computer's screen yet (the
     * desktop's `PairingAsker.JoinAsync`). The round is pinned the first time it is seen, with its commitment, and
     * the nonce goes out only after that commitment has arrived: a machine in the middle must fix its nonce before
     * it learns the TV's. A later status cannot change a pinned round's commitment.
     */
    private suspend fun join(mine: Int, seed: ByteArray, computer: FoundComputer, id: String, status: PairStatus) {
        if (mine != attempt || status.state != "ShowingCode") return
        val pinned = synchronized(roundLock) {
            val now = round?.takeIf { it.attempt == mine }
            val promised = PeerWire.fromBase64Url(status.commit)
            if (status.round > (now?.number ?: 0) && promised != null && promised.isNotEmpty()) {
                Round(mine, status.round, promised, PairingCode.askerNonce(seed, status.round)).also { round = it }
            } else {
                now
            }
        } ?: return
        if (pinned.number != status.round || status.shown) return
        try {
            post(
                computer.base + "/rpm/pair/nonce",
                WireJson.encodeToString(PairNonce.serializer(), PairNonce(id, pinned.number, PeerWire.toBase64Url(pinned.nonce))),
                PairStatus.serializer(),
                token = null,
            )?.let { onStatus(mine, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Sent again with the next status for the round.
        }
    }

    private fun onStatus(mine: Int, status: PairStatus) {
        if (mine != attempt || _pairing.value is Pairing.Ended) return
        val name = pairingWith?.name.orEmpty()
        when (status.state) {
            "ShowingCode" -> {
                val wrong = status.triesLeft < lastTries
                lastTries = status.triesLeft
                _pairing.value = Pairing.ShowingCode(name, status.triesLeft, wrong)
            }
            "Accepted" -> {
                pairingWith?.let(::record)
                end(mine, Ending.Accepted)
            }
            "Declined" -> end(mine, Ending.Declined)
            "Expired" -> end(mine, Ending.Expired)
            "TooManyTries" -> end(mine, Ending.TooManyTries)
            "Full" -> end(mine, Ending.Full)
            else -> end(mine, Ending.Lost)
        }
    }

    private fun end(mine: Int, how: Ending) {
        if (mine != attempt || _pairing.value is Pairing.Ended) return
        _pairing.value = Pairing.Ended(pairingWith?.name.orEmpty(), how)
    }

    /** Records [computer] as paired. Recording the same computer again replaces the old entry. */
    @Synchronized
    private fun record(computer: FoundComputer) {
        val next = _paired.value.filterNot { it.deviceId.equals(computer.deviceId, ignoreCase = true) } +
            PairedComputer(computer.deviceId, PeerWire.toBase64Url(computer.publicKey), computer.name)
        _paired.value = next
        try {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(WireJson.encodeToString(ListSerializer(PairedComputer.serializer()), next))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (_: Exception) {
        }
    }

    private fun readPaired(): List<PairedComputer> = try {
        if (file.exists()) WireJson.decodeFromString(ListSerializer(PairedComputer.serializer()), file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun isPaired(key: String): Boolean = _paired.value.any { it.deviceId.equals(key, ignoreCase = true) }

    // Sessions ---------------------------------------------------------------------------------------

    private class Token(val value: String, val expiresAtMs: Long)

    private val tokens = ConcurrentHashMap<String, Token>()
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    /** A token for [computer]. It is reused until 2 minutes before it expires. Only one request at a time runs
     * per computer, so two callers do not ask for two tokens. Throws [PeerRefused] with 403 if the computer no
     * longer trusts the TV. */
    suspend fun token(computer: FoundComputer): String = sessionLocks.getOrPut(computer.key) { Mutex() }.withLock {
        tokens[computer.key]?.takeIf { it.expiresAtMs - clock() > 120_000 }?.let { return@withLock it.value }
        val me = identity()
        val challenge = get(computer.base + "/rpm/session", SessionChallenge.serializer(), token = null)
            ?: throw IOException("no challenge")
        // The account goes inside the signature. It tells the computer whether this TV is one of its owner's own
        // devices, and nothing on the network says so before the key is proved.
        val account = auth.session.value?.userId.orEmpty()
        val signature = me.sign(PeerWire.sessionText(computer.deviceId, challenge.challenge, account))
        val grant = post(
            computer.base + "/rpm/session",
            WireJson.encodeToString(SessionRedeem.serializer(), SessionRedeem(PeerWire.toBase64Url(me.publicKey), challenge.challenge, PeerWire.toBase64Url(signature), account)),
            SessionGrant.serializer(),
            token = null,
        ) ?: throw IOException("no grant")
        tokens[computer.key] = Token(grant.token, clock() + grant.expiresIn * 1000L)
        grant.token
    }

    /** Drops [computer]'s token after a 401, so the next call gets a new one. */
    fun forgetToken(computer: FoundComputer) {
        tokens.remove(computer.key)
    }

    // Catalogues and reachability ----------------------------------------------------------------------

    /** The catalogue of [computer], or null if it is still version [have] (204). It is read from the stream and
     * not from a string, because a library of 22,000 tracks is about 15 MB of JSON. A 401 drops the token and
     * the request is repeated once. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    suspend fun catalog(computer: FoundComputer, have: String?): PeerCatalog? {
        val url = computer.base + "/rpm/catalog" + (have?.let { "?have=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
        repeat(2) { round ->
            val token = token(computer)
            val request = Request.Builder().url(url).get().auth(token).build()
            http.value.newCall(request).await().use { response ->
                when (response.code) {
                    200 -> return response.body.byteStream().use { WireJson.decodeFromStream(PeerCatalog.serializer(), it) }
                    204 -> return null
                    401 -> if (round == 0) forgetToken(computer) else throw PeerRefused(401)
                    403 -> throw PeerRefused(403)
                    else -> throw IOException("computer answered ${response.code}")
                }
            }
        }
        throw PeerRefused(401)
    }

    /** When each computer last failed (a refused connection or a dropped track), and at which address. */
    private class Failure(val atMs: Long, val base: String)

    private val failures = ConcurrentHashMap<String, Failure>()

    /** Marks [key] as away after a failure. It is tried again after [AWAY_MS], or as soon as it is found on
     * the network at another address or port (which means its server restarted). */
    fun markAway(key: String) {
        val base = (discovery.found.value[key] ?: trustedNas.value[key])?.base ?: return
        failures[key] = Failure(clock(), base)
    }

    /** The computer to read from now: it is paired, found on the network, and has not failed recently at the
     * address where it is found. */
    fun usable(key: String): FoundComputer? {
        val found = (if (isPaired(key)) discovery.found.value[key] else trustedNas.value[key]) ?: return null
        val failed = failures[key] ?: return found
        if (failed.base != found.base || clock() - failed.atMs > AWAY_MS) {
            failures.remove(key)
            return found
        }
        return null
    }

    // Requests ---------------------------------------------------------------------------------------

    /** A GET request. Returns null for 204. A 401 or 403 throws [PeerRefused]. */
    suspend fun <T> get(url: String, reader: KSerializer<T>, token: String?): T? =
        call(Request.Builder().url(url).get().auth(token).build(), reader)

    private suspend fun <T> post(url: String, json: String, reader: KSerializer<T>?, token: String?): T? =
        call(Request.Builder().url(url).post(json.toRequestBody(JSON)).auth(token).build(), reader)

    private fun Request.Builder.auth(token: String?) = apply { if (token != null) header("Authorization", "Bearer $token") }

    private suspend fun <T> call(request: Request, reader: KSerializer<T>?): T? =
        http.value.newCall(request).await().use { response ->
            when (response.code) {
                200 -> reader?.let { WireJson.decodeFromString(it, response.body.string()) }
                204 -> null
                401, 403 -> throw PeerRefused(response.code)
                else -> throw IOException("computer answered ${response.code}")
            }
        }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val AWAY_MS = 120_000L

        /**
         * The NAS libraries to use: a host counts only when the computer that named it is still paired, and the
         * announcement on the network has the device id, key and library id that computer named. No code is
         * asked: the trust comes from the computer the TV paired with.
         */
        fun trustedNas(
            vouched: Map<String, List<PeerHost>>,
            paired: List<PairedComputer>,
            found: Map<String, FoundComputer>,
            libraryIds: Map<String, String>,
        ): Map<String, FoundComputer> {
            val owners = paired.map { it.deviceId.uppercase() }.toSet()
            val trusted = HashMap<String, FoundComputer>()
            for ((owner, hosts) in vouched) {
                if (owner.uppercase() !in owners) continue
                for (host in hosts) {
                    val key = host.deviceId.uppercase()
                    val seen = found[key] ?: continue
                    val named = PeerWire.fromBase64Url(host.publicKey) ?: continue
                    if (!named.contentEquals(seen.publicKey)) continue
                    if (!libraryIds[key].equals(host.libraryId, ignoreCase = true)) continue
                    trusted[key] = seen
                }
            }
            return trusted
        }
    }
}
