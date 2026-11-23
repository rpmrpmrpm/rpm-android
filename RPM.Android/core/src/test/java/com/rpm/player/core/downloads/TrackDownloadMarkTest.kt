package com.rpm.player.core.downloads

import com.rpm.player.core.downloads.AlbumDownloads.Standing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which mark a song's row shows, from where its page's album or playlist stands and where the song stands. Ported from
 * the iPhone's `TrackDownloadMarkTests`.
 */
class TrackDownloadMarkTest {
    private val downloading = Standing.Downloading(have = 1, of = 4, fraction = 0.3, preparing = false)
    private val stuck = Standing.Stuck(have = 3, of = 4)

    private val every = listOf(
        TrackDownload.Absent,
        TrackDownload.Waiting,
        TrackDownload.Preparing,
        TrackDownload.Arriving(0.2),
        TrackDownload.Stuck,
        TrackDownload.OnPhone,
    )

    private fun mark(page: Standing?, track: TrackDownload) = TrackDownloadMark.of(page) { track }

    /** While the page downloads, each row shows its own song's state. */
    @Test fun whileThePageDownloadsEachRowShowsItsSong() {
        assertEquals(TrackDownloadMark.Waiting, mark(downloading, TrackDownload.Waiting))
        assertEquals(TrackDownloadMark.Preparing, mark(downloading, TrackDownload.Preparing))
        assertEquals(TrackDownloadMark.Arriving(0.5), mark(downloading, TrackDownload.Arriving(0.5)))
        assertEquals(TrackDownloadMark.Done, mark(downloading, TrackDownload.OnPhone))
        assertEquals(TrackDownloadMark.Stuck, mark(downloading, TrackDownload.Stuck))
    }

    /** A page that is stuck shows which songs are on the phone and which could not come. */
    @Test fun aStuckPageShowsWhichSongsFailed() {
        assertEquals(TrackDownloadMark.Stuck, mark(stuck, TrackDownload.Stuck))
        assertEquals(TrackDownloadMark.Done, mark(stuck, TrackDownload.OnPhone))
    }

    /** A song the page's entry does not hold, such as a song added to the record after it was asked for, shows
     * nothing. */
    @Test fun aSongNobodyAskedForShowsNothingWhileThePageDownloads() {
        assertNull(mark(downloading, TrackDownload.Absent))
    }

    /** When the whole page is on the phone the key says so, and the rows show nothing. */
    @Test fun aPageOnThePhoneShowsNoMarks() {
        for (track in every) assertNull(mark(Standing.OnPhone, track))
    }

    /** A page nobody asked for marks only the songs another album or playlist brought to the phone. */
    @Test fun aPageNotAskedForMarksOnlySongsOnThePhone() {
        assertEquals(TrackDownloadMark.Done, mark(null, TrackDownload.OnPhone))
        for (track in every - TrackDownload.OnPhone) assertNull(mark(null, track))
    }

    /** The song's state is not read where the page alone decides, so those rows do not follow it. */
    @Test fun theSongIsNotReadWhenThePageIsOnThePhone() {
        var read = false
        assertNull(TrackDownloadMark.of(Standing.OnPhone) { read = true; TrackDownload.OnPhone })
        assertFalse(read)
    }

    /** A screen holding the two flows reads a song's state the way the downloader does. */
    @Test fun aSongIsReadFromWhatIsComingThenWhatIsHeld() {
        val coming = mapOf("t1" to TrackDownload.Arriving(0.4))
        assertEquals(TrackDownload.Arriving(0.4), AlbumDownloads.trackOf("t1", coming, setOf("t2")))
        assertEquals(TrackDownload.OnPhone, AlbumDownloads.trackOf("t2", coming, setOf("t2")))
        assertEquals(TrackDownload.Absent, AlbumDownloads.trackOf("t3", coming, setOf("t2")))
    }
}
