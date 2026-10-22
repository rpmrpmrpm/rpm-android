package com.rpm.player.core.playback

import androidx.media3.common.C
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class LiveSeekingTest {

    /** A seek's byte position names its second, which the encode is asked from. */
    @Test fun aSeekPositionNamesItsSecond() {
        val places = LivePlaces()
        assertEquals(12.345, places.secondAt(LivePlaces.positionOf(12_345_000)), 1e-9)
        assertEquals(0L, LivePlaces.positionOf(0))
        assertEquals(0.0, places.secondAt(0), 1e-9)
    }

    /** Media3 resumes a load that failed at the byte it had read to: that byte is where the last read left the song,
     * after a seek too. */
    @Test fun aResumeCarriesOnWhereTheLastReadEnded() {
        val places = LivePlaces()
        val seek = LivePlaces.positionOf(30_000_000)
        places.leftAt(seek + 4_096, 31.25)
        assertEquals(31.25, places.secondAt(seek + 4_096), 1e-9)
        // A read that ended inside a frame left no place to come back to.
        places.leftAt(seek + 5_000, null)
        try {
            places.secondAt(seek + 5_000)
            fail("a position inside a frame names no second")
        } catch (_: IOException) {
        }
    }

    /** A byte offset that is neither the start, a seek nor a resume cannot be answered by an encode with no bytes. */
    @Test fun anyOtherByteIsRefused() {
        try {
            LivePlaces().secondAt(1_234)
            fail("a live encode has no byte 1234")
        } catch (_: IOException) {
        }
    }

    /** With the stated length, the encode is seekable to the second, and its seek point is the second's position. */
    @Test fun withAStatedLengthTheEncodeIsSeekable() {
        val headers = mapOf("x-rpm-duration" to listOf("245.32"), LiveEncodeSource.LIVE_HEADER to listOf("1"))
        assertEquals(245_320_000L, LiveExtractors.seekableDurationUs(headers))
        val map = LiveSeekMap(245_320_000L)
        assertTrue(map.isSeekable)
        assertEquals(245_320_000L, map.durationUs)
        assertEquals(SeekPoint(60_000_000, LivePlaces.positionOf(60_000_000)), map.getSeekPoints(60_000_000).first)
    }

    /** No stated length, no seeking: the length is not guessed. A stored object is never taken for an encode. */
    @Test fun withoutAStatedLengthItIsNotSeekable() {
        assertNull(LiveExtractors.seekableDurationUs(mapOf(LiveEncodeSource.LIVE_HEADER to listOf("1"))))
        assertNull(LiveExtractors.seekableDurationUs(mapOf("x-rpm-duration" to listOf("245.32"))))
    }

    /** The ADTS extractor states that it cannot seek; the wrapper puts the encode's own seek map in its place. */
    @Test fun theWrapperGivesMedia3TheEncodesSeekMap() {
        val adts = object : Extractor {
            override fun sniff(input: ExtractorInput) = true
            override fun init(output: ExtractorOutput) = output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
            override fun read(input: ExtractorInput, seekPosition: PositionHolder) = Extractor.RESULT_END_OF_INPUT
            override fun seek(position: Long, timeUs: Long) = Unit
            override fun release() = Unit
        }
        var told: SeekMap? = null
        LiveSeekingExtractor(adts, 245_320_000L).init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = throw UnsupportedOperationException()
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) {
                told = seekMap
            }
        })
        val map = told!!
        assertTrue(map.isSeekable)
        assertEquals(245_320_000L, map.durationUs)
    }
}
