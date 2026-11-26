package com.rpm.player.core.library

import com.rpm.player.core.cloud.CloudPlaylist
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.PlaylistPut
import com.rpm.player.core.cloud.PlaylistWrite
import com.rpm.player.core.cloud.Shelf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The renames and removals of the iPhone's PlaylistWriterTests (RPMLibraryTests/PlaylistWriterTests.swift). */
class PlaylistWriterTest {
    /** A store held in memory. A refusal queued with [refuse] answers the next write with that stored copy. */
    private class Store(vararg held: CloudPlaylist) : PlaylistStore {
        val held = held.toMutableList()
        val writes = mutableListOf<Pair<String, PlaylistWrite>>()
        val deletes = mutableListOf<String>()
        var listings = 0
        private val refusals = ArrayDeque<CloudPlaylist>()
        var failDeletes = false

        fun refuse(with: CloudPlaylist) = refusals.addLast(with)

        override suspend fun playlists(): List<CloudPlaylist> {
            listings++
            return held.toList()
        }

        override suspend fun putPlaylist(playlistId: String, write: PlaylistWrite): PlaylistPut {
            writes += playlistId to write
            refusals.removeFirstOrNull()?.let { return PlaylistPut.Conflict(it) }
            return PlaylistPut.Written(12_345)
        }

        override suspend fun deletePlaylist(playlistId: String) {
            if (failDeletes) throw IOException("offline")
            deletes += playlistId
            held.removeAll { it.playlistId == playlistId }
        }
    }

    /** The writes the writer recorded for the screen. */
    private var edits = OwnEdits.NONE

    private fun writer(store: Store) =
        PlaylistWriter(store, user = { "me" }, edited = { user, change -> edits = change(edits.forUser(user)) }, clock = { 1_000L })

    private fun stored(id: String, name: String, ids: List<String>, folder: List<String> = emptyList(), stamp: Long = 500) =
        CloudPlaylist(id, name, ids, folder, stamp)

    private fun track(id: String) = CloudTrack(rpmId = id)

    /** A list as a page holds it. */
    private fun list(id: String, name: String, tracks: List<CloudTrack>, generated: Boolean = false) = TrackList(
        id = "own/$id", storeId = id, name = ListName.Text(name), tracks = tracks, missing = 0, folder = emptyList(),
        generated = generated, kind = null, plainMood = false, genre = null, decade = 0, blurb = null, stamp = 500,
    )

    // ── renaming ──

    @Test fun aRenameKeepsTheStoresSongsAndFolderAndSendsTheNewName() = runTest {
        val store = Store(stored("p1", "Old", listOf("a", "b", "c"), folder = listOf("Mixes"), stamp = 900))

        // The page still shows an older order, without "c". The rename writes the store's songs.
        val refused = writer(store).rename(list("p1", "Old", listOf(track("a"), track("b"))), Shelf.Own, "  Sunday  ")

        assertNull(refused)
        assertEquals(1, store.writes.size)
        val (id, write) = store.writes[0]
        assertEquals("p1", id)
        assertEquals("Sunday", write.name)
        assertEquals(listOf("a", "b", "c"), write.trackIds)
        assertEquals(listOf("Mixes"), write.folder)
        assertEquals(900L, write.ifUnchangedSince)
        val shown = edits.playlists.getValue("p1")
        assertEquals("Sunday", shown.name)
        assertEquals(12_345L, shown.updatedAt)
    }

    /** Another device changed the songs first. The songs are merged, and the new name is kept: a merge that took the
     * store's name would undo the rename. */
    @Test fun aRenameThatMeetsAnotherDevicesChangeKeepsItsNewName() = runTest {
        val store = Store(stored("p1", "Old", listOf("a", "b"), stamp = 900))
        store.refuse(with = stored("p1", "Old", listOf("a", "b", "d"), stamp = 950))

        val refused = writer(store).rename(list("p1", "Old", listOf(track("a"), track("b"))), Shelf.Own, "New")

        assertNull(refused)
        assertEquals(2, store.writes.size)
        val write = store.writes[1].second
        assertEquals("New", write.name)
        assertEquals(listOf("a", "b", "d"), write.trackIds)
        assertEquals(950L, write.ifUnchangedSince)
        assertEquals("New", edits.playlists.getValue("p1").name)
    }

    @Test fun aRenameToTheSameNameWritesNothing() = runTest {
        val store = Store(stored("p1", "Same", listOf("a"), stamp = 900))
        val refused = writer(store).rename(list("p1", "Same", listOf(track("a"))), Shelf.Own, "Same ")

        assertNull(refused)
        assertTrue(store.writes.isEmpty())
        assertEquals(900L, edits.playlists.getValue("p1").updatedAt)
    }

    @Test fun aBlankNameIsRefusedBeforeAnythingIsAsked() = runTest {
        val store = Store(stored("p1", "Old", listOf("a")))
        assertEquals(PlaylistRefusal.Nameless, writer(store).rename(list("p1", "Old", listOf(track("a"))), Shelf.Own, "   "))
        assertEquals(0, store.listings)
    }

    @Test fun aBorrowedOrGeneratedListIsNotRenamed() = runTest {
        val store = Store(stored("p1", "Old", listOf("a")))
        assertEquals(PlaylistRefusal.NotYours, writer(store).rename(list("p1", "Old", listOf(track("a"))), Shelf.Borrowed("someone"), "New"))
        assertEquals(PlaylistRefusal.Generated, writer(store).rename(list("p1", "Old", listOf(track("a")), generated = true), Shelf.Own, "New"))
        assertTrue(store.writes.isEmpty())
    }

    @Test fun aRenameOfAListTheStoreNoLongerHoldsIsGone() = runTest {
        val store = Store()
        assertEquals(PlaylistRefusal.Gone, writer(store).rename(list("p1", "Old", listOf(track("a"))), Shelf.Own, "New"))
        assertTrue(store.writes.isEmpty())
    }

    // ── removing ──

    @Test fun aRemovalNamesTheList() = runTest {
        val store = Store(stored("p1", "Old", listOf("a")))
        assertNull(writer(store).delete(list("p1", "Old", listOf(track("a"))), Shelf.Own))

        assertEquals(listOf("p1"), store.deletes)
        // Recorded for the screen, so the list goes at once and an older read does not bring it back.
        assertEquals(1_000L, edits.removed["p1"])
    }

    @Test fun aBorrowedOrGeneratedListIsNotRemoved() = runTest {
        val store = Store(stored("p1", "Old", listOf("a")))
        assertEquals(PlaylistRefusal.NotYours, writer(store).delete(list("p1", "Old", listOf(track("a"))), Shelf.Borrowed("someone")))
        assertEquals(PlaylistRefusal.Generated, writer(store).delete(list("p1", "Old", listOf(track("a")), generated = true), Shelf.Own))
        assertTrue(store.deletes.isEmpty())
    }

    /** A removal that failed must not look as if it worked: nothing is recorded, and the list stays on screen. */
    @Test fun aRemovalThatFailedTakesNothingOffTheScreen() = runTest {
        val store = Store(stored("p1", "Old", listOf("a"))).apply { failDeletes = true }
        assertEquals(PlaylistRefusal.Failed, writer(store).delete(list("p1", "Old", listOf(track("a"))), Shelf.Own))
        assertTrue(edits.removed.isEmpty())
    }

    // ── starter tracks ──

    // ── making ──

    /**
     * New playlist in the playlist picker makes one in the folder that is open, with the song being filed in it. The
     * phone shows it at once, from the copy it wrote, under the store's own stamp.
     */
    @Test fun aNewPlaylistIsWrittenInItsFolderWithItsSongs() = runTest {
        val store = Store()

        assertNull(writer(store).make("  Road  ", listOf("Trips", "Summer"), listOf("a")))

        val write = store.writes.single().second
        assertEquals("Road", write.name)
        assertEquals(listOf("Trips", "Summer"), write.folder)
        assertEquals(listOf("a"), write.trackIds)
        assertEquals("nothing may be stored under a new id", 0L, write.ifUnchangedSince)
        val shown = edits.playlists.values.single()
        assertEquals(CloudPlaylist(store.writes.single().first, "Road", listOf("a"), listOf("Trips", "Summer"), 12_345), shown)
    }

    /** A starter track is not in the cloud, so a playlist never names one. */
    @Test fun aNewPlaylistLeavesTheStarterTracksOut() = runTest {
        val store = Store()
        val starter = StarterTracks.tracks.first().rpmId

        assertNull(writer(store).make("Mine", emptyList(), listOf("a", starter)))

        assertEquals(listOf("a"), store.writes.single().second.trackIds)
    }

    @Test fun addingOnlyStarterTracksWritesNothing() = runTest {
        val store = Store(stored("p1", "Old", listOf("a")))

        val added = writer(store).add(list("p1", "Old", listOf(track("a"))), Shelf.Own, StarterTracks.tracks.map { it.rpmId })

        assertEquals(SongsAdded.Refused(PlaylistRefusal.Failed), added)
        assertTrue(store.writes.isEmpty())
    }
}
