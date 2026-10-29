package com.rpm.player.core.playback

import com.rpm.player.core.diag.Diag
import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.cloud.CloudClient
import com.rpm.player.core.cloud.TrackPlaysEntry
import com.rpm.player.core.cloud.TrackPlaysReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends this TV's play totals to `cloud/track-plays`, as the iOS app's `PlaysSync` and the desktop's
 * `CloudSyncService.Plays` send theirs: the totals that changed since they were last sent, at most 200
 * per report, most recently played first, under this TV's device id. The desktop takes them in with
 * every other device's and builds the generated lists from them. The TV reads every device's totals
 * back when it loads the library, so it needs no pull of its own.
 *
 * Runs at launch, when the app comes back to the front, and at most five minutes after a play. One
 * pass runs at a time. A pass that fails marks nothing as sent, and the next one sends the same
 * totals again, which changes nothing.
 */
class TrackPlaysSync(
    private val cloud: CloudClient,
    private val auth: AuthClient,
    private val log: TrackPlayLog,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val passing = Mutex()

    @Volatile
    private var lastPassMs = 0L

    /** The pass that [soon] has scheduled and that has not yet run. Guarded by `this`. */
    private var scheduled: Job? = null

    /** Runs a pass now, after any pass already running. */
    fun pass() {
        scope.launch(Dispatchers.IO) { run() }
    }

    /**
     * Schedules a pass five minutes after the last one started (at once if that time has passed), unless
     * one is already scheduled. Called after each counted play. A play counted while a pass is sending
     * is in the next scheduled pass.
     */
    fun soon() {
        synchronized(this) {
            if (scheduled?.isActive == true) return
            val wait = (lastPassMs + AFTER_A_PLAY_MS - clock()).coerceAtLeast(0)
            scheduled = scope.launch(Dispatchers.IO) {
                delay(wait)
                run()
            }
        }
    }

    private suspend fun run() = passing.withLock {
        val userId = auth.session.value?.userId ?: return@withLock
        lastPassMs = clock()
        try {
            // A full report may leave more behind it, so the next one goes at once.
            while (true) {
                val (device, due) = log.forSync { deviceFor(userId) to unsent(MAX_ROWS) }
                if (device == null || due.isEmpty()) return@withLock
                cloud.putTrackPlays(TrackPlaysReport(device, due.map { TrackPlaysEntry(it.rpmId, it.plays, it.lastPlayedAt) }))
                log.forSync { noteSent(due) }
                Diag.i(TAG, "sent ${due.size} play totals")
                if (due.size < MAX_ROWS) return@withLock
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nothing more is marked as sent. The next pass sends the same totals again.
        }
    }

    companion object {
        /** The API's `TrackPlayStore.MaxRowsPerReport`. A report over it is refused whole. */
        const val MAX_ROWS = 200
        const val AFTER_A_PLAY_MS = 5 * 60_000L

        /** For `adb logcat -s rpm.plays`. */
        const val TAG = "rpm.plays"
    }
}
