package com.rpm.player.core.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.AdtsExtractor
import java.io.IOException
import kotlin.math.roundToLong

/*
 * Seeking inside a live encode, as the iPhone does it: a seek is the same address asked again `?from=<second>`, which
 * the encoder answers exactly, because it holds the whole record. There are no bytes to ask a range of.
 *
 * Media3 seeks a stream by byte. So the encode's seek map ([LiveSeekMap]) hands Media3 a byte position that stands for
 * a second ([LivePlaces.positionOf]), and [LiveEncodeSource] reads the second back out of the position it is opened
 * at ([LivePlaces.secondAt]) and asks the encode from there. The extractor is told the second with the seek, so the
 * song's clock carries on from it.
 *
 * Only with the length the server states (`X-RPM-Duration`). Without it the encode stays unseekable, as Media3 makes
 * any stream with no length, rather than guess one.
 */

/**
 * Which second of the song an open of a live encode at a byte position asks for. Three positions are answered: the
 * start, a seek (a position [positionOf] made), and the byte the last read left off at, which is where Media3 resumes
 * a load that failed ([leftAt]). Any other byte is refused: the encode has none to give.
 */
class LivePlaces {
    private var left = NOWHERE
    private var leftSecond: Double? = null

    fun secondAt(position: Long): Double = when {
        position == 0L -> 0.0
        position == left -> leftSecond ?: throw IOException("the last read of the live encode stopped inside a frame")
        position >= SEEK_BASE -> (position - SEEK_BASE) / 1_000_000.0
        else -> throw IOException("a live encode has no byte $position")
    }

    /** The read that opened at some position has stopped at [position], having handed on the song to [second], or
     * inside a frame (null). */
    fun leftAt(position: Long, second: Double?) {
        left = position
        leftSecond = second
    }

    companion object {
        /** Seek positions start here, far beyond any byte a live encode could send from its start. */
        private const val SEEK_BASE = 1L shl 52
        private const val NOWHERE = -1L

        /** The byte position that stands for [timeUs] in the song. The start is byte 0, as for any stream. */
        fun positionOf(timeUs: Long): Long = if (timeUs <= 0) 0 else SEEK_BASE + timeUs
    }
}

/** A live encode's seek map: the record's stated length, and a seek point at exactly the second asked for. */
@OptIn(UnstableApi::class)
class LiveSeekMap(private val durationUs: Long) : SeekMap {
    override fun isSeekable(): Boolean = true

    override fun getDurationUs(): Long = durationUs

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val at = timeUs.coerceIn(0, durationUs)
        return SeekMap.SeekPoints(SeekPoint(at, LivePlaces.positionOf(at)))
    }
}

/** Media3's ADTS extractor with the encode's seek map in place of its own, which can only say that it cannot seek. */
@OptIn(UnstableApi::class)
class LiveSeekingExtractor(private val adts: Extractor, private val durationUs: Long) : Extractor {
    override fun sniff(input: ExtractorInput): Boolean = adts.sniff(input)

    override fun init(output: ExtractorOutput) = adts.init(
        object : ExtractorOutput by output {
            override fun seekMap(seekMap: SeekMap) = output.seekMap(LiveSeekMap(durationUs))
        },
    )

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = adts.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = adts.seek(position, timeUs)

    override fun release() = adts.release()
}

/**
 * The player's extractors: Media3's own for everything, and for a live encode whose length the server states, the
 * ADTS extractor with the encode's seek map ([LiveSeekingExtractor]). [LiveEncodeSource] marks a live encode's
 * response ([LiveEncodeSource.LIVE_HEADER]), so a stored object is never taken for one.
 */
@OptIn(UnstableApi::class)
class LiveExtractors(private val others: ExtractorsFactory = DefaultExtractorsFactory()) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> = others.createExtractors()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
        val durationUs = seekableDurationUs(responseHeaders) ?: return others.createExtractors(uri, responseHeaders)
        return arrayOf(LiveSeekingExtractor(AdtsExtractor(), durationUs))
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        others.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    companion object {
        /** The length of a live encode that can be seeked in, or null: a stored object, or an encode whose length the
         * server only estimated. */
        fun seekableDurationUs(headers: Map<String, List<String>>): Long? {
            if (headers.keys.none { it.equals(LiveEncodeSource.LIVE_HEADER, ignoreCase = true) }) return null
            return LiveEncode.durationOf(headers)?.let { (it * 1_000_000).roundToLong() }
        }
    }
}
