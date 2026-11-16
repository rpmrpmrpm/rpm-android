package com.rpm.player.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What [ProfileBadges] unlocks. It is the iPhone's `ProfileBadgesTests`, on the same cases. */
class ProfileBadgesTest {

    @Test fun anEmptyCollectionUnlocksNothing() {
        assertEquals(emptyList<String>(), ProfileBadges.unlocked(0, 0, emptyMap()))
    }

    @Test fun theSizeBadgesFollowTheAlbumCount() {
        val held = ProfileBadges.unlocked(1_610, 23_329, emptyMap())
        assertEquals(listOf("first-spin", "crate-digger", "record-collector", "music-connoisseur", "ten-thousand"), held)
    }

    @Test fun aGenreBadgeNeedsAFifthAndTwentyFive() {
        // 30 of 100 is a fifth and 25: unlocked. 30 of 200 is not a fifth.
        assertTrue("jazz" in ProfileBadges.unlocked(100, 1_000, mapOf("Jazz" to 30)))
        assertFalse("jazz" in ProfileBadges.unlocked(200, 2_000, mapOf("Jazz" to 30)))
        // 20 of 50 is a fifth but not 25.
        assertFalse("jazz" in ProfileBadges.unlocked(50, 500, mapOf("Jazz" to 20)))
    }

    @Test fun aGenreBadgeCountsItsWholeFamily() {
        val held = ProfileBadges.unlocked(100, 1_000, mapOf("Indie" to 15, "Punk" to 10, "Rock" to 5))
        assertTrue("rock" in held)
    }

    @Test fun theRangeBadgesCountGenresWithFiveAlbums() {
        val ten = (1..10).associate { "Genre $it" to 5 }
        assertTrue("genre-hopper" in ProfileBadges.unlocked(50, 500, ten))
        assertFalse("omnivore" in ProfileBadges.unlocked(50, 500, ten))
        // The shelf for music that cannot be filed is not a genre.
        val nine = (1..9).associate { "Genre $it" to 5 } + ("Other / Unknown" to 50)
        assertFalse("genre-hopper" in ProfileBadges.unlocked(95, 950, nine))
    }

    @Test fun everyBadgeHasAUniqueId() {
        assertEquals(ProfileBadges.all.size, ProfileBadges.all.map { it.id }.toSet().size)
        assertTrue(ProfileBadges.all.all { Regex("^[a-z0-9-]{1,40}$").matches(it.id) })
    }
}
