package com.rpm.player.phone.player

import com.rpm.player.core.lyrics.LyricSheet
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the player shows the lyrics in place of the cover: the iPhone's `LyricsShownTests`. */
class LyricsShownTest {

    private val sung = LyricSheet.parse("[00:12.00]First line\n[00:15.50]Second line")

    @Test
    fun aRecordWithLyricsShowsThemWhenAskedFor() {
        assertTrue(sung.isSynced)
        assertTrue(LyricsLookup.shows(asked = true, sheet = sung, looking = false))
        assertFalse(LyricsLookup.shows(asked = false, sheet = sung, looking = false))
    }

    /** The lookup for a new record is still running. The lyrics stay up, so a record that has lyrics does not show the
     * cover for a moment first. */
    @Test
    fun theLyricsStayUpWhileTheNextRecordIsLookedUp() {
        assertTrue(LyricsLookup.shows(asked = true, sheet = LyricSheet.EMPTY, looking = true))
    }

    /** The next record has no lyrics. The lookup ends with the same empty sheet it started with, so nothing changes but
     * `looking`. The cover must come back all the same, because the lyrics key is greyed out and the person cannot close
     * the lyrics themselves. */
    @Test
    fun aRecordWithNoLyricsShowsTheCoverEvenThoughTheSheetDidNotChange() {
        assertFalse(LyricsLookup.shows(asked = true, sheet = LyricSheet.EMPTY, looking = false))
    }

    /** The person's choice is kept: the record after one with no lyrics shows its lyrics again. */
    @Test
    fun theChoiceIsKeptForTheNextRecordWithLyrics() {
        val asked = true
        assertFalse(LyricsLookup.shows(asked, LyricSheet.EMPTY, looking = false))
        assertTrue(LyricsLookup.shows(asked, sung, looking = false))
    }
}
