package com.rpm.player.core.room

import kotlin.math.pow

/**
 * The colour an artist's page stands on, read off their photograph. The iPhone has the same rule with the same
 * numbers (`GroundLead` in RPMDesign).
 *
 * **The photograph's best vivid colour, not its most common one.** The colour most of a photograph is made of is very
 * often the wall or the sky behind the band: grey, beige or white. A page led by that is a grey page. The vivid colour
 * (a jacket, a light, a guitar) is the one a person remembers the photograph by. A photograph with no vivid colour at
 * all (black and white) is led by its most common colour instead.
 *
 * **No brighter than [MOST_LUMINANCE].** The page's text is white. A yellow or a pale blue as the ground would leave it
 * hard to read, so a colour brighter than that is darkened, in linear light, to exactly that brightness. Its hue and
 * its saturation stay as they are.
 */
object GroundLead {
    /**
     * The brightest the colour may be, as relative luminance (0 is black, 1 is white). White over it has a contrast of
     * about 4:1, and the page's veil over the colour raises that above 6:1 at the top of the page.
     */
    const val MOST_LUMINANCE = 0.2f

    fun of(glow: Rgb?, dominant: Rgb?): Rgb? {
        val colour = glow ?: dominant ?: return null
        val r = linear(colour.r)
        val g = linear(colour.g)
        val b = linear(colour.b)
        val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
        if (luminance <= MOST_LUMINANCE) return colour
        val k = MOST_LUMINANCE / luminance
        return Rgb(encoded(r * k), encoded(g * k), encoded(b * k))
    }

    private fun linear(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun encoded(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
    }
}
