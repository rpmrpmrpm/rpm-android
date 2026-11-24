package com.rpm.player.core.frames

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import com.rpm.player.core.library.GeneratedListLook
import com.rpm.player.core.room.CardRoom
import com.rpm.player.core.room.RoomPalette
import com.rpm.player.core.room.RoomPose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The room behind a frame's window, still, for the lists that have no picture of their own and for the folders
 * somebody made (`RoomView` behind the window on the iPhone). It is the room as a card draws it ([CardRoom]): a small
 * picture painted once, off the main thread, in the pose the iPhone's still room stands in (the pose [seed] picks,
 * [RoomPose]), and stretched smooth over the square. The iPhone's squares of a list hold still everywhere but on the
 * list's own page and the home banner, because a moving room in every row of a list is a shader per row.
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
private fun paintRoom(palette: RoomPalette, seconds: Double, w: Int, h: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    CardRoom.paint(bitmap, palette, seconds, IntArray(w * h), CardRoom.Orbits())
    return bitmap.asImageBitmap()
}

/** The room pictures painted so far. A few dozen small pictures at most: a shelf of generated lists at two sizes. */
private val ROOMS = LruCache<String, ImageBitmap>(48)
