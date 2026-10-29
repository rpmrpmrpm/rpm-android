package com.rpm.player.core.motion

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.rpm.player.core.diag.Diag
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How much moves on screen, from most to least. Each level keeps everything that the level above
 * it stopped.
 *
 * The judge never steps down from [Full] ([MotionLadder.FLOOR] is [Full]): the rooms and the drifting
 * cards move at every level, as on the desktop, and the screens are drawn at the screen's own
 * pixels. [TwoThirds] and [Still] exist only for measuring. A debuggable build's launch switch can
 * hold them.
 */
enum class Motion {
    /** The room lit by the music at the display's rate; the banner's front card and the tuner's player drift. */
    Full,

    /** The screens are drawn at two thirds of the screen's pixels and scaled up (the TV's `DrawnAt`).
     * Covers, logos, text and edges look soft. Only the launch switch reaches this level, because
     * the judge never steps to it. */
    TwoThirds,

    /** The room is drawn once in its colours and held still, and no card drifts; the player's
     * spectrum still moves. The judge never steps to it ([MotionLadder.FLOOR]). Only the debug pin
     * reaches it, for measuring. */
    Still,
}

/** The level the screens draw at. Provided by `RpmApp`; reading it outside that is an error, so there is no default. */
val LocalMotion = compositionLocalOf<Motion> { error("No Motion provided") }


/**
 * Decides from the window's frame timings when to step down a level. Plain arithmetic, fed one frame
 * at a time on one thread. A frame counts as slow only when it took more than one and a half display
 * frames AND its GPU part (issuing the draw commands and swapping the buffer) alone took more than one.
 * That is the slowness a lower level can remove, because the room and the drifting cards compete for
 * the GPU. A frame that is slow on the UI thread, or while a new cover is uploaded, is not counted:
 * such frames appear while a large collection loads its covers, and they would step the level down
 * for no reason. Once armed, when at least [STEP_SHARE] of the last [WINDOW] frames were slow, it
 * decides one more step, empties its window and waits [SETTLE_MS] before judging again, so the next
 * decision is about the new level. Before [arm] it judges nothing. The ladder feeds it only frames
 * that answer a key press.
 */
class MotionJudge {
    private val slow = BooleanArray(WINDOW)
    private var filled = 0
    private var next = 0
    private var slowCount = 0
    private var armedAtMs = Long.MAX_VALUE

    /** How many steps it has decided since it was created. */
    var steps = 0
        private set

    /** How many of the window's frames were slow when it last decided a step. */
    var slowAtLastStep = 0
        private set

    /** Judges frames from [atMs] on; frames before then are ignored. */
    fun arm(atMs: Long) {
        armedAtMs = atMs
        clear()
    }

    /** Takes one frame of [totalNanos], [gpuNanos] of it issuing commands and swapping, at a display
     * interval of [intervalNanos]. Returns true when it decided a step. */
    fun frame(totalNanos: Long, gpuNanos: Long, intervalNanos: Long, nowMs: Long): Boolean {
        if (nowMs < armedAtMs) return false
        val isSlow = totalNanos * 2 > intervalNanos * 3 && gpuNanos > intervalNanos
        if (filled == WINDOW && slow[next]) slowCount--
        slow[next] = isSlow
        if (isSlow) slowCount++
        next = (next + 1) % WINDOW
        if (filled < WINDOW) filled++
        if (filled < WINDOW || slowCount < WINDOW * STEP_SHARE) return false
        steps++
        slowAtLastStep = slowCount
        arm(nowMs + SETTLE_MS)
        return true
    }

    private fun clear() {
        slow.fill(false)
        filled = 0
        next = 0
        slowCount = 0
    }

    companion object {
        const val WINDOW = 120
        const val STEP_SHARE = 0.3f
        const val SETTLE_MS = 3_000L
        const val FIRST_ARM_MS = 10_000L

        /** A frame is judged when its display frame was due within this long after a key went down. */
        const val ANSWER_NANOS = 1_000_000_000L
    }
}

/**
 * The device's motion level for the life of the process: where it starts, and the steps down that
 * the judge decides while the window draws. The level is saved with the app's version and the
 * judging rule, so the next launch starts where this one settled. A new version or rule starts again
 * at [Motion.Full]. There is no guess from the hardware: every box starts with everything moving
 * and is lowered only by what its own frames measure.
 *
 * [level] is written and read on the main thread. Frame timings are judged on the `rpm.motion`
 * thread. Only frames that answer a key press are judged: frames due within a second of a key going
 * down ([keyDown]). Loading with nobody pressing a key makes slow frames that a person does not
 * feel, and they would step the level down.
 */
class MotionLadder(private val context: Context) {
    private val prefs = context.getSharedPreferences("rpm.motion", Context.MODE_PRIVATE)
    private val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    private val thread = HandlerThread("rpm.motion").apply { start() }
    private val judging = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val judge = MotionJudge()
    private var resumedBefore = false
    private var pinned = false
    private var start: Motion = saved() ?: Motion.Full
    /** One display frame; written on the main thread in [attach], read on the judging thread. */
    @Volatile private var intervalNanos = 16_666_667L
    /** When a key last went down, on the `System.nanoTime` clock, which frame timings also use. */
    @Volatile private var lastKeyNanos = Long.MIN_VALUE / 2

    var level by mutableStateOf(start)
        private set

    init {
        Diag.i(TAG, "start at $start (saved ${saved()}, ${Build.MODEL})")
    }

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
        val sinceKey = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) - lastKeyNanos
        if (sinceKey !in 0..MotionJudge.ANSWER_NANOS) return@OnFrameMetricsAvailableListener
        val gpu = metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) + metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
        if (judge.frame(metrics.getMetric(FrameMetrics.TOTAL_DURATION), gpu, intervalNanos, android.os.SystemClock.uptimeMillis())) {
            val steps = judge.steps
            val slow = judge.slowAtLastStep
            main.post { apply(steps, slow) }
        }
    }

    /** A key went down; the frames of the next second are judged. Call from `dispatchKeyEvent`. */
    fun keyDown() {
        lastKeyNanos = System.nanoTime()
    }

    /** Holds the level at [motion] for this run and stops judging. Used by a debuggable build's launch switch. */
    fun pin(motion: Motion) {
        pinned = true
        start = motion
        level = motion
        Diag.i(TAG, "pinned at $motion")
    }

    /** Starts judging [window]'s frames. Call from `onResume`. */
    fun attach(window: Window) {
        if (pinned || level >= FLOOR) return
        val rate = window.decorView.display?.refreshRate ?: 60f
        intervalNanos = (1e9 / rate.coerceAtLeast(1f)).toLong()
        val after = if (resumedBefore) MotionJudge.SETTLE_MS else MotionJudge.FIRST_ARM_MS
        resumedBefore = true
        val armAt = android.os.SystemClock.uptimeMillis() + after
        judging.post { judge.arm(armAt) }
        window.addOnFrameMetricsAvailableListener(listener, judging)
    }

    /** Stops judging [window]'s frames. Call from `onPause`. */
    fun detach(window: Window) {
        if (pinned) return
        runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
    }

    // The level is the start plus every step decided, and never lower than it already is, so a late
    // or repeated message from the judge gives the same answer.
    private fun apply(steps: Int, slow: Int) {
        if (pinned) return
        val wanted = Motion.entries[(start.ordinal + steps).coerceAtMost(FLOOR.ordinal)]
        if (wanted.ordinal <= level.ordinal) return
        level = wanted
        prefs.edit().putString(KEY_LEVEL, wanted.name).putLong(KEY_VERSION, version).putInt(KEY_RULE, RULE).apply()
        Diag.i(TAG, "stepped down to $wanted ($slow of ${MotionJudge.WINDOW} frames slow)")
    }

    private fun saved(): Motion? {
        if (prefs.getLong(KEY_VERSION, -1) != version || prefs.getInt(KEY_RULE, 0) != RULE) return null
        return prefs.getString(KEY_LEVEL, null)?.let { name -> Motion.entries.firstOrNull { it.name == name } }
    }

    companion object {
        /** The lowest level the judge steps to. It is [Motion.Full], so the judge takes no step. At two
         * thirds, covers and logos were soft and the frame rate was no better, because the cards are
         * in layers of their own. */
        val FLOOR = Motion.Full

        private const val TAG = "rpm.motion"
        private const val KEY_LEVEL = "level"
        private const val KEY_VERSION = "version"
        private const val KEY_RULE = "rule"

        /** The judging rule that a saved level was measured under. A level saved under another rule
         * is not used. Raise this number whenever the judging rule or the meaning of the levels
         * changes. */
        private const val RULE = 6
    }
}
