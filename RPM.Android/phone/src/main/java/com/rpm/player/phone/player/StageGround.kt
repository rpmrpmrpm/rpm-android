package com.rpm.player.phone.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import coil3.ImageLoader
import com.rpm.player.core.playback.NowPlaying
import com.rpm.player.core.room.PaintedRoom
import com.rpm.player.core.room.RoomLevels
import com.rpm.player.core.room.RoomPalette
import com.rpm.player.core.room.Rgb
import com.rpm.player.phone.design.rememberSongCover
import com.rpm.player.phone.ui.StageRoom
import com.rpm.player.phone.ui.rememberRoomColours
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * What the sheet's room and its spectrum share: the room, once it is made, and where the cover's foot is, which the
 * room is dimmed below. The foot is measured from the layout, not worked out, because the cover is centred in the
 * space the other controls leave (`SleeveFoot` on iPhone). Neither is Compose state: the room is told the foot
 * directly, and the spectrum reads the room in its own drawing.
 */
@Stable
class Stage {
    var room: PaintedRoom? = null
        internal set

    /** Where the sheet's top is in the window now (it moves with a pull), and how tall the sheet is. */
    internal var top: () -> Float = { 0f }
    internal var height = 1f

    /**
     * Whether the room is dimmed below the cover's foot. True for the upright sheet, where the words are under the
     * cover. On its side the words are beside the cover, and the room is not dimmed below it ([StageGround]'s `foot`).
     */
    internal var footFollows = true

    /** Tells the room where the cover's foot is, given the cover's foot in the window. */
    internal fun footAt(bottom: Float) {
        if (!footFollows) return
        room?.foot = ((bottom - top()) / height.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }
}

/**
 * The sheet's background: the room, lit by the record that is playing (the iPhone's `NowPlayingSheet.ground`). It is the
 * one room in the app that IS the record being played, so the music lights it and it does not breathe on its own. Its
 * colours are those of [lit] ([rememberLitPicture]), dissolving to the next one's. It runs while the sheet is up, at half
 * its rate while the phone saves power ([com.rpm.player.core.room.RoomPainter.saving]), and while the sheet covers the
 * screen every other room stops ([covers]).
 *
 * [foot] is where the room starts to be darker, as a share of its height from the top. Below 0 leaves it to the cover
 * ([Stage.footAt]).
 */
@Composable
fun StageGround(lit: Any?, images: ImageLoader, stage: Stage, covers: Boolean, modifier: Modifier, foot: Float = -1f) {
    val read = rememberRoomColours(lit, images)
    // A picture's colours are read a moment after it arrives. Until then the room keeps the colours it has, so a
    // station's songs go from cover to cover and not through the app's blue on the way.
    val held = remember { HeldPalette() }
    SideEffect { if (read != null) held.palette = read }
    val colours = if (lit == null) null else read ?: held.palette
    StageRoom(colours ?: RoomPalette.FALLBACK, running = true, covers = covers, foot = foot, modifier) { stage.room = it }
}

/** The colours the room was last given from a picture. Not state: it is only read when the picture changes. */
private class HeldPalette {
    var palette: RoomPalette? = null
}

/**
 * What lights the sheet's room and its glass: the record's cover; for a station, the cover of the song it announces
 * while one shows over its logo ([rememberSongCover]), else the logo (`NowPlayingSheet.lit` on iPhone).
 */
@Composable
internal fun rememberLitPicture(now: NowPlaying): Any? {
    val song = rememberSongCover(now.station)
    return now.cover ?: song ?: now.station?.logo
}

/**
 * The spectrum coming out of both sides of the cover (the iPhone's `SpectrumRunView`): bass where it leaves the cover,
 * the top end at the screen's edge ([width] across), mirrored about the cover's middle line. [left] and [right] are the
 * cover's edges as drawn and [centre] its middle line, in this scope; [side] its side as drawn, which the reach is
 * measured against; [pitch] the room one bar and its gap want.
 * Read from the stage's room on each of its pictures, so only this drawing follows the music.
 */
fun DrawScope.spectrumRun(room: PaintedRoom, width: Float, left: Float, right: Float, centre: Float, side: Float, pitch: Float) {
    room.frame
    val run = room.run ?: return
    val palette = room.shownPalette ?: return
    if (side <= 0f) return
    val reach = side * REACH
    val rest = side * REST
    bars(run, palette, right, max(0f, width - right), 1f, centre, reach, rest, pitch)
    bars(run, palette, left, max(0f, left), -1f, centre, reach, rest, pitch)
}

/** The bars for one side of the cover, from [from] outward in [direction], across [room] pixels. */
private fun DrawScope.bars(run: FloatArray, palette: RoomPalette, from: Float, room: Float, direction: Float, centre: Float, reach: Float, rest: Float, pitchWanted: Float) {
    if (room <= 0f || pitchWanted <= 0f) return
    val columns = min((room / pitchWanted).toInt(), RoomLevels.BANDS)
    if (columns < FEWEST) return
    val pitch = room / columns
    val half = pitch * FILL * 0.5f
    for (i in 0 until columns) {
        val level = level(run, i, columns)
        val u = if (columns > 1) i / (columns - 1f) else 0f
        val x = from + direction * (i + 0.5f) * pitch
        val tall = rest + level * reach * (1 - DAMP * u.coerceIn(0f, 1f).pow(DAMP_BEND))
        val tone = mix(mix(palette.tint, palette.glow, u), WHITE, 0.62f + level * 0.38f)
        drawRoundRect(
            Color(tone.r, tone.g, tone.b, (0.24f + level * 0.76f).coerceIn(0f, 1f)),
            topLeft = Offset(x - half, centre - tall),
            size = Size(half * 2, tall * 2),
            cornerRadius = CornerRadius(half, half),
        )
    }
}

/** The run's bands that fall in one column, averaged (`SpectrumRun.level`). */
private fun level(bands: FloatArray, column: Int, columns: Int): Float {
    val from = column * bands.size / columns
    var upto = (column + 1) * bands.size / columns
    if (upto <= from) upto = from + 1
    if (upto > bands.size) upto = bands.size
    var sum = 0f
    for (at in from until upto) sum += bands[at]
    return sum / (upto - from)
}

private fun mix(a: Rgb, b: Rgb, t: Float) = Rgb(
    (a.r + (b.r - a.r) * t).coerceIn(0f, 1f),
    (a.g + (b.g - a.g) * t).coerceIn(0f, 1f),
    (a.b + (b.b - a.b) * t).coerceIn(0f, 1f),
)

private val WHITE = Rgb(1f, 1f, 1f)

/** Fewer columns than this read as a smudge, not a spectrum (`SpectrumRun.fewest`). */
private const val FEWEST = 3

/** The share of the pitch a bar fills; the rest is the gap. */
private const val FILL = 0.55f

/** How far a bar at full level reaches from the middle line, as a share of the cover's side. */
private const val REACH = 0.30f

/** How much of that reach is lost by the screen's edge, and the curve of the loss. */
private const val DAMP = 0.30f
private const val DAMP_BEND = 1.6f

/** The resting line, as a share of the cover's side: silence is a spectrum at rest. */
private const val REST = 0.006f
