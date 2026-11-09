package com.rpm.player.phone.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.rpm.player.phone.design.gaussian
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A photograph blurred toward its foot and mirrored under itself, fading out: the iPhone's `RunOff`
 * (RPMUI/ArtGround.swift) with `mirrorFade`, as home's banner cards and an artist's band draw it.
 *
 * **It is baked once into one bitmap, off the main thread**, as the iPhone bakes it (`RunOffPlate`): the live version
 * is several blurs redrawn on every frame, which the Titan cannot afford while a page scrolls. The bitmap is the plate
 * and the mirror, one above the other, at [SCALE] pixels to the dp. It is drawn over the sharp photograph: transparent
 * down to where the blur starts, opaque where the card is at its full blur and all through the mirror, which fades out
 * as `mirrorFade` fades it. So drawing it is one scaled bitmap, and the sharp part of the photograph stays at full
 * resolution under it.
 *
 * What is baked is the card's face as the iPhone draws it: the photograph cropped to fill the plate, darkened by
 * [HELD], under the wash at its foot ([FOOT]). The blur of ten points is let in from [blurFrom] over three tenths of
 * the plate, the blur of thirty-two from two tenths further down to the foot, as `RunOff.painted` lets them in.
 *
 * The iPhone also blurs a band round the foot, where the card and its mirror meet. It is not baked here: the card
 * reaches its full blur at its foot, and the mirror is that same blur turned over, so the two already meet without a
 * line.
 */
object RunOff {
    /** Pixels to the dp in the baked bitmap. The blurred parts hold no detail a finer bitmap would keep. */
    const val SCALE = 1f

    /** How much the picture is darkened across the whole card (`HomeFeatureCard.held` on iPhone). */
    const val HELD = 0.2f

    /** What is turned over under the card (`RunOffReflection` on iPhone). */
    enum class Reflection {
        /** The card as drawn: blurred where it is blurred, sharp where it is sharp. An artist's band, whose mirror is short. */
        AsDrawn,

        /** The card at the blur of its foot, all the way down. Home's banner, whose mirror runs behind the quick selection. */
        Blurred,
    }

    /**
     * Bakes [photo] for a card [width] by [plate] dp with a mirror [depth] dp deep under it. Call it off the main thread.
     * The result's rows from the top to [plate] × [SCALE] go over the card, and the rest under it.
     *
     * [darkened] bakes the face darkened by [HELD] and under the wash at its foot, as home's banner is. An artist's band
     * is baked without them outside night mode ([LocalNightMode]).
     *
     * [scale] is the bitmap's pixels to the dp, [SCALE] unless the caller says otherwise. An artist's room, which moves,
     * is baked again for every picture the room paints, at the room's own tenth of the pixels (the artist page's
     * `BandGround`).
     */
    fun bake(
        photo: Bitmap,
        width: Float,
        plate: Float,
        depth: Float,
        blurFrom: Float,
        reflection: Reflection,
        darkened: Boolean = true,
        scale: Float = SCALE,
    ): ImageBitmap {
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val p = (plate * scale).roundToInt().coerceAtLeast(2)
        val d = (depth * scale).roundToInt().coerceAtLeast(0)
        val face = Bitmap.createBitmap(w, p, Bitmap.Config.ARGB_8888)
        Canvas(face).apply {
            // The part of the photograph that ContentScale.Crop shows in the plate.
            val fit = max(w.toFloat() / photo.width, p.toFloat() / photo.height)
            val cropW = (w / fit).roundToInt().coerceIn(1, photo.width)
            val cropH = (p / fit).roundToInt().coerceIn(1, photo.height)
            val left = (photo.width - cropW) / 2
            val top = (photo.height - cropH) / 2
            drawBitmap(photo, Rect(left, top, left + cropW, top + cropH), Rect(0, 0, w, p), Paint(Paint.FILTER_BITMAP_FLAG))
            if (darkened) {
                drawColor(android.graphics.Color.argb((HELD * 255).roundToInt(), 0, 0, 0))
                val wash = Paint().apply {
                    shader = LinearGradient(0f, 0f, 0f, p.toFloat(), FOOT_COLOURS, FOOT_STOPS, Shader.TileMode.CLAMP)
                }
                drawRect(0f, 0f, w.toFloat(), p.toFloat(), wash)
            }
        }
        val sharp = IntArray(w * p)
        face.getPixels(sharp, 0, w, 0, 0, w, p)
        face.recycle()
        val soft = sharp.copyOf().also { gaussian(it, w, p, 10f * scale) }
        val softer = sharp.copyOf().also { gaussian(it, w, p, 32f * scale) }

        val out = IntArray(w * (p + d))
        // The card: the two blurs let in over their stretches, as alpha over the sharp photograph drawn under it.
        val secondFrom = minOf(0.95f, blurFrom + 0.2f)
        for (y in 0 until p) {
            val at = y / (p - 1f)
            val first = ramp(at, blurFrom, minOf(1f, blurFrom + 0.3f))
            val second = ramp(at, secondFrom, 1f)
            val a = first * (1 - second)
            val b = second
            val shown = a + b
            val row = y * w
            if (shown <= 0f) continue
            for (x in 0 until w) {
                out[row + x] = over(soft[row + x], a / shown, softer[row + x], b / shown, shown)
            }
        }
        // The mirror: the card turned over about its foot, fading to nothing.
        for (y in 0 until d) {
            val from = (p - 1 - y).coerceAtLeast(0)
            val fade = mirrorFade(if (d > 1) y / (d - 1f) else 1f)
            if (fade <= 0f) continue
            val row = (p + y) * w
            val source = from * w
            when (reflection) {
                Reflection.Blurred -> for (x in 0 until w) out[row + x] = withAlpha(softer[source + x], fade)
                Reflection.AsDrawn -> {
                    // The card as drawn at that row: the sharp photograph under the blurs that row lets in.
                    val at = from / (p - 1f)
                    val first = ramp(at, blurFrom, minOf(1f, blurFrom + 0.3f))
                    val second = ramp(at, secondFrom, 1f)
                    val a = first * (1 - second)
                    val b = second
                    val s = 1 - a - b
                    for (x in 0 until w) out[row + x] = withAlpha(mix3(sharp[source + x], s, soft[source + x], a, softer[source + x], b), fade)
                }
            }
        }
        return Bitmap.createBitmap(out, w, p + d, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** Clear down to [from] and opaque from [to], as a share of the plate's height. */
    private fun ramp(at: Float, from: Float, to: Float): Float =
        if (to <= from) (if (at >= to) 1f else 0f) else ((at - from) / (to - from)).coerceIn(0f, 1f)

    /**
     * `mirrorFade`'s ramp at [at] of the mirror's depth: full, 0.84, 0.5, 0.16, nothing, at each quarter, flat at both
     * ends, so neither end shows as a line.
     */
    private fun mirrorFade(at: Float): Float {
        val stops = MIRROR_STOPS
        val scaled = (at.coerceIn(0f, 1f)) * (stops.size - 1)
        val i = scaled.toInt().coerceAtMost(stops.size - 2)
        val t = scaled - i
        return stops[i] + (stops[i + 1] - stops[i]) * t
    }

    /** Two opaque colours mixed by [wa] and [wb] (which add to one), at [alpha], unpremultiplied. */
    private fun over(ca: Int, wa: Float, cb: Int, wb: Float, alpha: Float): Int {
        val r = ((ca shr 16 and 0xFF) * wa + (cb shr 16 and 0xFF) * wb).roundToInt()
        val g = ((ca shr 8 and 0xFF) * wa + (cb shr 8 and 0xFF) * wb).roundToInt()
        val b = ((ca and 0xFF) * wa + (cb and 0xFF) * wb).roundToInt()
        return ((alpha * 255).roundToInt().coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    private fun mix3(ca: Int, wa: Float, cb: Int, wb: Float, cc: Int, wc: Float): Int {
        val r = ((ca shr 16 and 0xFF) * wa + (cb shr 16 and 0xFF) * wb + (cc shr 16 and 0xFF) * wc).roundToInt()
        val g = ((ca shr 8 and 0xFF) * wa + (cb shr 8 and 0xFF) * wb + (cc shr 8 and 0xFF) * wc).roundToInt()
        val b = ((ca and 0xFF) * wa + (cb and 0xFF) * wb + (cc and 0xFF) * wc).roundToInt()
        return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    private fun withAlpha(colour: Int, alpha: Float): Int =
        ((alpha * 255).roundToInt().coerceIn(0, 255) shl 24) or (colour and 0xFFFFFF)

    private val MIRROR_STOPS = floatArrayOf(1f, 0.84f, 0.5f, 0.16f, 0f)

    /**
     * The wash at the card's foot (`HomeFeatureCard.foot` on iPhone): level from where the writing starts to the foot,
     * so the mirror continues the same shade and the two do not meet in a dark stripe.
     */
    private val FOOT_STOPS = floatArrayOf(0f, 0.4f, 0.68f, 1f)
    private val FOOT_COLOURS = intArrayOf(
        android.graphics.Color.argb(0, 0, 0, 0),
        android.graphics.Color.argb((0.25f * 255).roundToInt(), 0, 0, 0),
        android.graphics.Color.argb((0.7f * 255).roundToInt(), 0, 0, 0),
        android.graphics.Color.argb((0.7f * 255).roundToInt(), 0, 0, 0),
    )
}
