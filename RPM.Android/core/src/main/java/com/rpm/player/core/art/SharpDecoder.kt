package com.rpm.player.core.art

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import coil3.size.Precision
import coil3.size.Size
import coil3.size.isOriginal
import coil3.size.pxOrElse
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Marks a picture that the app's own fetchers return (covers, discs, list pictures, station logos) for [SharpDecoder].
 * Only these pictures get the mark: they are drawn small from a larger copy, and none of them has an EXIF rotation,
 * which this decoder does not read.
 */
object SharpScaled : ImageSource.Metadata()

/**
 * Decodes a [SharpScaled] picture to the requested size. It first reduces by whole halvings in the decoder (which
 * are averaged, not skipped), then does the rest of the reduction with [Shrink]. The image loader's own decoder
 * does the last step bilinearly, which makes small covers and logos look jagged.
 *
 * A picture that is already at or under the requested size is decoded as it is. It is enlarged (bilinearly) only
 * when the request asks for the exact size, as the image loader's decoder does.
 */
class SharpDecoder(private val source: ImageSource, private val options: Options) : Decoder {
    override suspend fun decode(): DecodeResult = GATE.withPermit { runInterruptible { decodeNow() } }

    private fun decodeNow(): DecodeResult {
        val bytes = source.source().readByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) throw IOException("not a picture")

        val multiplier = multiplier(srcW, srcH)
        val toW = (srcW * multiplier).roundToInt().coerceAtLeast(1)
        val toH = (srcH * multiplier).roundToInt().coerceAtLeast(1)
        val shrinking = multiplier < 1.0 && (toW < srcW || toH < srcH)

        val sample = if (shrinking) DecodeUtils.calculateInSampleSize(srcW, srcH, toW, toH, options.scale) else 1
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: throw IOException("cannot decode")

        val drawn = when {
            shrinking && (decoded.width > toW || decoded.height > toH) -> shrunk(decoded, minOf(toW, decoded.width), minOf(toH, decoded.height))
            multiplier > 1.0 && options.precision == Precision.EXACT -> grown(decoded, toW, toH)
            else -> decoded
        }
        return DecodeResult(image = forDrawing(drawn).asImage(), isSampled = drawn.width < srcW || drawn.height < srcH)
    }

    /** How much the request scales the picture: 1 for its own size, less than 1 to shrink it. */
    private fun multiplier(srcW: Int, srcH: Int): Double {
        val size = options.size
        if (size.isOriginal) return 1.0
        val w = size.width.pxOrElse { -1 }
        val h = size.height.pxOrElse { -1 }
        return when {
            w > 0 && h > 0 -> DecodeUtils.computeSizeMultiplier(srcW, srcH, w, h, options.scale, Size.ORIGINAL)
            w > 0 -> w.toDouble() / srcW
            h > 0 -> h.toDouble() / srcH
            else -> 1.0
        }
    }

    private fun shrunk(bitmap: Bitmap, toW: Int, toH: Int): Bitmap {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val out = Shrink.argb(pixels, bitmap.width, bitmap.height, toW, toH)
        val wasOpaque = !bitmap.hasAlpha()
        bitmap.recycle()
        return Bitmap.createBitmap(out, toW, toH, Bitmap.Config.ARGB_8888).apply { setHasAlpha(!wasOpaque) }
    }

    private fun grown(bitmap: Bitmap, toW: Int, toH: Int): Bitmap {
        val out = Bitmap.createScaledBitmap(bitmap, toW, toH, true)
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    /** The bitmap in the form the request asked for: a hardware bitmap if the request allows one, as the loader's
     * own decoder gives, so drawing it does not upload it again. */
    private fun forDrawing(bitmap: Bitmap): Bitmap {
        if (options.bitmapConfig != Bitmap.Config.HARDWARE) return bitmap
        val hardware = bitmap.copy(Bitmap.Config.HARDWARE, false) ?: return bitmap
        bitmap.recycle()
        return hardware
    }

    /** Takes [SharpScaled] pictures that are not SVG. Everything else goes to the next decoder. Add it after the
     * SVG decoder. */
    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            if (result.source.metadata !== SharpScaled) return null
            if (result.mimeType?.contains("svg") == true) return null
            return SharpDecoder(result.source, options)
        }
    }

    private companion object {
        /** At most this many pictures decode at once. This is the same limit as the image loader's own decoder. */
        val GATE = Semaphore(4)
    }
}
