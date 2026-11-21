package com.rpm.player.tv.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.rpm.player.core.lyrics.KaraokeClock
import com.rpm.player.core.lyrics.KaraokeLight
import com.rpm.player.core.lyrics.KaraokeLines
import com.rpm.player.core.lyrics.LyricScore
import com.rpm.player.core.lyrics.LyricSheet
import com.rpm.player.core.lyrics.LyricVoices
import com.rpm.player.core.lyrics.MeasuredLine
import com.rpm.player.core.lyrics.WordSpan
import com.rpm.player.core.playback.NowPlaying
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The lyrics as they are sung, kept on the middle of the column. It is the iPhone's `LyricView`
 * (RPMUI/LyricView.swift).
 *
 * The sheet is cut into stretches of words and rests ([LyricScore]). Only the stretch being sung is shown. Each
 * line fades by its distance from the sung line (1, 0.42, 0.24, 0.12, then nothing). The sung line is white and
 * the others are `#EAEAEA`. In a rest (between verses, or an instrumental that nobody marked) the words go and
 * three dots fill in across the rest. The lines of the next stretch appear only when it starts. Before the first
 * line (an instrumental intro) only the same dots are shown, counting in to it, so nothing there reads as part of
 * the lyrics. While the next track's sheet is still being read ([LyricSheet.EMPTY]) the dots stand faint, unfilled,
 * so the column says lyrics are coming.
 *
 * With [karaoke] the sung line lights as it is sung: a soft edge of light wipes through each word, letter by
 * letter, as on the desktop's Pegasus ([SweptLine], [KaraokeLight]). The timing is taken from the moments that the
 * cloud measured off the record, where it has them ([measured], [com.rpm.player.core.lyrics.MeasuredLyrics]).
 * Otherwise the line's time is shared between the words by length ([com.rpm.player.core.lyrics.LyricTiming], the
 * desktop's sweep). Which line is up follows [KaraokeLines.held], as on the phone: a line stays up while its last
 * measured word is sung, and the next one takes over just before its first measured word.
 *
 * Words a sheet puts in round brackets are a second voice, as on the desktop and the phone ([LyricVoices]). They are
 * taken out of the line onto a smaller row under it, which opens while the line is sung and folds away after. A line
 * bracketed from end to end has no row of its own: it comes in under the line before it while it is sung, is lit word
 * by word, and folds away when it is done. The column moves on and centres as if it were not there.
 *
 * The playhead is read once a frame, carried forward from the controller's last reading and made steady by a
 * [KaraokeClock], so the light never steps back. The piece in front changes only when the playhead reaches a new one.
 */
@Composable
fun LyricColumn(sheet: LyricSheet, nowPlaying: NowPlaying?, karaoke: Boolean, measured: List<MeasuredLine>, modifier: Modifier) {
    val playing by rememberUpdatedState(nowPlaying)
    // A line bracketed from end to end goes with the line before it, and takes no row of its own ([LyricVoices]).
    val score = remember(sheet) { LyricScore.of(sheet, asides = true) }
    var at by remember(sheet) { mutableIntStateOf(-1) }
    // Seconds into the song, updated once a frame. Only things that move within a piece read it, the dots, the
    // karaoke line and the backing lines, and only when they are drawn (or through a derived state), so a frame redraws
    // them and recomposes nothing.
    var now by remember(sheet) { mutableDoubleStateOf(0.0) }
    // Each sung piece's measured words, where the record has been measured; worked out once per answer. Empty without
    // karaoke, which leaves the stamps to decide.
    val heard = remember(score, measured, karaoke) { if (karaoke) KaraokeLines.heard(score, measured) else emptyList() }
    val heardNow by rememberUpdatedState(heard)
    // One steady clock per record: the reported position moves in steps, and the light must not step back with it.
    val clock = remember(nowPlaying?.mediaId) { KaraokeClock() }
    val clockNow by rememberUpdatedState(clock)

    LaunchedEffect(score) {
        // Where the playhead was when the piece last moved on. Back to an earlier piece is taken only for a real seek,
        // so that no small step of the playhead at a line's edge can flip the sung line back and forth.
        var movedOnAt = Double.NEGATIVE_INFINITY
        while (true) {
            withFrameMillis { }
            val up = SystemClock.uptimeMillis()
            val seconds = playing?.let { clockNow.seconds(it.positionAt(up), it.advancing, up) } ?: 0.0
            now = seconds
            val piece = KaraokeLines.held(score, score.index(seconds), seconds, heardNow) ?: -1
            val back = piece < at && seconds > movedOnAt - SEEK_BACK
            if (piece != at && !back) {
                if (piece > at) movedOnAt = seconds
                at = piece
            }
        }
    }

    val list = rememberLazyListState()
    // The sung piece's top goes to the top of the content padding. The scroll follows the piece where it is on each
    // frame, not where it was when the scroll began: the second voice of the line before it folds away during the
    // scroll and moves it up by that much. A piece that is not on screen (after a seek) is scrolled to by the list.
    LaunchedEffect(at) {
        val target = at.coerceAtLeast(0)
        if (target >= score.parts.size) return@LaunchedEffect
        fun off(): Float? = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }?.offset?.toFloat()
        if (off() == null) {
            list.animateScrollToItem(target)
            return@LaunchedEffect
        }
        if (abs(off() ?: 0f) <= 0.5f) return@LaunchedEffect
        var moved = 0f
        val start = withFrameMillis { it }
        while (true) {
            val time = withFrameMillis { it } - start
            val left = off() ?: break
            if (time >= SCROLL_MS || abs(left) <= 0.5f) {
                list.scrollBy(left)
                break
            }
            moved += list.scrollBy((moved + left) * settled(time) - moved)
        }
    }

    BoxWithConstraints(modifier) {
        val size = (maxHeight * 0.030f).coerceIn(15.px, 34.px)
        val lineHeight = size * 1.33f
        val here = score.parts.getOrNull(at)
        val resting = here?.isRest == true
        LazyColumn(
            state = list,
            userScrollEnabled = false,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(size * 0.62f),
            // The item scrolled to starts half a line above the middle, so a one-line lyric is centred.
            contentPadding = PaddingValues(top = maxHeight / 2 - lineHeight / 2, bottom = maxHeight / 2),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(score.parts, key = { it.id }) { part ->
                val ours = here != null && part.section == here.section
                val distance = abs(part.id - at)
                when (val piece = part.piece) {
                    is LyricScore.Piece.Rest -> {
                        val alpha by animateFloatAsState(if (ours) fade(distance) else 0f, tween(350), label = "rest")
                        Box(Modifier.fillMaxWidth().padding(vertical = size * 0.2f).graphicsLayer { this.alpha = alpha }, contentAlignment = Alignment.Center) {
                            Dots(through = { through(now, piece.from, piece.until) }, dot = size * 0.32f)
                        }
                    }
                    is LyricScore.Piece.Words -> {
                        val sung = part.id == at
                        val target = if (ours && !resting) fade(distance) else 0f
                        val style = ui(size.value.sp, FontWeight.SemiBold)
                        // The second voice is drawn smaller, at the same weight and ink.
                        val small = ui((size.value * LyricVoices.SCALE).sp, FontWeight.SemiBold)
                        val text = piece.line.text
                        // Its measured words where they fit the line, else its time shared between its words by length.
                        val spans = remember(score, part.id, heard) { KaraokeLines.spans(part, score, heard) }
                        // The words in brackets, taken out of the line onto a smaller row under it ([LyricVoices.split]).
                        val split = remember(text, spans) { LyricVoices.split(text, spans) }
                        val fading = remember(part.id) { Animatable(target) }
                        var wasSung by remember(part.id) { mutableStateOf(sung) }
                        LaunchedEffect(target, sung) {
                            // A karaoke line that hands over has already faded its letters back towards rest. It carries
                            // on from the strength it was showing, so it does not flash white before it fades.
                            if (karaoke && wasSung && !sung) fading.snapTo(KaraokeLight.overall(spans, now, fades = false).coerceAtMost(fading.value))
                            wasSung = sung
                            fading.animateTo(target, tween(350))
                        }
                        // Whole while it is sung with karaoke: its letters before the light are already at the other
                        // lines' strength, so nothing jumps as it takes over, and no fade can leave it half lit.
                        val lineAlpha = { if (sung && karaoke && target > 0f) 1f else fading.value }
                        val ink = if (sung) Color.White else SUNG_NOT
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            val shape = Modifier.fillMaxWidth().graphicsLayer { this.alpha = lineAlpha() }
                            val verse = split?.verse
                            if (sung && karaoke) {
                                SweptLine(
                                    text = verse?.text ?: text,
                                    spans = verse?.spans ?: spans,
                                    now = { now },
                                    style = style.copy(textAlign = TextAlign.Center),
                                    modifier = shape,
                                    light = spans,
                                    letters = verse?.letters,
                                )
                            } else {
                                Text(text = verse?.text ?: text, style = style, color = ink, textAlign = TextAlign.Center, modifier = shape)
                            }
                            // The second voice is read with its line: it shows only while the line is sung, and at the
                            // line's own strength. It folds away when the line is done, and the lines under it close up.
                            split?.voice?.let { voice ->
                                Fold(sung, size * VOICE_GAP) { open ->
                                    val faded = Modifier.fillMaxWidth().graphicsLayer { this.alpha = lineAlpha() * open() }
                                    if (sung && karaoke) {
                                        SweptLine(voice.text, voice.spans, { now }, small.copy(textAlign = TextAlign.Center), faded, spans, voice.letters)
                                    } else {
                                        Text(text = voice.text, style = small, color = ink, textAlign = TextAlign.Center, modifier = faded)
                                    }
                                }
                            }
                            // The backing lines that go with this line come in under it while they are sung.
                            for (aside in piece.asides) Aside(aside, measured, { now }, karaoke, small, size * VOICE_GAP)
                        }
                    }
                }
            }
        }
        // The intro: nothing is being sung yet, so only the dots, counting in to the first line, as in a rest. A sheet
        // not read yet has no first line, and its dots stay faint.
        val opening by animateFloatAsState(if (at < 0) 1f else 0f, tween(350), label = "opening")
        if (opening > 0f) {
            Box(Modifier.align(Alignment.Center).graphicsLayer { alpha = opening }) {
                val first = sheet.lines.firstOrNull()?.seconds
                Dots(
                    through = { if (first == null) 0f else if (first <= 0.0) 1f else (now / first).toFloat().coerceIn(0f, 1f) },
                    dot = size * 0.32f,
                )
            }
        }
    }
}

/**
 * A backing line ([LyricScore.Aside]) under the line it goes with: shown while it is sung ([KaraokeLines.voice]),
 * lit word by word with karaoke, and folded away when it is done. It has no row of its own in the score, so the column
 * moves on as if it were not there. [now] is read through a derived state, so the row recomposes only when it opens
 * or closes.
 */
@Composable
private fun Aside(aside: LyricScore.Aside, measured: List<MeasuredLine>, now: () -> Double, karaoke: Boolean, style: TextStyle, gap: Dp) {
    val voice = remember(aside, measured, karaoke) { KaraokeLines.voice(aside, if (karaoke) measured else emptyList()) }
    val time by rememberUpdatedState(now)
    val shown by remember(voice) { derivedStateOf { time().let { it >= voice.from && it < voice.until } } }
    // The words as the row shows them: the line without the brackets round it.
    val row = remember(aside, voice) { LyricVoices.unbracketed(aside.line.text, voice.spans) }
    Fold(shown, gap) { open ->
        val faded = Modifier.fillMaxWidth().graphicsLayer { this.alpha = open() }
        if (karaoke) {
            SweptLine(row.text, row.spans, now, style.copy(textAlign = TextAlign.Center), faded, voice.spans, row.letters)
        } else {
            Text(text = row.text, style = style, color = Color.White, textAlign = TextAlign.Center, modifier = faded)
        }
    }
}

/**
 * A row that opens under a line and folds away again. While [open] it takes its full height and shows; otherwise it
 * takes none and is not drawn. Between the two it eases over [FOLD_MS], its height and its strength together, so the
 * lines under it move with it and do not jump. [gap] is the space between the line and the row. [content] reads how
 * open it is, from 0 to 1, in its drawing only.
 */
@Composable
private fun Fold(open: Boolean, gap: Dp, content: @Composable (open: () -> Float) -> Unit) {
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
            .padding(top = gap),
    ) {
        content { amount }
    }
}

/** How far the scroll to the sung piece has gone after [ms] milliseconds, from 0 to 1: the curve of the list's own
 * scroll to an item, a spring with no bounce at medium stiffness. */
private fun settled(ms: Long): Float {
    val t = SCROLL_STIFFNESS * ms / 1000.0
    return (1 - (1 + t) * exp(-t)).toFloat()
}

/** The square root of the stiffness of the list's own scroll spring (`Spring.StiffnessMedium`, 1500). */
private const val SCROLL_STIFFNESS = 38.73

/** The longest the scroll to the sung piece goes on before it jumps the last of the way, in milliseconds. By then
 * the spring is within a few thousandths of the end. */
private const val SCROLL_MS = 400L

/** How long a second voice takes to open or fold away, in milliseconds. Quicker than the fades: it is a thing
 * finishing, and a slow fold would look like the lyrics were still deciding. */
private const val FOLD_MS = 250

/** The gap between a line and the second voice under it, as a share of the type's size. */
private const val VOICE_GAP = 0.2f

/** How far the playhead must go back, in seconds, before the column goes back a piece: a seek, not jitter. */
private const val SEEK_BACK = 0.5

/** The opacity for a line, by its distance from the sung piece. */
private fun fade(distance: Int): Float = when (distance) {
    0 -> 1f
    1 -> 0.42f
    2 -> 0.24f
    3 -> 0.12f
    else -> 0f
}

/** How far through a rest the playhead is, from 0 to 1. A rest with no end never fills. */
private fun through(now: Double, from: Double, until: Double): Float {
    val run = until - from
    if (!run.isFinite() || run <= 0) return 0f
    return ((now - from) / run).toFloat().coerceIn(0f, 1f)
}

/** The three dots of a break, filling in across it. Each dot owns a third of the break, so together they fill like
 * one bar. A dot is faint before its turn ([DOT_REST]). [through] is read when the dots are drawn, once a frame. */
@Composable
private fun Dots(through: () -> Float, dot: Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(dot * 1.4f)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(dot)
                    .graphicsLayer {
                        val share = (through() * 3 - i).coerceIn(0f, 1f)
                        alpha = DOT_REST + (1 - DOT_REST) * share
                    }
                    .background(Color.White, CircleShape),
            )
        }
    }
}

/**
 * The sung line for karaoke, lit as the desktop's Pegasus lights it: a soft edge of light wipes through each word
 * as it is sung, from [KaraokeLight.REST] up to white, letter by letter ([KaraokeLight]).
 *
 * It is drawn, not composed. [BasicText] lays the line out once and draws nothing itself. Each frame the draw reads
 * [now], works out every letter's strength and draws the laid-out text once per row, with a horizontal gradient
 * whose stops sit on the letters' centres. So nothing recomposes or re-lays-out while the line is sung (an
 * AnnotatedString per frame did both), and the Amiko's Mali draws one shaded text pass per row, with no offscreen
 * layer. The gradient also blends between letters, so the edge is smoother than the desktop's one colour a letter.
 *
 * The letters are lit as the letters of [light], the words of the whole line, and [letters] says which of those each
 * letter of [spans] is (null when [spans] are the whole line). So a line cut into its verse and its second voice
 * ([LyricVoices.split]) is lit as one line: the light goes from one row to the other as the words do.
 */
@Composable
private fun SweptLine(
    text: String,
    spans: List<WordSpan>,
    now: () -> Double,
    style: TextStyle,
    modifier: Modifier,
    light: List<WordSpan> = spans,
    letters: IntArray? = null,
) {
    val sweep = remember(text, spans, light, letters) { Sweep(spans, light, letters) }
    // The layout is kept apart from the sweep. The timing can arrive while the line is sung, which makes a new sweep
    // for the same text; the text is not laid out again for it, so a sweep that waited for its own layout would never
    // get one and the line would stand unlit.
    val laid = remember(text) { arrayOfNulls<TextLayoutResult>(1) }
    BasicText(
        text = text,
        style = style,
        onTextLayout = { laid[0] = it },
        modifier = modifier.drawWithContent { laid[0]?.let { sweep.draw(this, it, now()) } },
    )
}

/**
 * The geometry and the brushes of one [SweptLine]. Where each letter sits is read off the layout once, when it is
 * laid out; a frame only works out strengths and, when one has moved, builds a brush per row. A frame where nothing
 * moved (a rest between words, a held note, a line done) reuses the last brushes and allocates nothing.
 */
private class Sweep(private val spans: List<WordSpan>, private val light: List<WordSpan>, private val letters: IntArray?) {
    private val strength = FloatArray(KaraokeLight.letters(spans))
    // The strengths of the whole line's letters, which [letters] picks this row's from; the same array without them.
    private val whole = if (letters == null) strength else FloatArray(KaraokeLight.letters(light))
    private val drawn = FloatArray(strength.size) { -1f }
    private var layout: TextLayoutResult? = null
    private var x = FloatArray(0)
    // The letter after the last one on each row. Letters are in text order, so each row's are a run.
    private var ends = IntArray(0)
    private var brushes = arrayOfNulls<Brush>(0)

    private fun laid(result: TextLayoutResult) {
        if (result === layout) return
        layout = result
        val length = result.layoutInput.text.length
        x = FloatArray(strength.size)
        ends = IntArray(result.lineCount)
        var k = 0
        for (word in spans) for (c in 0 until word.length) {
            val at = (word.start + c).coerceIn(0, maxOf(0, length - 1))
            val box = result.getBoundingBox(at)
            x[k] = (box.left + box.right) / 2
            val row = result.getLineForOffset(at)
            for (r in row until ends.size) ends[r] = k + 1
            k++
        }
        brushes = arrayOfNulls(result.lineCount)
        drawn.fill(-1f)
    }

    fun draw(scope: DrawScope, laidOut: TextLayoutResult, now: Double) = with(scope) {
        laid(laidOut)
        val layout = layout ?: return@with
        // The sung line stays lit after its last word for as long as it is the one being sung: faded back to rest
        // there, it would stand grey through every pause before the next line. It fades when the next line takes over.
        KaraokeLight.light(light, now, whole, fades = false)
        if (letters != null) for (k in strength.indices) strength[k] = whole.getOrElse(letters[k]) { KaraokeLight.REST }
        val moved = !strength.contentEquals(drawn)
        if (moved) strength.copyInto(drawn)
        var first = 0
        for (r in 0 until layout.lineCount) {
            val end = ends.getOrElse(r) { strength.size }
            val brush = (if (moved) null else brushes[r]) ?: brushFor(first, end).also { brushes[r] = it }
            first = end
            // The alpha is given: left unsaid, a gradient is drawn at whatever alpha the text's paint was left at, which
            // after a frame of the line all at rest is 0.42, so a line that had begun to light would stand grey.
            if (layout.lineCount == 1) {
                drawText(layout, brush, alpha = 1f)
            } else {
                // Each row has its own gradient, so it is drawn clipped to its own band. The bands meet, and the
                // outer ones run on past the box, so no ascender or descender is cut.
                val top = if (r == 0) -size.height else layout.getLineTop(r)
                val bottom = if (r == layout.lineCount - 1) size.height * 2 else layout.getLineTop(r + 1)
                clipRect(left = -size.width, top = top, right = size.width * 2, bottom = bottom) { drawText(layout, brush, alpha = 1f) }
            }
        }
    }

    /** The brush for the letters [first] until [end], one row: a gradient through their centres, keeping only the
     * letters where the strength turns, or one colour when the row is all one strength. Before the first centre and
     * after the last the gradient holds its end colours. A right-to-left row runs its gradient the other way. */
    private fun brushFor(first: Int, end: Int): Brush {
        if (end <= first) return SolidColor(Color.White)
        val from = x[first]
        val until = x[end - 1]
        if (from == until || (first + 1 until end).all { strength[it] == strength[first] }) {
            return SolidColor(Color.White.copy(alpha = strength[first]))
        }
        val stops = ArrayList<Pair<Float, Color>>()
        var last = 0f
        for (i in first until end) {
            val turns = i == first || i == end - 1 || strength[i] != strength[i - 1] || strength[i] != strength[i + 1]
            if (!turns) continue
            last = ((x[i] - from) / (until - from)).coerceIn(last, 1f)
            stops += last to Color.White.copy(alpha = strength[i])
        }
        return Brush.horizontalGradient(*stops.toTypedArray(), startX = from, endX = until)
    }
}

private val SUNG_NOT = Color(0xFFEAEAEA)

/** How faint a dot is before its turn. */
private const val DOT_REST = 0.16f
