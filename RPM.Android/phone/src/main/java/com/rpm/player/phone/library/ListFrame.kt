package com.rpm.player.phone.library

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.rpm.player.core.R
import com.rpm.player.core.frames.FrameFace
import com.rpm.player.core.frames.FramedList
import com.rpm.player.core.frames.ink
import com.rpm.player.core.frames.ramp
import com.rpm.player.core.library.ListFrameMetrics
import com.rpm.player.core.room.Rgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                typeface = FrameFace.of(context)
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

/** The list frame drawing, read once for the app. */
internal object FrameDrawing {
    @Volatile var held: ImageBitmap? = null
        private set

    suspend fun load(context: Context): ImageBitmap = held ?: withContext(Dispatchers.IO) {
        val read = BitmapFactory.decodeResource(context.resources, R.drawable.list_frame, BitmapFactory.Options().apply { inScaled = false })
        read.asImageBitmap().also { held = it }
    }
}

private fun colour(c: Rgb) = Color(c.r, c.g, c.b)
