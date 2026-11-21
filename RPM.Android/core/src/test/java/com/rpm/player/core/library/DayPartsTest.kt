package com.rpm.player.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The desktop's `ListeningProfileTests`, where the day is cut. */
class DayPartsTest {

    @Test fun theHourLandsInThePartOfTheDayItBelongsTo() {
        val want = mapOf(
            23 to DayPart.Night, 0 to DayPart.Night, 4 to DayPart.Night,
            5 to DayPart.Morning, 11 to DayPart.Morning,
            12 to DayPart.Afternoon, 17 to DayPart.Afternoon,
            18 to DayPart.Evening, 22 to DayPart.Evening,
        )
        for ((hour, part) in want) assertEquals("hour $hour", part, DayParts.of(hour))
    }

    @Test fun anHourThatIsNotAnHourIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { DayParts.of(24) }
        assertThrows(IllegalArgumentException::class.java) { DayParts.of(-1) }
    }

    @Test fun theFourPartsCoverTheDayBetweenThem() {
        val seen = (0 until 24).map(DayParts::of)
        assertEquals(DayParts.acrossTheDay.toSet(), seen.toSet())
        assertEquals(4, seen.toSet().size)
    }
}
