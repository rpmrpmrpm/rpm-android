package com.rpm.player.core.cast

import android.content.Context
import android.os.SystemClock
import com.rpm.player.core.diag.Diag
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.rpm.player.core.R
import com.rpm.player.core.playback.StationItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** What the player screen shows of casting. */
sealed interface CastState {
    /** The device being connected to or sent to. Null when casting is off. */
    val device: CastDevice?

    data object Off : CastState {
        override val device: CastDevice? get() = null
    }

    data class Connecting(override val device: CastDevice) : CastState

    /** Sending to [device]. [ours] is true when RPM's receiver is running there and not Google's. */
    data class On(override val device: CastDevice, val ours: Boolean) : CastState
}

/** A message to show once under the player: a cast that could not start or that ended by itself. */
data class CastNotice(val text: String, val serial: Long)

/** Reads a record's cover as a JPEG for the receiver. Returns null if the record has no cover. */
fun interface CastCovers {
    suspend fun jpegOf(item: MediaItem): ByteArray?
}

/**
 * Casting for the whole app: the devices on the network, the session with one of them (a `Sitting`), and what
 * the player screen shows of it. The playback service attaches its player ([attach]) and builds its audio sink
 * on [route]. The screen reads [state], [discovery] and [notice], and calls [castTo] and [stop].
 *
 * Everything here runs on the main thread, where the service's player lives. The network round trips run on
 * `Dispatchers.IO` inside [CastLink]. A `Sitting` always ends through one function ([Sitting.end]). Every step
 * of a `Sitting` that suspends checks afterwards that it is still the current `Sitting`, because the session
 * may have ended or been replaced while it waited.
 */
class Casting(
    private val context: Context,
    /** Main-thread scope that lives as long as the process. */
    private val scope: CoroutineScope,
    private val covers: CastCovers,
) {
    /** What the audio sink reads to know where to send the audio. */
    val route = CastRoute()

    val discovery = CastDiscovery(context, scope)

    private val _state = MutableStateFlow<CastState>(CastState.Off)
    val state: StateFlow<CastState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<CastNotice?>(null)
    val notice: StateFlow<CastNotice?> = _notice.asStateFlow()

    private val _roomVolume = MutableStateFlow<Int?>(null)

    /** The room's volume, 0 to 100, while casting. Null otherwise. */
    val roomVolume: StateFlow<Int?> = _roomVolume.asStateFlow()

    private var player: Player? = null
    private var sitting: Sitting? = null

    private val events = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            sitting?.playChanged(playWhenReady)
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // A seek is recorded here, on the main thread, before the playback thread flushes. From this point,
            // replies still in flight about the stream that is about to be cut are dropped.
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                sitting?.room?.takenBack()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                sitting?.trackChanged(mediaItem)
            }
        }
    }

    /** The service's player, which casting controls and follows. */
    fun attach(player: Player) {
        this.player = player
        player.addListener(events)
    }

    /** The service is ending, so the session ends with it. */
    fun detach() {
        stop()
        player?.removeListener(events)
        player = null
    }

    /** Starts sending to [device]. Any current session ends first. */
    fun castTo(device: CastDevice) {
        if (player == null) return
        if (state.value.device?.id == device.id) return
        sitting?.end(Ending.Moved)
        val next = Sitting(device)
        sitting = next
        scope.launch { next.begin() }
    }

    /** Moves the sound back to the TV. */
    fun stop() {
        sitting?.end(Ending.ByPerson)
    }

    /** Sets the room's volume. The remote's volume keys call this while casting. */
    fun setRoomVolume(level: Int) {
        sitting?.turnTo(level)
    }

    fun stepRoomVolume(up: Boolean) {
        val now = _roomVolume.value ?: return
        setRoomVolume(now + if (up) CastVolume.STEP else -CastVolume.STEP)
    }

    private fun say(text: String) {
        _notice.value = CastNotice(text, SystemClock.uptimeMillis())
    }

    /** Why a `Sitting` ended, and what is shown about it. */
    private sealed interface Ending {
        /** The person chose the TV, or chose another device. */
        data object ByPerson : Ending
        data object Moved : Ending

        /** Someone stopped the sound at the speaker, so the player pauses. */
        data object Stopped : Ending

        /** The device stopped working as a destination. The music continues on the TV. */
        data class FellOver(val message: Int) : Ending

        /** Casting never started. */
        data class NotStarted(val message: Int) : Ending
    }

    /** One casting session with one device, from choosing the device to releasing it. */
    private inner class Sitting(val device: CastDevice) {
        val room = RoomStream()
        private val ring = CastFeed.ring()
        private var server: CastServer? = null
        private val volume = CastVolume(SystemClock::elapsedRealtime)
        private val jobs = mutableListOf<Job>()
        private var link: CastLink? = null
        private var app: ReceiverStatus.Application? = null
        private var ours = false
        private var host = ""
        private var feed: CastFeed? = null
        /** The stream the receiver holds (its generation), and the media session ID it holds it under. */
        private var loaded: Pair<Int, Int>? = null
        private var loading: Job? = null
        /** Counts what the screen has been told about, so a cover read for an earlier record is dropped. */
        private var screenTurn = 0
        private var volumeSending = false
        private var lastSentLevel: Int? = null

        private val alive: Boolean get() = sitting === this

        suspend fun begin() {
            room.pickingUp()
            _state.value = CastState.Connecting(device)
            val opened = withContext(Dispatchers.IO) { CastLink.open(device) }
            if (!alive) {
                opened?.let { withContext(Dispatchers.IO) { it.close() } }
                return
            }
            if (opened == null) return end(Ending.NotStarted(R.string.Rpm_Cast_Unreachable))
            link = opened
            jobs += scope.launch { for (message in opened.events) heard(message) }
            jobs += scope.launch {
                opened.gone.await()
                end(Ending.FellOver(R.string.Cast_Disconnected))
            }

            // Try RPM's own receiver first and Google's second, as the phone and the desktop do. If a device
            // will not launch ours, it plays on the stock receiver, and then the speaker's next and previous
            // controls do not work.
            val launched = startApp(CastApps.RPM) ?: if (alive && !refusedAsMember) startApp(CastApps.DEFAULT_MEDIA) else null
            if (!alive) return
            if (launched == null) {
                return end(Ending.NotStarted(if (refusedAsMember) R.string.Cast_InPair else R.string.Rpm_Cast_Refused))
            }
            app = launched
            ours = launched.appId == CastApps.RPM
            Diag.i(TAG, "${device.name}: ${if (ours) "RPM's receiver" else "the stock receiver"} on ${launched.transportId}")

            host = opened.ownAddress?.hostAddress?.substringBefore('%').orEmpty()
            if (host.isEmpty()) return end(Ending.NotStarted(R.string.Cast_ConnectFailed))
            val serving = withContext(Dispatchers.IO) { runCatching { CastServer(ring).apply { start() } }.getOrNull() }
            if (!alive) {
                serving?.let { withContext(Dispatchers.IO) { it.stop() } }
                return
            }
            if (serving == null) return end(Ending.NotStarted(R.string.Cast_ConnectFailed))
            server = serving

            // Read the room's own level before anything is sent, so a room at 20% does not jump to another level.
            val status = opened.ask(CastPayload.getStatus, CastNamespace.RECEIVER)?.let { ReceiverStatus.of(it.payload) }
            if (!alive) return
            status?.volume?.level?.let { volume.adopt(it) }
            _roomVolume.value = volume.level.takeIf { volume.known }

            val p = player ?: return end(Ending.NotStarted(R.string.Cast_ConnectFailed))
            val made = CastFeed(ring, System::nanoTime, Streams())
            made.paused = !p.playWhenReady
            feed = made
            route.cannotCast = {
                scope.launch { if (alive) end(Ending.FellOver(R.string.Tv_Cast_CannotCast)) }
            }
            _state.value = CastState.On(device, ours)

            // The sink switches at its next call. A seek to the current position makes that call a flush, so the
            // stream starts at the second the TV had reached. A station continues from the decoder.
            route.wanted = made
            if (p.isCurrentMediaItemSeekable && !p.isCurrentMediaItemLive) p.seekTo(p.currentMediaItemIndex, p.currentPosition)

            jobs += scope.launch { poll() }
        }

        /** Whether the last launch was refused because the device is in a group or a stereo pair. */
        private var refusedAsMember = false

        /** Starts [appId] on the device, or joins it if it is already running, and opens a connection to it. */
        private suspend fun startApp(appId: String): ReceiverStatus.Application? {
            val link = link ?: return null
            val now = link.ask(CastPayload.getStatus, CastNamespace.RECEIVER)?.let { ReceiverStatus.of(it.payload) }
            if (!alive) return null
            val running = now?.app(appId) ?: run {
                val answer = link.ask(CastPayload.launch(appId), CastNamespace.RECEIVER, withinMs = LAUNCH_MS) ?: return null
                if (CastPayload.typeOf(answer.payload) == "LAUNCH_ERROR") {
                    // Google answers every launch on a speaker in a group or pair with NOT_ALLOWED.
                    if (answer.payload.contains("NOT_ALLOWED")) refusedAsMember = true
                    Diag.i(TAG, "${device.name}: $appId refused: ${answer.payload}")
                    return null
                }
                ReceiverStatus.of(answer.payload)?.app(appId)
            } ?: return null
            if (!alive) return null
            // A device drops every message to a transport that it is not connected to.
            link.connect(running.transportId)
            return running
        }

        /** What the feed tells the controller. These are called on the playback thread. */
        private inner class Streams : CastFeed.Listener {
            override fun serve(stream: CastStream?) {
                server?.serve(stream)
                // A cut changes the turn that room reports are matched against. The poll also checks the feed's own count.
                scope.launch { if (alive) room.takenBack() }
            }

            override fun began(stream: CastStream) {
                scope.launch { if (alive) streamBegan(stream) }
            }
        }

        private fun streamBegan(stream: CastStream) {
            room.takenBack()
            loading?.cancel()
            loading = scope.launch { load(stream) }
        }

        /**
         * Hands [stream] to the receiver once the ring holds its head start. After the wait and after the
         * LOAD, it checks again that this is still the current stream, because two skips in a row start two of
         * these calls at the same time.
         */
        private suspend fun load(stream: CastStream) {
            val feed = feed ?: return
            val link = link ?: return
            val app = app ?: return
            val server = server ?: return
            fun current() = alive && feed.current?.generation == stream.generation

            // A receiver does not start until it has buffered a few seconds. A stream that arrives at normal
            // speed can never give it that, so the audio already waiting when it connects is its whole head
            // start. Do not wait while paused, because the decoder may fill nothing then.
            val until = SystemClock.uptimeMillis() + FILL_MS
            while (current() && player?.playWhenReady == true && feed.heldSeconds() < FILL_S && SystemClock.uptimeMillis() < until) {
                delay(FILL_POLL_MS)
            }
            if (!current()) return
            val p = player ?: return
            val item = p.currentMediaItem
            val words = wordsOf(item)

            // The cover's address is sent in the LOAD before the picture is read. A fetch that arrives first waits
            // for the cover.
            screenTurn += 1
            val mine = screenTurn
            server.cover.expect()
            val artUrl = item?.let { server.coverAddress(host, randomName()) }
            if (item == null) server.cover.show(null) else scope.launch {
                val jpeg = runCatching { covers.jpegOf(item) }.getOrNull()
                if (alive && mine == screenTurn) server.cover.show(jpeg)
            }

            val autoplay = p.playWhenReady
            val answer = link.ask(
                CastPayload.load(server.address(host, stream), words.title, words.artist, words.album, artUrl, autoplay),
                CastNamespace.MEDIA,
                app.transportId,
                LOAD_MS,
            )
            if (!current()) return
            when (val said = answer?.let { LoadAnswer.of(it.payload) }) {
                null -> end(Ending.FellOver(R.string.Cast_StreamRefused))
                LoadAnswer.Superseded -> Unit
                is LoadAnswer.Refused -> {
                    Diag.i(TAG, "${device.name}: LOAD refused: ${said.reason}")
                    end(Ending.FellOver(R.string.Cast_StreamRefused))
                }
                is LoadAnswer.Taken -> {
                    room.nowHolding(said.mediaSessionId)
                    loaded = stream.generation to said.mediaSessionId
                    if (ours) {
                        tell(
                            CastNowPlaying(
                                words.title, words.artist, words.album, artUrl, words.seconds,
                                trackAt = p.currentPosition.coerceAtLeast(0) / 1000.0,
                                streamAt = 0.0,
                            ),
                        )
                    }
                    // A key pressed while the stream was being handed over is sent now.
                    if (p.playWhenReady != autoplay) playChanged(p.playWhenReady)
                    jobs += scope.launch { watchFetch(stream.generation) }
                }
            }
        }

        /** An accepted LOAD does not mean the stream was fetched. Without this check, a device that never fetches the
         * stream would stay silent for ever. */
        private suspend fun watchFetch(generation: Int) {
            val server = server ?: return
            val until = SystemClock.uptimeMillis() + FETCH_MS
            while (alive && SystemClock.uptimeMillis() < until) {
                if (server.fetchedGeneration >= generation) {
                    room.itFetched()
                    return
                }
                delay(FETCH_POLL_MS)
            }
            if (alive && feed?.current?.generation == generation) end(Ending.FellOver(R.string.Cast_NeverFetched))
        }

        /** Asks the room for its position twice a second, and asks the device for its status every two seconds. */
        private suspend fun poll() {
            var round = 0
            while (alive) {
                delay(POLL_MS)
                val link = link ?: return
                val app = app ?: return
                val feed = feed ?: return
                val asked = room.turns
                val told = room.stamp
                val cut = feed.turns
                val reply = link.ask(CastPayload.getStatus, CastNamespace.MEDIA, app.transportId, ASK_POLL_MS)
                if (!alive) return
                val report = RoomReport.of(reply?.payload)
                val held = loaded
                val stream = feed.current
                if (report is RoomReport.Holding && held != null && stream != null && feed.turns == cut &&
                    held.first == stream.generation && report.status.mediaSessionId == held.second
                ) {
                    report.status.currentTime?.let { feed.says(stream.generation, it) }
                }
                val word = if (feed.turns != cut) RoomStream.Word.Nothing else room.reading(report, asked, told, paused = player?.playWhenReady != true)
                act(word)
                if (!alive) return
                if (feed.stalled()) return end(Ending.FellOver(R.string.Cast_StoppedPlaying))

                round += 1
                if (round % DEVICE_EVERY == 0) {
                    val askedDevice = room.turns
                    val status = link.ask(CastPayload.getStatus, CastNamespace.RECEIVER, withinMs = ASK_POLL_MS)?.let { ReceiverStatus.of(it.payload) }
                    if (!alive) return
                    if (status != null) deviceSaid(status, askedDevice)
                }
            }
        }

        private fun deviceSaid(status: ReceiverStatus, askedUnder: Int) {
            volume.adopt(status.volume?.level)?.let { _roomVolume.value = it }
            val app = app ?: return
            act(room.reading(status, app.sessionId, askedUnder))
        }

        private fun act(word: RoomStream.Word) {
            val p = player ?: return
            when (word) {
                RoomStream.Word.Nothing -> Unit
                RoomStream.Word.Pause -> p.pause()
                RoomStream.Word.Resume -> p.play()
                is RoomStream.Word.Stopped -> {
                    Diag.i(TAG, "${device.name}: stopped at the speaker (${word.because})")
                    end(Ending.Stopped)
                }
            }
        }

        /** A message from the device that nobody asked for. */
        private fun heard(message: CastMessage) {
            if (!alive) return
            when (message.namespace) {
                CastNamespace.RECEIVER -> ReceiverStatus.of(message.payload)?.let { deviceSaid(it, room.turns) }
                CastNamespace.CONTROL -> when (val word = RoomWord.of(message.payload)) {
                    RoomWord.Next -> player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
                    RoomWord.Previous -> player?.seekToPrevious()
                    RoomWord.ArtFailed -> artAsked()
                    is RoomWord.Said -> Diag.i(TAG, "${device.name} says ${word.type}: ${word.payload.take(300)}")
                    null -> Unit
                }
                else -> Unit
            }
        }

        /** The receiver could not fetch the cover from its address. Reply with the cover bytes, or with none. */
        private fun artAsked() {
            val link = link ?: return
            val app = app ?: return
            val server = server ?: return
            val jpeg = server.cover.now()
            scope.launch { link.tell(CastNamespace.CONTROL, ControlWords.art(jpeg), app.transportId) }
        }

        /** The player's play state changed. The room is told, using the media session it holds. */
        fun playChanged(playWhenReady: Boolean) {
            feed?.paused = !playWhenReady
            val session = room.session ?: return
            val link = link ?: return
            val app = app ?: return
            room.toldIt()
            scope.launch {
                link.ask(if (playWhenReady) CastPayload.play(session) else CastPayload.pause(session), CastNamespace.MEDIA, app.transportId)
                room.heardBack()
            }
        }

        /** A record followed another inside the stream. The screen is told, with the anchor set to the room's
         * current position. */
        fun trackChanged(item: MediaItem?) {
            if (!ours || item == null) return
            val feed = feed ?: return
            val server = server ?: return
            val streamAt = feed.inTheStream()
            val words = wordsOf(item)
            screenTurn += 1
            val mine = screenTurn
            scope.launch {
                // Send it once, with its cover: a `meta` without a cover tells the receiver that the record has none.
                val jpeg = runCatching { covers.jpegOf(item) }.getOrNull()
                if (!alive || mine != screenTurn) return@launch
                server.cover.show(jpeg)
                val artUrl = jpeg?.takeIf { it.isNotEmpty() }?.let { server.coverAddress(host, nameOf(it)) }
                tell(CastNowPlaying(words.title, words.artist, words.album, artUrl, words.seconds, trackAt = 0.0, streamAt = streamAt))
            }
        }

        private fun tell(playing: CastNowPlaying) {
            val link = link ?: return
            val app = app ?: return
            scope.launch { link.tell(CastNamespace.CONTROL, playing.payload(), app.transportId) }
        }

        fun turnTo(level: Int) {
            if (volume.set(level)) {
                _roomVolume.value = volume.level
                pushVolume()
            } else {
                _roomVolume.value = volume.level
            }
        }

        private fun pushVolume() {
            if (volumeSending) return
            val link = link ?: return
            volumeSending = true
            jobs += scope.launch {
                try {
                    while (alive) {
                        val level = volume.next(lastSentLevel) ?: break
                        link.ask(CastPayload.setVolume(level / 100.0), CastNamespace.RECEIVER)
                        lastSentLevel = level
                        delay(CastVolume.EVERY_MS)
                    }
                } finally {
                    volumeSending = false
                }
            }
        }

        /**
         * The only way a `Sitting` ends. The order is: move the sound back to the TV (reset the route, then seek
         * to the room's position so that the stream's flush is the switch), stop the jobs, then release the
         * device and close the server.
         */
        fun end(ending: Ending) {
            if (!alive) return
            sitting = null
            room.gone()
            val p = player
            val wasSending = route.wanted === feed && feed != null
            route.wanted = null
            route.cannotCast = null
            if (ending == Ending.Stopped) p?.pause()
            if (p != null && wasSending && p.isCurrentMediaItemSeekable && !p.isCurrentMediaItemLive) {
                p.seekTo(p.currentMediaItemIndex, p.currentPosition)
            }
            loading?.cancel()
            jobs.forEach { it.cancel() }
            jobs.clear()
            val link = link
            val app = app
            val server = server
            // Use the app's scope, which lasts longer than the session, so releasing the device is not cancelled
            // together with the session's jobs.
            scope.launch {
                withContext(Dispatchers.IO) {
                    // Stop the app on the speaker, unless the speaker has already disconnected from us.
                    if (link != null && app != null && ending != Ending.Stopped && !link.gone.isCompleted) {
                        link.ask(CastPayload.stopApp(app.sessionId), CastNamespace.RECEIVER, withinMs = STOP_MS)
                    }
                    link?.close()
                    server?.stop()
                }
            }
            if (ending != Ending.Moved) {
                _state.value = CastState.Off
                _roomVolume.value = null
            }
            when (ending) {
                is Ending.FellOver -> say(context.getString(ending.message, device.name))
                is Ending.NotStarted -> say(context.getString(ending.message, device.name))
                else -> Unit
            }
            Diag.i(TAG, "${device.name}: sitting ended: $ending")
        }
    }

    /** The text that a LOAD and a `meta` message carry about a record. */
    private data class Words(val title: String, val artist: String?, val album: String?, val seconds: Double?)

    private fun wordsOf(item: MediaItem?): Words {
        val metadata = item?.mediaMetadata ?: return Words("", null, null, null)
        val station = item.let(StationItems::stationOf)
        if (station != null) {
            // Use what the station announces, if it announces anything, with the station name as the artist.
            val said = player?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() }
            return Words(said ?: station.name, if (said != null) station.name else null, null, null)
        }
        return Words(
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() },
            album = metadata.albumTitle?.toString()?.takeIf { it.isNotBlank() },
            seconds = metadata.durationMs?.takeIf { it > 0 }?.let { it / 1000.0 },
        )
    }

    private companion object {
        const val TAG = "rpm.cast"

        /** The head start (in seconds of audio) that a receiver is given before it is told to fetch, and the longest
         * wait for it. */
        const val FILL_S = 4.0
        const val FILL_MS = 6_000L
        const val FILL_POLL_MS = 100L
        const val LAUNCH_MS = 20_000L
        const val LOAD_MS = 20_000L

        /** The desktop's `CastSink.FetchTimeout`. */
        const val FETCH_MS = 20_000L
        const val FETCH_POLL_MS = 250L
        const val POLL_MS = 500L
        const val ASK_POLL_MS = 3_000L
        const val STOP_MS = 2_000L

        /** The device status is requested once for every this many polls of the media transport. */
        const val DEVICE_EVERY = 4

        fun randomName(): String = java.util.UUID.randomUUID().toString().replace("-", "")

        /** Names a cover by its contents, so the same cover across an album is one address that the receiver keeps. */
        fun nameOf(jpeg: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(jpeg).take(8).joinToString("") { "%02x".format(it) }
    }
}
