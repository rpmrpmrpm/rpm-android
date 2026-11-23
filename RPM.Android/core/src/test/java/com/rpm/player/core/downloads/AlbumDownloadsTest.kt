package com.rpm.player.core.downloads

import com.rpm.player.core.cloud.ApiException
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.library.AlbumWall
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.ListName
import com.rpm.player.core.library.StarterTracks
import com.rpm.player.core.library.TrackList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * What the downloader does when the store answers a sign with a live encode: it does not download it, it asks the store
 * to make the rendition and shows the album as preparing. Ported from the iPhone's `AlbumDownloadsTests`, with a
 * transfer of a stored object and a refused one added.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumDownloadsTest {
    private val root: File = Files.createTempDirectory("rpm-downloads").toFile()
    private val book = DownloadBook(root)

    @After fun clean() {
        root.deleteRecursively()
    }

    /** A store that has made nothing at any rate, unless [stored] says otherwise. */
    private class Store {
        val signed = mutableListOf<Pair<String, Int>>()
        val asked = mutableListOf<Pair<List<String>, Int>>()
        var failing = 0
        var stored = false
        var status = 200
    }

    private var kilobits = 320

    private fun TestScope.downloads(store: Store): AlbumDownloads = AlbumDownloads(
        book = book,
        online = { true },
        doubt = {},
        sign = { rpmId, rate ->
            store.signed += rpmId to rate
            AlbumDownloads.Signed("https://store.invalid/$rpmId", rate, live = !store.stored)
        },
        make = { rpmIds, rate ->
            if (store.failing > 0) {
                store.failing--
                throw ApiException.Status(400)
            }
            store.asked += rpmIds to rate
        },
        transfer = { _, into, moved ->
            into.parentFile?.mkdirs()
            into.writeBytes(ByteArray(100))
            moved(1.0)
            store.status
        },
        rate = { kilobits },
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        clock = { 1_000_000_000L + testScheduler.currentTime },
    ).also {
        it.begin()
        runCurrent()
    }

    private fun album(key: String, ids: List<String>) =
        CloudAlbum(key, key, "Talk Talk", ids.map { CloudTrack(rpmId = it, title = it, album = key) })

    @Test fun aLiveAnswerIsNotDownloadedAndTheStoreIsAskedToMakeIt() = runTest {
        val store = Store()
        val downloads = downloads(store)
        downloads.download(album("a", listOf("t1", "t2", "t3")))
        runCurrent()

        assertEquals(listOf(listOf("t1", "t2", "t3") to 320), store.asked)
        assertEquals(AlbumDownloads.Standing.Downloading(0, 3, 0.0, preparing = true), downloads.standing("a"))
        assertTrue(downloads.busy.value)

        // Every track signed once, and none again before its wait is up.
        downloads.pumpNow()
        assertEquals(3, store.signed.size)
        assertEquals(1, store.asked.size)
        assertNull(book.held("t1"))
    }

    /** The store starts the transcoder at most once every thirty seconds, and each run reads the want list once. So the
     * whole album goes in the first ask, before the tracks are signed one at a time. */
    @Test fun theFirstLiveAnswerAsksForEveryTrackStillToCome() = runTest {
        val store = Store()
        val downloads = downloads(store)
        downloads.download(album("a", listOf("t1", "t2", "t3", "t4", "t5", "t6")))
        runCurrent()
        downloads.pumpNow()

        assertEquals(6, store.signed.size)
        assertEquals(listOf("t1", "t2", "t3", "t4", "t5", "t6"), store.asked.first().first)
        assertEquals("the tracks signed after the first ask were asked for again", 1, store.asked.size)
    }

    @Test fun anAskThatFailedIsSentAgainOnTheNextPass() = runTest {
        val store = Store().apply { failing = 1 }
        val downloads = downloads(store)
        downloads.download(album("a", listOf("t1", "t2")))
        runCurrent()
        downloads.pumpNow()

        assertEquals(listOf(listOf("t1", "t2") to 320), store.asked)
        // Not signed again: the ask is what was lost, not the answer.
        assertEquals(2, store.signed.size)
    }

    @Test fun removingAnAlbumThatIsPreparingEndsItsWait() = runTest {
        val store = Store()
        val downloads = downloads(store)
        downloads.download(album("a", listOf("t1", "t2")))
        runCurrent()

        downloads.remove(setOf("a"))
        runCurrent()
        assertNull(downloads.standing("a"))

        downloads.pumpNow()
        assertEquals(2, store.signed.size)
        assertEquals(1, store.asked.size)
    }

    @Test fun aChangeOfRateSignsAgainAtOnceAndAsksAtTheNewRate() = runTest {
        val store = Store()
        val downloads = downloads(store)
        downloads.download(album("a", listOf("t1", "t2")))
        runCurrent()

        kilobits = 256
        downloads.pumpNow()

        assertEquals(listOf(320, 320, 256, 256), store.signed.map { it.second })
        assertEquals(listOf(320, 256), store.asked.map { it.second })
        assertEquals(listOf("t1", "t2"), store.asked.last().first)
    }

    @Test fun tryingAgainStartsTheWaitOver() = runTest {
        val store = Store()
        val downloads = downloads(store)
        val record = album("a", listOf("t1"))
        downloads.download(record)
        runCurrent()
        downloads.download(record)
        runCurrent()

        assertEquals(2, store.signed.size)
        assertEquals(2, store.asked.size)
    }

    @Test fun aStoredObjectIsDownloadedAndTheAlbumIsOnThePhone() = runTest {
        val store = Store().apply { stored = true }
        val downloads = downloads(store)
        downloads.download(album("a", listOf("t1", "t2")))
        runCurrent()

        assertEquals(AlbumDownloads.Standing.OnPhone, downloads.standing("a"))
        assertEquals(setOf("t1", "t2"), downloads.held.value)
        assertEquals(setOf("a"), downloads.onPhone.value)
        assertEquals(200L, downloads.rows.value.single().bytes)
        assertTrue(store.asked.isEmpty())
        assertTrue(!downloads.busy.value)
    }

    @Test fun aTrackRefusedThreeTimesLeavesTheAlbumStuck() = runTest {
        val store = Store().apply {
            stored = true
            status = 404
        }
        val downloads = downloads(store)
        downloads.download(album("a", listOf("t1")))
        runCurrent()

        assertEquals(AlbumDownloads.MOST_GOES, store.signed.size)
        assertEquals(AlbumDownloads.Standing.Stuck(0, 1), downloads.standing("a"))
        assertNull(book.held("t1"))
    }

    // ── playlists ──

    private fun playlist(id: String, ids: List<String>, missing: Int = 0) = TrackList(
        id = id,
        storeId = id,
        name = ListName.Text(id),
        tracks = ids.map { CloudTrack(rpmId = it, title = it, album = "x") },
        missing = missing,
        folder = emptyList(),
        generated = false,
        kind = null,
        plainMood = false,
        genre = null,
        decade = 0,
        blurb = null,
    )

    /** The name a list is shown by, as the shell resolves it. */
    private val nameOf: (TrackList) -> String = { (it.name as ListName.Text).text }

    private fun AlbumDownloads.download(list: TrackList) = download(list, nameOf(list))

    /** The tracks the book holds for a downloaded playlist, or null when it is not downloaded. */
    private fun held(id: String): List<String>? =
        book.all.firstOrNull { it.key == DownloadBook.playlistKey(id) }?.tracks?.map { it.rpmId }

    /** A playlist is kept in its own order, each track once, and without the starter tracks, which are in the app
     * already. */
    @Test fun aPlaylistIsKeptWithEachTrackOnceAndNoStarterTracks() {
        val starter = StarterTracks.PREFIX + "moonlight"
        val entry = AlbumDownloads.entry(playlist("p1", listOf("t2", starter, "t1", "t2")), "p1")

        assertEquals(DownloadBook.playlistKey("p1"), entry.key)
        assertEquals(listOf("t2", "t1"), entry.tracks.map { it.rpmId })
    }

    @Test fun aPlaylistIsDownloadedUnderItsOwnKey() = runTest {
        val store = Store()
        val downloads = downloads(store)
        downloads.download(playlist("p1", listOf("t1", "t2")))
        runCurrent()

        assertEquals(listOf("t1", "t2"), store.asked.first().first)
        assertEquals(
            AlbumDownloads.Standing.Downloading(0, 2, 0.0, preparing = true),
            downloads.standing(DownloadBook.playlistKey("p1")),
        )
        assertEquals(listOf(DownloadBook.playlistKey("p1")), downloads.rows.value.map { it.album.key })
    }

    /** A list with nothing to download is not asked for. */
    @Test fun aListOfOnlyStarterTracksIsNotDownloaded() = runTest {
        val downloads = downloads(Store())
        downloads.download(playlist("p1", listOf(StarterTracks.PREFIX + "moonlight")))
        runCurrent()

        assertNull(downloads.standing(DownloadBook.playlistKey("p1")))
        assertTrue(book.all.isEmpty())
    }

    /** **Removing a playlist leaves the tracks an album still holds.** They stay wanted, so the album goes on
     * downloading them. */
    @Test fun removingAPlaylistLeavesTheTracksOfADownloadedAlbum() = runTest {
        val downloads = downloads(Store())
        downloads.download(album("a", listOf("t1")))
        downloads.download(playlist("p1", listOf("t1", "t2")))
        runCurrent()

        downloads.remove(setOf(DownloadBook.playlistKey("p1")))
        runCurrent()

        assertNull(downloads.standing(DownloadBook.playlistKey("p1")))
        assertTrue(downloads.standing("a") != null)
        assertTrue(book.isWanted("t1"))
        assertFalse(book.isWanted("t2"))
    }

    /** A downloaded playlist follows its list: songs added are wanted, songs taken out are not. */
    @Test fun aDownloadedPlaylistFollowsItsList() = runTest {
        val downloads = downloads(Store())
        downloads.download(playlist("p1", listOf("t1", "t2")))
        runCurrent()

        downloads.follow(Shelf.Own, listOf(playlist("p1", listOf("t1", "t3")), playlist("p2", listOf("t9"))), nameOf)
        runCurrent()

        assertEquals(listOf("t1", "t3"), held("p1"))
        assertNull("a list that was not downloaded is not started", held("p2"))
        assertFalse(book.isWanted("t2"))
        assertTrue(book.isWanted("t3"))
    }

    /** **A list with songs this phone cannot resolve only gains songs.** They can be out of sight for a moment, while
     * the listing is read again, and their files must stay. */
    @Test fun aListWithUnresolvedSongsOnlyGainsSongs() = runTest {
        val downloads = downloads(Store())
        downloads.download(playlist("p1", listOf("t1", "t2")))
        runCurrent()

        downloads.follow(Shelf.Own, listOf(playlist("p1", emptyList(), missing = 2)), nameOf)
        runCurrent()
        assertEquals(listOf("t1", "t2"), held("p1"))

        downloads.follow(Shelf.Own, listOf(playlist("p1", listOf("t3"), missing = 2)), nameOf)
        runCurrent()
        assertEquals(listOf("t3", "t1", "t2"), held("p1"))
    }

    /** Another account's list with the same id is not this account's download. */
    @Test fun anotherAccountsListIsNotFollowed() = runTest {
        val downloads = downloads(Store())
        downloads.download(playlist("p1", listOf("t1")))
        runCurrent()

        downloads.follow(Shelf.Borrowed("u2"), listOf(playlist("p1", listOf("t5"))), nameOf)
        runCurrent()

        assertEquals(listOf("t1"), held("p1"))
    }

    /** Offline, the wall shows a record as on the phone when a downloaded playlist holds one of its tracks. The total
     * counts a shared track's file once. */
    @Test fun aRecordWithATrackInADownloadedPlaylistIsOnThePhone() = runTest {
        val store = Store().apply { stored = true }
        val downloads = downloads(store)
        val list = playlist("p1", listOf("t1", "t2"))
        downloads.download(album("a", listOf("t1")))
        downloads.download(list)
        runCurrent()

        assertEquals(AlbumDownloads.Standing.OnPhone, downloads.standing(DownloadBook.playlistKey("p1")))
        assertEquals(setOf("a") + list.tracks.map { AlbumWall.key(it) }, downloads.onPhone.value)
        assertEquals(200L, downloads.bytes.value)
        assertEquals(setOf("t1", "t2"), downloads.held.value)
    }
}
