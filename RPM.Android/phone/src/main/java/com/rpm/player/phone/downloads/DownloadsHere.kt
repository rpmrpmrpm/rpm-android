package com.rpm.player.phone.downloads

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.downloads.AlbumDownloads
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.StarterTracks
import com.rpm.player.core.net.Reach
import com.rpm.player.phone.PhoneApplication

/**
 * The downloads as the shell reads them: what the iPhone's `RootView` takes from `model.downloads` and
 * `model.reading`. One object, so the shell's own file gains one line for it.
 *
 * Each value is read from its state when it is asked for, so only the composable that asks recomposes when it changes:
 * a ring that fills redraws the record page's key, not the shell.
 */
@Stable
class DownloadsHere internal constructor(
    val engine: AlbumDownloads,
    private val reach: State<Reach>,
    private val onPhone: State<Set<String>>,
    private val held: State<Set<String>>,
    private val shown: State<Map<String, AlbumDownloads.Standing>>,
    private val rowsNow: State<List<AlbumDownloads.Row>>,
    private val askToNotify: State<() -> Unit>,
) {
    /** The settled reading ([PhoneApplication.line]): what the offline bar shows. */
    val online: Boolean get() = reach.value.isOnline

    /** What plays here, for the record tiles ([LocalPlayable]). */
    val playable: PlayableHere get() = PlayableHere(online, onPhone.value)

    /** Whether one song plays now: anything online, and offline a song with a file on the phone. */
    val canPlay: (CloudTrack) -> Boolean = { track -> online || track.rpmId in held.value }

    /** The albums asked for, newest first: the downloads page. */
    val rows: List<AlbumDownloads.Row> get() = rowsNow.value

    /** How many albums are downloaded and what they take: the profile tab's downloads row. */
    val downloaded: Pair<Int, Long> get() = rowsNow.value.let { rows -> rows.size to rows.sumOf { it.bytes } }

    /**
     * The download key for a record's page, or null on a shelf another account shares: a download is signed as one of
     * the account's own tracks, and another person's record is not one.
     */
    fun key(album: CloudAlbum, shelf: Shelf): DownloadKey? {
        // A starter record is in the app already, and the cloud has nothing to download for it.
        if (shelf != Shelf.Own || StarterTracks.isStarter(album)) return null
        return DownloadKey(
            standing = shown.value[album.key],
            onDownload = {
                askToNotify.value()
                engine.download(album)
            },
            onRemove = { engine.remove(setOf(album.key)) },
        )
    }
}

/**
 * The downloads for the shell. It starts the downloader once the app is showing (`AppModel.show` on the iPhone begins
 * it behind the wall), asks for whatever is missing each time the app comes forward (`downloads.pump()` on the
 * iPhone's return to the foreground), and asks once a session for the permission to show the downloads notification,
 * the first time a download starts (Android 13 and later; the downloads run without it).
 */
@Composable
fun rememberDownloadsHere(): DownloadsHere {
    val context = LocalContext.current
    val app = context.applicationContext as PhoneApplication
    val engine = app.downloads
    val reach = app.line.collectAsStateWithLifecycle()
    val onPhone = engine.onPhone.collectAsStateWithLifecycle()
    val held = engine.held.collectAsStateWithLifecycle()
    val shown = engine.shown.collectAsStateWithLifecycle()
    val rows = engine.rows.collectAsStateWithLifecycle()

    LaunchedEffect(engine) { engine.begin() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { engine.pump() }

    val asker = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val asked = remember { booleanArrayOf(false) }
    val askToNotify = rememberUpdatedState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked[0] &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            asked[0] = true
            asker.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    return remember(engine) { DownloadsHere(engine, reach, onPhone, held, shown, rows, askToNotify) }
}
