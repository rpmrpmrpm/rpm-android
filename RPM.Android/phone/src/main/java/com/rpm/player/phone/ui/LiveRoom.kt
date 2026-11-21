package com.rpm.player.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.util.LruCache
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.rpm.player.core.room.CoverColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.rpm.player.core.room.PaintedRoom
import com.rpm.player.core.room.RoomPalette
import com.rpm.player.phone.PhoneApplication
import kotlin.math.ceil
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform

/**
 * A card's room, moving: the iPhone's `RoomView(breathes: true)`. It is the room at full strength, breathing on its own
 * slow cycles rather than with the music, because a card is not the record being played. [seed] picks the pose it
 * starts from ([com.rpm.player.core.room.RoomPose]), as on iPhone, so the same list or card always opens on the same
 * picture. [zeroNanos] is when its clock started (`System.nanoTime`): leave it for a room that stands alone; give one
 * shared time to rooms that must draw the same frame, as the banner's cards do (`RoomView.Zero.shared`).
 *
 * It is a picture drawn in the page ([com.rpm.player.core.room.RoomPainter] says why), so it is clipped, rounded,
 * scrolled and faded with whatever holds it, and the glass over it frosts it.
 *
 * **It moves only while it can be seen.** The caller says when it should move ([running]: the iPhone's own condition
 * for each place). Besides that it stops while the app is not in front, while none of it is inside the window (scrolled
 * away, or on a banner card that is not in front), while the player covers the screen, and while the phone saves power
 * ([com.rpm.player.core.room.RoomPainter.saving]). A stopped room keeps its picture. It is drawn at a tenth of its
 * pixels ([share]) and stretched smooth.
 */
@Composable
fun LiveRoom(
    palette: RoomPalette,
    seed: String,
    running: Boolean,
    modifier: Modifier,
    zeroNanos: Long? = null,
    share: Float = CARD_SHARE,
    onRoom: ((PaintedRoom, Boolean) -> Unit)? = null,
) {
    val painter = (LocalContext.current.applicationContext as PhoneApplication).rooms
    val room = remember(seed, zeroNanos) { if (zeroNanos != null) painter.card(seed, zeroNanos) else painter.card(seed) }
    // Handed to whoever draws the room again somewhere else (the banner's reflection), and taken back when it goes.
    if (onRoom != null) {
        val tell by rememberUpdatedState(onRoom)
        DisposableEffect(room) {
            tell(room, true)
            onDispose { tell(room, false) }
        }
    }
    RoomPicture(room, palette, running, covers = false, foot = 1f, share, modifier)
}

/**
 * The player's room: the iPhone's `RoomView(breathes: false)` behind the now playing sheet. It is lit by the record
 * that is playing, dimmed to read the text over it, darker below [foot] (where the text starts, as a share of the
 * height from the top; below 0 leaves it to whoever sets [PaintedRoom.foot] itself) and in the corners. While it runs and
 * [covers] the screen, every other room stops.
 * [onRoom] hands the room to the caller, whose spectrum reads its run ([PaintedRoom.run]).
 */
@Composable
fun StageRoom(palette: RoomPalette, running: Boolean, covers: Boolean, foot: Float, modifier: Modifier, onRoom: (PaintedRoom) -> Unit = {}) {
    val painter = (LocalContext.current.applicationContext as PhoneApplication).rooms
    val room = remember { painter.stage("stage") }
    SideEffect { onRoom(room) }
    RoomPicture(room, palette, running, covers, foot, STAGE_SHARE, modifier)
}

@Composable
private fun RoomPicture(room: PaintedRoom, palette: RoomPalette, running: Boolean, covers: Boolean, foot: Float, share: Float, modifier: Modifier) {
    DisposableEffect(room) { onDispose { room.close() } }
    val life by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val seen = remember { Seen() }
    val resumed = life.isAtLeast(Lifecycle.State.RESUMED)
    SideEffect {
        room.palette = palette
        room.covers = covers
        if (foot >= 0f) room.foot = foot
        seen.wanted = running && resumed
        room.running = seen.wanted && seen.inWindow
    }
    Canvas(
        modifier
            .onSizeChanged { room.size(small(it.width, share), small(it.height, share)) }
            // Read on every move of the page, so it costs one rectangle; the room is told only when it changes.
            .onGloballyPositioned {
                val box = it.boundsInWindow()
                val inWindow = box.width > 0f && box.height > 0f
                if (inWindow != seen.inWindow) {
                    seen.inWindow = inWindow
                    room.running = seen.wanted && inWindow
                }
            },
    ) {
        room.frame
        val image = room.picture
        if (image == null) {
            drawRect(Color(palette.ground.r, palette.ground.g, palette.ground.b))
            return@Canvas
        }
        drawImage(
            image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.Low,
        )
    }
}

/** Whether the room is asked to move and whether any of it is in the window, kept between compositions. */
private class Seen {
    var wanted = false
    var inWindow = true
}

/** A side of the picture for a side of [pixels] drawn at [share]: at least 8, so a small square still has a room in it. */
private fun small(pixels: Int, share: Float): Int = ceil(pixels * share).toInt().coerceAtLeast(8)

/** A card's room is drawn at a tenth of its pixels, as the desktop's and the TV's are. */
const val CARD_SHARE = 0.1f

/** The player's room at an eighth, as the TV's room behind its window. */
const val STAGE_SHARE = 0.125f

/**
 * The room's colours read off [picture] (a cover, a list's own picture): the iPhone's `RoomPalette.from(tint:glow:shade:)`
 * over the sleeve's dominant, highlight and shade colours ([CoverColor.read]). Null while it is read, and for no
 * picture. The picture is read at the 48 pixel square the colours are taken from, off the main thread, once per
 * picture for the life of the app.
 */
@Composable
fun rememberRoomColours(picture: Any?, images: ImageLoader): RoomPalette? {
    val context = LocalContext.current
    var read by remember(picture) { mutableStateOf(picture?.let { COLOURS.get(it) }) }
    if (picture != null && read == null) LaunchedEffect(picture) {
        read = withContext(Dispatchers.Default) {
            val request = ImageRequest.Builder(context).data(picture).size(48).allowHardware(false).build()
            val bitmap = (images.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@withContext null
            CoverColor.read(bitmap).room
        }?.also { COLOURS.put(picture, it) }
    }
    return read
}

/** The colours read so far, by picture. */
private val COLOURS = LruCache<Any, RoomPalette>(64)

/**
 * A room's reflection under the card it fills, [x] across and filling this scope's height: the bottom of the room's
 * newest picture turned over about the card's foot ([platePx] tall), under the card's foot wash turned over with it,
 * fading out as `mirrorFade` fades the photographs' reflections ([RunOff]). It is what the iPhone's one room program
 * draws below its fold (`RoomView.fold`): the home banner's cards and an artist's band cast it. Reading
 * [PaintedRoom.frame] here redraws only this drawing when the room moves.
 */
fun DrawScope.roomReflection(room: PaintedRoom, x: Int, platePx: Float) {
    room.frame
    val picture = room.picture ?: return
    val w = size.width
    val h = size.height
    if (h <= 0f || platePx <= 0f) return
    val rows = (picture.height * (h / platePx)).roundToInt().coerceIn(1, picture.height)
    drawContext.canvas.saveLayer(Rect(x.toFloat(), 0f, x + w, h), Paint())
    withTransform({ scale(1f, -1f, pivot = Offset(0f, h / 2f)) }) {
        drawImage(
            picture,
            srcOffset = IntOffset(0, picture.height - rows),
            srcSize = IntSize(picture.width, rows),
            dstOffset = IntOffset(x, 0),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
    }
    // The foot wash as it lies on the card, read upwards from the foot.
    val wash = Array(WASH_SAMPLES) { i ->
        val u = i / (WASH_SAMPLES - 1f)
        u to Color.Black.copy(alpha = footWash(1f - u * h / platePx))
    }
    drawRect(Brush.verticalGradient(*wash, startY = 0f, endY = h), topLeft = Offset(x.toFloat(), 0f), size = Size(w, h))
    drawRect(
        Brush.verticalGradient(
            0f to Color.Black,
            0.25f to Color.Black.copy(alpha = 0.84f),
            0.5f to Color.Black.copy(alpha = 0.5f),
            0.75f to Color.Black.copy(alpha = 0.16f),
            1f to Color.Transparent,
            startY = 0f,
            endY = h,
        ),
        topLeft = Offset(x.toFloat(), 0f),
        size = Size(w, h),
        blendMode = BlendMode.DstIn,
    )
    drawContext.canvas.restore()
}

/** The card's foot wash (`HomeFeatureCard.foot`) at [at] of the card's height from its top: clear, 0.25 at four
 * tenths, 0.7 from where the writing starts to the foot. */
private fun footWash(at: Float): Float = when {
    at <= 0f -> 0f
    at < 0.4f -> at / 0.4f * 0.25f
    at < 0.68f -> 0.25f + (at - 0.4f) / 0.28f * 0.45f
    else -> 0.7f
}

private const val WASH_SAMPLES = 9
