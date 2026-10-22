package com.rpm.player.core.playback

import com.rpm.player.core.cloud.Shelf
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.rpm.player.core.SessionOpening
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.IntentCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.rpm.player.core.cast.CastPlayer
import com.rpm.player.core.cast.CastingSink
import com.rpm.player.core.radio.RadioBrowser
import com.rpm.player.core.radio.RadioStation
import com.rpm.player.core.radio.StationCounter
import com.rpm.player.core.RpmApplication
import com.rpm.player.core.services
import java.io.File
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the player, so music keeps playing when the activity is gone (Home, or another app in front).
 * The activity talks to it through a `MediaController`.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val scope = MainScope()
    private var session: MediaSession? = null
    private val counter = StationCounter(System::currentTimeMillis)

    /** The tracks heard, read once a second beside the stations. */
    private val trackCounter = TrackCounter { System.currentTimeMillis() / 1000 }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var lastQueue: LastQueue
    /** The queue saved by the run before, read when the service starts; null once used or replaced. */
    private var resumable: SavedQueue? = null

    /** Where this sitting began in the queue, published to controllers with the station's reconnect state. */
    private val sitting = Sitting()
    private var attempting = false

    /** What plays without a network, on a device that keeps downloads; null on the TV ([com.rpm.player.core.Services.line]). */
    private var offline: OfflinePlayback? = null

    // A play is the signed-in account's. One counted with nobody signed in (the session ended in the
    // second before the reading) is not kept.
    private fun bankPlay(play: TrackCounter.Counted) {
        ListeningHistory.note(play.ref, play.at)
        val userId = services.auth.session.value?.userId ?: return
        android.util.Log.i(TrackPlaysSync.TAG, "play counted: ${play.ref.mediaId}")
        services.trackPlays.note(userId, play.ref.rpmId, play.at)
        services.trackPlaysSync.soon()
    }

    private fun bank(part: StationCounter.Heard) {
        services.radio.listens.note(part.station, part.seconds, part.newTune)
        services.radio.listensSync.soon()
    }

    override fun onCreate() {
        super.onCreate()
        val services = services
        lastQueue = LastQueue(File(filesDir, "last-queue.json"), scope)
        // A queue that played from a shared collection is not put back: the app opens on the person's own
        // collection, and the mini player must not show another account's track.
        resumable = lastQueue.read()?.takeUnless { saved ->
            saved.items.any { TrackRef.parse(it.mediaId)?.shelf is Shelf.Borrowed }
        }
        if (resumable == null) lastQueue.save(null)

        // The queue holds rpm-cloud:// addresses; each is opened at the computer or the cloud, whichever
        // was chosen for the track. A station's address is its
        // stream's, and passes through. One client pins each computer's key and checks every other host.
        val sources = services.trackSources
        val network = OkHttpDataSource.Factory(services.peerHttp).setUserAgent(RadioBrowser.USER_AGENT)
        val opened = SourcedDataSource.Factory(network, sources, FileDataSource.Factory())

        // The sink is wrapped so the room can read what is being heard, at the moment it is heard, and so the
        // audio can go to a Cast device instead of the TV.
        // Outside both, the volume levelling, so the room, the cast and the TV all hear the levelled sound.
        val classical = services.classicalMarks
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = LevelSink(
                TapSink(
                    CastingSink(
                        super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                            ?: DefaultAudioSink.Builder(context).build(),
                        services.casting.route,
                    ),
                    services.audioTap,
                ),
                services.levelling,
                classical = { item ->
                    val ref = TrackRef.parse(item.mediaId)
                    val m = item.mediaMetadata
                    ref != null && classical.isClassical(ref.shelf, m.albumArtist?.toString(), m.artist?.toString(), m.albumTitle?.toString())
                },
            )
        }.apply {
            // Media3's FFmpeg renderer for what Android cannot decode (ALAC, AC-3, E-AC-3), built with the
            // same sink, so the room hears it too.
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }

        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(opened).setLoadErrorHandlingPolicy(
                    // A track that is not on the phone waits for the network instead of failing (OfflineRetries). On the
                    // TV, which has no reading, it is never offline.
                    OfflineRetries(LiveStreamRetries(), offline = { services.line?.value?.isOnline == false }, services.copies),
                ),
            )
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()

        // The session's player says its device is remote while casting, so the remote's volume keys move the
        // speaker, and carries the playing item's picture for the launcher's card.
        val sessionPlayer = CastPlayer(player, services.casting)
        val builder = MediaSession.Builder(this, SessionArtwork(sessionPlayer, services.nowPlayingPictures, scope)).setCallback(Callback())
        // How the launcher's now playing card and the notification open the app: each app answers (SessionOpening).
        when (val opening = (application as RpmApplication).sessionOpening()) {
            is SessionOpening.Player -> builder.setSessionActivity(
                PendingIntent.getActivity(this, 0, opening.intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            SessionOpening.LauncherEntry -> Unit
        }
        session = builder.build()
        offline = services.line?.let { line -> OfflinePlayback(player, line, services.copies, scope, ::publishExtras).also { it.attach() } }
        services.casting.attach(player)
        services.sleep.attach(player)
        scope.launch {
            combine(services.casting.state, services.casting.roomVolume) { state, level -> state to level }
                .distinctUntilChanged()
                .collect { sessionPlayer.changed() }
        }

        // A track keeps its source while it plays and the next one may be loading; a track that fails
        // from a computer starts over at the same moment from the cloud ("HE × HF").
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val next = if (player.hasNextMediaItem()) player.getMediaItemAt(player.nextMediaItemIndex) else null
                sources.keepOnly(listOfNotNull(player.currentMediaItem, next).map { it.mediaId }.toSet())
            }

            override fun onPlayerError(error: PlaybackException) {
                val mediaId = player.currentMediaItem?.mediaId?.takeIf { StationItems.uuidOf(it) == null } ?: return
                // A computer that went away, or a download removed while it played: the cloud has the track.
                val failed = sources.sourceOf(mediaId)
                if (failed !is TrackSource.Computer && failed !is TrackSource.Local) return
                // Offline the cloud cannot have it either; the error stands.
                if (offline?.isOffline == true) return
                sources.useCloud(mediaId)
                player.prepare()
            }
        })

        // The queue is kept for the next run whenever it or its place changes, and every 15 seconds
        // while playing (below), so Play on the remote can resume it after the app has gone.
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED)) {
                    keepQueue(player)
                }
                // Where the sitting begins is worked out again from the queue on every change to it or to its place.
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    val before = sitting.from
                    if (sitting.settle(idsOf(player), player.currentMediaItemIndex) != before) publishExtras()
                }
            }
        })

        // A song radio's item becoming the current one queues the songs after it, here so it goes on with the app
        // gone. It picks from the collection on screen.
        // With the setting on, a list that ends goes on as a mixtape of songs like it. Only the TV has the setting so
        // far, so a phone, which has no way to turn it off, leaves it off.
        val settings = getSharedPreferences(SongRadioRuns.SETTINGS_FILE, MODE_PRIVATE)
        val onTv = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        SongRadioRuns(
            player,
            scope,
            collection = { (services.library.state.value as? com.rpm.player.core.library.LibraryState.Ready)?.collection },
            mixtapeAfter = { onTv && settings.getBoolean(SongRadioRuns.MIXTAPE_AFTER, SongRadioRuns.MIXTAPE_AFTER_DEFAULT) },
        ).attach()

        // A dropped station is tuned in again.
        player.addListener(
            StationReconnect(player, scope) {
                attempting = it
                publishExtras()
            },
        )

        // How long each station is heard, read once a second here, where playback lives, so a station
        // heard with the activity gone still counts.
        scope.launch {
            // The station decoded once per item, not once a second.
            var ticks = 0
            var item: MediaItem? = null
            var station: RadioStation? = null
            while (true) {
                delay(1_000)
                val current = player.currentMediaItem
                if (current != item) {
                    item = current
                    station = current?.let(StationItems::stationOf)
                }
                counter.read(station, player.isPlaying)?.let(::bank)
                val duration = player.duration.takeIf { it > 0 } ?: current?.mediaMetadata?.durationMs ?: 0L
                // A queue played to its end keeps its last track as the current item; that listen is over
                // and is judged now, not when the service ends. Played again, it is a new listen.
                trackCounter.read(
                    now = current?.takeIf { player.playbackState != Player.STATE_ENDED }?.let { TrackRef.parse(it.mediaId) },
                    playing = player.isPlaying,
                    position = player.currentPosition / 1000.0,
                    duration = duration / 1000.0,
                )?.let(::bankPlay)
                if (player.isPlaying && ++ticks % KEEP_EVERY_S == 0) keepQueue(player)
            }
        }

        // The last queue is put back as the service starts, paused and not prepared, as the Apple TV puts its queue
        // back at launch (TVModel.putBackTheQueue): the mini player, the menu's Now playing and the player show what
        // was playing, and Play resumes it where it was. Nothing is read from the network until Play. Without
        // this, a service the system had stopped as idle would come back empty, and the app would have no mini
        // player and no way to the player.
        if (services.auth.session.value != null) {
            resumption(player)?.let { player.setMediaItems(it.mediaItems, it.startIndex, it.startPositionMs) }
        }

        // A session that ends stops the music and empties the queue.
        scope.launch {
            services.auth.session.map { it == null }.distinctUntilChanged().collect { signedOut ->
                if (signedOut) {
                    player.stop()
                    player.clearMediaItems()
                    resumable = null
                    lastQueue.save(null)
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session


    /**
     * A media key that reaches the service by its start intent, which on Android 9 to 11 is how Media3
     * delivers a key while the session is not playing: the system starts the service as a foreground
     * service, and Android stops the app unless it shows a notification within 5 seconds. Media3 shows
     * one when the key starts playback; when it does not (nothing to resume), the service shows and
     * drops a notification itself and stops.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Exit rpm: the music stops and the service goes, its notification with it.
        if (intent?.action == ACTION_EXIT) {
            pauseAllPlayersAndStopSelf()
            return START_NOT_STICKY
        }
        val result = super.onStartCommand(intent, flags, startId)
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            // Media3 hands the key to the session on the main thread after this returns.
            handler.postDelayed(::settleForeground, SETTLE_MS)
        }
        return result
    }

    private fun settleForeground() {
        val player = session?.player
        if (player != null && player.playWhenReady && player.mediaItemCount > 0) return
        try {
            startForeground(IDLE_NOTIFICATION, idleNotification())
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: IllegalStateException) {
            // Android 12 and later refuse a foreground start from the background; there is nothing to settle then.
            Log.w(TAG, "foreground not settled: $e")
        }
        stopSelf()
    }

    private fun idleNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(IDLE_CHANNEL) == null) {
            val name = getString(com.rpm.player.core.R.string.App_Name)
            manager.createNotificationChannel(NotificationChannel(IDLE_CHANNEL, name, NotificationManager.IMPORTANCE_LOW))
        }
        return androidx.core.app.NotificationCompat.Builder(this, IDLE_CHANNEL)
            .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
            .setContentTitle(getString(com.rpm.player.core.R.string.App_Name))
            .setSilent(true)
            .build()
    }

    /** The session's extras, every fact in them at once: setting them replaces the whole bundle, so each fact is
     * published together with the others or not at all. */
    private fun publishExtras() {
        session?.setSessionExtras(
            Bundle().apply {
                putBoolean(StationReconnect.EXTRA_ATTEMPTING, attempting)
                putInt(Sitting.EXTRA_FROM, sitting.from)
                offline?.let { putString(PlaybackFailure.EXTRA, it.failure.name) }
            },
        )
    }

    private fun idsOf(player: Player): List<String> = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    /** Saves the queue as it stands, or forgets it when it is emptied. A queue the service built from the
     * saved one replaces it, so it is not resumed twice. */
    private fun keepQueue(player: Player) {
        if (player.mediaItemCount == 0) {
            // An empty player at start-up keeps the saved queue for the Play that may resume it.
            if (resumable == null) lastQueue.save(null)
            return
        }
        resumable = null
        lastQueue.save(
            SavedQueue(
                items = (0 until player.mediaItemCount).map { LastQueue.savedOf(player.getMediaItemAt(it)) },
                index = player.currentMediaItemIndex,
                positionMs = player.currentPosition.coerceAtLeast(0),
            ),
        )
    }

    /** The saved queue as playable items, or null when there is none. Whoever plays it starts a sitting at its
     * place, as a list handed over by a controller does. */
    private fun resumption(player: Player): MediaItemsWithStartPosition? {
        val saved = resumable ?: return null
        val items = saved.items.map(LastQueue::itemOf)
        val index = saved.index.coerceIn(0, items.size - 1)
        // A station has no place to go back to.
        val position = if (saved.items[index].station != null) 0 else saved.positionMs
        sitting.handed(items.map { it.mediaId }, index, idsOf(player))
        return MediaItemsWithStartPosition(items, index, position)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // The listen in hand ends with the service; what it heard since its last part is counted.
        counter.handOut()?.let(::bank)
        trackCounter.settle()?.let(::bankPlay)
        // The cast ends with the player it was sending from.
        services.casting.detach()
        services.sleep.detach()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private inner class Callback : MediaSession.Callback {

        /**
         * What a controller may ask. Media3 1.11 gives a controller from another app that it cannot vouch for only the
         * commands that read, and drops the rest without a word. Android TV's now playing card is such a controller:
         * its service (`com.google.android.tvrecommendations`) pauses the session for the card's Stop, and rpm cannot
         * see that package (Android 11's package visibility), so Media3 does not trust it; the pause is dropped and the
         * music goes on. Such a controller is also given the transport ([TRANSPORT]):
         * play, pause, stop, seeking and skipping, which every surface that shows what is playing needs, and nothing
         * that changes the queue. The app's own controller and trusted ones keep Media3's answer. Each connection from
         * another app is logged (`adb logcat -s rpm.playback`), once.
         */
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName == packageName) return super.onConnect(session, controller)
            Log.i(TAG, "${controller.packageName} connects, trusted ${controller.isTrusted}")
            if (controller.isTrusted) return super.onConnect(session, controller)
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_PLAYER_COMMANDS.buildUpon().addAll(TRANSPORT).build())
                .build()
        }

        /** Says in the log which controller asked for what, so a command from the launcher's card or another app
         * can be followed (`adb logcat -s rpm.playback`). The app's own controller is left out: it asks often. */
        @Deprecated("Deprecated in MediaSession.Callback")
        override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
            if (controller.packageName != packageName) Log.i(TAG, "${controller.packageName} asks command $playerCommand")
            @Suppress("DEPRECATION")
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }

        /** A controller's items arrive without their address, which Media3 strips for safety. A track's
         * address is a fact of its media id; a station's travels in its request metadata. Both are put
         * back here. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item ->
                when {
                    item.mediaId.isEmpty() -> item
                    StationItems.uuidOf(item.mediaId) != null -> StationItems.restore(item)
                    else -> TrackRef.parse(item.mediaId)?.let { item.buildUpon().setUri(CloudTrackUri.of(it)).build() } ?: item
                }
            }.toMutableList(),
        )

        /** A controller hands over a list to play: a new list starts a new sitting where it starts, and the list
         * already playing handed back is the player moving inside the one shown ([Sitting.handed]). */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            val asked = if (startIndex == C.INDEX_UNSET) 0 else startIndex
            // Offline, a list starts at the first of its tracks from there that is on the phone (OfflinePlayback).
            val from = offline?.startAt(mediaItems, asked) ?: asked
            offline?.started()
            sitting.handed(mediaItems.map { it.mediaId }, from, idsOf(mediaSession.player))
            if (from != asked) return super.onSetMediaItems(mediaSession, controller, mediaItems, from, 0)
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }

        /** Every media key, in front or behind, is handled here and only here, by [KeyRules]. */
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                ?: return false
            val action = KeyRules.forMediaKey(event.keyCode, session.player.meansToPlay, (application as RpmApplication).pauseKeyResumes) ?: return false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val player = session.player
                // Play with nothing queued resumes the last queue, where it was.
                val resumed = if (action == PlayerAction.PLAY && player.mediaItemCount == 0) resumption(player) else null
                if (resumed != null) {
                    player.setMediaItems(resumed.mediaItems, resumed.startIndex, resumed.startPositionMs)
                    player.prepare()
                    player.play()
                } else {
                    player.perform(action)
                }
            }
            return true
        }

        /** What a controller's Play resumes when nothing is queued (Media3 asks for it): the last queue. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaItemsWithStartPosition> =
            resumption(mediaSession.player)?.let { Futures.immediateFuture(it) }
                ?: Futures.immediateFailedFuture(UnsupportedOperationException("no queue to resume"))
    }

    companion object {
        /** Started with this action, the service pauses and stops: the app's Exit. */
        const val ACTION_EXIT = "com.rpm.player.EXIT"

        /** The commands a controller from another app that Media3 does not trust is given beside the ones that read
         * (Callback.onConnect). */
        private val TRANSPORT = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        ).build()

        /** The rate asked of the cloud. The Swift head's default; a free account is served 128. */
        const val STREAM_KILOBITS = 320

        /** Left and Right on the player screen, and the remote's rewind and fast-forward. */
        const val SEEK_STEP_MS = 10_000L

        private const val TAG = "rpm.playback"
        /** How long after a key's start intent the service looks whether it started playback; well
         * inside Android's 5 seconds. */
        private const val SETTLE_MS = 1_500L
        private const val KEEP_EVERY_S = 15
        private const val IDLE_NOTIFICATION = 7
        private const val IDLE_CHANNEL = "rpm.playback.idle"
    }
}
