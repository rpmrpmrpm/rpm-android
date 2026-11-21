package com.rpm.player.phone.home

import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.CoverIndex
import com.rpm.player.core.library.QuickPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickRefTest {
    private val a1 = CloudTrack("a1", title = "One")
    private val a2 = CloudTrack("a2", title = "Two")
    private val b1 = CloudTrack("b1", title = "Three")
    private val first = CloudAlbum("first", "First", "Act", listOf(a1, a2))
    private val second = CloudAlbum("second", "Second", "Act", listOf(b1))
    private val collection = Collection.of(
        Shelf.Own,
        listOf(first, second),
        listOf(a1, a2, b1).associateBy { it.rpmId },
        emptyMap(),
        emptyList(),
        CoverIndex.Unknown,
    )

    private fun names(picks: List<QuickPick>) = picks.map {
        when (it) {
            is QuickPick.Radio -> "radio:" + it.track.rpmId
            is QuickPick.Song -> "song:" + it.track.rpmId
            is QuickPick.OfAlbum -> "album:" + it.album.key
            is QuickPick.OfList -> "list:" + it.list.id
            is QuickPick.OfStation -> "station"
        }
    }

    /** What the person added comes after the suggested mixtapes and before the rest, newest first. */
    @Test fun addedPicksStandAfterTheMixtapes() {
        val suggested = listOf(QuickPick.Radio(a1, first), QuickPick.Song(a2, first), QuickPick.OfAlbum(first))
        val merged = QuickRef.merged(suggested, listOf("album:second", "song:b1"), collection, most = 16)
        assertEquals(listOf("radio:a1", "album:second", "song:b1", "song:a2", "album:first"), names(merged))
    }

    /** A suggestion the person also added is shown once, where they put it. */
    @Test fun aSuggestionTheyAddedIsShownOnce() {
        val suggested = listOf(QuickPick.Song(a2, first), QuickPick.OfAlbum(first))
        val merged = QuickRef.merged(suggested, listOf("album:first"), collection, most = 16)
        assertEquals(listOf("album:first", "song:a2"), names(merged))
    }

    /** Something the collection no longer holds is left out, and the line count stays at the most asked for. */
    @Test fun missingThingsAreSkippedAndTheCountHolds() {
        val suggested = listOf(QuickPick.Song(a1, first), QuickPick.Song(a2, first), QuickPick.Song(b1, second))
        val merged = QuickRef.merged(suggested, listOf("song:gone", "album:second"), collection, most = 3)
        assertEquals(listOf("album:second", "song:a1", "song:a2"), names(merged))
    }

    @Test fun refsReadBack() {
        assertEquals(QuickPick.Song(a1, first), QuickRef.pick(QuickRef.of(a1), collection))
        assertEquals(QuickPick.OfAlbum(second), QuickRef.pick(QuickRef.of(second), collection))
        assertNull(QuickRef.pick("list:none", collection))
        assertNull(QuickRef.pick("something else", collection))
    }
}
