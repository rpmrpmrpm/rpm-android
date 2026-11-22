package com.rpm.player.phone.coach

import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.CloudTrackTags
import com.rpm.player.core.library.Plays
import com.rpm.player.core.library.StarterTracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The song the welcome's last step plays ([sampleOf]). */
class WelcomeSampleTest {
    private fun song(id: String, seconds: Double = 200.0, favourite: Boolean = false) =
        CloudTrack(rpmId = id, title = "Song $id", artist = "Act", tags = CloudTrackTags(durationSeconds = seconds, favorite = favourite))

    private val everything: (String) -> Boolean = { true }

    /** A new account has only the starter tracks, and the welcome still has a song to spin: Connect first. */
    @Test fun theStarterShelfSpinsConnect() {
        val picked = sampleOf(StarterTracks.tracks.reversed(), emptyMap(), everything)
        assertEquals(StarterTracks.PREFIX + "connect", picked?.rpmId)
    }

    @Test fun aStarterThatCannotPlayIsPassedOver() {
        val picked = sampleOf(StarterTracks.tracks, emptyMap()) { it != StarterTracks.PREFIX + "connect" }
        assertEquals(StarterTracks.PREFIX + "gymnopedie", picked?.rpmId)
    }

    @Test fun theMostPlayedSongOfTheirOwnComesFirst() {
        val plays = mapOf("b" to Plays(5, 0), "a" to Plays(2, 0))
        assertEquals("b", sampleOf(listOf(song("a"), song("b"), song("c")), plays, everything)?.rpmId)
    }

    @Test fun withNoPlaysAMarkedSongComesFirst() {
        assertEquals("c", sampleOf(listOf(song("a"), song("b"), song("c", favourite = true)), emptyMap(), everything)?.rpmId)
    }

    /** Ninety seconds is enough to look round the player while it plays. */
    @Test fun aShortSongIsNeverTheSample() {
        assertNull(sampleOf(listOf(song("a", seconds = 60.0)), emptyMap(), everything))
    }

    @Test fun nothingPlayableIsNoSample() {
        assertNull(sampleOf(StarterTracks.tracks, emptyMap()) { false })
    }
}
