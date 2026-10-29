package com.rpm.player.phone.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.rpm.player.core.diag.Diag
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rpm.player.core.R
import com.rpm.player.core.downloads.AlbumDownloads
import com.rpm.player.phone.MainActivity
import com.rpm.player.phone.PhoneApplication
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while albums download, with a notification that says what is coming. The
 * iPhone hands its transfers to a background `URLSession`, which the system carries on with while the app sleeps;
 * Android has nothing like it for an app's own requests, so the transfers run in this process ([AlbumDownloads]) and
 * this service keeps the process from being frozen or ended while they do.
 *
 * **Why a `dataSync` foreground service, and not a user-initiated data transfer job.** Android 14 added jobs
 * (`JobInfo.Builder.setUserInitiated`) for exactly this, a transfer the person asked for. They exist only from API 34,
 * and the app runs from API 28 (the Titan it is tested on runs 33), so a job would need this service beside it for
 * every older phone: two ways of doing one thing, one of them untested on the test phone. A `dataSync` service runs
 * on every version the app supports. Its limits, from Android 15 at `targetSdk` 35 and later, do not get in the way:
 *
 * - it may run for six hours in each 24. An album takes minutes. When the time is up the system calls [onTimeout],
 *   and the service stops at once, as it must; the transfers in flight finish while the process lives, and whatever
 *   is left is fetched when the app next comes forward ([AlbumDownloads.pump] on start);
 * - it may not be started from `BOOT_COMPLETED`, which nothing here does;
 * - like any foreground service, it may not be started from the background. It is started when work appears: a key
 *   pressed, the app coming forward, or the network coming back while it already runs. A start the system refuses is
 *   logged and dropped, and the work waits for the app to come forward.
 *
 * **It runs only while there is work.** It watches [AlbumDownloads.busy] and stops itself when nothing is on its way
 * and nothing waits for the store. With no network the downloader is not busy, so a phone offline for the night does
 * not spend its six hours waiting.
 *
 * A partial wake lock is held while it runs, so a screen that turns off does not stop the transfers.
 */
class DownloadService : Service() {
    private val scope = MainScope()
    private var watching = false
    private var lock: PowerManager.WakeLock? = null
    private var postedAt = 0L

    private val downloads: AlbumDownloads get() = (application as PhoneApplication).downloads

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Into the foreground first, whatever happens next: a service started with startForegroundService that does not
        // call this in time ends the app.
        val first = notification(downloads.rows.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, first)
        }
        if (lock == null) {
            lock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rpm:downloads")
                .apply { acquire(LOCK_MS) }
        }
        if (!watching) {
            watching = true
            scope.launch {
                combine(downloads.rows, downloads.busy) { rows, busy -> rows to busy }.collect { (rows, busy) ->
                    if (!busy) {
                        stop()
                        return@collect
                    }
                    // The rows change with every two percent of every track; the shade is told at most twice a second.
                    val now = SystemClock.elapsedRealtime()
                    if (now - postedAt >= POST_EVERY_MS) {
                        postedAt = now
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(rows))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15's six hours of `dataSync` in a day are spent. The service must stop now. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Diag.w(AlbumDownloads.TAG, "the system's time for downloads in the background is spent; they go on when the app comes forward")
        stop()
    }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        lock?.takeIf { it.isHeld }?.release()
        lock = null
        scope.cancel()
        super.onDestroy()
    }

    /** The album coming in first, how far it has got, and a bar for it. A tap opens the app. */
    private fun notification(rows: List<AlbumDownloads.Row>): Notification {
        channel()
        val coming = rows.filter { it.standing is AlbumDownloads.Standing.Downloading }
        val first = coming.lastOrNull() // The rows are newest first; the downloader works oldest first.
        val standing = first?.standing as? AlbumDownloads.Standing.Downloading
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(first?.album?.album ?: getString(R.string.Rpm_Downloads_Title))
            .setContentText(
                when {
                    standing == null -> null
                    standing.preparing -> getString(R.string.Rpm_Download_Preparing, standing.have.toString(), standing.of.toString())
                    else -> getString(R.string.Rpm_Download_Progress, standing.have.toString(), standing.of.toString())
                },
            )
            .setProgress(100, ((standing?.fraction ?: 0.0) * 100).toInt(), standing?.preparing ?: true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun channel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.Rpm_Downloads_Title), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 7301
        private const val POST_EVERY_MS = 500L

        /** The longest the wake lock is held without being let go, in case the service is never destroyed. */
        private const val LOCK_MS = 6 * 60 * 60 * 1000L

        /** Starts the service, if the system allows a foreground service to start now. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException, from the background on Android 12 and later.
                Diag.w(AlbumDownloads.TAG, "downloads wait for the app to come forward: $e")
            }
        }
    }
}
