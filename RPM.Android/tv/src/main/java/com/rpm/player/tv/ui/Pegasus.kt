package com.rpm.player.tv.ui

import com.rpm.player.core.playback.NowPlaying
import com.rpm.player.core.playback.rememberPosition
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.crossfade
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.rpm.player.core.R
import com.rpm.player.core.library.DiscArt
import com.rpm.player.tv.playerSettings
import com.rpm.player.core.radio.Icy
import com.rpm.player.core.radio.StationLogo
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/** The player's views, in the order the view key steps through them (PegasusLayout.cs). The desktop's third, the
 * bare room, is not offered: the screensaver shows the room without the player now, so a view of it is not needed. */
enum class PegasusView { Spread, Centred }

/**
 * The sleeve's side for a screen: 0.38 of the height, at least 140 desktop pixels, at most 0.34 of the
 * width (PegasusLayout's CoverFraction, CoverMin, CoverMaxWidthFraction). The room's spectrum starts from
 * the same figure.
 */
fun sleeveSide(width: Dp, height: Dp): Dp = (height * 0.38f).coerceIn(140.px, max(140.px, width * 0.34f))

/**
 * The desktop's spread view (its default): the sleeve centred on the screen with the record standing out
 * of it to the right, the three lines down the left with the seek bar ([seek]) and [keys] under them, and a shade
 * behind the words. The room draws the spectrum to the right of the record ([com.rpm.player.core.room.RoomTarget.bare]).
 *
 * Figures are PegasusChrome.xaml.cs's: the column starts 0.075 of the width in and is
 * `min(0.30 w, (w - side) / 2 - left - 0.12 side)` wide; the lines are centred on the middle of the
 * screen and the seek bar and keys hang under them, so the words never move when the keys hide.
 *
 * While the queue drawer is open ([drawerOpen]) the lines, the seek bar and the keys are put away: the drawer
 * pushes the picture right, and the words would stand half under the drawer's glass. They fade out over the
 * drawer's slide ([DRAWER_GLIDE_MS]) and come back once it closes over [BESIDE_MS] on the same curve as the lyrics
 * fading in beside the record, so the left side returns slowly rather than in one frame. The shade stays. The
 * fade is read when drawing, and the keys cannot take the focus while the drawer is open anyway, so the queue key
 * takes it back at once on a close even while it is still faint.
 */
@Composable
fun PegasusSpread(
    nowPlaying: NowPlaying?,
    images: ImageLoader,
    vinyl: FloatArray,
    frame: Color,
    keysAlpha: () -> Float,
    seek: @Composable () -> Unit,
    keys: @Composable (column: Dp) -> Unit,
    drawerOpen: Boolean,
) {
    val wordsShown = animateFloatAsState(
        if (drawerOpen) 0f else 1f,
        if (drawerOpen) tween(DRAWER_GLIDE_MS) else tween(LEFT_BACK_MS, easing = QuadInOut),
        label = "words",
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val side = sleeveSide(w, h)
        val left = w * 0.075f
        val column = max(120.px, min(w * 0.30f, (w - side) / 2 - left - side * 0.12f))

        // The shade behind the words (WordScrim): 0.42 of the width, dark at the left edge.
        Box(
            Modifier
                .width(max(240.px, w * 0.42f))
                .fillMaxHeight()
                .background(Brush.horizontalGradient(0f to Color(0x40000000), 0.55f to Color(0x1F000000), 1f to Color.Transparent)),
        )

        Box(Modifier.size(side).align(Alignment.Center)) {
            Art(nowPlaying, images, vinyl, frame)
        }

        WordsAndKeys(
            nowPlaying = nowPlaying,
            side = side,
            column = column,
            align = TextAlign.Start,
            keysAlpha = keysAlpha,
            seek = seek,
            keys = keys,
            gap = 24.s + max(20.px, side * 0.08f),
            modifier = Modifier.offset(x = left).width(column).fillMaxHeight().graphicsLayer { alpha = wordsShown.value },
            centreLines = true,
        )
    }
}

/**
 * The desktop's centred view: the sleeve centred on the screen, the three lines centred under it, the
 * seek bar ([seek]) and [keys] under them; no shade and no spectrum. The column is `max(240, min(0.56 w,
 * 1.5 side))` wide and starts `max(20, 0.08 side)` under the sleeve.
 */
@Composable
fun PegasusCentred(
    nowPlaying: NowPlaying?,
    images: ImageLoader,
    vinyl: FloatArray,
    frame: Color,
    keysAlpha: () -> Float,
    seek: @Composable () -> Unit,
    keys: @Composable (column: Dp) -> Unit,
    /** The lyrics, drawn beside the record when the lyric key is on; null for none. The record and its lines then
     * stand left of the middle and the lyrics take the right of the screen. This is for the compact view only. */
    lyrics: (@Composable () -> Unit)?,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val side = sleeveSide(w, h)
        val column = max(240.px, min(w * 0.56f, side * 1.5f))
        val top = h / 2 + side / 2 + max(20.px, side * 0.08f)
        // Centred on the screen, or on the left part of it when the lyrics are beside: the record and its lines glide
        // there and back over [BESIDE_MS] while the lyrics fade in or out.
        val beside by animateFloatAsState(if (lyrics != null) 1f else 0f, tween(BESIDE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "beside")
        // The lyrics last shown, kept while they fade out after the key turned them off.
        val shownLyrics = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
        if (lyrics != null) shownLyrics.value = lyrics
        val shift = w * (BESIDE_CENTRE - 0.5f) * beside

        Box(Modifier.size(side).align(Alignment.Center).offset(x = shift)) {
            Art(nowPlaying, images, vinyl, frame)
        }
        val fading = shownLyrics.value
        if (fading != null && beside > 0f) {
            Box(
                Modifier
                    .padding(start = w * BESIDE_LYRICS, end = w * BESIDE_SAFE, top = h * BESIDE_SAFE, bottom = h * BESIDE_SAFE)
                    .fillMaxSize()
                    .graphicsLayer { alpha = beside },
            ) { fading() }
        }
        WordsAndKeys(
            nowPlaying = nowPlaying,
            side = side,
            column = column,
            align = TextAlign.Center,
            keysAlpha = keysAlpha,
            seek = seek,
            keys = keys,
            // Right under the album line, not the spread view's gap: under a centred sleeve the gap would leave
            // the screen's lower part empty. This is for the compact view only.
            gap = 14.s,
            modifier = Modifier.align(Alignment.TopCenter).offset(x = shift, y = top).width(column + (min(column, w * (BESIDE_LYRICS - 0.04f)) - column) * beside),
            centreLines = false,
        )
    }
}

/** Where the record's group is centred, and where the lyrics start, as shares of the width, in the lyrics-beside
 * compact view; and the margin kept from the screen's other edges. The disc stands out about 0.29 of the sleeve's
 * side to the right, which at these figures leaves it clear of the lyrics. */
private const val BESIDE_CENTRE = 0.27f
private const val BESIDE_LYRICS = 0.52f
private const val BESIDE_SAFE = 0.05f
internal const val BESIDE_MS = 450

/** How long the full view's words and keys take to come back once the queue drawer has closed: an even, slow fade.
 * At [BESIDE_MS] on the lyrics' curve most of the change came in its first moments and read as a jump. */
private const val LEFT_BACK_MS = 900

/** A track's record, or a station's logo in the sleeve with no record. */
@Composable
private fun Art(nowPlaying: NowPlaying?, images: ImageLoader, vinyl: FloatArray, frame: Color) {
    val station = nowPlaying?.station
    if (station != null) {
        StationArt(station.logo, images, frame, cover = rememberSongCover(station))
    } else {
        RecordArt(nowPlaying?.cover, nowPlaying?.disc, images, vinyl, SleeveLook.Framed(frame), out = 1f, turning = nowPlaying?.playing == true, turnSeconds = TURN_SECONDS, isRecord = nowPlaying != null)
    }
}

/** One turn of the label while playing (RecordDisc's RevolutionSeconds). */
private const val TURN_SECONDS = 1.8f

/**
 * The three lines, then the seek bar ([seek], not for a station) and the keys under them. With
 * [centreLines], the lines are centred on the middle of [modifier]'s height and the rest hangs under them
 * (the desktop's CentreWords);
 * otherwise everything stacks from the top. The block under the lines starts [gap] under them (the
 * desktop's `24·s + max(20, 0.08 side)` in the spread view, 14·s in the centred one), and fades with
 * [keysAlpha], read when the block is drawn; the lines never fade.
 */
@Composable
private fun WordsAndKeys(
    nowPlaying: NowPlaying?,
    side: Dp,
    column: Dp,
    align: TextAlign,
    keysAlpha: () -> Float,
    seek: @Composable () -> Unit,
    keys: @Composable (column: Dp) -> Unit,
    gap: Dp,
    modifier: Modifier,
    centreLines: Boolean,
) {
    val title = (side.value * 0.075f).coerceIn(16.px.value, 44.px.value)
    Layout(
        modifier = modifier,
        content = {
            Lines(nowPlaying, title, align)
            Column(Modifier.graphicsLayer { alpha = keysAlpha() }) {
                if (nowPlaying?.station == null) seek()
                Box(Modifier.padding(top = 16.s)) { keys(column) }
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val lines = measurables[0].measure(loose)
        val block = measurables[1].measure(loose)
        val gapPx = gap.roundToPx()
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else lines.height + gapPx + block.height
        val linesTop = if (centreLines) (height - lines.height) / 2 else 0
        layout(constraints.maxWidth, height) {
            lines.place(0, linesTop)
            block.place(0, linesTop + lines.height + gapPx)
        }
    }
}

/**
 * The title, artist and album at 1 : 0.75 : 0.62 of [title] (dp), SemiBold white, then `#B3FFFFFF`,
 * then `#80FFFFFF`, 2 px apart. The desktop has 1 : 0.60 : 0.45; the artist and album are larger here to
 * be read from a couch. A line that does not fit waits 10 seconds and then walks across the
 * column, as the desktop's Marquee does; an empty line keeps its height, so nothing moves between tracks.
 */
@Composable
private fun Lines(nowPlaying: NowPlaying?, title: Float, align: TextAlign) {
    val (first, second, third) = linesOf(nowPlaying)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.px)) {
        Line(first, title, FontWeight.SemiBold, Ink.Words, align)
        Line(second, title * 0.75f, FontWeight.Normal, Ink.Artist, align)
        Line(third, title * 0.62f, FontWeight.Normal, Ink.Album, align)
    }
}

@Composable
private fun Line(text: String, size: Float, weight: FontWeight, colour: Color, align: TextAlign) {
    Text(
        text = text.ifEmpty { " " },
        style = ui(size.sp, weight),
        color = colour,
        maxLines = 1,
        softWrap = false,
        textAlign = align,
        modifier = Modifier
            .fillMaxWidth()
            .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 10_000, repeatDelayMillis = 2_000, velocity = 21.dp),
    )
}

/** The three lines for what is playing. A track: title, artist, album. A station, as the desktop's
 * RadioStreamSource writes them: the announced title, else the station's name; the announced artist, else
 * nothing; the station's name when something is announced, else nothing. A station that is reconnecting
 * or in an ad break says so on the second line. */
@Composable
private fun linesOf(nowPlaying: NowPlaying?): Triple<String, String, String> {
    val station = nowPlaying?.station ?: return Triple(nowPlaying?.title.orEmpty(), nowPlaying?.artist.orEmpty(), nowPlaying?.album.orEmpty())
    if (station.reconnecting) return Triple(station.name, stringResource(R.string.Radio_Reconnecting), "")
    return when (val said = station.said) {
        Icy.Said.AdBreak -> Triple(station.name, stringResource(R.string.Radio_AdBreak), "")
        is Icy.Said.Song -> Triple(said.title, said.artist.orEmpty(), station.name)
        null -> Triple(station.name, "", "")
    }
}

/**
 * The seek bar (Controls/SeekBar.xaml at the block's scale): a 3-high track in `#8BFFFFFF`, white for
 * what has played, a 3 x 14 white knob at the join, and only the time left, as `-mm:ss`, right-aligned
 * above the bar's right end in 18 `#99FFFFFF` (the desktop's 12, larger to be read from a couch). There is
 * no elapsed time.
 *
 * The remote can stand on it (the desktop's `TakesKeys`): it glows as a key does, and Left and Right move a
 * preview of the playhead through [keys], which is the only way it seeks. The knob and the time left follow
 * the preview, the knob 16 high as the desktop's is while it previews, and the seek is made when the key
 * comes up. Left and Right never move the focus off it; at either end the key does nothing. [modifier]
 * carries the focus rules, which are the player screen's.
 */
@Composable
fun SeekBar(nowPlaying: NowPlaying?, keys: SeekKeys, modifier: Modifier) {
    val position by rememberPosition(nowPlaying)
    val duration = nowPlaying?.durationMs ?: 0
    // The keys' preview while it is for the item playing now: one left from another item is not drawn.
    val previewMs = keys.preview?.takeIf { it.item.mediaId == nowPlaying?.mediaId }?.toMs
    val at = previewMs ?: position
    val fraction = if (duration > 0) (at.toFloat() / duration).coerceIn(0f, 1f) else 0f
    // A bar taken off the screen with a key down (lyrics arriving swap the view) never sees the key come up.
    DisposableEffect(keys) { onDispose { keys.commit() } }
    Column(
        modifier
            .deskFocus(SEEK_CORNER)
            // A key held while the focus left the bar comes up somewhere else, so the bar commits on the way out too.
            .onFocusChanged { if (!it.isFocused) keys.commit() }
            .onKeyEvent { event ->
                val way = when (event.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> return@onKeyEvent false
                }
                when (event.type) {
                    KeyEventType.KeyDown -> keys.step(way, event.nativeKeyEvent.repeatCount > 0, event.nativeKeyEvent.eventTime)
                    KeyEventType.KeyUp -> keys.commit()
                }
                true
            }
            .focusable()
            .fillMaxWidth(),
    ) {
        Text(
            text = if (duration > 0) "-" + remainingOf(duration - at) else " ",
            style = ui(18.ts),
            color = Ink.Dim,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
        Canvas(Modifier.fillMaxWidth().height(14.s)) {
            val line = 3.s.toPx()
            val knob = 3.s.toPx()
            // Grown upward with its foot where it was, so nothing under the bar moves.
            val knobHeight = if (previewMs != null) KNOB_PREVIEW.s.toPx() else size.height
            val y = size.height - size.height / 2
            val x = size.width * fraction
            drawRoundRect(SEEK_TRACK, Offset(0f, y - line / 2), androidx.compose.ui.geometry.Size(size.width, line), androidx.compose.ui.geometry.CornerRadius(line / 2))
            drawRoundRect(Color.White, Offset(0f, y - line / 2), androidx.compose.ui.geometry.Size(x, line), androidx.compose.ui.geometry.CornerRadius(line / 2))
            drawRoundRect(
                Color.White,
                Offset((x - knob / 2).coerceIn(0f, size.width - knob), size.height - knobHeight),
                androidx.compose.ui.geometry.Size(knob, knobHeight),
                androidx.compose.ui.geometry.CornerRadius(knob / 2),
            )
        }
    }
}

private val SEEK_TRACK = Color(0x8BFFFFFF)

/** The knob's height while the keys preview a seek (the desktop's KnobHover), in `s` units; 14 at rest. */
private const val KNOB_PREVIEW = 16

/** The seek bar's corner, as [deskFocus] is told it. */
private val SEEK_CORNER = 8.s

/** The time left as the desktop's seek bar writes it: two-digit minutes, then seconds; hours when there are any. */
fun remainingOf(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

/** How a sleeve is drawn: the player's, framed in the cover's colour; or the record page's, bare with
 * a corner of its own. */
sealed interface SleeveLook {
    data class Framed(val frame: Color) : SleeveLook
    data class Bare(val corner: Dp) : SleeveLook
}

/**
 * The record and its sleeve, in a square of the sleeve's side; the disc stands [out] (0 to 1) of the
 * way to 0.312 of the side to the right, drawn outside the square, behind the sleeve. While [turning],
 * the label turns once every [turnSeconds], spinning up and down over a second (RecordDisc). [disc] is the
 * record's own disc picture, or null for a record without one. A record without one ([isRecord]) wears the plain CD,
 * or the drawn record when the vinyl is chosen ([PlayerSettings.discStandIn]); anything else (a list) wears the drawn
 * record.
 */
@Composable
fun RecordArt(cover: Any?, disc: DiscArt?, images: ImageLoader, vinyl: FloatArray, look: SleeveLook, out: Float, turning: Boolean, turnSeconds: Float, isRecord: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = maxWidth
        val angle = rememberTurn(turning, turnSeconds)
        Disc(cover, disc, isRecord, images, vinyl, side * 0.96f, angle, Modifier.align(Alignment.Center).offset(x = side * 0.312f * out))
        when (look) {
            is SleeveLook.Framed -> Sleeve(cover, images, side, look.frame)
            is SleeveLook.Bare -> BareSleeve(cover, images, look.corner)
        }
    }
}

/** The record page's sleeve: the cover on `#0A0A0A` with its own corner and no frame. */
@Composable
private fun BareSleeve(cover: Any?, images: ImageLoader, corner: Dp) {
    val shape = RoundedCornerShape(corner)
    Box(Modifier.fillMaxSize().clip(shape).background(Color(0xFF0A0A0A))) {
        Crossfade(targetState = cover, animationSpec = tween(COVER_FADE_MS), label = "cover") { picture ->
            if (picture != null) PlayerPicture(picture, images, Modifier.fillMaxSize())
        }
    }
}

/**
 * A cover in the player or on the record page, asked for at its own size, which no card has decoded. Until it
 * arrives the same cover a card left in memory, smaller, stands in, and the sharp one fades over it in
 * [PICTURE_FADE_MS]. Without the stand-in the sleeve shows its dark well for about 0.4 s on the Amiko and the
 * cover then appears in one frame, which looks like a flicker when playback starts.
 */
@Composable
private fun PlayerPicture(picture: Any, images: ImageLoader, modifier: Modifier) {
    val context = LocalContext.current
    val request = remember(picture, images) {
        ImageRequest.Builder(context)
            .data(picture)
            // The key a card's request for this picture was kept under: no transformation and no size in it.
            .placeholderMemoryCacheKey(images.components.key(picture, Options(context)))
            .crossfade(PICTURE_FADE_MS)
            .build()
    }
    AsyncImage(model = request, imageLoader = images, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

/** How long a picture in the player takes to fade over what stood in for it. */
const val PICTURE_FADE_MS = 300

/**
 * The label's angle in degrees, kept as a frame-clock state read only when drawing. The speed ramps
 * between still and one turn per [seconds] on a smoothstep over a second, so the record starts and stops
 * as a turntable does.
 */
@Composable
private fun rememberTurn(turning: Boolean, seconds: Float): State<Float> {
    val angle = remember { mutableFloatStateOf(0f) }
    val wanted by rememberUpdatedState(turning)
    LaunchedEffect(seconds) {
        var ramp = 0f
        while (true) {
            // At rest no frame is asked for: the loop waits until the record is wanted to turn again. Asking
            // for every frame while still would wake the frame clock sixty times a second for nothing.
            if (!wanted) snapshotFlow { wanted }.first { it }
            var last = 0L
            while (wanted || ramp > 0f) {
                withFrameNanos { now ->
                    val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                    last = now
                    ramp = (ramp + (if (wanted) dt else -dt) / SPIN_UP_SECONDS).coerceIn(0f, 1f)
                    val speed = ramp * ramp * (3 - 2 * ramp)
                    if (speed > 0f) angle.floatValue = (angle.floatValue + speed * 360f / seconds * dt) % 360f
                }
            }
        }
    }
    return angle
}

private const val SPIN_UP_SECONDS = 1f

/**
 * The disc: its shadow (RecordDisc's radial halo at 0.55, thrown down and right), the vinyl pressed in
 * the sleeve's colour, the label carrying the cover (66/200 of the disc), which turns, and the spindle.
 *
 * A record with a disc picture of its own ([disc]) turns that in place of the label, at the whole disc's size
 * and cut round, as the Swift head's RecordDisc does. A scan of a CD replaces the vinyl, which fades out under it,
 * and wears the desktop's CD overlay ([CdDress]);
 * a scan of a label is transparent outside its middle third, so the vinyl shows around it ([ScannedDisc]). The
 * picture fades in over the label when it arrives, and the label is drawn until then.
 */
@Composable
private fun Disc(cover: Any?, disc: DiscArt?, isRecord: Boolean, images: ImageLoader, vinyl: FloatArray, diameter: Dp, angle: State<Float>, modifier: Modifier) {
    val context = LocalContext.current
    val choice = context.playerSettings.discStandIn
    // The plain CD only where the record has no disc picture at all. One that is still being fetched is waited for, so
    // the stand-in is never shown on its way to the real disc.
    val standIn = remember(disc == null, isRecord, choice) {
        if (disc == null && isRecord) ScannedDisc.standIn(context, choice) else null
    }
    val scanned = rememberScannedDisc(disc, images, with(LocalDensity.current) { diameter.roundToPx() }) ?: standIn
    val vinylShown by animateFloatAsState(if (scanned?.coversRecord == true) 0f else 1f, tween(COVER_FADE_MS), label = "vinyl")
    val halo = diameter * 1.06f
    Box(modifier.requiredSize(halo), contentAlignment = Alignment.Center) {
        Canvas(Modifier.requiredSize(halo).offset(x = diameter * 0.025f, y = diameter * 0.015f)) {
            drawCircle(
                brush = Brush.radialGradient(
                    0.00f to Color(0xB3000000),
                    0.86f to Color(0xB3000000),
                    0.94f to Color(0x59000000),
                    1.00f to Color.Transparent,
                    center = Offset(size.width / 2, size.height / 2),
                    radius = size.minDimension / 2,
                ),
                alpha = 0.55f,
            )
        }
        Image(
            painter = painterResource(R.drawable.vinyl_record),
            contentDescription = null,
            alpha = vinylShown,
            colorFilter = ColorFilter.colorMatrix(ColorMatrix(vinyl)),
            modifier = Modifier.requiredSize(diameter),
        )
        Crossfade(targetState = scanned, animationSpec = tween(COVER_FADE_MS), label = "face") { face ->
            // The whole disc's size for both, centred: Crossfade's own box puts what it holds at its top left.
            Box(Modifier.requiredSize(diameter), contentAlignment = Alignment.Center) {
                if (face != null) {
                    // The overlay in the same turning layer as the picture, so it turns with the disc.
                    Box(Modifier.requiredSize(diameter).graphicsLayer { rotationZ = angle.value }, contentAlignment = Alignment.Center) {
                        Image(
                            bitmap = face.picture,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.requiredSize(diameter).clip(CircleShape),
                        )
                        if (face.coversRecord) CdDress(diameter)
                    }
                } else {
                    Label(cover, images, diameter, angle)
                }
            }
        }
        Box(
            Modifier.requiredSize(diameter * 0.024f).clip(CircleShape).background(Color(0xFF0B111A))
                .border(0.5.dp, Color(0x22FFFFFF), CircleShape),
        )
    }
}

/**
 * The two pictures the desktop draws over a whole CD (CdOverlay.cs): an even rim with the rpm hub over the whole
 * disc, which hides the table a photographed disc's clear rim shows, and a CD player's spindle in the hole,
 * [SPINDLE_SHARE] of the disc across. Only over a scan of the whole disc ([ScannedDisc.coversRecord]): a label sits
 * on the app's own pressing, which has its own hole. The pictures are the desktop's Assets/cd-face.png and
 * cd-spindle.png.
 */
@Composable
private fun CdDress(diameter: Dp) {
    Image(painterResource(R.drawable.cd_face), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.requiredSize(diameter))
    Image(painterResource(R.drawable.cd_spindle), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.requiredSize(diameter * SPINDLE_SHARE))
}

/** The spindle's square against the disc's diameter: 176 px of the face's 1400 in the desktop's pictures. */
private const val SPINDLE_SHARE = 176f / 1400f

/** The drawn record's label: the cover on a black circle a third of the disc across, turning with it. */
@Composable
private fun Label(cover: Any?, images: ImageLoader, diameter: Dp, angle: State<Float>) {
    Box(
        Modifier
            .requiredSize(diameter * 0.33f)
            .graphicsLayer { rotationZ = angle.value }
            .clip(CircleShape)
            .background(Color(0xFF0A0A0A))
            .border(0.5.dp, Color(0x59000000), CircleShape),
    ) {
        Crossfade(targetState = cover, animationSpec = tween(COVER_FADE_MS), label = "label") { picture ->
            if (picture != null) PlayerPicture(picture, images, Modifier.fillMaxSize())
        }
    }
}

/**
 * The sleeve (CoverFrame): the cover in a frame `max(1, round(0.014 side))` thick, in the cover's own
 * dominant colour ([frame]), with a 1 px dark edge inside it, on `#0A0A0A`; covers cross-fade over
 * 900 ms (CoverFadeMs), and the frame eases to the new cover's colour over the same 900 ms.
 */
@Composable
private fun Sleeve(cover: Any?, images: ImageLoader, side: Dp, frame: Color) {
    val thickness = maxOf(1f, (side.value * 2 * 0.014f).roundToInt().toFloat()).px
    val shape = RoundedCornerShape(maxOf(2.px.value, side.value * 0.012f).dp)
    // Composes only this box again on each frame of the ease, not the player around it.
    val drawnFrame by animateColorAsState(frame, tween(COVER_FADE_MS), label = "frame")
    Box(Modifier.size(side).clip(shape).background(Color(0xFF0A0A0A)).border(thickness, drawnFrame, shape).padding(thickness)) {
        Crossfade(targetState = cover, animationSpec = tween(COVER_FADE_MS), label = "cover") { picture ->
            if (picture != null) PlayerPicture(picture, images, Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().border(1.px, Color(0x59000000)))
    }
}

private const val COVER_FADE_MS = 900

/** The frame colour when the cover has no usable colour (#FF12161E). */
val SLEEVE_FRAME = Color(0xFF12161E)

/**
 * A station in the sleeve, in the sleeve's frame: its logo dressed by [StationMark], so a mark stands on
 * its made ground filling the sleeve (the desktop's CoverGround, the header's TrackGround) and what does
 * not fill is held 34 px off the edges, on the sleeve's dark. Over it, the cover of the song the station says
 * it is playing ([cover], [SongCoverOver]) where one was found. There is no record: a station puts nothing
 * on the deck.
 */
@Composable
fun StationArt(logo: StationLogo, images: ImageLoader, frame: Color, cover: String? = null) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = maxWidth
        val thickness = maxOf(1f, (side.value * 2 * 0.014f).roundToInt().toFloat()).px
        val shape = RoundedCornerShape(maxOf(2.px.value, side.value * 0.012f).dp)
        val drawnFrame by animateColorAsState(frame, tween(COVER_FADE_MS), label = "frame")
        Box(Modifier.fillMaxSize().clip(shape).background(SLEEVE_DARK).border(thickness, drawnFrame, shape)) {
            StationMark(logo, images, inset = stationInset(side), fadeInMs = PICTURE_FADE_MS, onState = {}, modifier = Modifier.fillMaxSize())
            SongCoverOver(cover, images)
        }
    }
}

/**
 * How far a station's logo that does not fill is held off the edges of a sleeve of [side], as a share of the
 * side: 34 px, as in [StationArt]. The mini player dresses the logo with the share of the player's own sleeve
 * ([sleeveSide] on the screen), so its picture of a station is the player's, only smaller.
 */
fun stationInset(side: Dp): Float = 34.px / side

/** The sleeve's dark, which shows round a station's logo that does not fill it, and alone where it has none. */
val SLEEVE_DARK = Color(0xFF0A0A0A)

fun clockOf(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
