package com.rpm.player.core.downloads

import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.library.AlbumWall
import com.rpm.player.core.library.CloudAlbum
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The book is where a finished download and a removal can meet, so both orders are tested, and so is a book read back
 * from the disk. Ported from the iPhone's `DownloadBookTests`. */
class DownloadBookTest {
    private val root: File = Files.createTempDirectory("rpm-downloads").toFile()

    @After fun clean() {
        root.deleteRecursively()
    }

    private fun album(key: String, ids: List<String>) =
        CloudAlbum(key, key, "Talk Talk", ids.map { CloudTrack(rpmId = it, title = it, album = key) })

    /** A file that has just finished downloading, outside the book's folder. */
    private fun arrived(bytes: Int = 1000): File =
        File.createTempFile("arrived", null).apply { writeBytes(ByteArray(bytes) { 7 }) }

    @Test fun anAlbumIsOnThePhoneOnceEveryTrackHasAFile() {
        val book = DownloadBook(root)
        book.want(album("a", listOf("t1", "t2")))
        assertEquals(0 to 2, book.count("a"))
        assertEquals(listOf("t1", "t2"), book.missing)

        assertTrue(book.keep("t1", arrived(), 320))
        assertEquals(1 to 2, book.count("a"))
        assertEquals(320, book.held("t1")?.kilobits)

        assertTrue(book.keep("t2", arrived(), 320))
        assertEquals(2 to 2, book.count("a"))
        assertTrue(book.missing.isEmpty())
        assertEquals(2000L, book.bytes("a"))
        assertEquals(book.held("t1")?.file, book.fileOf("t1"))
    }

    /** **A download that arrives after its album was removed is discarded**, and the file passed in is deleted too. */
    @Test fun aDownloadThatLandsAfterItsAlbumWasRemovedIsThrownAway() {
        val book = DownloadBook(root)
        book.want(album("a", listOf("t1")))
        book.drop(setOf("a"))
        val file = arrived()
        assertFalse(book.keep("t1", file, 320))
        assertNull(book.held("t1"))
        assertFalse(file.exists())
    }

    /** The other order: removing an album after its download arrived deletes the stored files. */
    @Test fun removingAnAlbumDeletesItsFiles() {
        val book = DownloadBook(root)
        book.want(album("a", listOf("t1")))
        book.keep("t1", arrived(), 320)
        val file = book.held("t1")!!.file

        assertEquals(setOf("t1"), book.drop(setOf("a")))
        assertNull(book.held("t1"))
        assertFalse(file.exists())
        assertTrue(book.all.isEmpty())
    }

    @Test fun aBookReadBackFromTheDiskHoldsWhatWasWritten() {
        DownloadBook(root).apply {
            want(album("a", listOf("t1", "t2")))
            keep("t1", arrived(), 256)
            claim("me")
        }
        val again = DownloadBook(root)
        assertEquals(listOf("a"), again.all.map { it.key })
        assertEquals(listOf("t1", "t2"), again.all.first().tracks.map { it.rpmId })
        assertEquals(256, again.held("t1")?.kilobits)
        assertEquals(listOf("t2"), again.missing)
        assertEquals("me", again.owner)
    }

    /** A stored file deleted outside the book makes the track missing again. */
    @Test fun aCopyWhoseFileHasGoneIsForgottenOnTheNextRead() {
        DownloadBook(root).apply {
            want(album("a", listOf("t1")))
            keep("t1", arrived(), 320)
            held("t1")!!.file.delete()
        }
        val again = DownloadBook(root)
        assertNull(again.held("t1"))
        assertEquals(listOf("t1"), again.missing)
    }

    /** Missing tracks are listed oldest ask first. The album list is newest first. */
    @Test fun whatIsMissingComesInTheOrderItWasAskedFor() {
        val book = DownloadBook(root)
        book.want(album("b", listOf("b1", "b2")), at = 200)
        book.want(album("a", listOf("a1")), at = 100)
        assertEquals(listOf("a1", "b1", "b2"), book.missing)
        assertEquals(listOf("b", "a"), book.all.map { it.key })
    }

    // ── albums and playlists that share tracks ──

    /** **A track that an album and a playlist share keeps its file until both are removed**, and removing the first
     * does not name it as gone, so its transfer is not stopped. */
    @Test fun aSharedTrackStaysUntilEverythingThatHoldsItIsRemoved() {
        val book = DownloadBook(root)
        val list = DownloadBook.playlistKey("own/p1")
        book.want(album("a", listOf("t1", "t2")))
        book.want(album(list, listOf("t2", "t3")))
        book.keep("t2", arrived(), 320)
        val file = book.held("t2")!!.file

        assertEquals(setOf("t3"), book.drop(setOf(list)))
        assertTrue(book.held("t2") != null)
        assertTrue(file.exists())
        assertEquals(1 to 2, book.count("a"))

        assertEquals(setOf("t1", "t2"), book.drop(setOf("a")))
        assertNull(book.held("t2"))
        assertFalse(file.exists())
    }

    /** The same, the other way round: removing the album leaves the playlist's copy. */
    @Test fun removingTheAlbumLeavesThePlaylistItsTrack() {
        val book = DownloadBook(root)
        val list = DownloadBook.playlistKey("own/p1")
        book.want(album("a", listOf("t1")))
        book.want(album(list, listOf("t1")))
        book.keep("t1", arrived(), 320)

        assertTrue(book.drop(setOf("a")).isEmpty())
        assertEquals(1 to 1, book.count(list))
        assertTrue(book.isWanted("t1"))
    }

    /** A track held twice is fetched once: it is listed once in what is missing, and its file is counted once in the
     * total. */
    @Test fun aSharedTrackIsFetchedAndCountedOnce() {
        val book = DownloadBook(root)
        book.want(album("a", listOf("t1", "t2")), at = 100)
        book.want(album(DownloadBook.playlistKey("own/p1"), listOf("t2", "t1", "t3", "t3")), at = 200)

        assertEquals(listOf("t1", "t2", "t3"), book.missing)

        book.keep("t1", arrived(1000), 320)
        book.keep("t2", arrived(500), 320)
        assertEquals(listOf("t3"), book.missing)
        assertEquals(1500L, book.totalBytes)
        assertEquals(1500L, book.bytes("a"))
    }

    @Test fun aPlaylistKeyIsNeverARecordKey() {
        val list = DownloadBook.playlistKey("own/p1")
        assertTrue(DownloadBook.isPlaylistKey(list))
        assertFalse(DownloadBook.isPlaylistKey(AlbumWall.key(CloudTrack(rpmId = "t1", title = "t1", album = "a"))))
        assertFalse(DownloadBook.isPlaylistKey(AlbumWall.key(CloudTrack(rpmId = "t2", relativePath = "playlist:x/y.flac"))))
    }

    @Test fun forgettingEverythingLeavesNothing() {
        val book = DownloadBook(root)
        book.want(album("a", listOf("t1")))
        book.keep("t1", arrived(), 320)
        book.forgetAll()
        assertTrue(book.all.isEmpty())
        assertNull(book.held("t1"))
        assertTrue(DownloadBook(root).all.isEmpty())
    }

    /** A half-written transfer left by a process that was ended is not kept. */
    @Test fun whatWasArrivingWhenTheProcessEndedIsDeleted() {
        val book = DownloadBook(root)
        book.want(album("a", listOf("t1")))
        val half = book.partial("t1").apply { writeBytes(ByteArray(10)) }
        DownloadBook(root).load()
        assertFalse(half.exists())
    }

    @Test fun anIdWithASlashIsOneFile() {
        assertEquals("a%2Fb", DownloadBook.name("a/b"))
    }
}
