package com.rpm.player.core.playback

import com.rpm.player.core.library.DayPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId

class DayPartPlaysTest {

    @get:Rule val folder = TemporaryFolder()

    private fun log() = DayPartPlays(folder.root.resolve("day-part-plays.json"), CoroutineScope(Dispatchers.Unconfined))

    /** The desktop's `What_gets_played_at_night_is_not_what_gets_played_in_the_morning`, on this device's counts. */
    @Test fun whatGetsPlayedAtNightIsNotWhatGetsPlayedInTheMorning() {
        val log = log()
        repeat(6) { log.count("me", "Jazz", DayPart.Night) }
        repeat(4) { log.count("me", "Rock", DayPart.Morning) }
        assertEquals(listOf("Jazz"), log.genresAt("me", DayPart.Night))
        assertEquals(listOf("Rock"), log.genresAt("me", DayPart.Morning))
        assertEquals(emptyList<String>(), log.genresAt("me", DayPart.Afternoon))
    }

    @Test fun mostPlayedFirstAndTheNameBreaksATie() {
        assertEquals(listOf("Rock", "Jazz", "Pop"), DayPartPlays.ranked(mapOf("Pop" to 2, "Jazz" to 2, "Rock" to 5, "Folk" to 0)))
    }

    @Test fun theCountsAreKeptAcrossARestart() {
        val log = log()
        repeat(3) { log.count("me", "Jazz", DayPart.Evening) }
        log.count("me", "Soul", DayPart.Evening)
        assertEquals(listOf("Jazz", "Soul"), log().genresAt("me", DayPart.Evening))
    }

    @Test fun anotherAccountStartsTheCountsOver() {
        val log = log()
        log.count("me", "Jazz", DayPart.Night)
        assertEquals(emptyList<String>(), log.genresAt("someone else", DayPart.Night))
        log.count("someone else", "Rock", DayPart.Night)
        assertEquals(listOf("Rock"), log.genresAt("someone else", DayPart.Night))
        assertEquals(emptyList<String>(), log.genresAt("me", DayPart.Night))
    }

    @Test fun aFileThatCannotBeReadIsNotOverwritten() {
        val file = folder.root.resolve("day-part-plays.json")
        file.writeText("not json")
        val log = log()
        log.count("me", "Jazz", DayPart.Night)
        assertEquals("not json", file.readText())
    }

    @Test fun thePlayIsCountedAtTheHourOfThisDevicesClock() {
        // 2026-01-01 23:30 UTC is half past midnight in Amsterdam.
        val at = 1767310200L
        assertEquals(23, DayPartPlays.hourOf(at, ZoneId.of("UTC")))
        assertEquals(0, DayPartPlays.hourOf(at, ZoneId.of("Europe/Amsterdam")))
    }
}
