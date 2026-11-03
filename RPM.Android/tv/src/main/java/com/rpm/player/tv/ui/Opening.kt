package com.rpm.player.tv.ui

import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.size
import android.os.SystemClock
import com.rpm.player.core.library.QuickPick
import com.rpm.player.core.library.LibraryState
import com.rpm.player.core.library.BannerCard
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import coil3.request.ImageRequest
import coil3.ImageLoader
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import java.text.NumberFormat
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.rpm.player.core.R
import com.rpm.player.core.opening.SWEEP_MS
import com.rpm.player.core.opening.SweepSurface

/**
 * The opening screen: the rpm wordmark on black, dimmed, with a glowing
 * band that sweeps across it from left to right, lighting the letters it passes, once a second, over and
 * over, until the app is ready to show home.
 *
 * The dimmed wordmark is drawn here, where the window's background draws it before the app's first frame
 * (opening_window.xml), so nothing moves when that frame comes. The band is drawn over it by
 * [SweepSurface] (core), on a thread of its own: home's first composition holds the main thread for seconds on a
 * low-end TV box (over 200 frames skipped), and a sweep drawn by the main thread would stand still for all
 * of it.
 *
 * [ready] hands over at the end of a pass, never in the middle of one: once it is true, the band is faded
 * out and [onSwept] called at the next moment a pass ends, counted from when the sweep started, with the
 * band clear of the letters. The caller fades the rest out with it.
 */
@Composable
fun OpeningSplash(
    ready: Boolean,
    /** How many tracks have come from the cloud so far, while the library is read from the cloud with nothing
     * cached on the TV ([com.rpm.player.core.library.LibraryState.Loading]); 0 otherwise. */
    reading: Int,
    onSwept: () -> Unit,
    modifier: Modifier,
) {
    val handOver by rememberUpdatedState(ready)
    val swept by rememberUpdatedState(onSwept)
    val sweep = remember { arrayOfNulls<SweepSurface>(1) }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.wordmark_dim), contentDescription = null, modifier = Modifier.size(WORDMARK_WIDE, WORDMARK_HIGH))
        AndroidView(
            factory = { context -> SweepSurface(context).also { sweep[0] = it } },
            modifier = Modifier.size(WORDMARK_WIDE, WORDMARK_HIGH),
        )
        // A first launch, or one with no library kept on the TV, reads the whole library from the cloud before
        // home can show, 15 to 20 seconds for 23,000 tracks on a low-end TV box; the line under the logo says so,
        // without a count of the tracks. A launch from the cache never shows it: the library publishes the count only
        // when nothing is cached.
        val lineIn by animateFloatAsState(if (reading > 0) 1f else 0f, tween(READING_FADE_MS), label = "reading")
        if (reading > 0 || lineIn > 0f) {
            Text(
                stringResource(R.string.Rpm_Library_Reading),
                style = ui(18.ts),
                color = Color(0x99FFFFFF),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = WORDMARK_HIGH / 2 + READING_GAP)
                    .graphicsLayer { alpha = lineIn },
            )
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { handOver }.first { it }
        val surface = sweep[0]
        if (surface != null) {
            val elapsed = SystemClock.uptimeMillis() - surface.began
            delay(SWEEP_MS - elapsed % SWEEP_MS)
            surface.fadeOut(OPENING_FADE_MS)
        }
        swept()
    }
}

/**
 * Fetches the pictures home shows first into the image loader, so home appears with them in rather than
 * filling in after the opening screen has gone (the desktop's splash waits for the banner's and the lists'
 * pictures the same way, HomeView.IsReady):
 * the first three banner cards, the first twelve of Quick selection, the first six lists. At
 * [PICTURE_EDGE] pixels, which the loader reuses for the smaller boxes home draws them in. A picture that
 * fails is passed over; the caller bounds the wait.
 */
suspend fun fetchHomePictures(ready: LibraryState.Ready, images: ImageLoader, context: Context) = coroutineScope {
    val collection = ready.collection
    val home = ready.home
    val models = buildList<Any> {
        home.banner.take(3).forEach { card ->
            when (card) {
                is BannerCard.OfAlbum -> collection.cover(card.album)?.let(::add)
                is BannerCard.OfList -> collection.cover(card.list)?.let(::add)
                is BannerCard.OfStation -> Unit
            }
        }
        home.quick.take(12).forEach { pick ->
            when (pick) {
                is QuickPick.Song -> pick.album?.let(collection::cover)?.let(::add)
                is QuickPick.Radio -> pick.album?.let(collection::cover)?.let(::add)
                is QuickPick.OfAlbum -> collection.cover(pick.album)?.let(::add)
                is QuickPick.OfList -> collection.cover(pick.list)?.let(::add)
                is QuickPick.OfStation -> Unit
            }
        }
        home.madeForYou.take(6).forEach { list -> collection.cover(list)?.let(::add) }
    }.distinct()
    models.map { model ->
        async { runCatching { images.execute(ImageRequest.Builder(context).data(model).size(PICTURE_EDGE).build()) } }
    }.awaitAll()
}

/** Where the opening screen says when it handed over and why, for `adb logcat -s rpm.opening`. */
const val OPENING_LOG = "rpm.opening"

/** Milliseconds since the process started, for the opening's log lines. */
fun sinceStart(): Long = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()

/** How long the opening screen waits for home's first pictures before it shows home anyway. */
const val OPENING_PICTURES_MS = 3_000L

/** The size the first pictures are fetched at: a home card's square at 1080, rounded up. */
private const val PICTURE_EDGE = 400

/** The wordmark's size: 260 wide at 1080, as in the sketch, and its own height for that. The same 130 by
 * 50 dp as wordmark_dim.xml and opening_window.xml, which hold the dim (0.32). */
private val WORDMARK_WIDE = 260.px
private val WORDMARK_HIGH = 100.px

/** How far under the logo's middle the count line stands, past the logo's lower half. */
private val READING_GAP = 40.px

/** How long the count line takes to come up. */
private const val READING_FADE_MS = 300

/** How long the opening screen takes to fade away once the app is ready. */
const val OPENING_FADE_MS = 600

/**
 * The sound the app makes when it opens: the desktop's `rpm-open` (AppSound.PlayOpen), the site's mp3 of it,
 * started as home fades in. At [SOFTER] of full, as the desktop plays it under the player's level, and
 * stopped as soon as music starts ([stop]): whoever puts a record on has said what the speakers are for.
 * Played once per process, through its own MediaPlayer, sharing nothing with the playback service's
 * player. Best effort: a sound that cannot be prepared is not played and not reported.
 *
 * Not played over music that is already playing: the app opened again while its own queue plays, or another
 * app's music.
 * Asked of the system when the opening hands over, which answers whether or not this activity's controller
 * has connected to the playback service yet.
 */
class OpeningSound(private val context: Context) {
    private var player: MediaPlayer? = null
    private var played = false

    fun play() {
        if (played) return
        played = true
        if (context.getSystemService(AudioManager::class.java)?.isMusicActive == true) return
        val sound = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                context.resources.openRawResourceFd(R.raw.rpm_open).use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                setVolume(SOFTER, SOFTER)
                setOnPreparedListener { if (player === it) it.start() }
                setOnCompletionListener { stop() }
                setOnErrorListener { _, _, _ -> stop(); true }
                prepareAsync()
            }
        }.getOrNull()
        player = sound
    }

    fun stop() {
        val sound = player ?: return
        player = null
        runCatching { sound.stop() }
        sound.release()
    }

    private companion object {
        /** The desktop's `Softer`: a flourish, under the music's level. */
        const val SOFTER = 0.7f
    }
}
