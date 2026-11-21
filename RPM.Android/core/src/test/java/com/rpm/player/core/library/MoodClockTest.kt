package com.rpm.player.core.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** The desktop's `MoodClockTests`. */
class MoodClockTest {

    @Test fun eachHourAsksForItsOwnMood() {
        val want = mapOf(
            0 to Mood.Sleep, 3 to Mood.Sleep, 4 to Mood.Sleep,
            5 to Mood.Calm, 8 to Mood.Calm, 9 to Mood.Calm,
            10 to Mood.Focus, 16 to Mood.Focus,
            17 to Mood.Energetic, 19 to Mood.Energetic,
            20 to Mood.Danceable, 21 to Mood.Danceable,
            22 to Mood.Sleep, 23 to Mood.Sleep,
        )
        for ((hour, mood) in want) assertEquals("hour $hour", mood, MoodClock.best(hour))
    }

    @Test fun nightIntoMorningIsCalm() {
        for (hour in 5..9) assertEquals(Mood.Calm, MoodClock.best(hour))
    }

    @Test fun theNightRunsOnSleep() {
        for (hour in listOf(22, 23, 0, 1, 2, 3, 4)) assertEquals(Mood.Sleep, MoodClock.best(hour))
    }

    @Test fun everyBandNamesEveryMood() {
        // A band that named only one mood would give nothing on a collection that cannot fill that list.
        val moods = setOf(Mood.Sleep, Mood.Calm, Mood.Focus, Mood.Energetic, Mood.Danceable)
        for (hour in 0 until 24) {
            val order = MoodClock.order(hour)
            assertEquals(moods.size, order.size)
            assertEquals(moods, order.toSet())
        }
    }

    @Test fun anHourOffTheClockIsWrappedRatherThanRefused() {
        assertEquals(MoodClock.best(1), MoodClock.best(25))
        assertEquals(MoodClock.best(23), MoodClock.best(-1))
        assertEquals(MoodClock.best(0), MoodClock.best(24))
    }
}
