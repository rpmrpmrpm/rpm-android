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
import com.rpm.player.core.library.FolderFrameMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The frame on the cover of one of the app's own playlist folders, lettered with the folder's name (the iPhone's
 * `FolderFrame`, RPMSharedUI/FolderFrame.swift).
 *
 * It is a different drawing from a list's frame ([ListFrameOverlay]), so a folder does not look like one more list:
 * a black square with a folder-shaped window cut out of it (a tab at the top right), and a bar under the window where
 * the folder's name is set in the middle on one line ([FolderFrameMetrics]). The folder's photograph shows through the window
 * ([PlaylistPhotos.folder][com.rpm.player.core.library.PlaylistPhotos.folder]); the caller draws it under this layer.
 *
 * The drawing is inked in the folder's two colours as a list's frame is ([ramp]): its black in the border colour, and
 * the name in the lettering colour, in Cabin Bold with a system font for letters Cabin does not have. The window stays
 * transparent. The drawing is read once for the whole app, off the main thread; until then nothing is drawn.
 */
@Composable
fun FolderFrameOverlay(framed: FramedList, modifier: Modifier) {
    val context = LocalContext.current
    var drawing by remember { mutableStateOf(FolderDrawing.held) }
    if (drawing == null) LaunchedEffect(Unit) { drawing = FolderDrawing.load(context) }
    val shown = drawing ?: return
    val ramp = remember(framed.look) { ColorFilter.colorMatrix(ramp(framed.look.border, framed.look.text)) }
    Canvas(
        modifier.drawWithCache {
            // Set once for each size the square is drawn at: the name's size and place are measured here, not on
            // every frame.
            val edge = size.width
            val text = framed.look.text
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = FrameDrawing.face(context)
                color = Color(text.r, text.g, text.b).toArgb()
            }
            val set = FolderFrameMetrics.lettering(framed.name, edge) { said, at -> ink(paint, said, at) }
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
                    paint.textSize = set.size
                    canvas.nativeCanvas.drawText(framed.name, set.x, set.baseline, paint)
                }
            }
        },
    ) {}
}

/** The folder frame drawing, read once for the app. */
private object FolderDrawing {
    @Volatile var held: ImageBitmap? = null
        private set

    suspend fun load(context: Context): ImageBitmap = held ?: withContext(Dispatchers.IO) {
        val read = BitmapFactory.decodeResource(context.resources, R.drawable.folder_frame, BitmapFactory.Options().apply { inScaled = false })
        read.asImageBitmap().also { held = it }
    }
}
