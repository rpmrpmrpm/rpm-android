package com.rpm.player.phone.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.content.res.ResourcesCompat
import com.rpm.player.core.R
import com.rpm.player.core.library.FolderFace
import com.rpm.player.core.library.GeneratedListLook
import com.rpm.player.core.library.ListFrameMetrics
import com.rpm.player.core.library.TrackList
import com.rpm.player.core.room.CardRoom
import com.rpm.player.core.room.Rgb
import com.rpm.player.core.room.RoomPalette
import com.rpm.player.core.room.RoomPose
import com.rpm.player.phone.ui.LiveRoom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A generated list as the squares draw it: its name and the colours of its frame. It matches the iPhone's
 * `FramedList` (RPMSharedUI/FramedList.swift). The playlists view, its covers and a list's own page all use it, so a
 * list never gets two different covers.
 *
 * @param name the list's name in the reader's language, lettered down the frame's band.
 */
@Immutable
data class FramedList(val name: String, val look: GeneratedListLook.Look) {
    companion object {
        /** The frame for [list], or null for a list somebody made, or a kind this build has no colours for. */
        fun of(list: TrackList?, name: String): FramedList? = list?.let(GeneratedListLook::of)?.let { FramedList(name, it) }

        /**
         * The frame of one of the app's own folders, lettered with the folder's name, or null for a folder somebody
         * made (`FramedList.folder` on the iPhone). A photograph goes behind its window
         * ([PlaylistPhotos.folder][com.rpm.player.core.library.PlaylistPhotos.folder]), so the folder looks like a
         * list of its own.
         *
         * One mood's folder wears the colours of that mood's list of the whole collection, so the folder and what is
         * in it look alike. The folder of the decades wears the 1980s'. The folder of all the moods wears the app's
         * own colours ([GeneratedListLook.app]).
         */
        fun folder(face: FolderFace, name: String): FramedList? = when (face) {
            FolderFace.Plain -> null
            FolderFace.Moods -> GeneratedListLook.app
            FolderFace.Decades -> GeneratedListLook.of("Decade", null, 1980)
            is FolderFace.Mood -> GeneratedListLook.of(face.kind, null, 0)
        }?.let { FramedList(name, it) }
    }
}

/**
 * The frame on the cover of every generated list, lettered with its name, drawn over whatever is behind its window.
 * It matches the iPhone's `PlaylistFrame` and `listFrame` (RPMSharedUI/PlaylistFrame.swift) and the desktop's
 * `CoverOverlay`: one drawing, black everywhere but a window in the middle, with the app's mark in the corner and an
 * empty band down the right. Its black and white are mapped to the list's two inks, and the name is set in the band.
 *
 * **The window stays clear.** The caller draws what is behind it: the list's photograph, or the room for the five
 * lists that have none. The frame is a layer of its own over that, as on the iPhone.
 *
 * The drawing is read once for the whole app, off the main thread. Until it has been read, nothing is drawn.
 */
@Composable
fun ListFrameOverlay(framed: FramedList, modifier: Modifier) {
    val context = LocalContext.current
    var drawing by remember { mutableStateOf(FrameDrawing.held) }
    if (drawing == null) LaunchedEffect(Unit) { drawing = FrameDrawing.load(context) }
    val shown = drawing ?: return
    val ramp = remember(framed.look) { ColorFilter.colorMatrix(ramp(framed.look.border, framed.look.text)) }
    Canvas(
        modifier.drawWithCache {
            // Set once for each size the square is drawn at: the name's size and place are measured here, not
            // on every frame.
            val edge = size.width
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = FrameDrawing.face(context)
                color = colour(framed.look.text).toArgb()
            }
            val set = ListFrameMetrics.lettering(framed.name, edge) { said, at -> ink(paint, said, at) }
            val (x, y) = ListFrameMetrics.anchor(edge)
            onDrawBehind {
                drawImage(
                    shown,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(shown.width, shown.height),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    colorFilter = ramp,
                    filterQuality = FilterQuality.Medium,
                )
                if (set != null) drawIntoCanvas { canvas ->
                    // Turned a quarter clockwise, so the tops of the letters point to the outer edge and the name
                    // reads top to bottom, as the drawing's own lettering does.
                    val native = canvas.nativeCanvas
                    paint.textSize = set.size
                    native.save()
                    native.translate(x, y)
                    native.rotate(90f)
                    native.drawText(framed.name, set.alongBand, set.acrossBand, paint)
                    native.restore()
                }
            }
        },
    ) {}
}

/**
 * The room behind a list's window, for the five lists that have no picture of their own (`RoomView` behind the
 * window on the iPhone), still. It is the room as a card draws it ([CardRoom]): a small picture painted once, off the
 * main thread, in the pose the iPhone's still room stands in (the pose [seed] picks, [RoomPose]), and stretched smooth
 * over the square. The iPhone's squares of a list hold still everywhere but on the list's own page and the home
 * banner, because a moving room in every row of a list is a shader per row; those two places draw a [LiveRoom].
 */
@Composable
fun ListRoom(look: GeneratedListLook.Look, seed: String, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val (w, h) = CardRoom.small(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), fewest = 32)
        val palette = look.room
        val key = "$palette:$seed:$w:$h"
        var picture by remember(key) { mutableStateOf(ROOMS.get(key)) }
        if (picture == null) LaunchedEffect(key) {
            picture = withContext(Dispatchers.Default) { paintRoom(palette, RoomPose.offset(seed), w, h) }.also { ROOMS.put(key, it) }
        }
        picture?.let { shown ->
            Canvas(Modifier.matchParentSize()) {
                drawImage(shown, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
            }
        }
    }
}

/** One still room picture of [w] by [h] in [palette], [seconds] along its drift. */
internal fun paintRoom(palette: RoomPalette, seconds: Double, w: Int, h: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    CardRoom.paint(bitmap, palette, seconds, IntArray(w * h), CardRoom.Orbits())
    return bitmap.asImageBitmap()
}

/** The room pictures painted so far. A few dozen small pictures at most: a shelf of generated lists at two sizes. */
private val ROOMS = LruCache<String, ImageBitmap>(48)

/** The frame drawing and the face its name is set in, each read once for the app. */
private object FrameDrawing {
    @Volatile var held: ImageBitmap? = null
        private set

    @Volatile private var typeface: Typeface? = null

    suspend fun load(context: Context): ImageBitmap = held ?: withContext(Dispatchers.IO) {
        val read = BitmapFactory.decodeResource(context.resources, R.drawable.list_frame, BitmapFactory.Options().apply { inScaled = false })
        read.asImageBitmap().also { held = it }
    }

    /** Cabin Bold where it has the letters. Android falls back to a system font for the rest, so a name such as
     * 你的专属歌单 still reads, as on the iPhone. */
    fun face(context: Context): Typeface = typeface ?: (ResourcesCompat.getFont(context, R.font.cabin_bold) ?: Typeface.DEFAULT_BOLD).also { typeface = it }
}

/** The ink box of [said] set at [size], from the glyphs' outlines rather than the line the font reserves. */
private fun ink(paint: Paint, said: String, size: Float): ListFrameMetrics.Ink {
    paint.textSize = size
    val path = Path()
    paint.getTextPath(said, 0, said.length, 0f, 0f, path)
    val box = RectF()
    path.computeBounds(box, true)
    if (box.isEmpty) return ListFrameMetrics.Ink(0f, 0f, 0f, 0f)
    return ListFrameMetrics.Ink(left = box.left, width = box.width(), top = box.top, bottom = box.bottom)
}

/** Maps the drawing's grey to a mix of [border] (its black) and [text] (its white), alpha kept: a two-colour ramp,
 * which is linear, so one colour matrix. Android applies it to unpremultiplied colour, so a soft edge is read by its
 * own colour and not by how much of it is covered. */
private fun ramp(border: Rgb, text: Rgb): ColorMatrix = ColorMatrix(
    floatArrayOf(
        text.r - border.r, 0f, 0f, 0f, border.r * 255f,
        0f, text.g - border.g, 0f, 0f, border.g * 255f,
        0f, 0f, text.b - border.b, 0f, border.b * 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

private fun colour(c: Rgb) = Color(c.r, c.g, c.b)
