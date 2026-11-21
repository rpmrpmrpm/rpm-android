package com.rpm.player.phone.library

import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.CoverIndex
import com.rpm.player.core.library.Plays
import com.rpm.player.core.playback.ListeningHistory
import com.rpm.player.core.playback.TrackRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayedLatelyTest {
    private val tracks = listOf("a", "b", "c", "d").map { CloudTrack(it, title = it) }
    private val album = CloudAlbum("k", "Record", "Act", tracks)

    private fun collection(shelf: Shelf, plays: Map<String, Plays>) =
        Collection.of(shelf, listOf(album), tracks.associateBy { it.rpmId }, plays, emptyList(), CoverIndex.Unknown)

    /** Newest first, each song once, songs never played left out. */
    @Test fun newestFirstFromThePlays() {
        val c = collection(Shelf.Own, mapOf("a" to Plays(3, 100), "b" to Plays(1, 300), "c" to Plays(2, 200)))
        assertEquals(listOf("b", "c", "a"), PlayedLately.of(c, emptyList()).map { it.rpmId })
    }

    /** A song heard on this phone since the library was read goes to the top, without a new read. */
    @Test fun thisPhonesListensComeOnTop() {
        val c = collection(Shelf.Own, mapOf("a" to Plays(3, 100), "b" to Plays(1, 300)))
        val listens = listOf(ListeningHistory.Listen(TrackRef(Shelf.Own, "a"), 400), ListeningHistory.Listen(TrackRef(Shelf.Own, "d"), 350))
        assertEquals(listOf("a", "d", "b"), PlayedLately.of(c, listens).map { it.rpmId })
    }

    /** Another account's collection has none of this person's plays. */
    @Test fun aBorrowedCollectionHasNoHistory() {
        val c = collection(Shelf.Borrowed("someone"), mapOf("a" to Plays(3, 100)))
        assertTrue(PlayedLately.of(c, emptyList()).isEmpty())
    }
}
