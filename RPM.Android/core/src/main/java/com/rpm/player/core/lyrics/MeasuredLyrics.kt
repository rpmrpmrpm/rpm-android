package com.rpm.player.core.lyrics

import com.rpm.player.core.cloud.CloudClient
import com.rpm.player.core.library.StarterTracks
import com.rpm.player.core.playback.TrackRef
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** One word's time span in the recording, in seconds from the start of the track. */
@Serializable
data class MeasuredWord(val from: Double = 0.0, val to: Double = 0.0)

/** One lyric line as a desktop measured it: its place in the sheet, its time and text, and its words in
 * reading order. */
@Serializable
data class MeasuredLine(val index: Int = 0, val seconds: Double = 0.0, val text: String = "", val words: List<MeasuredWord> = emptyList())

/** One line of this device's sheet, sent so the recording can be timed against it. */
@Serializable
data class SheetLine(val index: Int, val seconds: Double, val text: String)

@Serializable
data class MeasureAsk(val lines: List<SheetLine>)

/** The sheet an owner chose on a desktop (`cloud/lyric-sheet/{rpmId}`). */
@Serializable
data class ChosenSheet(val lrc: String = "", val chosenAt: String? = null, val owner: String? = null)

/**
 * What the cloud keeps of a recording's lyrics: the measured [lines], and how far the sheet is off the record
 * ([offset], seconds; positive when the singing comes later than the sheet says), worked out for the sheet in
 * [offsetLines]. No [offset] when it was never worked out; 0 when the sheet is in time.
 */
@Serializable
data class MeasuredAnswer(
    val lines: List<MeasuredLine> = emptyList(),
    val owner: String? = null,
    val offset: Double? = null,
    val offsetLines: List<SheetLine> = emptyList(),
)

/**
 * The word timing that a desktop measured from a track's recording (`cloud/lyric-timing/{rpmId}`),
 * used for karaoke on this device. A measured line is used only when it is the same line on this
 * device's sheet: the same text, the same time, and the same number of words ([forLine]). Every
 * other line uses the estimate from [LyricTiming.sweep].
 *
 * Read once per track. A track that nobody has measured, or a failed read, gives no measurement. A starter track
 * ([StarterTracks]) is not in the cloud, so it is never asked about.
 */
class MeasuredLyrics(private val cloud: CloudClient) {
    suspend fun of(track: TrackRef): List<MeasuredLine> = read(track).lines

    /** The whole answer for [track]: the measured lines and the sheet's offset. Empty when nothing was measured or
     * the read failed. A listener who is not a supporter is given the offset alone, where there is one. */
    suspend fun read(track: TrackRef): MeasuredAnswer = if (StarterTracks.isStarter(track.rpmId)) MeasuredAnswer() else try {
        cloud.lyricTiming(track)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        MeasuredAnswer()
    }

    /** The synced sheet the track's owner chose on a desktop, which comes before LRCLIB's first guess; null when
     * they chose none, it has no times, or the read failed. */
    suspend fun chosenSheet(track: TrackRef): LyricSheet? = if (StarterTracks.isStarter(track.rpmId)) null else try {
        cloud.lyricSheet(track)?.let(LyricSheet::parse)?.takeIf { it.isSynced }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Asks the cloud to time [sheet]'s words from [track]'s recording (`cloud/lyric-timing/{rpmId}/measure`).
     * The transcoder does the work, and [of] reads the answer later. False when the request failed. */
    suspend fun ask(track: TrackRef, sheet: LyricSheet): Boolean = if (StarterTracks.isStarter(track.rpmId)) false else try {
        cloud.measureLyrics(track, MeasureAsk(sheet.lines.mapIndexed { i, line -> SheetLine(i, line.seconds, line.text) }))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    companion object {
        /** Whether [measured] covers most of [sheet]'s sung lines. If it does, no new measurement is needed. */
        fun covers(measured: List<MeasuredLine>, sheet: LyricSheet): Boolean {
            val sung = sheet.lines.filter { !LyricScore.isWordless(it.text) }
            if (sung.isEmpty()) return true
            val placed = sung.count { forLine(measured, it.text, it.seconds) != null }
            return placed * 4 >= sung.size * 3
        }

        /** The offset of [answer] for [sheet]: its seconds where it was worked out for this very sheet (every line it
         * was worked out on is in [sheet], with the same text and time), else 0. An offset worked out for another
         * sheet of the song would move this one wrong. */
        fun offsetFor(answer: MeasuredAnswer, sheet: LyricSheet): Double {
            val offset = answer.offset ?: return 0.0
            if (offset == 0.0 || answer.offsetLines.isEmpty()) return 0.0
            val same = answer.offsetLines.all { line ->
                sheet.lines.any { it.text == line.text && kotlin.math.abs(it.seconds - line.seconds) < SAME_MOMENT }
            }
            return if (same) offset else 0.0
        }

        /** [sheet] moved by [offset] seconds, to where the record sings it; no line before the start. */
        fun shifted(sheet: LyricSheet, offset: Double): LyricSheet =
            if (offset == 0.0) sheet else LyricSheet(sheet.lines.map { it.copy(seconds = moved(it.seconds, offset)) })

        /** [lines] with their sheet times moved by [offset] as [shifted] moves the sheet, so they still match its
         * lines ([forLine]). Their words are times in the record already and stay where they are. */
        fun shifted(lines: List<MeasuredLine>, offset: Double): List<MeasuredLine> =
            if (offset == 0.0) lines else lines.map { it.copy(seconds = moved(it.seconds, offset)) }

        private fun moved(seconds: Double, offset: Double): Double = maxOf(0.0, seconds + offset)

        /** How far apart two times may be and still belong to the same line. LRC stamps are kept to the hundredth. */
        private const val SAME_MOMENT = 0.05

        /** The measured words of the line [text] at [seconds], as spans of [text], or null when no
         * measured line matches. */
        fun forLine(measured: List<MeasuredLine>, text: String, seconds: Double): List<WordSpan>? {
            val line = measured.firstOrNull { it.text == text && kotlin.math.abs(it.seconds - seconds) < SAME_MOMENT } ?: return null
            val shape = LyricTiming.sweep(text, seconds, Double.POSITIVE_INFINITY)
            if (shape.size != line.words.size) return null
            return shape.mapIndexed { i, span -> span.copy(from = line.words[i].from, to = maxOf(line.words[i].from, line.words[i].to)) }
        }
    }
}
