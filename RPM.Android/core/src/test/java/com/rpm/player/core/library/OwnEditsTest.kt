package com.rpm.player.core.library

import com.rpm.player.core.cloud.CloudPlaylist
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.CloudTrackTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnEditsTest {
    private fun library(tracks: List<CloudTrack>, playlists: List<CloudPlaylist> = emptyList(), readAt: Long = 0) =
        CloudLibrary(tracks = tracks, coverKeys = null, coverStamps = null, plays = emptyList(), moods = null, playlists = playlists, readAt = readAt)

    private val song = CloudTrack(rpmId = "a", tags = CloudTrackTags(genre = "Rock"), updatedAt = 100)

    @Test fun aFavouriteIsLaidOverARowReadBeforeIt() {
        val edits = OwnEdits("me", marks = mapOf("a" to OwnEdits.Mark(true, at = 7L, stamp = 200L)), playlists = emptyMap(), made = emptyMap())
        val laid = edits.over(library(listOf(song))).tracks.single()
        assertTrue(laid.tags!!.favorite)
        // When it was marked goes with it, so the marked songs put it first.
        assertEquals(7L, laid.tags.favoritedAt)
        assertEquals("Rock", laid.tags.genre)
    }

    /** A row read after the write holds it, or something newer from another device, and is left as it is. */
    @Test fun aRowReadAfterTheWriteIsLeftAlone() {
        val edits = OwnEdits("me", marks = mapOf("a" to OwnEdits.Mark(true, at = 7L, stamp = 50L)), playlists = emptyMap(), made = emptyMap())
        assertFalse(edits.over(library(listOf(song))).tracks.single().tags!!.favorite)
    }

    @Test fun aPlaylistWrittenHereReplacesAnOlderCopyOnly() {
        val older = CloudPlaylist("p", name = "Mine", trackIds = listOf("a"), updatedAt = 100)
        val written = CloudPlaylist("p", name = "Mine", trackIds = listOf("a", "b"), updatedAt = 200)
        val edits = OwnEdits("me", marks = emptyMap(), playlists = mapOf("p" to written), made = emptyMap())
        assertEquals(written, edits.over(library(emptyList(), listOf(older))).playlists.single())

        val newer = CloudPlaylist("p", name = "Mine", trackIds = listOf("c"), updatedAt = 300)
        assertEquals(newer, edits.over(library(emptyList(), listOf(newer))).playlists.single())
    }

    @Test fun aPlaylistDeletedElsewhereStaysDeleted() {
        val written = CloudPlaylist("p", name = "Mine", trackIds = listOf("a"), updatedAt = 200)
        val edits = OwnEdits("me", marks = emptyMap(), playlists = mapOf("p" to written), made = emptyMap())
        assertTrue(edits.over(library(emptyList(), emptyList())).playlists.isEmpty())
    }

    @Test fun noEditsLeaveTheLibraryAsItIs() {
        val held = library(listOf(song))
        assertSame(held, OwnEdits.NONE.over(held))
    }

    @Test fun anotherAccountStartsWithNoEdits() {
        val mine = OwnEdits("me", marks = mapOf("a" to OwnEdits.Mark(true, at = 7L, stamp = 1L)), playlists = emptyMap(), made = emptyMap())
        assertSame(mine, mine.forUser("me"))
        assertTrue(mine.forUser("you").marks.isEmpty())
        assertEquals("you", mine.forUser("you").user)
    }

    @Test fun aMarkTakenOffIsLaidOverARowReadBeforeIt() {
        val loved = song.copy(tags = song.tags!!.copy(favorite = true))
        val edits = OwnEdits("me", marks = mapOf("a" to OwnEdits.Mark(false, at = 0L, stamp = 200L)), playlists = emptyMap(), made = emptyMap())
        assertFalse(edits.over(library(listOf(loved))).tracks.single().tags!!.favorite)
    }

    private val made = CloudPlaylist("n", name = "New", trackIds = listOf("a"), updatedAt = 300)
    private val makes = OwnEdits("me", marks = emptyMap(), playlists = mapOf("n" to made), made = mapOf("n" to 500L))

    /** A read that began before the playlist was made cannot have it, and it is added. */
    @Test fun aPlaylistMadeAfterTheReadIsAdded() {
        assertEquals(listOf(made), makes.over(library(emptyList(), emptyList(), readAt = 100)).playlists)
    }

    /** A read that began after it was made and does not have it means it was deleted elsewhere. */
    @Test fun aPlaylistMadeBeforeTheReadAndMissingFromItStaysDeleted() {
        assertTrue(makes.over(library(emptyList(), emptyList(), readAt = 900)).playlists.isEmpty())
    }

    @Test fun aPlaylistMadeHereAndInTheReadIsNotShownTwice() {
        assertEquals(listOf(made), makes.over(library(emptyList(), listOf(made), readAt = 100)).playlists)
    }

    // ── a removal (the iPhone's HeldPlaylistsTests, "a removal") ──

    private val p = CloudPlaylist("p", name = "Mine", trackIds = listOf("a"), updatedAt = 100)
    private val q = CloudPlaylist("q", name = "Other", trackIds = listOf("b"), updatedAt = 100)
    private val none = OwnEdits("me", marks = emptyMap(), playlists = emptyMap(), made = emptyMap())

    @Test fun aRemovedPlaylistGoesAtOnce() {
        val edits = none.removedHere("p", at = 500)
        assertEquals(listOf(q), edits.over(library(emptyList(), listOf(p, q), readAt = 100)).playlists)
    }

    /** The read was answered before the store dropped the row, so it still lists it. It must not bring the playlist back. */
    @Test fun aReadStartedBeforeTheRemovalDoesNotBringItBack() {
        val edits = none.removedHere("p", at = 500)
        assertTrue(edits.over(library(emptyList(), listOf(p), readAt = 400)).playlists.isEmpty())
    }

    /** A read that started after the removal and still lists the playlist: another device wrote it again, and the store's
     * answer stands. */
    @Test fun aReadStartedAfterTheRemovalShowsWhatTheStoreHolds() {
        val edits = none.removedHere("p", at = 500)
        val again = CloudPlaylist("p", name = "Mine", trackIds = listOf("a", "b"), updatedAt = 300)
        assertEquals(listOf(again), edits.over(library(emptyList(), listOf(again), readAt = 600)).playlists)
    }

    /** An add sent just before the delete, ending after it, does not bring the playlist back. */
    @Test fun aWriteThatEndsAfterTheRemovalDoesNotBringItBack() {
        val added = CloudPlaylist("p", name = "Mine", trackIds = listOf("a", "b"), updatedAt = 200)
        val edits = none.removedHere("p", at = 500).wrote(added, began = 450)
        assertTrue(edits.over(library(emptyList(), listOf(p), readAt = 400)).playlists.isEmpty())
        assertTrue(edits.playlists.isEmpty())
    }

    /** A playlist made here and then removed is not added back to a read that began before it was made. */
    @Test fun aPlaylistMadeHereAndRemovedStaysRemoved() {
        val edits = makes.removedHere("n", at = 900)
        assertTrue(edits.over(library(emptyList(), emptyList(), readAt = 100)).playlists.isEmpty())
    }

    /** The iPhone's "a removal from another value changes nothing": here, a removal recorded for another account. */
    @Test fun aRemovalForAnotherAccountChangesNothing() {
        val theirs = none.removedHere("p", at = 500)
        val held = library(emptyList(), listOf(p), readAt = 400)
        assertSame(held, theirs.forUser("you").over(held))
    }

    @Test fun aFavouriteIsStampedInDotNetTicks() {
        // 1 January 2026, 00:00 UTC: DateTime(2026, 1, 1).Ticks.
        assertEquals(639_028_224_000_000_000L, OwnWriter.utcTicks(1_767_225_600_000L))
    }
}
