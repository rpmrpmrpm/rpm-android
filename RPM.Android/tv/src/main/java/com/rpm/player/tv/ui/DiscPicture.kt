package com.rpm.player.tv.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import android.content.Context
import com.rpm.player.core.library.BlankDisc
import com.rpm.player.core.library.DiscArt
import com.rpm.player.core.library.DiscStandIn
import com.rpm.player.core.library.DiscScan

/**
 * A disc picture ready to draw, and whether it is the whole record or only its label ([DiscScan]).
 * [of] reads the picture and is the only way to make one, so every disc picture has had that question
 * answered (as with the Swift head's `ScannedDisc`).
 */
class ScannedDisc private constructor(val picture: ImageBitmap, val coversRecord: Boolean) {
    companion object {
        /** Reads [bitmap]. A bitmap whose pixels cannot be read (a hardware bitmap), or that has no
         * transparency, counts as a whole record. */
        fun of(bitmap: Bitmap): ScannedDisc {
            val covers = bitmap.config == Bitmap.Config.HARDWARE || !bitmap.hasAlpha() ||
                DiscScan.coversRecord(bitmap.width, bitmap.height) { x, y -> bitmap.getPixel(x, y) ushr 24 }
            return ScannedDisc(bitmap.asImageBitmap(), covers)
        }

        @Volatile
        private var blank: ScannedDisc? = null

        /**
         * The plain CD ([BlankDisc]) when [choice] is the CD, for a record with no disc picture of its own; null when it
         * is the vinyl. Read once and kept.
         */
        fun standIn(context: Context, choice: DiscStandIn): ScannedDisc? {
            if (choice != DiscStandIn.Cd) return null
            blank?.let { return it }
            return BlankDisc.bitmap(context)?.let(::of)?.also { blank = it }
        }
    }
}

/**
 * The disc picture for [art], fetched at [edge] pixels and read. It is null when there is no disc,
 * when it is not fetched yet, or when the fetch failed. The answer is kept with the [DiscArt] it
 * belongs to and is given only while that is still the [DiscArt] requested. A fetch for the previous
 * record that finishes late therefore cannot be drawn on this record, in whichever order the two
 * arrive. The next track of the same record requests the same [DiscArt] and keeps the picture it has.
 */
@Composable
fun rememberScannedDisc(art: DiscArt?, images: ImageLoader, edge: Int): ScannedDisc? {
    val context = LocalContext.current
    var fetched by remember { mutableStateOf<Pair<DiscArt, ScannedDisc>?>(null) }
    LaunchedEffect(art, edge) {
        if (art == null || edge <= 0) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(art)
            .size(edge)
            // In software: whether it is a whole record is read from its pixels, which a hardware bitmap does not give.
            .allowHardware(false)
            .build()
        val bitmap = (images.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
        fetched = art to ScannedDisc.of(bitmap)
    }
    return fetched?.takeIf { it.first == art }?.second
}
