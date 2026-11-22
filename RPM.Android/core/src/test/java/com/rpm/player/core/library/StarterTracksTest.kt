package com.rpm.player.core.library

import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.CloudTrackTags
import com.rpm.player.core.cloud.Shelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The starter tracks the phone shows in place of an empty collection, and how the cloud is kept away from them. */
class StarterTracksTest {
    private val starters = StarterTracks.tracks
    private val mine = listOf(
        CloudTrack(rpmId = "8114221e-17e5-493e-872f-96946345e7ef", title = "Song", artist = "Act", album = "Record", tags = CloudTrackTags(durationSeconds = 200.0)),
    )

    // ── when they are shown ──

    @Test fun anEmptyOwnCollectionShowsTheStarterTracks() {
        assertSame(starters, StarterTracks.shown(Shelf.Own, emptyList(), starters))
    }

    @Test fun oneSongOfTheirOwnTakesThemAway() {
        assertSame(mine, StarterTracks.shown(Shelf.Own, mine, starters))
    }

    /** Another account's collection is theirs to fill: an empty one stays empty. */
    @Test fun aBorrowedShelfNeverShowsThem() {
        assertTrue(StarterTracks.shown(Shelf.Borrowed("owner"), emptyList(), starters).isEmpty())
    }

    /** The TV hands the library no starter tracks, so an empty collection stays empty there. */
    @Test fun anAppThatShipsNoneShowsNone() {
        assertTrue(StarterTracks.shown(Shelf.Own, emptyList(), emptyList()).isEmpty())
    }

    @Test fun thePreviewShowsThemOverMusic() {
        assertSame(starters, StarterTracks.shown(Shelf.Own, mine, starters, force = true))
        assertSame(mine, StarterTracks.shown(Shelf.Borrowed("owner"), mine, starters, force = true))
    }

    // ── what they are ──

    @Test fun threeRecordsOfOneTrackEach() {
        val albums = AlbumWall.group(starters)
        assertEquals(3, albums.size)
        for (album in albums) {
            assertEquals(1, album.tracks.size)
            assertTrue(StarterTracks.isStarter(album))
            assertTrue(album.key, StarterTracks.isStarterKey(album.key))
        }
        assertEquals(setOf("Connect - Single", "Gymnopedie no. 1", "Moonlight Sonata"), albums.map { it.album }.toSet())
    }

    /** The welcome needs a song of at least 90 seconds, and the player shows the record's names. */
    @Test fun everyTrackIsNamedAndLongEnoughForTheWelcome() {
        for (track in starters) {
            assertFalse(track.title.isNullOrBlank())
            assertFalse(track.artist.isNullOrBlank())
            assertTrue(track.rpmId, (track.tags?.durationSeconds ?: 0.0) >= 90.0)
        }
        assertEquals(starters.map { it.rpmId }.toSet(), StarterTracks.welcomeOrder.toSet())
    }

    @Test fun eachTrackPlaysFromItsOwnAsset() {
        val assets = starters.map { StarterTracks.assetOf(it.rpmId) }
        assertEquals(listOf("starter/connect.ogg", "starter/gymnopedie.ogg", "starter/moonlight.ogg"), assets)
        assertNull(StarterTracks.assetOf(mine.single().rpmId))
    }

    @Test fun aCloudTrackOrRecordIsNotAStarter() {
        assertFalse(StarterTracks.isStarter(mine.single().rpmId))
        val albums = AlbumWall.group(mine + starters)
        val own = albums.single { it.album == "Record" }
        assertFalse(StarterTracks.isStarter(own))
        assertFalse(StarterTracks.isStarterKey(own.key))
        assertFalse(StarterTracks.isStarter(CloudAlbum("k", "Mixed", "Act", mine + starters.first())))
    }

    // ── pictures ──

    /** The covers come from the app's assets; the cloud's index is not asked about them. */
    @Test fun theCollectionGivesEachStarterRecordItsShippedCover() {
        val albums = AlbumWall.group(mine + starters)
        val collection = Collection.of(Shelf.Own, albums, (mine + starters).associateBy { it.rpmId }, emptyMap(), emptyList(), CoverIndex.Unknown)
        for (album in albums.filter(StarterTracks::isStarter)) {
            val cover = collection.cover(album)
            assertNotNull(album.album, cover)
            val asset = StarterTracks.pictureAsset(cover!!.keys.single())
            assertTrue("$asset", asset != null && asset.startsWith("starter/") && asset.endsWith(".jpg"))
        }
        // A record of the person's own is asked of the cloud as before.
        val own = collection.cover(albums.single { it.album == "Record" })!!
        assertTrue(own.keys.none { StarterTracks.pictureAsset(it) != null })
    }

    @Test fun onlyTheMoonlightSonataHasADisc() {
        val albums = AlbumWall.group(starters)
        val collection = Collection.of(Shelf.Own, albums, starters.associateBy { it.rpmId }, emptyMap(), emptyList(), CoverIndex.Known(emptyMap()))
        val discs = albums.associate { it.album to collection.disc(it) }
        assertNull(discs["Connect - Single"])
        assertNull(discs["Gymnopedie no. 1"])
        assertEquals("starter/moonlight-disc.webp", StarterTracks.pictureAsset(discs["Moonlight Sonata"]!!.keys.single()))
    }

    @Test fun theCollectionOfStarterTracksIsTheStarterShelf() {
        val starterShelf = Collection.of(Shelf.Own, AlbumWall.group(starters), starters.associateBy { it.rpmId }, emptyMap(), emptyList(), CoverIndex.Unknown)
        assertTrue(StarterTracks.isStarterShelf(starterShelf))
        val music = Collection.of(Shelf.Own, AlbumWall.group(mine), mine.associateBy { it.rpmId }, emptyMap(), emptyList(), CoverIndex.Unknown)
        assertFalse(StarterTracks.isStarterShelf(music))
        val empty = Collection.of(Shelf.Own, emptyList(), emptyMap(), emptyMap(), emptyList(), CoverIndex.Unknown)
        assertFalse(StarterTracks.isStarterShelf(empty))
    }

    @Test fun aCloudPictureKeyIsNotAnAsset() {
        assertNull(StarterTracks.pictureAsset(null))
        assertNull(StarterTracks.pictureAsset("3f1c0b2a9e"))
        assertNull(StarterTracks.pictureAsset(StarterTracks.PREFIX + "cover-unknown"))
        assertNull(StarterTracks.pictureAsset(StarterTracks.PREFIX + "disc-connect"))
    }
}
