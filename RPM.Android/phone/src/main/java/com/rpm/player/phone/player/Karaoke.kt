package com.rpm.player.phone.player

import android.os.SystemClock
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rpm.player.core.Services
import com.rpm.player.core.lyrics.KaraokeLight
import com.rpm.player.core.lyrics.LyricSheet
import com.rpm.player.core.lyrics.MeasuredLine
import com.rpm.player.core.lyrics.MeasuredLyrics
import com.rpm.player.core.lyrics.WordSpan
import com.rpm.player.core.playback.NowPlaying
import com.rpm.player.core.playback.TrackRef
import com.rpm.player.phone.PhoneSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Whether the sung line lights letter by letter: the Karaoke lyrics switch ([PhoneSettings.karaoke]), for an account
 * with the supporter features. As on the iPhone (`karaokeWanted && model.isSupporter` in its `RootView`), the switch is
 * kept for everyone and has an effect only for a supporter, and it is off until the plan has been read.
 */
@Composable
fun karaokeOn(services: Services, settings: PhoneSettings): Boolean {
    val quota by services.ownFace.quota.collectAsStateWithLifecycle()
    return settings.karaoke && quota?.supporter == true
}

/**
 * The words of the playing song as the cloud measured them from the recording, for karaoke: the iPhone's
 * `AppModel.readMeasured` and `measuredLines`. Made once with the [PlayerWiring], so an answer outlives one showing of
 * the player.
 */
@Stable
class KaraokeTiming(private val measured: MeasuredLyrics) {
    /** The measured lines and the record they are for, moved to where the shown sheet has its lines. */
    private var held by mutableStateOf<Pair<String, List<MeasuredLine>>>("" to emptyList())

    /** The measured lines of [mediaId], or none. A song that has just started does not light its words from the last
     * song's moments. */
    fun lines(mediaId: String?): List<MeasuredLine> = held.takeIf { it.first == mediaId }?.second ?: emptyList()

    /**
     * Reads the measured words of [mediaId]. If they do not cover its lyric sheet, the cloud is asked to measure them
     * from the recording. That takes the transcoder about half a minute for a song, so the answer is read again after
     * 20 seconds and then every 15 seconds, at most eight times, as on the iPhone and the TV.
     *
     * [sheet] is the sheet as it was found, which the cloud measures and matches its lines against. The shown sheet was
     * moved by [offset] ([LyricsLookup.offset]), and the measured lines are moved with it, so they still match its lines
     * ([MeasuredLyrics.shifted]); their words are times in the record already.
     *
     * [known] are the measured lines the lyrics lookup already read for this record ([LyricsLookup.answer]). They are used
     * as the first answer, so the words light from the first line and are not asked for a second time.
     *
     * The caller runs this while the player is shown and karaoke is on, and cancels it when the song changes.
     */
    suspend fun read(mediaId: String, sheet: LyricSheet, offset: Double, known: List<MeasuredLine>? = null) {
        if (!sheet.isSynced) return
        val track = TrackRef.parse(mediaId) ?: return

        fun hold(lines: List<MeasuredLine>): List<MeasuredLine> {
            held = mediaId to MeasuredLyrics.shifted(lines, offset)
            return lines
        }

        suspend fun read(): List<MeasuredLine> = hold(withContext(Dispatchers.IO) { measured.of(track) })

        if (MeasuredLyrics.covers(known?.let(::hold) ?: read(), sheet)) return
        if (!withContext(Dispatchers.IO) { measured.ask(track, sheet) }) return
        for (round in 0 until ROUNDS) {
            delay(if (round == 0) FIRST_WAIT_MS else WAIT_MS)
            if (MeasuredLyrics.covers(read(), sheet)) return
        }
    }

    /**
     * Asks the cloud to measure the words of the song after the one playing, while this one plays, so its karaoke is
     * ready when it starts: the transcoder needs about half a minute for a song, longer than many songs take to reach
     * their first line. The sheet is found the way the lyrics lookup will find it when the song starts (the owner's
     * chosen sheet, else LRCLIB's for the same names and length), so the measured lines match it. Each song is asked
     * about once for the life of the app. Only the person's own songs: the cloud measures nobody else's.
     *
     * Called only while the player is shown with karaoke on, as the lyrics lookup is: ordinary listening sends no record
     * names anywhere.
     */
    suspend fun warm(mediaId: String, artist: String?, title: String?, album: String?, seconds: Double, lrcLib: com.rpm.player.core.lyrics.LrcLib) {
        val track = TrackRef.parse(mediaId) ?: return
        if (track.shelf != com.rpm.player.core.cloud.Shelf.Own || title.isNullOrBlank()) return
        if (!warmed.add(mediaId)) return
        withContext(Dispatchers.IO) {
            val sheet = measured.chosenSheet(track) ?: lrcLib.sheet(artist, title, album, seconds)
            if (!sheet.isSynced) return@withContext
            if (MeasuredLyrics.covers(measured.of(track), sheet)) return@withContext
            measured.ask(track, sheet)
        }
    }

    /** The songs [warm] has asked about. */
    private val warmed = java.util.Collections.synchronizedSet(HashSet<String>())

    private companion object {
        /** About two and a half minutes of asking: the first look soon after the request, then every four seconds. A
         * read is one small request, and it stops at the first answer that covers the sheet. */
        const val ROUNDS = 36
        const val FIRST_WAIT_MS = 6_000L
        const val WAIT_MS = 4_000L
    }
}

/**
 * The sung line for karaoke: a soft edge of light moves through each word as it is sung, letter by letter, from
 * [KaraokeLight.REST] up to white. The iPhone's `SweptLine` (RPMUI/LyricView.swift).
 *
 * **Only this line is redrawn every frame, and only drawn, never composed again.** The text is laid out once; each frame
 * works out the letters' strengths and, where one moved, paints each row of the text with a gradient through its
 * letters' centres. The space between two words is part of the gradient between the letters either side, so the light
 * does not drop between words. The time comes from the player's last report carried forward
 * ([NowPlaying.positionAt]), which is what the iPhone's line works out from its own readings.
 */
@Composable
internal fun SweptLine(text: String, spans: List<WordSpan>, now: NowPlaying, style: TextStyle, modifier: Modifier = Modifier) {
    val sweep = remember(text, spans) { Sweep(spans) }
    // Kept apart from the sweep: measured words that arrive while the line is sung make a new sweep for the same text,
    // which is not laid out again.
    val laid = remember(text) { arrayOfNulls<TextLayoutResult>(1) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(now) {
        while (now.advancing) withFrameMillis { frame = it }
    }
    BasicText(
        text,
        style = style,
        onTextLayout = { laid[0] = it },
        modifier = modifier.drawWithContent {
            // Read here, so a new frame redraws this line and nothing else.
            frame
            val layout = laid[0] ?: return@drawWithContent drawContent()
            sweep.draw(this, layout, now.positionAt(SystemClock.uptimeMillis()) / 1000.0)
        },
    )
}

/**
 * Where each letter of one [SweptLine] sits, and the brushes it was last painted with. The places are read off the
 * layout once; a frame where no letter changed strength (a held note, a pause, a line done) reuses the brushes and
 * allocates nothing.
 */
private class Sweep(private val spans: List<WordSpan>) {
    private val strength = FloatArray(KaraokeLight.letters(spans))
    private val painted = FloatArray(strength.size) { -1f }
    private var layout: TextLayoutResult? = null
    private var centres = FloatArray(0)

    /** The letter after the last one on each row. The letters are in reading order, so each row's are one run. */
    private var ends = IntArray(0)
    private var brushes = arrayOfNulls<Brush>(0)

    private fun measure(result: TextLayoutResult) {
        if (result === layout) return
        layout = result
        val length = result.layoutInput.text.length
        centres = FloatArray(strength.size)
        ends = IntArray(result.lineCount)
        var k = 0
        for (word in spans) for (c in 0 until word.length) {
            val at = (word.start + c).coerceIn(0, maxOf(0, length - 1))
            val box = result.getBoundingBox(at)
            centres[k] = (box.left + box.right) / 2
            for (row in result.getLineForOffset(at) until ends.size) ends[row] = k + 1
            k++
        }
        brushes = arrayOfNulls(result.lineCount)
        painted.fill(-1f)
    }

    fun draw(scope: DrawScope, result: TextLayoutResult, now: Double) = with(scope) {
        measure(result)
        // The sung line does not fade after its last word: it stays lit until the next line takes over.
        KaraokeLight.light(spans, now, strength, fades = false)
        val moved = !strength.contentEquals(painted)
        if (moved) strength.copyInto(painted)
        var first = 0
        for (row in 0 until result.lineCount) {
            val end = ends.getOrElse(row) { strength.size }
            val brush = (if (moved) null else brushes[row]) ?: brushFor(first, end).also { brushes[row] = it }
            first = end
            // The alpha is given: left out, a gradient is drawn at the alpha the paint was last left at.
            if (result.lineCount == 1) {
                drawText(result, brush, alpha = 1f)
            } else {
                // Each row has its own gradient, so it is drawn clipped to its own band. The outer bands run on past the
                // box, so no ascender or descender is cut.
                val top = if (row == 0) -size.height else result.getLineTop(row)
                val bottom = if (row == result.lineCount - 1) size.height * 2 else result.getLineTop(row + 1)
                clipRect(left = -size.width, top = top, right = size.width * 2, bottom = bottom) { drawText(result, brush, alpha = 1f) }
            }
        }
    }

    /** The brush for the letters [first] until [end] of one row: a gradient through their centres, with a stop only
     * where the strength turns, or one colour where the row is all one strength. */
    private fun brushFor(first: Int, end: Int): Brush {
        if (end <= first) return SolidColor(Color.White.copy(alpha = KaraokeLight.REST))
        val from = centres[first]
        val until = centres[end - 1]
        if (from == until || (first + 1 until end).all { strength[it] == strength[first] }) {
            return SolidColor(Color.White.copy(alpha = strength[first]))
        }
        val stops = ArrayList<Pair<Float, Color>>()
        var last = 0f
        for (i in first until end) {
            val turns = i == first || i == end - 1 || strength[i] != strength[i - 1] || strength[i] != strength[i + 1]
            if (!turns) continue
            last = ((centres[i] - from) / (until - from)).coerceIn(last, 1f)
            stops += last to Color.White.copy(alpha = strength[i])
        }
        return Brush.horizontalGradient(*stops.toTypedArray(), startX = from, endX = until)
    }
}
