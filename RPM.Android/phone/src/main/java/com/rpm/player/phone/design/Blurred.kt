package com.rpm.player.phone.design

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.size.pxOrElse
import coil3.transform.Transformation
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A picture drawn blurred behind a page: the iPhone's `.blur(radius:)` on a background picture, for the record's
 * colours behind a list or home's banner.
 *
 * The blur is worked out once, when the picture loads, not on every frame. A `Modifier.blur` is a render effect
 * the GPU runs again each time anything over it moves, and on the Titan (Helio P60, 1440 px) two such
 * backgrounds cost about 30 ms of every frame while a list scrolled. Here the picture is cropped to the shape it
 * fills, shrunk to [EDGE] pixels and blurred in that small size off the main thread. The loader keeps the result,
 * and drawing it is only a scaled bitmap. A blur this wide holds no detail a small picture loses, and the
 * bilinear filter that scales it up leaves no steps.
 *
 * @param sigma the standard deviation of the blur at the size the picture is drawn, as the other blurs state
 * theirs (see [radiusFor]).
 * @param colorFilter applied as the blurred picture is drawn, for a ground that changes its colours (see [ArtGround]).
 */
@Composable
fun BlurredPicture(picture: Any, images: ImageLoader, sigma: Dp, modifier: Modifier = Modifier, colorFilter: ColorFilter? = null) {
    val sigmaPx = with(LocalDensity.current) { sigma.toPx() }
    // Measured first, so the request has its size and a blur already made is drawn on the first frame ([rememberPicture]).
    BoxWithConstraints(modifier) {
        AsyncImage(
            model = rememberPicture(picture, maxWidth, maxHeight, remember(sigmaPx) { { transformations(Blur(sigmaPx)) } }),
            contentDescription = null,
            imageLoader = images,
            // The transformation has already cropped to the drawn shape.
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
            colorFilter = colorFilter,
        )
    }
}

/** Crops to the requested shape, shrinks to [EDGE] on the longer side and blurs by [sigma], given at the requested size. */
private class Blur(private val sigma: Float) : Transformation() {
    override val cacheKey = "rpm.blur($sigma)"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val wantW = size.width.pxOrElse { input.width }.coerceAtLeast(1)
        val wantH = size.height.pxOrElse { input.height }.coerceAtLeast(1)
        // The part of the picture ContentScale.Crop would show in a wantW × wantH box.
        val fit = max(wantW.toFloat() / input.width, wantH.toFloat() / input.height)
        val cropW = (wantW / fit).roundToInt().coerceIn(1, input.width)
        val cropH = (wantH / fit).roundToInt().coerceIn(1, input.height)
        val left = (input.width - cropW) / 2
        val top = (input.height - cropH) / 2
        val shrink = EDGE.toFloat() / max(wantW, wantH)
        val outW = (wantW * shrink).roundToInt().coerceAtLeast(1)
        val outH = (wantH * shrink).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(input, Rect(left, top, left + cropW, top + cropH), Rect(0, 0, outW, outH), Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(outW * outH)
        small.getPixels(pixels, 0, outW, 0, 0, outW, outH)
        gaussian(pixels, outW, outH, sigma * shrink)
        small.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        return small
    }
}

/** The longer side of a blurred picture, in pixels. */
private const val EDGE = 128

/**
 * A Gaussian blur of [sigma] pixels approximated by three box blurs (each pass horizontal then vertical), the
 * edges clamped as `BlurredEdgeTreatment.Rectangle` clamps them. Box widths from Kovesi's "Fast almost-Gaussian
 * filtering".
 */
internal fun gaussian(pixels: IntArray, w: Int, h: Int, sigma: Float) {
    if (sigma < 0.5f) return
    val passes = 3
    val ideal = sqrt(12f * sigma * sigma / passes + 1)
    var lower = ideal.toInt()
    if (lower % 2 == 0) lower--
    val upper = lower + 2
    val m = ((12f * sigma * sigma - passes * lower * lower - 4f * passes * lower - 3f * passes) / (-4f * lower - 4)).roundToInt()
    val scratch = IntArray(pixels.size)
    for (i in 0 until passes) {
        val r = ((if (i < m) lower else upper) - 1) / 2
        boxRows(pixels, scratch, w, h, r)
        boxColumns(scratch, pixels, w, h, r)
    }
}

private fun boxRows(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
    val span = 2 * r + 1
    for (y in 0 until h) {
        val row = y * w
        var a = 0; var rr = 0; var g = 0; var b = 0
        for (k in -r..r) {
            val p = src[row + k.coerceIn(0, w - 1)]
            a += p ushr 24; rr += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
        }
        for (x in 0 until w) {
            dst[row + x] = ((a / span) shl 24) or ((rr / span) shl 16) or ((g / span) shl 8) or (b / span)
            val out = src[row + (x - r).coerceIn(0, w - 1)]
            val inn = src[row + (x + r + 1).coerceIn(0, w - 1)]
            a += (inn ushr 24) - (out ushr 24)
            rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            b += (inn and 0xFF) - (out and 0xFF)
        }
    }
}

private fun boxColumns(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
    val span = 2 * r + 1
    for (x in 0 until w) {
        var a = 0; var rr = 0; var g = 0; var b = 0
        for (k in -r..r) {
            val p = src[k.coerceIn(0, h - 1) * w + x]
            a += p ushr 24; rr += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
        }
        for (y in 0 until h) {
            dst[y * w + x] = ((a / span) shl 24) or ((rr / span) shl 16) or ((g / span) shl 8) or (b / span)
            val out = src[(y - r).coerceIn(0, h - 1) * w + x]
            val inn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
            a += (inn ushr 24) - (out ushr 24)
            rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            b += (inn and 0xFF) - (out and 0xFF)
        }
    }
}
