package com.rpm.player.core.diag

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackChoicesTest {
    @Test
    fun the_areas_start_with_other_and_use_the_desktops_values() {
        assertEquals(listOf("other", "playback", "library", "downloads", "cloud", "radio", "casting", "interface"),
            FeedbackChoices.phoneAreas.map { it.value })
        assertEquals(listOf("other", "playback", "library", "radio", "interface"), FeedbackChoices.tvAreas.map { it.value })
        assertEquals("problem", FeedbackChoices.kinds.first().value)
    }

    @Test
    fun a_screenshot_is_scaled_to_its_long_edge() {
        assertEquals(1600.0 / 2400, ShotFit.scale(1080, 2400, 1600), 1e-9)
        assertEquals(1600.0 / 3840, ShotFit.scale(3840, 2160, 1600), 1e-9)
        assertEquals(1.0, ShotFit.scale(1000, 800, 1600), 1e-9)
    }
}
