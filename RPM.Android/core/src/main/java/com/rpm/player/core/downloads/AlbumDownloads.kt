package com.rpm.player.core.downloads

import com.rpm.player.core.diag.Diag
import com.rpm.player.core.cloud.ApiException
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.net.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Downloads albums to the phone so they play without a network. The iPhone's `AlbumDownloads`
 * (RPMLibrary/AlbumDownloads.swift).
 *
 * **What to fetch is worked out, never remembered.** The book says which tracks are wanted and which have a file;
 * [inFlight] says which are on their way. [pump] takes the difference whenever it is asked, so it is right to call
 * from anywhere and at any time: the network coming back, the app coming forward, a download finishing, the key
 * being pressed.
 *
 * **Only stored objects are downloaded.** A rate the store has not made yet is signed as a live encode, and a live
 * encode that stops part way still answers 200. So the track waits in [RenditionWait] while the store makes it, and
 * is signed again until the store has the whole object.
 *
 * **The transfers run in this process.** The iPhone hands them to a background `URLSession`, which the system carries
 * on with while the app sleeps. Android has nothing like it for an app's own requests, so the phone keeps the process
 * in the foreground with a service of its own while [busy] is true (the phone's `DownloadService`). A process the
 * system ends anyway loses only the tracks in flight: the book still says they are missing, and the next [pump]
 * fetches them again.
 *
 * Everything here runs on [scope]'s thread, the main thread in the app, so the state needs no lock. Every suspension
 * is a point another call can enter through, which is why [pump] folds overlapping calls into one and why a track is
 * claimed in [inFlight] before it is signed. The disk is touched only on [io].
 *
 * @param sign a signed address for one of this account's own tracks at a rate, the rate it is served at, and whether
 * it is a live encode.
 * @param make asks the store to make these tracks at a rate. Used when a sign answers live, which a download never
 * takes.
 * @param rate the rate to ask for, read at the start of each pass.
 * @param online whether a request may go out now (the gate).
 * @param doubt called when a request found the line dead, so the gate closes for a moment.
 */
class AlbumDownloads(
    val book: DownloadBook,
    private val online: () -> Boolean,
    private val doubt: () -> Unit,
    private val sign: suspend (rpmId: String, kilobits: Int) -> Signed,
    private val make: suspend (rpmIds: List<String>, kilobits: Int) -> Unit,
    private val transfer: Transfer,
    private val rate: () -> Int,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What an album is on this phone. An album nobody asked for has no standing at all. */
    sealed interface Standing {
        /**
         * Some tracks still to come. [fraction] counts the parts of tracks that have arrived, so a ten-track album
         * does not sit at nought for its whole first track. [preparing] is true while nothing is transferring and
         * some of the tracks still to come wait for the store to make them at the chosen rate.
         */
        data class Downloading(val have: Int, val of: Int, val fraction: Double, val preparing: Boolean) : Standing

        /** Every track has a file. */
        data object OnPhone : Standing

        /** Some tracks failed [MOST_GOES] times and nothing is being tried for them. The key offers to try again. */
        data class Stuck(val have: Int, val of: Int) : Standing
    }

    /** One row of the downloads page: the record, what it takes on the phone, and where it stands. */
    data class Row(val album: CloudAlbum, val bytes: Long, val standing: Standing)

    /** A signed address, the rate it serves, and whether the store makes it while it sends. */
    data class Signed(val url: String, val kilobits: Int, val live: Boolean)

    /** Fetches one signed address into a file. */
    fun interface Transfer {
        /**
         * Writes the body of [url] to [into], telling [moved] how far it has got, and answers the HTTP status. A
         * failure of the network throws an [IOException].
         */
        suspend fun fetch(url: String, into: File, moved: (Double) -> Unit): Int
    }

    private val shownFlow = MutableStateFlow<Map<String, Standing>>(emptyMap())
    private val onPhoneFlow = MutableStateFlow<Set<String>>(emptySet())
    private val heldFlow = MutableStateFlow<Set<String>>(emptySet())
    private val rowsFlow = MutableStateFlow<List<Row>>(emptyList())
    private val busyFlow = MutableStateFlow(false)

    /** Every album with a standing, redrawn after each change. What the keys read. */
    val shown: StateFlow<Map<String, Standing>> = shownFlow.asStateFlow()

    /** The keys of albums with at least one track on the phone: what is greyed on the wall while offline. */
    val onPhone: StateFlow<Set<String>> = onPhoneFlow.asStateFlow()

    /** The tracks with a file on the phone: which songs of a record play offline. */
    val held: StateFlow<Set<String>> = heldFlow.asStateFlow()

    /** The albums asked for, newest first, with what each takes on the phone. What the downloads page draws. */
    val rows: StateFlow<List<Row>> = rowsFlow.asStateFlow()

    /** Whether anything is on its way or waiting for the store: while this is true the phone keeps the process up. */
    val busy: StateFlow<Boolean> = busyFlow.asStateFlow()

    /** Tracks being transferred, or being signed for a transfer. */
    private val inFlight = HashSet<String>()

    /** The transfer of each track in [inFlight] that has one. Cancelled when its album is removed. */
    private val transfers = HashMap<String, Job>()

    /** How far each transfer has got, as a fraction of the track. */
    private val partial = HashMap<String, Double>()

    /** Failed goes per track. In memory only: a relaunch is a fresh three goes, which is what somebody who closed the
     * app and opened it again expects. */
    private val goes = HashMap<String, Int>()

    /** Tracks the store is still making at the chosen rate. In memory only, like [goes]. */
    private val making = RenditionWait()

    /** The next pump for tracks in [making], or after the network failed a request. One at a time. */
    private var wake: Job? = null

    /** When a request last found the network dead. The next pass waits a while after it. */
    private var troubleAt: Long? = null

    private var pumping = false
    private var pumpAgain = false
    private var begun = false

    /** Reads the book from the disk, then asks for whatever is still missing. Called once, as the app opens. */
    fun begin() {
        if (begun) return
        begun = true
        scope.launch {
            withContext(io) { book.load() }
            redraw()
            pump()
        }
    }

    // ── what the screens ask ──

    /** Where an album stands, or null for one nobody asked for. */
    fun standing(key: String): Standing? = shownFlow.value[key]

    // ── what the keys do ──

    /** Downloads an album, or tries again for one that is stuck. */
    fun download(album: CloudAlbum) {
        scope.launch {
            withContext(io) { book.want(album) }
            for (track in album.tracks) goes.remove(track.rpmId)
            making.forget(album.tracks.map { it.rpmId })
            redraw()
            pump()
        }
    }

    /** Takes albums off the phone, and stops whatever was still coming for them. */
    fun remove(keys: Set<String>) {
        scope.launch {
            val gone = withContext(io) { book.drop(keys) }
            for (rpmId in gone) {
                partial.remove(rpmId)
                goes.remove(rpmId)
                transfers.remove(rpmId)?.cancel()
            }
            making.forget(gone)
            redraw()
        }
    }

    /** Signing out, or another account signing in: every file goes, and every transfer stops. */
    fun forgetEverything() {
        transfers.values.forEach { it.cancel() }
        transfers.clear()
        inFlight.clear()
        partial.clear()
        goes.clear()
        making.forgetAll()
        wake?.cancel()
        wake = null
        scope.launch {
            withContext(io) { book.forgetAll() }
            redraw()
        }
    }

    /** The files are [userId]'s. A book that holds another account's music is emptied first. */
    fun belongTo(userId: String) {
        scope.launch {
            val before = withContext(io) { book.owner }
            if (before != null && before != userId) {
                Diag.i(TAG, "another account signed in: the downloads go")
                forgetEverything()
            }
            withContext(io) { book.claim(userId) }
        }
    }

    // ── the work ──

    /**
     * Asks for whatever is missing, up to [AT_ONCE] at a time. Safe to call from anywhere and as often as anybody
     * likes: a call that arrives while one is running is folded into it rather than run beside it.
     */
    fun pump() {
        scope.launch { pumpNow() }
    }

    /** [pump], for a caller that waits for the pass to end. */
    suspend fun pumpNow() {
        if (!book.isLoaded) return
        if (pumping) {
            pumpAgain = true
            return
        }
        pumping = true
        try {
            do {
                pumpAgain = false
                pumpOnce()
            } while (pumpAgain)
        } finally {
            pumping = false
        }
    }

    private suspend fun pumpOnce() {
        // The signature is one of this app's own requests, and there is no point sending it with no network. The
        // network coming back calls this again.
        if (!online()) {
            redraw()
            return
        }

        // Read once per pass, so every track in one pass is asked for at the same rate. A change of setting is
        // picked up by the next pass, which the setting's own change starts.
        val asked = rate()
        val now = clock()
        val waiting = book.missing.filter {
            it !in inFlight && (goes[it] ?: 0) < MOST_GOES && making.isDue(it, asked, now)
        }

        var more = false
        for (rpmId in waiting.take(maxOf(0, AT_ONCE - inFlight.size))) {
            // Claimed before the suspension, so a second pump arriving in the gap does not sign the same track.
            inFlight += rpmId
            redraw()
            try {
                val signed = sign(rpmId, asked)

                // Removed while it was being signed.
                if (!book.isWanted(rpmId)) {
                    inFlight -= rpmId
                    continue
                }

                // Not made at this rate yet. A download never takes the live encode: it has answered 200 before it
                // knows whether it will finish, so a cut-off track would be kept as a whole one. The store is asked to
                // make it, and the stored object is downloaded once there is one.
                if (signed.live) {
                    inFlight -= rpmId
                    when (making.stillMaking(rpmId, asked, clock())) {
                        // The slot is free again, so the next track can be signed in this pump, not at the next wake.
                        RenditionWait.Said.New -> more = true
                        RenditionWait.Said.Waiting -> Unit
                        RenditionWait.Said.Overdue -> {
                            goes[rpmId] = (goes[rpmId] ?: 0) + 1
                            if ((goes[rpmId] ?: 0) >= MOST_GOES) making.forget(listOf(rpmId))
                            Diag.w(TAG, "$rpmId was not made at $asked kbps in time for a download")
                        }
                    }
                    continue
                }

                making.forget(listOf(rpmId))
                start(rpmId, signed)
            } catch (e: CancellationException) {
                inFlight -= rpmId
                throw e
            } catch (e: ApiException) {
                inFlight -= rpmId
                goes[rpmId] = (goes[rpmId] ?: 0) + 1
                Diag.w(TAG, "could not sign $rpmId for a download: $e")
            } catch (e: IOException) {
                // The network, not the track: not a go. The pass stops, and the next is due after a while.
                inFlight -= rpmId
                networkTrouble(e)
                break
            }
        }

        askTheStoreToMake()
        redraw()
        scheduleWake()

        if (more) pumpAgain = true
    }

    /** Starts the transfer of a signed track. Its end is reported to [finished] on this thread. */
    private fun start(rpmId: String, signed: Signed) {
        Diag.i(TAG, "downloading $rpmId at ${signed.kilobits} kbps")
        transfers[rpmId] = scope.launch {
            val into = book.partial(rpmId)
            try {
                val status = withContext(io) {
                    transfer.fetch(signed.url, into) { fraction -> scope.launch { moved(rpmId, fraction) } }
                }
                // A 403 is an expired signature and a 404 a track gone from the store. Either way the body is not the
                // record, and it is not kept.
                val kept = status in 200..299 && withContext(io) { book.keep(rpmId, into, signed.kilobits) }
                if (status !in 200..299) Diag.w(TAG, "$rpmId answered $status")
                finished(rpmId, kept, counts = true)
            } catch (e: CancellationException) {
                // A cancel is somebody removing the album, not the track failing.
                withContext(kotlinx.coroutines.NonCancellable + io) { into.delete() }
                finished(rpmId, kept = false, counts = false)
                throw e
            } catch (e: IOException) {
                withContext(kotlinx.coroutines.NonCancellable + io) { into.delete() }
                // A read stopped by a removal fails as a closed socket; that is not the network.
                if (coroutineContext[Job]?.isActive != false) networkTrouble(e)
                finished(rpmId, kept = false, counts = false)
            } finally {
                if (transfers[rpmId] === coroutineContext[Job]) transfers.remove(rpmId)
            }
        }
    }

    /**
     * Asks the store to make every track waiting on it that it has not been asked for yet, **and every other track
     * still to come at the same rate**, in one ask.
     *
     * All at once because of how the store runs the transcoder. An ask starts it at most once every thirty seconds,
     * and a run reads the want list once, at its start. Tracks asked for in later pieces land after that read and wait
     * for the five-minute schedule. Asking for tracks that turn out to exist costs nothing.
     *
     * An ask that fails is left unasked and sent again on the next pass.
     */
    private suspend fun askTheStoreToMake() {
        for ((kilobits, waiting) in making.unasked) {
            val wanted = waiting.filter { book.isWanted(it) }.toSet()
            making.forget(waiting.filter { it !in wanted })
            if (wanted.isEmpty()) continue

            // In the book's order, which is the order the transcoder is handed them.
            val send = book.missing.filter {
                it in wanted || (it !in inFlight && (goes[it] ?: 0) < MOST_GOES && !making.wasAsked(it, kilobits))
            }
            val ahead = send.filter { it !in wanted }
            try {
                make(send, kilobits)
                making.asked(wanted, kilobits)
                making.askedAhead(ahead, kilobits)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                Diag.w(TAG, "could not ask for ${send.size} tracks at $kilobits kbps: $e")
            } catch (e: IOException) {
                networkTrouble(e)
                return
            }
        }
    }

    /** A request found the network dead. The gate closes for a moment, and the work is tried again after a while. */
    private fun networkTrouble(e: IOException) {
        Diag.w(TAG, "the network failed a download request: $e")
        troubleAt = clock()
        doubt()
    }

    /**
     * Pumps again when the next waiting track is due to be asked about, or a while after the network failed a
     * request. Never sooner than five seconds from now: a track that is due but was not signed because every slot
     * was taken would otherwise be a wake due at once, and a pump that wakes itself at once is a loop. Those tracks are
     * signed when a transfer finishes, which pumps.
     */
    private fun scheduleWake() {
        wake?.cancel()
        wake = null
        val retry = troubleAt?.let { it + RETRY_MS }?.takeIf { book.missing.isNotEmpty() }
        val next = listOfNotNull(making.nextCheck, retry).minOrNull() ?: return
        val wait = maxOf(next - clock(), MIN_WAKE_MS)
        wake = scope.launch {
            delay(wait)
            // Let go of the handle before pumping. The pump ends by scheduling the next wake, which cancels whatever
            // [wake] holds; if that were still this job, the pump would cancel itself.
            wake = null
            troubleAt = null
            pumpNow()
        }
    }

    // ── what the transfers say ──

    private fun moved(rpmId: String, fraction: Double) {
        if (rpmId !in inFlight) return
        val was = partial[rpmId] ?: 0.0
        // Every two percent is plenty for a ring.
        if (fraction - was < 0.02 && fraction < 1.0) return
        partial[rpmId] = fraction
        redraw()
    }

    private fun finished(rpmId: String, kept: Boolean, counts: Boolean) {
        inFlight -= rpmId
        partial.remove(rpmId)
        if (!kept && counts && book.isWanted(rpmId)) goes[rpmId] = (goes[rpmId] ?: 0) + 1
        if (kept) Diag.i(TAG, "$rpmId is on the phone")
        redraw()
        pump()
    }

    /** Works out every standing again from the book, the transfers in flight and the goes. */
    private fun redraw() {
        if (!book.isLoaded) return
        val next = HashMap<String, Standing>()
        val some = HashSet<String>()
        val files = HashSet<String>()
        val rows = ArrayList<Row>()

        for (album in book.all) {
            val count = book.count(album.key) ?: continue
            val (have, of) = count
            if (have > 0) some += album.key

            val left = album.tracks.map { it.rpmId }.filter { book.held(it) == null }
            album.tracks.mapNotNullTo(files) { track -> track.rpmId.takeIf { it !in left } }

            val standing = if (have == of) {
                Standing.OnPhone
            } else if (left.any { it in inFlight || (goes[it] ?: 0) < MOST_GOES }) {
                val arriving = left.sumOf { partial[it] ?: 0.0 }
                val fraction = if (of > 0) (have + arriving) / of else 0.0
                val preparing = left.none { it in inFlight } && left.any { making.isWaiting(it) }
                Standing.Downloading(have, of, fraction, preparing)
            } else {
                Standing.Stuck(have, of)
            }
            next[album.key] = standing
            rows += Row(album.asAlbum, book.bytes(album.key), standing)
        }

        if (next != shownFlow.value) shownFlow.value = next
        if (some != onPhoneFlow.value) onPhoneFlow.value = some
        if (files != heldFlow.value) heldFlow.value = files
        if (rows != rowsFlow.value) rowsFlow.value = rows
        val working = inFlight.isNotEmpty() || !making.isEmpty
        if (working != busyFlow.value) busyFlow.value = working
    }

    companion object {
        /** How many tracks are transferred at once. Enough to keep a connection busy between one finishing and the
         * next being signed; few enough that every address is used well inside its signature's life. */
        const val AT_ONCE = 4

        /** How many times one track is tried before its album is called stuck. */
        const val MOST_GOES = 3

        /** The soonest a wake is set for. */
        const val MIN_WAKE_MS = 5_000L

        /** How long after the network failed a request the work is tried again, if nothing else tries it sooner. */
        const val RETRY_MS = 15_000L

        /** Where the downloads log, for `adb logcat -s rpm.downloads`. */
        const val TAG = "rpm.downloads"
    }
}

/** Fetches a signed address with OkHttp, streaming the body to the file. */
class OkHttpTransfer(private val http: OkHttpClient) : AlbumDownloads.Transfer {
    override suspend fun fetch(url: String, into: File, moved: (Double) -> Unit): Int = kotlinx.coroutines.coroutineScope {
        val call = http.newCall(Request.Builder().url(url).build())
        // A blocking read does not notice a cancel by itself. When the album is removed, this closes the socket, and
        // the read stops.
        val guard = launch {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            read(call, into, moved)
        } finally {
            guard.cancel()
        }
    }

    private suspend fun read(call: okhttp3.Call, into: File, moved: (Double) -> Unit): Int {
        call.await().use { response ->
            if (response.code !in 200..299) return response.code
            val body = response.body
            val total = body.contentLength()
            var written = 0L
            var told = 0.0
            into.parentFile?.mkdirs()
            body.byteStream().use { input ->
                into.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val fraction = written.toDouble() / total
                            if (fraction - told >= 0.02) {
                                told = fraction
                                moved(fraction)
                            }
                        }
                    }
                }
            }
            // A body cut short without an error is not the whole track.
            if (total > 0 && written != total) throw IOException("got $written of $total bytes")
            return response.code
        }
    }
}
