package com.rpm.player.core.frames

import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.ColorMatrix
import androidx.core.content.res.ResourcesCompat
import com.rpm.player.core.R
import com.rpm.player.core.library.ListFrameMetrics
import com.rpm.player.core.room.Rgb

/** The face a frame's name is set in, read once for the app. A list's frame and a folder's frame both use it. */
object FrameFace {
    @Volatile private var typeface: Typeface? = null

    /** Cabin Bold where it has the letters. Android falls back to a system font for the rest, so a name such as
     * 你的专属歌单 still reads, as on the iPhone. */
    fun of(context: Context): Typeface =
        typeface ?: (ResourcesCompat.getFont(context, R.font.cabin_bold) ?: Typeface.DEFAULT_BOLD).also { typeface = it }
}

/** The ink box of [said] set at [size], from the glyphs' outlines rather than the line the font reserves. */
fun ink(paint: Paint, said: String, size: Float): ListFrameMetrics.Ink {
    paint.textSize = size
    val path = Path()
    paint.getTextPath(said, 0, said.length, 0f, 0f, path)
    val box = RectF()
    path.computeBounds(box, true)
    if (box.isEmpty) return ListFrameMetrics.Ink(0f, 0f, 0f, 0f)
    return ListFrameMetrics.Ink(left = box.left, width = box.width(), top = box.top, bottom = box.bottom)
}

/** Maps a frame drawing's grey to a mix of [border] (its black) and [text] (its white), alpha kept: a two-colour
 * ramp, which is linear, so one colour matrix. Android applies it to unpremultiplied colour, so a soft edge is read
 * by its own colour and not by how much of it is covered. */
fun ramp(border: Rgb, text: Rgb): ColorMatrix = ColorMatrix(
    floatArrayOf(
        text.r - border.r, 0f, 0f, 0f, border.r * 255f,
        0f, text.g - border.g, 0f, 0f, border.g * 255f,
        0f, 0f, text.b - border.b, 0f, border.b * 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)
