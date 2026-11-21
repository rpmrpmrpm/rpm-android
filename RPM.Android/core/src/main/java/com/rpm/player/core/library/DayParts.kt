package com.rpm.player.core.library

/** The four parts of a day that listening is counted in. The desktop's `DayPart`. */
enum class DayPart { Night, Morning, Afternoon, Evening }

/**
 * Which part of the day an hour on the listener's own clock is in. The desktop's `DayParts`, with the same edges:
 * night from 23:00 to 04:59, morning to 11:59, afternoon to 17:59, evening to 22:59. Night uses the same hours as the
 * desktop's "Late night" list (`SmartPlaylists.NightFromHour` and `NightUntilHour`).
 */
object DayParts {
    const val NIGHT_FROM = 23
    const val NIGHT_UNTIL = 5
    const val MORNING_UNTIL = 12
    const val AFTERNOON_UNTIL = 18

    /** The four parts in the order a day goes through them, starting from the morning. */
    val acrossTheDay = listOf(DayPart.Morning, DayPart.Afternoon, DayPart.Evening, DayPart.Night)

    /** The part [localHour] (0 to 23) is in. Any other hour is refused, as on the desktop. */
    fun of(localHour: Int): DayPart {
        require(localHour in 0..23) { "Not an hour of the day: $localHour" }
        // Night is checked first, with two comparisons: it is the one part that runs past midnight.
        return when {
            localHour >= NIGHT_FROM || localHour < NIGHT_UNTIL -> DayPart.Night
            localHour < MORNING_UNTIL -> DayPart.Morning
            localHour < AFTERNOON_UNTIL -> DayPart.Afternoon
            else -> DayPart.Evening
        }
    }
}
