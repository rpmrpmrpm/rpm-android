package com.rpm.player.core.library

import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.cloud.CloudMoodList
import com.rpm.player.core.cloud.CloudMoodLists
import com.rpm.player.core.cloud.CloudPlaylist
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.TrackPlaysRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HomeShelfTest {

    private fun track(id: String, album: String, added: Long = 0) =
        CloudTrack(rpmId = id, title = id, artist = "Artist $album", album = album, relativePath = "Artist $album/$album/$id.flac", addedAt = added)

    private fun collection(tracks: List<CloudTrack>, plays: List<TrackPlaysRow> = emptyList(), lists: List<TrackList> = emptyList()) =
        Collection.of(Shelf.Own, AlbumWall.group(tracks), tracks.associateBy { it.rpmId }, Lists.plays(plays), lists, CoverIndex.Unknown)

    @Test fun playsFromEveryDeviceAreAddedAndTheLatestPlayWins() {
        val plays = Lists.plays(
            listOf(
                TrackPlaysRow("a", deviceId = "pc", plays = 3, lastPlayedAt = 100),
                TrackPlaysRow("a", deviceId = "phone", plays = 2, lastPlayedAt = 300),
            ),
        )
        assertEquals(Plays(5, 300), plays["a"])
    }

    @Test fun rowsAreOrderedByTheirOwnFact() {
        val tracks = listOf(track("a1", "A", added = 10), track("b1", "B", added = 30), track("c1", "C", added = 20))
        val home = HomeShelf.build(
            collection(
                tracks,
                plays = listOf(
                    TrackPlaysRow("a1", plays = 9, lastPlayedAt = 50),
                    TrackPlaysRow("c1", plays = 1, lastPlayedAt = 90),
                ),
            ),
            Random(1),
        )
        assertEquals(listOf("B", "C", "A"), home.added.map { it.album })
        assertEquals(listOf("A", "C"), home.played.map { it.album })
        assertEquals(listOf("C", "A"), home.listened.map { it.album })
    }

    @Test fun quickSelectionPutsWhatWasPlayedInTheLastThirtyDaysFirst() {
        val day = 24L * 60 * 60
        val now = 100 * day
        val tracks = listOf(track("old", "Old"), track("new", "New"), track("newer", "Newer"))
        val home = HomeShelf.build(
            collection(
                tracks,
                plays = listOf(
                    // Played most of all, but not for two months.
                    TrackPlaysRow("old", plays = 500, lastPlayedAt = now - 60 * day),
                    TrackPlaysRow("new", plays = 3, lastPlayedAt = now - 2 * day),
                    TrackPlaysRow("newer", plays = 7, lastPlayedAt = now - 29 * day),
                ),
            ),
            Random(1),
            now = now,
        )
        val songs = home.quick.filterIsInstance<QuickPick.Song>().map { it.track.rpmId }
        val albums = home.quick.filterIsInstance<QuickPick.OfAlbum>().map { it.album.album }
        assertEquals(listOf("newer", "new", "old"), songs)
        assertEquals(listOf("Newer", "New", "Old"), albums)
        assertEquals(listOf("newer", "new"), home.quick.filterIsInstance<QuickPick.Radio>().map { it.track.rpmId })
        // The Most played row stays all-time.
        assertEquals(listOf("Old", "Newer", "New"), home.played.map { it.album })
    }

    @Test fun theBannerNeverShowsARecordTwice() {
        val tracks = (1..12).map { track("t$it", "Album $it", added = it.toLong()) }
        val plays = (1..12).map { TrackPlaysRow("t$it", plays = it, lastPlayedAt = it.toLong()) }
        val home = HomeShelf.build(collection(tracks, plays), Random(7))
        val keys = home.banner.filterIsInstance<BannerCard.OfAlbum>().map { it.album.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(home.banner.size <= HomeShelf.BANNER_CARDS)
        assertEquals(Kicker.CONTINUE, (home.banner.first() as BannerCard.OfAlbum).kicker)
    }

    @Test fun madeForYouHoldsThePlainListsTheComputerSentInTheHomeOrder() {
        val tracks = (1..12).map { track("t$it", "A") }
        val byId = tracks.associateBy { it.rpmId }
        val ids = tracks.map { it.rpmId }
        val lists = Lists.generated(
            CloudMoodLists(
                lists = listOf(
                    CloudMoodList(kind = "Calm", trackIds = ids),
                    CloudMoodList(kind = "RightNow", trackIds = ids),
                    CloudMoodList(kind = "Energetic", trackIds = ids.take(5)),
                    CloudMoodList(kind = "FavoriteTracks", trackIds = ids.take(1)),
                    CloudMoodList(kind = "Calm", genre = "Jazz", trackIds = ids),
                ),
            ),
            byId,
            Shelf.Own,
        )
        val home = HomeShelf.build(collection(tracks, lists = lists), Random(1))
        // The computer floors each list before it sends it, so a one-song Favourite tracks is shown.
        assertEquals(listOf("RightNow", "FavoriteTracks", "Calm", "Energetic"), home.madeForYou.map { it.kind })
    }

    @Test fun aGeneratedListShowsItsFirstWindow() {
        assertEquals(listOf("a", "b"), Lists.window(listOf("a", "b", "c", "d"), shows = 2))
        assertEquals(listOf("a", "b", "c"), Lists.window(listOf("a", "b", "c"), shows = 0))
    }

    @Test fun ownPlaylistsKeepTheirOrderAndDropWhatTheCatalogueLacks() {
        val tracks = listOf(track("x", "A"), track("y", "A"))
        val own = Lists.own(listOf(CloudPlaylist("p", "Mine", listOf("y", "missing", "x"))), tracks.associateBy { it.rpmId })
        assertEquals(listOf("y", "x"), own.single().tracks.map { it.rpmId })
    }

    @Test fun aDecadeAndAGenreCutAreNamedByWhatTheyAre() {
        assertEquals(ListName.Decade(1990), Lists.name("Decade", null, 1990, null))
        assertEquals(ListName.Text("Jazz"), Lists.name("Calm", "Jazz", 0, null))
        assertEquals(ListName.Mood("Calm", "Calm"), Lists.name("Calm", null, 0, null))
    }

    private fun shared(tracks: List<CloudTrack>, lists: List<TrackList> = emptyList()) =
        Collection.of(Shelf.Borrowed("owner"), AlbumWall.group(tracks), tracks.associateBy { it.rpmId }, emptyMap(), lists, CoverIndex.Unknown)

    @Test fun aSharedCollectionFillsQuickSelectionWithNothingPlayed() {
        val tracks = (1..40).flatMap { a -> (1..3).map { t -> track("a${a}t$t", "Album $a", added = a.toLong()) } }
        val home = HomeShelf.build(shared(tracks), Random(3))
        assertEquals(HomeShelf.QUICK_PICKS, home.quick.size)
        assertTrue(home.quick.any { it is QuickPick.Song })
        assertTrue(home.quick.any { it is QuickPick.OfAlbum })
        // Recommended for you is seeded from what was added last, as the desktop does on a borrowed shelf.
        assertTrue(home.suggested.isNotEmpty())
    }

    @Test fun aSharedCollectionsMoodListsOpenOnDifferentMoodsAndChangeEachLaunch() {
        val tracks = (1..12).map { track("t$it", "A") }
        val ids = tracks.map { it.rpmId }
        val moods = listOf("Calm", "Energetic", "Danceable", "Focus", "Sleep")
        val cut = CloudMoodLists(lists = moods.flatMap { kind -> listOf(null, "Jazz", "Rock", "Pop").map { genre -> CloudMoodList(kind = kind, genre = genre, trackIds = ids) } })
        val lists = Lists.generated(cut, tracks.associateBy { it.rpmId }, Shelf.Borrowed("owner"))
        val rows = (1..8).map { seed -> HomeShelf.build(shared(tracks, lists), Random(seed)).madeForYou }
        rows.forEach { row ->
            assertEquals(HomeShelf.ROW_LENGTH, row.size)
            assertEquals(moods.toSet(), row.take(moods.size).map { it.kind }.toSet())
        }
        assertTrue(rows.map { row -> row.map { it.id } }.toSet().size > 1)
    }
}
