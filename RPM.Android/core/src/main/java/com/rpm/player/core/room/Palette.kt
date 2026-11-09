package com.rpm.player.core.room

import android.graphics.Bitmap
import kotlin.math.exp
import kotlin.math.sqrt

/** A colour as three floats from 0 to 1. */
data class Rgb(val r: Float, val g: Float, val b: Float) {
    operator fun plus(o: Rgb) = Rgb(r + o.r, g + o.g, b + o.b)
    operator fun minus(o: Rgb) = Rgb(r - o.r, g - o.g, b - o.b)
    operator fun times(k: Float) = Rgb(r * k, g * k, b * k)

    companion object {
        fun of(r: Int, g: Int, b: Int) = Rgb(r / 255f, g / 255f, b / 255f)
    }
}

/**
 * The four colours the room is painted in: the dark it sits on, the sleeve's dominant tone, its vivid
 * accent, and the counter-colour the second and third families wear. Same as the iOS app's
 * `RoomPalette` (RPMStages), with the same numbers. No hue is ever turned to make up a colour the
 * sleeve does not have.
 */
data class RoomPalette(val ground: Rgb, val tint: Rgb, val glow: Rgb, val shade: Rgb) {

    /** Part of the way towards [target]; [k] from 0 (stay) to 1 (arrive). */
    fun eased(target: RoomPalette, k: Float) = RoomPalette(
        ground + (target.ground - ground) * k,
        tint + (target.tint - tint) * k,
        glow + (target.glow - glow) * k,
        shade + (target.shade - shade) * k,
    )

    /** Whether every channel of every colour is within a 512th of [other]'s, which is too close to see. */
    fun closeTo(other: RoomPalette): Boolean =
        near(ground, other.ground) && near(tint, other.tint) && near(glow, other.glow) && near(shade, other.shade)

    private fun near(a: Rgb, b: Rgb): Boolean =
        kotlin.math.abs(a.r - b.r) < CLOSE && kotlin.math.abs(a.g - b.g) < CLOSE && kotlin.math.abs(a.b - b.b) < CLOSE

    companion object {
        // The app's own blue on its own near-black, for a sleeve with no colour to give.
        private val TINT_FALLBACK = Rgb(0.012f, 0.443f, 0.816f)
        private val GLOW_FALLBACK = Rgb(0.02f, 0.78f, 1.0f)
        private val SHADE_FALLBACK = Rgb(0.62f, 0.16f, 0.72f)
        private val GROUND_FALLBACK = Rgb(0.012f, 0.030f, 0.070f)
        private val SHADE_GREY = Rgb(0.70f, 0.70f, 0.70f)

        val FALLBACK = RoomPalette(GROUND_FALLBACK, TINT_FALLBACK, GLOW_FALLBACK, SHADE_FALLBACK)

        fun from(tint: Rgb?, glow: Rgb?, shade: Rgb?): RoomPalette {
            val base = tint ?: glow ?: return FALLBACK
            val accent = glow ?: tint ?: return FALLBACK

            val litTint = vivid(base, saturate = 1.35f, lift = 0.62f)
            var litGlow = vivid(accent, saturate = 1.5f, lift = 0.90f)

            // The sleeve's third colour, where it is genuinely a third.
            var spare: Rgb? = shade?.takeUnless { same(it, base) || same(it, accent) }

            // Where the dominant tone and the accent are one hue, the third colour becomes the accent,
            // so the big lobes show two colours.
            if (same(litTint, litGlow) && spare != null) {
                litGlow = vivid(spare, saturate = 1.5f, lift = 0.90f)
                spare = null
            }

            val litShade = spare?.let { vivid(it, saturate = 1.35f, lift = 0.76f) } ?: SHADE_GREY
            val litGround = vivid(base, saturate = 1.1f, lift = 0.16f) * 0.55f + GROUND_FALLBACK * 0.45f
            return RoomPalette(litGround, litTint, litGlow, litShade)
        }

        internal fun vivid(c: Rgb, saturate: Float, lift: Float): Rgb {
            val luma = c.r * 0.2126f + c.g * 0.7152f + c.b * 0.0722f
            var v = Rgb(
                (luma + (c.r - luma) * saturate).coerceIn(0f, 1f),
                (luma + (c.g - luma) * saturate).coerceIn(0f, 1f),
                (luma + (c.b - luma) * saturate).coerceIn(0f, 1f),
            )
            val peak = maxOf(v.r, v.g, v.b)
            if (peak > 1e-4f) v = v * (lift / peak)
            return Rgb(v.r.coerceIn(0f, 1f), v.g.coerceIn(0f, 1f), v.b.coerceIn(0f, 1f))
        }

        internal fun same(a: Rgb, b: Rgb): Boolean {
            val na = normalised(a)
            val nb = normalised(b)
            val d = na - nb
            return sqrt(d.r * d.r + d.g * d.g + d.b * d.b) < 0.22f
        }

        private fun normalised(c: Rgb): Rgb {
            val length = sqrt(c.r * c.r + c.g * c.g + c.b * c.b)
            return if (length > 1e-4f) c * (1f / length) else Rgb(0f, 0f, 0f)
        }

        /** How far to move towards the target in a frame of [dtSeconds], so a change of record
         * dissolves the room over about a second and does not switch it at once. */
        fun easeStep(dtSeconds: Float): Float = 1f - exp(-dtSeconds * 3f)

        private const val CLOSE = 1f / 512f
    }
}

/**
 * Colours read off a cover, as the iOS app's `CoverColor` (RPMDesign) does, with the same numbers.
 * Works on RGBA bytes, which for a 48 × 48 square is two thousand pixels.
 */
object CoverColor {

    private class Bucket {
        var count = 0
        var r = 0
        var g = 0
        var b = 0
        fun add(red: Int, green: Int, blue: Int) {
            count++; r += red; g += green; b += blue
        }
        fun average() = Rgb.of(r / count, g / count, b / count)
    }

    private fun key(r: Int, g: Int, b: Int) = ((r shr 5) shl 10) or ((g shr 5) shl 5) or (b shr 5)

    /** The most common colour that is neither near black nor near white. */
    fun dominant(rgba: ByteArray): Rgb? {
        val buckets = LinkedHashMap<Int, Bucket>()
        forEachPixel(rgba) { r, g, b ->
            val hi = maxOf(r, g, b)
            val lo = minOf(r, g, b)
            if (hi < 24 || lo > 232) return@forEachPixel
            buckets.getOrPut(key(r, g, b), ::Bucket).add(r, g, b)
        }
        var best: Bucket? = null
        for (bucket in buckets.values) if (bucket.count > (best?.count ?: 0)) best = bucket
        return best?.average()
    }

    /** Up to [count] distinct vivid colours, best first, scored by saturation, area and brightness. */
    fun palette(rgba: ByteArray, count: Int): List<Rgb> {
        if (count <= 0) return emptyList()
        val buckets = vividBuckets(rgba)
        val scored = buckets.keys.sorted()
            .map { score(buckets.getValue(it)) to buckets.getValue(it).average() }
            .sortedByDescending { it.first }
        val chosen = mutableListOf<Rgb>()
        for ((_, colour) in scored) {
            if (chosen.size >= count) break
            if (chosen.none { RoomPalette.same(it, colour) }) chosen += colour
        }
        return chosen
    }

    /** The single highest-scoring vivid colour. */
    fun highlight(rgba: ByteArray): Rgb? {
        val buckets = vividBuckets(rgba)
        var best = -1.0
        var winner: Bucket? = null
        for (k in buckets.keys.sorted()) {
            val s = score(buckets.getValue(k))
            if (s > best) {
                best = s
                winner = buckets.getValue(k)
            }
        }
        return winner?.average()
    }

    private fun vividBuckets(rgba: ByteArray): Map<Int, Bucket> {
        val buckets = HashMap<Int, Bucket>()
        forEachPixel(rgba) { r, g, b ->
            val hi = maxOf(r, g, b)
            val lo = minOf(r, g, b)
            // Too dark, or too grey to be one of a cover's colours.
            if (hi < 40 || hi - lo < 24) return@forEachPixel
            buckets.getOrPut(key(r, g, b), ::Bucket).add(r, g, b)
        }
        return buckets
    }

    private fun score(bucket: Bucket): Double {
        val r = bucket.r.toDouble() / bucket.count
        val g = bucket.g.toDouble() / bucket.count
        val b = bucket.b.toDouble() / bucket.count
        val hi = maxOf(r, g, b)
        val lo = minOf(r, g, b)
        val saturation = if (hi <= 0) 0.0 else (hi - lo) / hi
        return saturation * sqrt(bucket.count.toDouble()) * (hi / 255)
    }

    private inline fun forEachPixel(rgba: ByteArray, each: (Int, Int, Int) -> Unit) {
        var i = 0
        while (i <= rgba.size - 4) {
            each(rgba[i].toInt() and 0xFF, rgba[i + 1].toInt() and 0xFF, rgba[i + 2].toInt() and 0xFF)
            i += 4
        }
    }

    /** Everything the player paints from one cover: the room, the hue the record is pressed in, and
     * the cover's dominant colour, which the sleeve's frame is drawn in (null for no usable colour).
     * [glow] is its best vivid colour, or null where it has none: an artist's page is led by it ([GroundLead]). */
    data class Reading(val room: RoomPalette, val vinyl: Rgb?, val dominant: Rgb?, val glow: Rgb? = null)

    /** The room palette and the record's hue for a cover, read off one 48 × 48 square the way the iOS
     * app's `Thumbnail.colours` and the desktop's `VinylPressing.PaletteFromArt` do: the dominant
     * colour as the tint, the best vivid colour as the glow, the next distinct vivid colour as the shade,
     * and the record pressed in the glow, or the tint, or black ([VinylHue]). */
    fun read(cover: Bitmap): Reading {
        val square = Bitmap.createScaledBitmap(cover, SAMPLE, SAMPLE, true)
        val pixels = IntArray(SAMPLE * SAMPLE)
        square.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
        if (square !== cover) square.recycle()
        val rgba = ByteArray(pixels.size * 4)
        for ((i, p) in pixels.withIndex()) {
            rgba[i * 4] = (p shr 16).toByte()
            rgba[i * 4 + 1] = (p shr 8).toByte()
            rgba[i * 4 + 2] = p.toByte()
            rgba[i * 4 + 3] = (p ushr 24).toByte()
        }
        val vivid = palette(rgba, 2)
        val tint = dominant(rgba)
        val glow = vivid.firstOrNull() ?: highlight(rgba)
        return Reading(
            room = RoomPalette.from(tint = tint, glow = glow, shade = vivid.getOrNull(1)),
            vinyl = VinylHue.choose(glow, tint),
            dominant = tint,
            glow = glow,
        )
    }

    private const val SAMPLE = 48
}

/**
 * Which colour a record is pressed in, and the colour matrix that presses it: the desktop's `VinylHue`
 * (RPM.Core/Art) and `VinylPressing.Tint`, with the same numbers. The vinyl image is a neutral record.
 * The matrix multiplies each channel by the hue over its own brightness, held under a ceiling and
 * applied at 0.88. A sleeve with no usable colour presses black.
 */
object VinylHue {
    private const val MIN_SATURATION = 0.18f
    private const val MIN_BRIGHTNESS = 0.16f
    private const val SATURATION = 0.88f
    private const val CEILING = 3.2f

    fun choose(highlight: Rgb?, dominant: Rgb?): Rgb? = when {
        usable(highlight) -> highlight
        usable(dominant) -> dominant
        else -> null
    }

    private fun usable(c: Rgb?): Boolean {
        if (c == null) return false
        val hi = maxOf(c.r, c.g, c.b)
        val lo = minOf(c.r, c.g, c.b)
        val saturation = if (hi <= 0f) 0f else (hi - lo) / hi
        return saturation >= MIN_SATURATION && hi >= MIN_BRIGHTNESS
    }

    /** A 4 x 5 colour matrix in Android's order, or the greyscale matrix when there is no hue. */
    fun matrix(hue: Rgb?): FloatArray {
        val brightness = if (hue == null) 0f else hue.r * 0.2126f + hue.g * 0.7152f + hue.b * 0.0722f
        if (hue == null || brightness < 1e-6f) {
            return floatArrayOf(
                0.2126f, 0.7152f, 0.0722f, 0f, 0f,
                0.2126f, 0.7152f, 0.0722f, 0f, 0f,
                0.2126f, 0.7152f, 0.0722f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        }
        val tint = floatArrayOf(hue.r / brightness, hue.g / brightness, hue.b / brightness)
        // Pulled back towards neutral until nothing is over the ceiling. It is not scaled down, because that
        // would remove the brightness that the unit luminance put in.
        val peak = tint.max()
        if (peak > CEILING) for (i in 0..2) tint[i] = 1 + (tint[i] - 1) * ((CEILING - 1) / (peak - 1))
        for (i in 0..2) tint[i] = 1 + (tint[i] - 1) * SATURATION
        return floatArrayOf(
            tint[0], 0f, 0f, 0f, 0f,
            0f, tint[1], 0f, 0f, 0f,
            0f, 0f, tint[2], 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }
}
