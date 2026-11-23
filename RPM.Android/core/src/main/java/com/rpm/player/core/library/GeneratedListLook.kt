package com.rpm.player.core.library

import com.rpm.player.core.room.RoomPalette
import com.rpm.player.core.room.Rgb
import com.rpm.player.core.room.RpmPalette
import kotlin.math.pow

/**
 * What colours a generated list is drawn in, from what the list is. It matches the iPhone's `GeneratedListLook`
 * (RPMDesign/GeneratedListLook.swift) and the desktop's `SmartPlaylistFace`, `PlaylistFigure.Colourway` and
 * `CoverOverlay.Ink`.
 *
 * **Rolled here, not sent.** A list carries its kind, genre and decade, which are everything the seed is made of
 * ([ListSeed]), so every app reaches the same three colours and no colour crosses the network.
 *
 * **Nothing is mixed.** Both inks are palette colours: the border is the darkest stop of the ramp the palette
 * ships beside the list's shadow colour, and the name is whichever of the three reads best on it.
 */
object GeneratedListLook {
    /** The three colours one list was rolled, sorted by light, so a room is never lit from its own shadow. */
    data class Trio(val hot: Rgb, val mid: Rgb, val deep: Rgb)

    /**
     * One list's whole look.
     *
     * @param border what the frame drawing paints black.
     * @param text what it paints white: the list's name down the band and the app's mark in the corner.
     * @param roomed whether the list has nothing of its own behind the frame's window and stands on the room ([roomed]).
     * @param apart how far along the room's drift the list's picture is caught: its kind's number on the desktop,
     * because two kinds can share colours (Favourite tracks and Loved and forgotten do) and would otherwise be one
     * picture (CoverRoom.Of).
     */
    data class Look(val trio: Trio, val border: Rgb, val text: Rgb, val roomed: Boolean, val apart: Int) {
        /** The room in the list's colours, in the order the room asks for them: the middle one is most of what is
         * seen, the hot one is the light, the deep one the shadow (`FramedList.room` on iPhone). */
        val room: RoomPalette get() = RoomPalette.from(trio.mid, trio.hot, trio.deep)
    }

    /** The app's own look: its blue, its cyan light and its violet shade, the colours the room has with no record in
     * it (`RoomPalette.fallback` on the iPhone). The folder of all the moods wears it, because it holds every mood and
     * so has no colour of one (`GeneratedListLook.app` on the iPhone). */
    val app: Look by lazy {
        val trio = Trio(hot = Rgb.of(0x05, 0xC7, 0xFF), mid = Rgb.of(0x03, 0x71, 0xD0), deep = Rgb.of(0x9E, 0x29, 0xB8))
        val border = toward(trio.mid, 0.25)
        var text = trio.hot
        for (candidate in listOf(trio.mid, trio.deep)) if (contrast(candidate, border) > contrast(text, border)) text = candidate
        Look(trio, border, text, roomed = false, apart = 0)
    }

    /** The colour a photograph behind the app's own look is washed in: its blue (`GeneratedListLook.app.tint`). */
    val appTint: Rgb get() = app.trio.mid

    /** The look of a list of [kind] cut to [genre] or [decade], or null for a kind this build has no hue for: an
     * older app reading a newer computer's cut. That list still shows, without a frame. */
    fun of(kind: String, genre: String?, decade: Int): Look? {
        val seed = ListSeed.of(kind, genre, decade) ?: return null
        val trio = trio(seed)
        val border = foot(trio.deep)
        // The brightest of the three is not always the one that reads: for the two crimson lists Magenta is
        // brightest at 2.95:1 against the border, and Crimson, the middle one, is 3.15:1.
        var text = trio.hot
        for (candidate in listOf(trio.mid, trio.deep)) if (contrast(candidate, border) > contrast(text, border)) text = candidate
        return Look(trio, border, text, roomed(kind), KINDS.indexOf(kind).coerceAtLeast(0))
    }

    /** The look of [list], or null for a list somebody made, or a kind this build does not know. */
    fun of(list: TrackList): Look? {
        if (!list.generated) return null
        val kind = list.kind ?: return null
        return of(kind, list.genre, list.decade)
    }

    /** The five lists that have nothing to put behind the window and stand on the room instead. Every
     * generated list is framed; these get the frame and the room, and not a picture. */
    fun roomed(kind: String): Boolean = kind in ROOMED

    private val ROOMED = setOf("RightNow", "OnRepeat", "FavoriteTracks", "FirstListens", "LovedAndForgotten")

    /** Three neighbours off the ring, chosen by the seed, in light order. The span (one step or two) is rolled
     * from bit 11 of the seed, so the generated covers do not all have the same harmony. */
    private fun trio(seed: Long): Trio {
        val ring = RpmPalette.RING
        val span = 1 + ((seed shr 11) % 2).toInt()
        val at = (seed % ring.size).toInt()
        val three = listOf(ring[at], ring[(at + span) % ring.size], ring[((at - span) % ring.size + ring.size) % ring.size])
            .sortedBy(::light)
        return Trio(hot = three[2], mid = three[1], deep = three[0])
    }

    /** The darkest stop of the ramp the palette ships beside [colour], read from the palette, not worked out.
     * Where there is none, the colour at 40%, the figure the palette's own ramps use for their foot. */
    private fun foot(colour: Rgb): Rgb {
        val at = RpmPalette.RING.indexOf(colour)
        return FOOT.getOrNull(at) ?: Rgb(colour.r * 0.40f, colour.g * 0.40f, colour.b * 0.40f)
    }

    /** [colour] with each channel scaled by [light] and rounded to a whole step of 255, as the iPhone's
     * `GeneratedListLook.toward` does, so both apps reach the same border. */
    internal fun toward(colour: Rgb, light: Double): Rgb = Rgb.of(
        Math.round(colour.r * 255.0 * light).toInt().coerceIn(0, 255),
        Math.round(colour.g * 255.0 * light).toInt().coerceIn(0, 255),
        Math.round(colour.b * 255.0 * light).toInt().coerceIn(0, 255),
    )

    /** Each ring colour's ramp foot, in ring order (RpmPalette.xaml's gradients). */
    private val FOOT = listOf(
        Rgb.of(0x54, 0x13, 0x05), Rgb.of(0x51, 0x2C, 0x06), Rgb.of(0x5F, 0x46, 0x07), Rgb.of(0x45, 0x53, 0x16),
        Rgb.of(0x07, 0x1E, 0x0F), Rgb.of(0x01, 0x2D, 0x53), Rgb.of(0x1C, 0x20, 0x45), Rgb.of(0x44, 0x20, 0x38),
        Rgb.of(0x5D, 0x08, 0x1F), Rgb.of(0x5F, 0x02, 0x03),
    )

    /** The desktop's `SmartPlaylistKind` in declaration order: a kind's index is its number there. */
    private val KINDS = listOf(
        "FavoriteTracks", "MostPlayed", "RecentlyAdded", "RecentlyPlayed", "NeverPlayed", "LovedAndForgotten",
        "PlayedNotLoved", "OnRepeat", "Rediscover", "LateNight", "FirstListens", "KeepSkipping", "Calm",
        "Energetic", "Danceable", "Focus", "Classical", "Decade", "Sleep", "RightNow",
        "Melancholy", "Happy", "Angry", "Romantic", "Zen", "NewYearsEve", "Christmas",
    )

    /** The plain weighted sum the trio is sorted by, on 0 to 255 channels as the iPhone has them. */
    private fun light(c: Rgb): Double = 255.0 * (0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b)

    /** WCAG's contrast ratio, from 1 to 21. Held to 3:1 for the band's large bold type. */
    internal fun contrast(a: Rgb, b: Rgb): Double {
        val one = luminance(a)
        val two = luminance(b)
        return (maxOf(one, two) + 0.05) / (minOf(one, two) + 0.05)
    }

    private fun luminance(c: Rgb): Double = 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b)

    private fun channel(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}
