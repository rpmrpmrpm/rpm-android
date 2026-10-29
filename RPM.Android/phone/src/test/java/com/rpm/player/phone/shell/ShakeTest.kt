package com.rpm.player.phone.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeTest {
    private val hard = 9.81f * 3

    @Test
    fun three_peaks_within_a_second_is_a_shake() {
        val counter = ShakeCounter()

        assertFalse(counter.feed(hard, 0f, 0f, 0))
        assertFalse(counter.feed(hard, 0f, 0f, 300))
        assertTrue(counter.feed(hard, 0f, 0f, 600))
    }

    @Test
    fun peaks_spread_over_more_than_a_second_are_walking() {
        val counter = ShakeCounter()

        assertFalse(counter.feed(hard, 0f, 0f, 0))
        assertFalse(counter.feed(hard, 0f, 0f, 700))
        assertFalse(counter.feed(hard, 0f, 0f, 1_400))
    }

    @Test
    fun gravity_alone_is_not_a_peak() {
        val counter = ShakeCounter()

        repeat(10) { assertFalse(counter.feed(0f, 9.81f, 0f, it * 50L)) }
    }

    @Test
    fun a_shake_right_after_a_shake_does_not_count() {
        val counter = ShakeCounter()
        listOf(0L, 200L, 400L).forEach { counter.feed(hard, 0f, 0f, it) }

        listOf(600L, 800L, 1_000L).forEach { assertFalse(counter.feed(hard, 0f, 0f, it)) }
    }
}
