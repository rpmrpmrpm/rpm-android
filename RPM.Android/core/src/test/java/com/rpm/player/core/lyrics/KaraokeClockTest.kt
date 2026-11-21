package com.rpm.player.core.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KaraokeClockTest {

    @Test
    fun followsAnEvenPosition() {
        val clock = KaraokeClock()
        assertEquals(10.0, clock.seconds(10_000, true, 1_000), 1e-9)
        assertEquals(10.5, clock.seconds(10_500, true, 1_500), 1e-9)
        assertEquals(12.0, clock.seconds(12_000, true, 3_000), 1e-9)
    }

    @Test
    fun aStepBackIsTakenUpSlowlyAndNeverGoesBack() {
        val clock = KaraokeClock()
        clock.seconds(10_000, true, 0)
        var last = 10.0
        // From here the player reports 200 ms less than the wall clock carries it to.
        for (frame in 1..300) {
            val up = frame * 16L
            val shown = clock.seconds(10_000 + up - 200, true, up)
            assertTrue(shown >= last)
            last = shown
        }
        // After almost five seconds the step is taken up.
        assertEquals((10_000 + 300 * 16 - 200) / 1000.0, last, 0.005)
    }

    @Test
    fun aStepForwardIsTakenUpSlowly() {
        val clock = KaraokeClock()
        clock.seconds(10_000, true, 0)
        // 200 ms ahead after 100 ms: the clock moves at most a twentieth faster.
        assertEquals(10.105, clock.seconds(10_300, true, 100), 1e-9)
    }

    @Test
    fun aSeekAndAPauseGoStraightThere() {
        val clock = KaraokeClock()
        clock.seconds(10_000, true, 0)
        assertEquals(60.0, clock.seconds(60_000, true, 100), 1e-9)
        assertEquals(30.0, clock.seconds(30_000, false, 200), 1e-9)
    }
}
