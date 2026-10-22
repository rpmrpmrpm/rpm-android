package com.rpm.player.core.library

import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.CloudTrackTags
import com.rpm.player.core.papers.TrackPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenrePopularTest {

    private fun album(key: String, artist: String, songs: Int) =
        CloudAlbum(key, key, artist, (1..songs).map { CloudTrack(rpmId = "$key-$it", title = "$key song $it", tags = CloudTrackTags(trackNumber = it)) })

    private fun plays(vararg albums: CloudAlbum) =
        albums.associate { a -> a.key to a.tracks.associate { t -> TrackPlace(1, t.tags!!.trackNumber) to (1000L - t.tags.trackNumber) } }

    @Test
    fun noActHasMoreThanItsShare() {
        val big = album("big", "Big Act", 10)
        val other = album("other", "Other Act", 10)
        val ranked = GenrePopular.ranked(32, GenrePopular.songs(listOf(big, other)), plays(big, other))
        assertEquals(GenrePopular.PER_ACT, ranked.count { it.second.artist == "Big Act" })
        assertEquals(GenrePopular.PER_ACT, ranked.count { it.second.artist == "Other Act" })
    }

    @Test
    fun theRecordsPlayedMostHereAreAskedFirst() {
        val a = album("a", "A", 1)
        val b = album("b", "B", 1)
        val c = album("c", "C", 1)
        val played = mapOf("a" to 1, "b" to 9, "c" to 5)
        assertEquals(listOf("b", "c", "a"), GenrePopular.records(listOf(a, b, c)) { played.getValue(it.key) }.map { it.key })
    }

    @Test
    fun tooFewIsNone() {
        val one = album("one", "Solo", 10)
        assertTrue(GenrePopular.ranked(32, GenrePopular.songs(listOf(one)), plays(one)).isEmpty())
    }
}
