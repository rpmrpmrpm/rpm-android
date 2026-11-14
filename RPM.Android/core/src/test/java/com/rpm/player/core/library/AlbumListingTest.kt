package com.rpm.player.core.library

import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.CloudTrackTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cases of the iOS app's AlbumListingTests. */
class AlbumListingTest {

    private fun song(title: String, artist: String, album: String, number: Int = 0) =
        CloudTrack(rpmId = "$artist/$album/$title", relativePath = "x/$title.flac", title = title, artist = artist, album = album, tags = CloudTrackTags(trackNumber = number))

    private fun record(artist: String, album: String, titles: List<String> = listOf("One")) =
        CloudAlbum("$artist|$album", album, artist, titles.mapIndexed { i, title -> song(title, artist, album, number = i + 1) })

    private fun names(listing: AlbumListing): List<String> = listing.rows.map {
        when (it) {
            is AlbumListing.Row.Record -> it.album.artist + " / " + it.album.album
            is AlbumListing.Row.Song -> it.track.title.orEmpty()
        }
    }

    /** The letter first, then the name: "(hed) p.e." is filed under H, so it must not sort ahead of every A. Names
     * with no letter go last, under '#'. */
    @Test fun byArtistTheRailsLettersRunInOrderDownTheList() {
        val listing = AlbumListing.of(
            listOf(record("10cc", "Sheet Music"), record("(hed) p.e.", "Broke"), record("Björk", "Debut"), record("ABBA", "Arrival")),
            ListOrdering.Artist,
        )

        assertEquals(listOf("ABBA / Arrival", "Björk / Debut", "(hed) p.e. / Broke", "10cc / Sheet Music"), names(listing))
        assertEquals("record:ABBA|Arrival", listing.firstUnder['A'])
        assertEquals("record:(hed) p.e.|Broke", listing.firstUnder['H'])
        assertEquals("record:10cc|Sheet Music", listing.firstUnder['#'])
        assertNull(listing.firstUnder['C'])
    }

    @Test fun byArtistOneArtistsRecordsAreInTitleOrder() {
        val listing = AlbumListing.of(
            listOf(record("Talk Talk", "Spirit of Eden"), record("Talk Talk", "Laughing Stock"), record("talk talk", "Colour of Spring")),
            ListOrdering.Artist,
        )

        assertEquals(listOf("talk talk / Colour of Spring", "Talk Talk / Laughing Stock", "Talk Talk / Spirit of Eden"), names(listing))
    }

    @Test fun accentsAndCaseDoNotSeparateNames() {
        val listing = AlbumListing.of(
            listOf(record("Emilie Simon", "B"), record("Émilie Simon", "A"), record("EMILIE SIMON", "C")),
            ListOrdering.Artist,
        )

        assertEquals(listOf("Émilie Simon / A", "Emilie Simon / B", "EMILIE SIMON / C"), names(listing))
        assertEquals(listOf('E'), listing.firstUnder.keys.sorted())
    }

    @Test fun byAlbumTheRecordsNameLeads() {
        val listing = AlbumListing.of(
            listOf(record("Air", "Moon Safari"), record("Blur", "13"), record("Portishead", "Dummy")),
            ListOrdering.Album,
        )

        assertEquals(listOf("Portishead / Dummy", "Air / Moon Safari", "Blur / 13"), names(listing))
        assertEquals("record:Portishead|Dummy", listing.firstUnder['D'])
        assertEquals("record:Blur|13", listing.firstUnder['#'])
    }

    /** In track order every song is its own row, by title, with the record it is on. */
    @Test fun byTrackEverySongIsARowByTitle() {
        val listing = AlbumListing.of(
            listOf(record("Air", "Moon Safari", listOf("Sexy Boy", "All I Need")), record("Blur", "13", listOf("Tender", "Bugman"))),
            ListOrdering.Track,
        )

        assertEquals(listOf("All I Need", "Bugman", "Sexy Boy", "Tender"), names(listing))
        assertEquals("13", (listing.rows[1] as AlbumListing.Row.Song).on.album)
        assertEquals(4, listing.rows.map { it.id }.toSet().size)
        assertEquals(listOf('A', 'B', 'S', 'T'), listing.firstUnder.keys.sorted())
    }

    /** Two songs with one title: the artist, then the record, decide. */
    @Test fun byTrackTheSameTitleIsOrderedByArtistThenRecord() {
        val listing = AlbumListing.of(
            listOf(record("Nina Simone", "Live", listOf("Feeling Good")), record("Muse", "Origin", listOf("Feeling Good")), record("Nina Simone", "Baltimore", listOf("Feeling Good"))),
            ListOrdering.Track,
        )

        val on = listing.rows.mapNotNull { (it as? AlbumListing.Row.Song)?.on?.let { album -> album.artist + " / " + album.album } }
        assertEquals(listOf("Muse / Origin", "Nina Simone / Baltimore", "Nina Simone / Live"), on)
    }

    @Test fun nothingListsNothing() {
        val listing = AlbumListing.of(emptyList(), ListOrdering.Track)
        assertTrue(listing.rows.isEmpty())
        assertTrue(listing.firstUnder.isEmpty())
    }

    /** Stored by name, so an unknown or missing name gives nothing and the caller's default stands. */
    @Test fun anOrderIsStoredByItsName() {
        assertEquals(ListOrdering.Track, ListOrdering.of("Track"))
        assertNull(ListOrdering.of("Plays"))
        assertNull(ListOrdering.of(null))
    }
}
