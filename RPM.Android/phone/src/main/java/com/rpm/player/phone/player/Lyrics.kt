package com.rpm.player.phone.player

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.lyrics.KaraokeClock
import com.rpm.player.core.lyrics.KaraokeLines
import com.rpm.player.core.lyrics.LrcLib
import com.rpm.player.core.lyrics.LyricScore
import com.rpm.player.core.lyrics.LyricVoices
import com.rpm.player.core.lyrics.LyricSheet
import com.rpm.player.core.lyrics.MeasuredLine
import com.rpm.player.core.lyrics.MeasuredLyrics
import com.rpm.player.core.lyrics.WordSpan
import com.rpm.player.core.playback.NowPlaying
import com.rpm.player.core.playback.TrackRef
import com.rpm.player.phone.design.system
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The words of what is playing: the sheet the owner chose on a desktop, kept in the cloud, or else the one LRCLIB gives.
 * It is the iPhone's `LyricsLookup` (RPMSession/LyricsLookup.swift).
 *
 * **The player asks only while it is shown**, so ordinary listening sends no record name to LRCLIB. It asks as it
 * appears, because the lyrics key needs to know whether there are lyrics before it is pressed.
 *
 * Where a desktop worked out how far the sheet is off the record, the sheet is moved by that much, so a line lights when
 * the record sings it ([MeasuredLyrics.offsetFor]). The TV does the same; the iPhone does not read the offset yet.
 */
@Stable
class LyricsLookup(private val lrcLib: LrcLib, private val measured: MeasuredLyrics) {
    /** The words, or [LyricSheet.EMPTY] where there are none. */
    var sheet by mutableStateOf(LyricSheet.EMPTY)
        private set

    /** Whether an answer is still coming. */
    var looking by mutableStateOf(false)
        private set

    /** The sheet as it was found, before it was moved by [offset]: the one the cloud is asked to measure, and the one
     * its measured lines are matched against ([KaraokeTiming]). */
    var original by mutableStateOf(LyricSheet.EMPTY)
        private set

    /** How far [sheet] was moved from [original], in seconds ([MeasuredLyrics.offsetFor]); 0 where it was not. */
    var offset by mutableStateOf(0.0)
        private set

    /** The cloud's measured words of the record [sheet] belongs to, as the lookup read them for the offset; null where
     * it did not read them. [KaraokeTiming] starts from them, so the words are not asked for twice. */
    var answer by mutableStateOf<com.rpm.player.core.lyrics.MeasuredAnswer?>(null)
        private set

    /** The media id of the record [sheet] belongs to, once its lookup has finished; null while one runs. */
    var mediaId by mutableStateOf<String?>(null)
        private set

    /** The record the sheet belongs to: its media id, then the names and length sent to LRCLIB. */
    private var asked: String? = null

    /**
     * Reads the words for [mediaId], once. [seconds] is the catalogue's length, not the player's: LRCLIB matches on
     * length, and the player reports nothing until the track has started. [explicit] says the song or its record is
     * marked explicit, so LRCLIB's uncensored sheet is preferred ([LrcLib.sheet]).
     */
    suspend fun read(mediaId: String?, artist: String?, title: String?, album: String?, seconds: Double, explicit: Boolean = false) {
        val track = mediaId?.let(TrackRef::parse)
        if (track == null || title.isNullOrBlank()) {
            sheet = LyricSheet.EMPTY
            original = LyricSheet.EMPTY
            offset = 0.0
            answer = null
            this.mediaId = null
            asked = null
            looking = false
            return
        }
        val key = "$mediaId|${artist.orEmpty()}|$title|${album.orEmpty()}|${seconds.toInt()}|$explicit"
        if (key == asked) return
        // Set before the wait, so a second call while the first waits does not look up the same record twice.
        asked = key
        sheet = LyricSheet.EMPTY
        original = LyricSheet.EMPTY
        offset = 0.0
        answer = null
        this.mediaId = null
        looking = true

        // The owner's own choice comes first. A track the cloud does not know, a sheet with no timings, or a failed
        // read all fall back to LRCLIB.
        var found = withContext(Dispatchers.IO) { measured.chosenSheet(track) } ?: LyricSheet.EMPTY
        if (asked != key) return
        if (!found.isSynced) found = withContext(Dispatchers.IO) { lrcLib.sheet(artist, title, album, seconds, explicit) }
        if (asked != key) return
        var moved = 0.0
        var read: com.rpm.player.core.lyrics.MeasuredAnswer? = null
        if (found.isSynced && track.shelf == Shelf.Own) {
            read = withContext(Dispatchers.IO) { measured.read(track) }
            if (asked != key) return
            moved = MeasuredLyrics.offsetFor(read, found)
        }
        answer = read
        original = found
        offset = moved
        sheet = MeasuredLyrics.shifted(found, moved)
        this.mediaId = mediaId
        looking = false
    }

    companion object {
        /**
         * Whether the player shows the lyrics in place of the cover. [asked] is the person's choice, from the lyrics
         * key. The lyrics are shown while they asked for them and this record has timed lyrics, or the lookup for it is
         * still running. A record with none shows the cover, and the next record with lyrics shows them again.
         *
         * **Worked out from the values, not switched by a change.** A new record sets the sheet to empty while it is
         * looked up, and a record with no lyrics ends the lookup with an empty sheet again. The sheet does not change at
         * the end, so a rule that waited for a change left the empty lyrics screen up, with the lyrics key greyed out and
         * no way to close it. The iPhone's `LyricsLookup.shows`.
         */
        fun shows(asked: Boolean, sheet: LyricSheet, looking: Boolean): Boolean = asked && (sheet.isSynced || looking)
    }
}

/** How many lines either side of the sung line are drawn at all. With [fade], this is the whole mask. */
private const val REACH = 3

/** The gap between lines, as a share of the type's own size. */
private const val LEADING = 0.62f

/**
 * The lyrics as they are sung, scrolling so the sung line stays in the middle. It is the iPhone's `LyricView`
 * (RPMUI/LyricView.swift): it draws a score ([LyricScore]), not a list of lines, so a break the writer did not mark is
 * still a break, and every opacity changes only when the playhead reaches a new piece.
 *
 * **The playhead is not read on every frame.** The piece being sung is worked out when the record reaches the next
 * piece's moment, by waiting for that moment, so the list recomposes once a line. Only the dots of a break, and of the
 * intro, follow the playhead, four times a second, as on iPhone.
 *
 * With [karaoke] (the setting, for a supporter), the sung line lights letter by letter ([SweptLine]), from the words the
 * cloud [measured] off the recording where they fit the line ([KaraokeLines]), and a line stays up while its last
 * measured word is still sung.
 *
 * Words a sheet puts in brackets are a second voice, shown as on the desktop ([LyricVoices]): on a smaller row under
 * their line while it is sung, folded away after. A line bracketed from end to end has no row of its own: it comes in
 * under the line before it while it is sung and folds away when it is done, and the lyrics move on as if it were not
 * there.
 */
@Composable
internal fun LyricView(sheet: LyricSheet, now: NowPlaying, karaoke: Boolean, measured: List<MeasuredLine>) {
    // A line bracketed from end to end goes with the line before it, and takes no row of its own ([LyricVoices]).
    val score = remember(sheet) { LyricScore.of(sheet, asides = true) }
    // Each piece's measured words, worked out once per answer. Empty without karaoke, which leaves the stamps to decide.
    val heard = remember(score, measured, karaoke) { if (karaoke) KaraokeLines.heard(score, measured) else emptyList() }
    // When each piece takes over: with karaoke, a line stays up while its last measured word is still sung, and every
    // line is sung for a moment ([KaraokeLines.held]).
    val handovers = remember(score, heard) { KaraokeLines.handovers(score, heard) }
    fun piece(seconds: Double): Int? = handovers.piece(seconds)
    // The line and its words are timed by one steady clock, so the light does not step back when the reported position
    // does.
    val clock = remember(now.mediaId) { KaraokeClock() }
    fun seconds(): Double = SystemClock.uptimeMillis().let { up -> clock.seconds(now.positionAt(up), now.advancing, up) }
    val at by produceState(piece(seconds()), handovers, now) {
        // The playhead is looked at when the next piece takes over, not on every frame. A value that did not change
        // recomposes nothing.
        while (true) {
            val seconds = seconds()
            value = piece(seconds)
            if (!now.advancing) break
            val next = handovers.next(seconds) ?: break
            delay(((next - seconds) * 1000).roundToInt().toLong().coerceAtLeast(16))
        }
    }
    val list = rememberLazyListState()
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val size = (maxWidth.value * 0.055f).coerceIn(16f, 26f)
        val half = maxHeight / 2
        val heightPx = with(density) { maxHeight.toPx() }

        // The sung line goes to the middle: straight there when the view opens, and with an easing scroll as the song
        // moves on. A line far off screen (after a seek) is jumped to.
        LaunchedEffect(at, score) {
            val target = at ?: 0
            if (score.parts.isEmpty()) return@LaunchedEffect
            val seen = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            if (seen == null) {
                list.scrollToItem(target)
                val placed = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target } ?: return@LaunchedEffect
                list.scrollBy(placed.size / 2f)
                return@LaunchedEffect
            }
            // The scroll follows the line where it is on each frame, not where it was when the scroll began: the second
            // voice of the line before it folds away during the scroll and moves it up by that much.
            fun off(): Float? = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }?.let {
                it.offset + it.size / 2f - list.layoutInfo.viewportStartOffset - heightPx / 2
            }
            if (abs(off() ?: 0f) <= 1f) return@LaunchedEffect
            var moved = 0f
            val start = withFrameMillis { it }
            while (true) {
                val time = withFrameMillis { it } - start
                val left = off() ?: break
                if (time >= SCROLL_MS + SETTLE_MS || (time >= SCROLL_MS && abs(left) <= 0.5f)) {
                    list.scrollBy(left)
                    break
                }
                val wanted = if (time < SCROLL_MS) (moved + left) * FastOutSlowInEasing.transform(time / SCROLL_MS.toFloat()) - moved else left
                moved += list.scrollBy(wanted)
            }
        }

        // **The lyrics follow the song and cannot be dragged**, as on iPhone (`scrollDisabled`). Only the seek bar
        // moves the song. The list still scrolls itself to the sung line: `scrollBy` is not a touch.
        LazyColumn(
            state = list,
            userScrollEnabled = false,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = half),
            verticalArrangement = Arrangement.spacedBy((size * LEADING).dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(score.parts, key = { _, part -> part.id }, contentType = { _, part -> part.isRest }) { _, part ->
                ScoreRow(part, score, at, size, now, clock, karaoke, heard, measured)
            }
        }

        // The intro: nothing is sung yet, so the sheet is blank, and the title and artist show over it with the dots
        // filling towards the first line. It goes once, when the singing starts.
        AnimatedVisibility(at == null, enter = fadeIn(tween(350)), exit = fadeOut(tween(350)), modifier = Modifier.align(Alignment.Center)) {
            Opening(sheet, now, size)
        }
    }
}

/** One piece of the score: a line of words, or the dots of the rest that closes a stretch. */
@Composable
private fun ScoreRow(
    part: LyricScore.Part,
    score: LyricScore,
    at: Int?,
    size: Float,
    now: NowPlaying,
    clock: KaraokeClock,
    karaoke: Boolean,
    heard: List<List<WordSpan>?>,
    measured: List<MeasuredLine>,
) {
    val here = at?.let { score.parts[it] }
    val distance = abs(part.id - (at ?: 0))
    val sung = part.id == at
    // While a rest plays, only its dots show. Only the stretch being sung shows: a reach on either side would pass
    // straight through a break and show the next verse during the solo.
    val resting = here?.isRest == true
    val ours = part.section == here?.section
    val wanted = when (part.piece) {
        is LyricScore.Piece.Rest -> if (ours) fade(distance, started = true) else 0f
        is LyricScore.Piece.Words -> if (ours && !resting) fade(distance, started = true) else 0f
    }
    val alpha by animateFloatAsState(wanted, tween(350, easing = FastOutSlowInEasing), label = "line")
    when (val piece = part.piece) {
        is LyricScore.Piece.Rest -> Box(Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha }, contentAlignment = Alignment.Center) {
            if (wanted > 0f || alpha > 0f) Dots(now) { seconds -> through(seconds, piece.from, piece.until) }
        }
        is LyricScore.Piece.Words -> {
            // The same weight on every line, sung or not: a bolder sung line would wrap again and move the lines under
            // it. Only the brightness shows which line is sung.
            val style = system(size, FontWeight.SemiBold).copy(textAlign = TextAlign.Center)
            val small = system(size * LyricVoices.SCALE, FontWeight.SemiBold).copy(textAlign = TextAlign.Center)
            val text = piece.line.text
            // Its measured words where they fit the line, else its time shared between its words by length.
            val spans = remember(part.id, heard) { KaraokeLines.spans(part, score, heard) }
            // The words in brackets, taken out of the line onto a smaller row under it ([LyricVoices.split]).
            val split = remember(text, spans) { LyricVoices.split(text, spans) }
            val ink = if (sung) Color.White else LINE
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val shape = Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha }
                val verse = split?.verse
                when {
                    sung && karaoke && verse != null ->
                        SweptLine(verse.text, verse.spans, now, clock, style.copy(color = Color.White), shape, spans, verse.letters)
                    sung && karaoke -> SweptLine(text, spans, now, clock, style.copy(color = Color.White), shape)
                    else -> BasicText(verse?.text ?: text, shape, style = style.copy(color = ink))
                }
                // The second voice is read with its line: it shows only while the line is sung, and at the line's own
                // strength. It folds away when the line is done, and the lines under it close up.
                split?.voice?.let { voice ->
                    Fold(sung, size) { open ->
                        val faded = Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha * open() }
                        if (sung && karaoke) {
                            SweptLine(voice.text, voice.spans, now, clock, small.copy(color = Color.White), faded, spans, voice.letters)
                        } else {
                            BasicText(voice.text, faded, style = small.copy(color = ink))
                        }
                    }
                }
                // The backing lines that go with this line pop in under it while they are sung, and fold away after.
                for (aside in piece.asides) Aside(aside, measured, now, clock, karaoke, size, small)
            }
        }
    }
}

/**
 * A backing line ([LyricScore.Aside]) under the line it goes with: shown while it is sung ([KaraokeLines.voice]),
 * lit word by word with karaoke, and folded away when it is done. It has no row of its own, so the lyrics move on as if
 * it were not there.
 */
@Composable
private fun Aside(
    aside: LyricScore.Aside,
    measured: List<MeasuredLine>,
    now: NowPlaying,
    clock: KaraokeClock,
    karaoke: Boolean,
    size: Float,
    style: TextStyle,
) {
    val voice = remember(aside, measured, karaoke) { KaraokeLines.voice(aside, if (karaoke) measured else emptyList()) }
    val shown by produceState(false, voice, now) {
        while (true) {
            val up = SystemClock.uptimeMillis()
            val seconds = clock.seconds(now.positionAt(up), now.advancing, up)
            value = seconds >= voice.from && seconds < voice.until
            if (!now.advancing) break
            val next = when {
                seconds < voice.from -> voice.from
                seconds < voice.until -> voice.until
                else -> break
            }
            delay(((next - seconds) * 1000).roundToInt().toLong().coerceAtLeast(16))
        }
    }
    // The words as the row shows them: the line without the brackets round it.
    val row = remember(aside, voice) { LyricVoices.unbracketed(aside.line.text, voice.spans) }
    Fold(shown, size) { open ->
        val faded = Modifier.fillMaxWidth().graphicsLayer { this.alpha = open() }
        if (karaoke) {
            SweptLine(row.text, row.spans, now, clock, style.copy(color = Color.White), faded, voice.spans, row.letters)
        } else {
            BasicText(row.text, faded, style = style.copy(color = Color.White))
        }
    }
}

/**
 * A row that opens under a line and folds away again. While [open] it takes its full height and shows; otherwise it
 * takes none and is not drawn. Between the two it eases over [FOLD_MS], its height and its strength together, so the
 * lines under it move with it and do not jump. [content] reads how open it is, from 0 to 1, in its drawing only.
 */
@Composable
private fun Fold(open: Boolean, size: Float, content: @Composable (open: () -> Float) -> Unit) {
    val amount by animateFloatAsState(if (open) 1f else 0f, tween(FOLD_MS, easing = FastOutSlowInEasing), label = "fold")
    if (!open && amount == 0f) return
    Box(
        Modifier
            .fillMaxWidth()
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, (placeable.height * amount).roundToInt()) { placeable.place(0, 0) }
            }
            .padding(top = (size * GAP).dp),
    ) {
        content { amount }
    }
}

/** How long a second voice takes to open or fold away, in milliseconds. Quicker than the scroll: it is a thing
 * finishing, and a slow fold would look like the lyrics were still deciding. */
private const val FOLD_MS = 250

/** The gap between a line and the second voice under it, as a share of the type's own size. */
private const val GAP = 0.2f

/** How long the scroll to the next line takes, in milliseconds, and how long it may go on after that to take up a fold
 * that moved the line while it scrolled. */
private const val SCROLL_MS = 450L
private const val SETTLE_MS = 300L

/** The colour of a line that is not being sung. */
private val LINE = Color(0xFFEAEAEA)

/** The opacity by distance from the sung line: 1, 0.42, 0.24, 0.12, then nothing. Nothing before the first line. */
private fun fade(distance: Int, started: Boolean): Float {
    if (!started) return 0f
    return when (distance) {
        0 -> 1f
        1 -> 0.42f
        2 -> 0.24f
        else -> if (distance <= REACH) 0.12f else 0f
    }
}

/** How far through a rest the record is at [seconds], from 0 to 1. A rest with no end never fills. */
private fun through(seconds: Double, from: Double, until: Double): Float {
    val run = until - from
    if (!run.isFinite() || run <= 0) return 0f
    return ((seconds - from) / run).coerceIn(0.0, 1.0).toFloat()
}

/** The screen before the first line: the title and artist, and how much of the intro is left. */
@Composable
private fun Opening(sheet: LyricSheet, now: NowPlaying, size: Float) {
    Column(Modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            BasicText(now.title, maxLines = 3, style = system(size, FontWeight.SemiBold).copy(color = Color.White, textAlign = TextAlign.Center))
            BasicText(now.artist, maxLines = 3, style = system(size * 0.82f, FontWeight.Medium).copy(color = LINE.copy(alpha = 0.66f), textAlign = TextAlign.Center))
        }
        val first = sheet.lines.firstOrNull()?.seconds ?: 0.0
        Dots(now) { seconds -> if (first <= 0) 1f else (seconds / first).coerceIn(0.0, 1.0).toFloat() }
    }
}

/** The opacity of a dot before its turn. All three show from the start; the fill shows how many are reached. */
private const val DOT_REST = 0.16f

/**
 * The three dots of a break, filling in across it, each over its own third, so the three read as one bar filling. The
 * playhead is read four times a second, and the fill eases linearly between readings, as on iPhone, so it does not step.
 */
@Composable
private fun Dots(now: NowPlaying, share: (Double) -> Float) {
    var through by remember { mutableFloatStateOf(share(now.positionAt(SystemClock.uptimeMillis()) / 1000.0)) }
    LaunchedEffect(now) {
        while (true) {
            through = share(now.positionAt(SystemClock.uptimeMillis()) / 1000.0)
            if (!now.advancing) break
            delay(250)
        }
    }
    val shown by animateFloatAsState(through, tween(250, easing = LinearEasing), label = "dots")
    Canvas(Modifier.size(41.dp, 7.dp)) {
        val r = size.height / 2
        val step = (size.width - size.height) / 2
        for (dot in 0 until 3) {
            val part = (shown * 3 - dot).coerceIn(0f, 1f)
            drawCircle(Color.White.copy(alpha = DOT_REST + (1 - DOT_REST) * part), r, androidx.compose.ui.geometry.Offset(r + step * dot, r))
        }
    }
}
