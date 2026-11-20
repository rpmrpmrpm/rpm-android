package com.rpm.player.core.playback

/**
 * Whether something that was played counts as having been listened to. It is a port of
 * `RPM.Core/Playback/Listen.cs`, as the iOS app's `Listen` is, and it must stay the same, because
 * what this TV counts is added to what the desktop and the iOS app count. A play counts after half
 * the track or four minutes, whichever comes first. A track under thirty seconds never counts.
 */
object Listen {
    const val SHORTEST_SECONDS = 30.0

    /** Less than this heard is not written in the listening log at all: a flick past. */
    const val NOTICED_SECONDS = 5.0
    const val ENOUGH_SECONDS = 4 * 60.0

    /** Whether [heard] seconds of a track [length] seconds long is a play. A length of zero (not known)
     * never counts: guessing would count a quick skip past a track whose duration nobody knows. */
    /** Whether a listen is written in the listening log, as a play or a skip. */
    fun worthRecording(heard: Double, length: Double): Boolean =
        length >= SHORTEST_SECONDS && heard >= NOTICED_SECONDS

    fun counts(heard: Double, length: Double): Boolean {
        if (length < SHORTEST_SECONDS || heard <= 0) return false
        return heard >= minOf(length / 2, ENOUGH_SECONDS)
    }
}

/**
 * Turns readings of the player into plays. It is the TV's version of the iOS app's `PlayCounter` and
 * `ListenTally`. It is read once a second, on one thread, with the queued track playing now (or null
 * for a station or nothing), whether it is playing, where it is and how long it is.
 *
 * Heard time is how far the position moved while playing between two readings, so a seek adds nothing
 * and a stall adds nothing. A move back to the start of the same track (played again, or repeated)
 * ends the listen and starts another, as `ListenTally` treats a restart. A different track ends it
 * too. An ended listen worth writing down is handed out ([Heard]), and [Listen.counts] says whether it was a play.
 */
class TrackCounter(
    /** The clock the listen's hour and weekday are read on: this device's own, except in a test. */
    private val zone: () -> java.time.ZoneId = { java.time.ZoneId.systemDefault() },
    /** Now, in Unix seconds. Last, so a call can pass it as a trailing lambda. */
    private val clockSeconds: () -> Long,
) {

    /** A play that counted: the track and when the listen ended, in Unix seconds. */
    data class Counted(val ref: TrackRef, val at: Long)

    /** A listen worth writing in the listening log: a play or a skip. [at] is when it ended, in Unix seconds. The
     * hour (0 to 23) and the weekday (0 = Sunday) are on this device's clock at that moment. */
    data class Heard(
        val ref: TrackRef,
        val at: Long,
        val heardSeconds: Double,
        val lengthSeconds: Double,
        val counted: Boolean,
        val localHour: Int,
        val localDayOfWeek: Int,
    ) {
        /** The play it was, or null for a skip. */
        val play: Counted? get() = if (counted) Counted(ref, at) else null
    }

    private var ref: TrackRef? = null
    private var heard = 0.0
    private var length = 0.0
    private var lastPosition: Double? = null

    /** Takes one reading and returns the play it ended, if it ended one that counted. See [readListen]. */
    fun read(now: TrackRef?, playing: Boolean, position: Double, duration: Double): Counted? =
        readListen(now, playing, position, duration)?.play

    /** Ends the listen in progress and returns its play, if it counted. See [settleListen]. */
    fun settle(): Counted? = settleListen()?.play

    /**
     * Takes one reading. [position] and [duration] are in seconds; [duration] zero when not known.
     * Returns the listen the reading ended, if it ended one worth writing down.
     */
    fun readListen(now: TrackRef?, playing: Boolean, position: Double, duration: Double): Heard? {
        if (now != ref) {
            val ended = settleListen()
            ref = now
            heard = 0.0
            length = duration
            lastPosition = if (now != null) position else null
            return ended
        }
        if (now == null) return null
        if (duration > 0) length = duration
        val last = lastPosition
        lastPosition = position
        if (last == null) return null
        val moved = position - last
        // Back to the start of the same track: a new listen, and the old one is judged first.
        if (position < RESTART_WITHIN && last > RESTART_WITHIN && moved < 0) {
            val ended = settleListen()
            ref = now
            heard = 0.0
            return ended
        }
        // Playing on: the position moved by about the time between readings. A bigger jump is a seek,
        // and a backward move is a seek back. Neither is heard.
        if (playing && moved > 0 && moved <= LONGEST_STEP) heard += moved
        return null
    }

    /** Ends the listen in progress, as the service ends. Returns it when it is worth writing down: long enough to
     * judge and heard for a few seconds ([Listen.worthRecording]), as a play or a skip. */
    fun settleListen(): Heard? {
        val current = ref ?: return null
        val heardNow = heard
        heard = 0.0
        if (!Listen.worthRecording(heardNow, length)) return null
        val at = clockSeconds()
        val local = java.time.Instant.ofEpochSecond(at).atZone(zone())
        return Heard(
            ref = current,
            at = at,
            heardSeconds = heardNow,
            lengthSeconds = length,
            counted = Listen.counts(heardNow, length),
            localHour = local.hour,
            // java.time counts Monday as 1 and Sunday as 7. The log counts Sunday as 0, as the desktop does.
            localDayOfWeek = local.dayOfWeek.value % 7,
        )
    }

    companion object {
        /** A position this close to the beginning, coming back from further on, is the track played again.
         * The iOS app's `ListenTally` uses half a second. Readings here are a second apart, so the first
         * reading after a restart can be up to a second in. */
        const val RESTART_WITHIN = 1.5

        /** Readings are a second apart. A move longer than this is a seek, not listening. */
        const val LONGEST_STEP = 5.0
    }
}
