package com.rpm.player.core.lyrics

import kotlin.math.abs

/**
 * The record's time for the karaoke words: the player's position, made steady.
 *
 * The position the player reports moves in steps. On some phones it jumps by up to a quarter of a second, forward or
 * back, every few seconds, while the sound itself plays on evenly: the audio clock under the player is read in steps,
 * and the media controller carries each reading forward until the next. The lit letters follow the time they are
 * given, so a step back would put out letters that are already lit, and a step forward would light several at once.
 *
 * This clock runs at the speed of the wall clock and moves towards the reported position slowly, never faster than
 * [SLEW] of the time that passes, so it never goes back while the record plays. A step of a quarter of a second is
 * taken up over about five seconds, and steps forward and back in turn mostly cancel. A difference larger than
 * [SNAP_MS] is a seek or a new record, and the clock goes straight there; so does a record that stands still.
 *
 * One clock serves one view of the lyrics, on one thread.
 */
class KaraokeClock {
    /** The time shown, in milliseconds of the record, at [at] on the `SystemClock.uptimeMillis` clock. */
    private var shown = Double.NaN
    private var at = 0L

    /**
     * The time to show at [uptimeMs], in seconds. [reportedMs] is the player's position at [uptimeMs], carried forward
     * from its last report; [advancing] is whether the record plays.
     */
    fun seconds(reportedMs: Long, advancing: Boolean, uptimeMs: Long): Double {
        val reported = reportedMs.toDouble()
        if (!advancing || shown.isNaN()) return set(reported, uptimeMs)
        // Asked about a moment before the last one: read off the clock, which is not moved.
        if (uptimeMs < at) return (shown - (at - uptimeMs)) / 1000
        val passed = (uptimeMs - at).toDouble()
        val carried = shown + passed
        val off = reported - carried
        if (abs(off) > SNAP_MS) return set(reported, uptimeMs)
        return set(carried + off.coerceIn(-passed * SLEW, passed * SLEW), uptimeMs)
    }

    private fun set(ms: Double, uptimeMs: Long): Double {
        shown = ms
        at = uptimeMs
        return ms / 1000
    }

    companion object {
        /** How much faster or slower than the wall clock the clock may run while it moves to the reported position. */
        const val SLEW = 0.05

        /** A difference from the reported position, in milliseconds, past which the clock goes straight there. */
        const val SNAP_MS = 600.0
    }
}
