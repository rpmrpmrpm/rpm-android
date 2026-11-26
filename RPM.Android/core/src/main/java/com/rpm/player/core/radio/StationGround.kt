package com.rpm.player.core.radio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import coil3.size.Size
import coil3.transform.Transformation
import com.rpm.player.core.room.CoverColor
import com.rpm.player.core.room.Rgb
import com.rpm.player.core.room.RpmPalette
import com.rpm.player.core.graphics.boxBlur
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The picture a station's logo stands on, for a logo that was published with nothing behind it. It
 * follows the desktop's `TransparentMark` and the `SoftFigure` figure it draws (`SoftFigure.Tile`),
 * through the iOS app's `StationGround`. The numbers are the same, so a station's square looks the same
 * in every app.
 *
 * A mark on nothing looks like a hole in a dark well, not like a badge. The ground is taken AWAY from
 * the mark's tone: the hues are the logo's, and the value is pushed the other way, dark under a light
 * mark and pale under a dark one. A ground in the mark's own tone would hide the mark.
 */
object StationGround {
    /**
     * Whether [picture] is a mark standing on nothing, rather than a tile with rounded corners. It reads
     * the outer ring, inset by one pixel. A mark is see-through nearly all the way round it. A rounded
     * tile (nearly every apple-touch icon) loses about a tenth of the ring to its corners.
     */
    fun standsAlone(picture: Bitmap): Boolean {
        if (picture.width < 3 || picture.height < 3 || !picture.hasAlpha()) return false
        val left = 1
        val top = 1
        val right = picture.width - 2
        val bottom = picture.height - 2
        var empty = 0
        var seen = 0
        for (i in 0 until BORDER_SAMPLES) {
            val t = i.toDouble() / BORDER_SAMPLES
            val across = left + (t * (right - left)).toInt()
            val down = top + (t * (bottom - top)).toInt()
            for ((x, y) in listOf(across to top, across to bottom, left to down, right to down)) {
                seen++
                if ((picture.getPixel(x, y) ushr 24) < SEE) empty++
            }
        }
        return seen > 0 && empty.toDouble() / seen >= MOSTLY_EMPTY
    }

    /** What the mark is drawn in: the mean of the pixels that are really there. Null when there is too
     * little of a mark to judge. */
    fun ink(picture: Bitmap): Rgb? {
        val pixels = IntArray(picture.width * picture.height)
        picture.getPixels(pixels, 0, picture.width, 0, 0, picture.width, picture.height)
        var red = 0L
        var green = 0L
        var blue = 0L
        var drawn = 0
        for (p in pixels) {
            if ((p ushr 24) < SOLID) continue
            red += (p shr 16) and 0xFF
            green += (p shr 8) and 0xFF
            blue += p and 0xFF
            drawn++
        }
        if (drawn < ENOUGH) return null
        return Rgb.of((red / drawn).toInt(), (green / drawn).toInt(), (blue / drawn).toInt())
    }

    /**
     * The ground [mark] stands on: its own hues, pushed away from its value (TransparentMark.Ground).
     * [identity] is what the arrangement is drawn from, so a station is the same square every time: its
     * uuid, as on the phone.
     */
    fun ground(mark: Bitmap, identity: String): Bitmap? {
        val seed = identity.ifEmpty { "rpm" }
        val ink = ink(mark) ?: return tile(ringColours(seed), seed)
        val square = square(mark)
        val light = isLight(ink)
        val colours = listOfNotNull(CoverColor.dominant(square), CoverColor.highlight(square), average(square))
            .map { if (light) dim(it, UNDER_A_LIGHT_MARK) else lift(it, UNDER_A_DARK_MARK) }
        return tile(colours.ifEmpty { ringColours(seed) }, seed)
    }

    /**
     * The figure itself (SoftFigure.Tile): six soft blobs in these colours over the darkest of them, one
     * shaft of light across, and the whole picture blurred. It uses the desktop's seed and roll, so the
     * arrangement for a name is the same as on the desktop. Drawn at [TILE] pixels, not the desktop's 320:
     * the blur is a seventh of the edge, so the smaller picture looks the same, and every surface here
     * scales it.
     */
    fun tile(colours: List<Rgb>, identity: String): Bitmap? {
        if (colours.isEmpty()) return null
        val edge = TILE.toFloat()
        val ink = palette(colours, identity)
        val roll = Roll(seed(identity) xor (VARIANT * 0x9E3779B9L).toInt())
        val out = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(argb(ink.minBy(::brightness), 1f))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(BLOBS) { at ->
            val colour = ink[at % ink.size]
            val x = roll.between(-0.15, 1.15).toFloat() * edge
            val y = roll.between(-0.15, 1.15).toFloat() * edge
            val radius = roll.between(0.45, 0.95).toFloat() * edge
            paint.shader = RadialGradient(x, y, radius, argb(colour, 1f), argb(colour, 0f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, edge, edge, paint)
        }
        val light = ink.maxBy(::brightness)
        val fromX = roll.between(0.1, 0.9).toFloat() * edge
        val toX = roll.between(0.1, 0.9).toFloat() * edge
        val middle = roll.between(0.3, 0.7).toFloat()
        paint.shader = LinearGradient(
            fromX, 0f, toX, edge,
            intArrayOf(argb(light, 0f), argb(light, SHAFT_ALPHA), argb(light, 0f)),
            floatArrayOf(0f, middle, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, edge, edge, paint)
        // Three box passes of radius r are a Gaussian of sigma about r. The desktop blurs by a seventh of
        // the edge.
        boxBlur(out, (TILE / BLUR_DIVISOR).roundToInt())
        return out
    }

    /** Whether [ink] is light enough that what goes behind it should be dark (CoverColor.PrefersBlackInk). */
    fun isLight(ink: Rgb): Boolean = brightness(ink) > 0.5

    /** FNV-1a over the name's lowercased UTF-16 units (SoftFigure.Seed). */
    fun seed(name: String): Int {
        var hash = 2166136261L.toInt()
        for (c in name.lowercase()) hash = (hash xor c.code) * 16777619
        return hash
    }

    /** xorshift32 (SoftFigure.Roll): lays a picture out the same way every time from one number. */
    class Roll(seed: Int) {
        private var state = if (seed == 0) 0x9E3779B9L.toInt() else seed

        fun between(low: Double, high: Double): Double {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return low + (high - low) * ((state.toLong() and 0xFFFFFFFFL).toDouble() / 4294967295.0)
        }
    }

    /** Each colour snapped to the palette and lit, plus the palette's neighbour of the first. Which side
     * the neighbour is on is decided by the name (SoftFigure.Palette). */
    private fun palette(colours: List<Rgb>, identity: String): List<Rgb> {
        val ink = colours.take(3).map { dim(RpmPalette.nearest(it) ?: it, LIT) }.toMutableList()
        val ring = RpmPalette.RING
        val snapped = RpmPalette.nearest(colours.first())
        if (snapped != null) {
            val step = if ((seed(identity) and 1) == 0) 1 else ring.size - 1
            ink += dim(ring[(ring.indexOf(snapped) + step) % ring.size], LIT)
        }
        return ink
    }

    /** Three neighbours on the palette's ring, chosen by where the name lands on it, for a mark that is
     * too small to read (SoftFigure.RingColours). */
    private fun ringColours(identity: String): List<Rgb> {
        val ring = RpmPalette.RING
        val at = ((seed(identity).toLong() and 0xFFFFFFFFL) % ring.size).toInt()
        return listOf(ring[at], ring[(at + 1) % ring.size], ring[(at + ring.size - 1) % ring.size])
    }

    /** The picture scaled to [SQUARE] by [SQUARE] pixels on black, as RGBA bytes. Transparent parts
     * become near black, which [CoverColor] skips. */
    private fun square(picture: Bitmap): ByteArray {
        val small = Bitmap.createBitmap(SQUARE, SQUARE, Bitmap.Config.ARGB_8888)
        Canvas(small).apply {
            drawColor(android.graphics.Color.BLACK)
            drawBitmap(picture, null, Rect(0, 0, SQUARE, SQUARE), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val pixels = IntArray(SQUARE * SQUARE)
        small.getPixels(pixels, 0, SQUARE, 0, 0, SQUARE, SQUARE)
        small.recycle()
        val rgba = ByteArray(pixels.size * 4)
        for ((i, p) in pixels.withIndex()) {
            rgba[i * 4] = (p shr 16).toByte()
            rgba[i * 4 + 1] = (p shr 8).toByte()
            rgba[i * 4 + 2] = p.toByte()
            rgba[i * 4 + 3] = (p ushr 24).toByte()
        }
        return rgba
    }

    /** The mean of the square, the transparent part included as black (CoverColor.Average). */
    private fun average(rgba: ByteArray): Rgb? {
        if (rgba.size < 4) return null
        var red = 0
        var green = 0
        var blue = 0
        var i = 0
        while (i <= rgba.size - 4) {
            red += rgba[i].toInt() and 0xFF
            green += rgba[i + 1].toInt() and 0xFF
            blue += rgba[i + 2].toInt() and 0xFF
            i += 4
        }
        val count = rgba.size / 4
        return Rgb.of(red / count, green / count, blue / count)
    }

    private fun dim(c: Rgb, amount: Double) = Rgb(clamp(c.r * amount), clamp(c.g * amount), clamp(c.b * amount))

    private fun lift(c: Rgb, amount: Double) = Rgb(clamp(c.r + (1 - c.r) * amount), clamp(c.g + (1 - c.g) * amount), clamp(c.b + (1 - c.b) * amount))

    private fun clamp(v: Double): Float = v.coerceIn(0.0, 1.0).toFloat()

    private fun brightness(c: Rgb): Double = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b

    private fun argb(c: Rgb, alpha: Float): Int = android.graphics.Color.argb(alpha, c.r, c.g, c.b)

    private const val BORDER_SAMPLES = 64
    private const val SEE = 24
    private const val MOSTLY_EMPTY = 0.5
    private const val SOLID = 250
    private const val ENOUGH = 64
    private const val UNDER_A_LIGHT_MARK = 0.22
    private const val UNDER_A_DARK_MARK = 0.74
    private const val BLOBS = 6
    private const val BLUR_DIVISOR = 7f
    private const val LIT = 0.72
    private const val SHAFT_ALPHA = 0x5A / 255f

    /** SoftFigure.Tile's variant, which is part of the seed. */
    private const val VARIANT = 2L
    private const val TILE = 160
    private const val SQUARE = 48
}

/**
 * A station's logo dressed as one square of [edge] pixels that every surface draws filling its well: the logo stands
 * on its made ground ([StationGround.ground]), fitted whole and held [inset] of the edge away from each side. Every
 * logo gets the ground, a mark with nothing behind it and a full square picture alike, so every station's square is
 * the same kind of thing (`StationDress` on iPhone).
 *
 * Only where no ground can be made is the logo drawn on its own: a nearly square picture (at most 1.1 to 1) fills the
 * square and is cropped by it, and any other picture is fitted whole and held [inset] away from each side, on the
 * well's own dark.
 *
 * [identity] is the station's uuid, which the ground's arrangement is drawn from.
 */
class StationBadge(private val identity: String, private val inset: Float, private val edge: Int) : Transformation() {
    override val cacheKey: String = "badge-grounded:$identity:$inset:$edge"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val out = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val whole = RectF(0f, 0f, edge.toFloat(), edge.toFloat())
        val ground = StationGround.ground(input, identity)
        if (ground != null) {
            canvas.drawBitmap(ground, middle(ground), whole, paint)
            ground.recycle()
        }
        if (ground == null && mayBeCropped(input.width, input.height)) {
            // Filled: the middle square of the logo, cropped by the square.
            val small = shrunk(input, min(input.width, input.height), edge)
            canvas.drawBitmap(small, middle(small), whole, paint)
            if (small !== input) small.recycle()
        } else {
            val room = edge * (1f - 2f * inset)
            val scale = min(room / input.width, room / input.height)
            val w = input.width * scale
            val h = input.height * scale
            val to = RectF((edge - w) / 2f, (edge - h) / 2f, (edge + w) / 2f, (edge + h) / 2f)
            val small = shrunk(input, input.width, max(w, 1f).roundToInt())
            canvas.drawBitmap(small, null, to, paint)
            if (small !== input) small.recycle()
        }
        return out
    }

    /** The middle square of [picture]. */
    private fun middle(picture: Bitmap): Rect {
        val side = min(picture.width, picture.height)
        return Rect((picture.width - side) / 2, (picture.height - side) / 2, (picture.width + side) / 2, (picture.height + side) / 2)
    }

    /**
     * [picture] halved until [span], one of its sides, is less than twice [target]. Then the last scale
     * onto the canvas is a bilinear step of less than two. One bilinear step of four or five reads only a
     * sixteenth of the source pixels and leaves rough, noisy edges along a logo's lettering.
     */
    private fun shrunk(picture: Bitmap, span: Int, target: Int): Bitmap {
        var current = picture
        var reach = span
        while (reach >= target * 2 && current.width >= 2 && current.height >= 2) {
            val half = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (current !== picture) current.recycle()
            current = half
            reach /= 2
        }
        return current
    }

    private companion object {
        /** Whether a picture of this shape may be filled to a square without anybody noticing what the
         * crop removed (FoundLogo.MayBeCropped): about a twentieth off either end at most. */
        fun mayBeCropped(width: Int, height: Int): Boolean =
            width > 0 && height > 0 && max(width, height).toDouble() / min(width, height) <= 1.1
    }
}
