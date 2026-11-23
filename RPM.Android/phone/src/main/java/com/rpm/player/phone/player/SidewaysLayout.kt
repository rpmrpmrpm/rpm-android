package com.rpm.player.phone.player

import kotlin.math.max
import kotlin.math.min

/** A rectangle on the screen, in dp from the screen's top start corner. */
internal data class Frame(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
    val centreX: Float get() = x + width / 2
    val centreY: Float get() = y + height / 2

    /** This frame moved in by the least that puts it inside [safe]: an inset that reaches past an edge moves it by the
     * difference and no more. */
    fun inside(safe: Frame): Frame {
        val dx = when {
            x < safe.x -> safe.x - x
            right > safe.right -> safe.right - right
            else -> 0f
        }
        val dy = when {
            y < safe.y -> safe.y - y
            bottom > safe.bottom -> safe.bottom - bottom
            else -> 0f
        }
        return copy(x = x + dx, y = y + dy)
    }

    companion object {
        fun around(centreX: Float, centreY: Float, width: Float, height: Float) =
            Frame(centreX - width / 2, centreY - height / 2, width, height)
    }
}

/**
 * Where everything on the modern player on its side goes ([SidewaysPlayer]), worked out from the size of the whole
 * screen, so it holds on any phone on its side. The places are shares of the screen's full width and height, measured
 * from the drawing the layout follows (an iPhone on its side, 844 by 390 points). The pieces keep the sizes they have
 * upright, scaled by the screen's height against that drawing's ([scale]), because the drawing shows them at those sizes.
 *
 * Nothing is put under a notch or a rounded corner: an element whose place reaches into [safe] is moved in by the
 * difference only. The right column's two edges are each held inside on their own, and the spectrum is cut at the edge
 * instead of moving the cover.
 *
 * The sheet's pieces are given whole frames: the words (title, artist and heart), the needle (seek bar and clocks), the
 * transport and the capsule. The title line, the heart, the seek line and the clocks' line inside them follow from the
 * pieces' own upright layout in [NowPlayingSheet], which this repeats.
 *
 * @param width the screen's full width, in dp.
 * @param height the screen's full height, in dp.
 * @param safe the part of the screen outside the notch, in dp.
 */
internal class SidewaysLayout(val width: Float, val height: Float, val safe: Frame) {
    /** The pieces' sizes against their upright ones. */
    val scale: Float = height / DRAWN_HEIGHT

    /** The cover, square: two thirds of the height, left of the middle. On a screen much less wide than the drawing it
     * keeps to its share of the width. */
    val cover: Frame = run {
        val tall = height * COVER_OF_HEIGHT
        val side = if (width / height < NARROW) min(tall, width * COVER_OF_WIDTH) else tall
        Frame.around(width * COVER_X, height * COVER_Y, side, side).inside(safe)
    }

    /** The span the spectrum bars run across, on the cover's rows. The cover is in it, and the bars run out of both of
     * its sides to the span's ends. */
    val spectrum: Frame = run {
        val left = max(width * RUN_LEFT, safe.x)
        val right = min(width * RUN_RIGHT, safe.right)
        Frame(left, cover.y, right - left, cover.height)
    }

    /** Where the lyrics go when they take the cover's place: the spectrum's span, from the top to the bottom. */
    val lyrics: Frame = Frame(spectrum.x, safe.y, spectrum.width, safe.height)

    private val columnLeft = max(width * COLUMN_LEFT, safe.x)
    private val columnRight = min(width * COLUMN_RIGHT, safe.right)

    /** The title's line: the line box the title is drawn in, beside the heart. Its letters' tops are on the drawing's
     * line; the line box is centred on the letters ([Marquee]). */
    val title: Frame = run {
        val type = TITLE * scale
        val baseline = height * TITLE_INK_TOP + type * CABIN_INK
        val line = TITLE_LINE * scale
        val top = baseline - type * CABIN_ASCENT - (line - type * CABIN_LINE) / 2
        Frame(columnLeft, top, columnRight - columnLeft - (WORDS_GAP + HEART_KEY) * scale, line)
    }

    /** The artist's line, under the title's. */
    val artist: Frame = Frame(title.x, title.bottom + LINES_GAP * scale, title.width, ARTIST_LINE * scale)

    /** The heart's key, at the top of the words, reaching out past the column so its glyph ends on the column's edge. */
    val heart: Frame = Frame(columnRight - (HEART_KEY - HEART_REACH) * scale, title.y, HEART_KEY * scale, HEART_KEY * scale)

    /** The words: the title, the artist and the heart. */
    val words: Frame = Frame(columnLeft, title.y, columnRight - columnLeft, artist.bottom - title.y)

    /** The seek bar's strip, which takes the finger: centred on the bar's line. */
    val seek: Frame = Frame.around((columnLeft + columnRight) / 2, height * SEEK_LINE, columnRight - columnLeft, SEEK_STRIP * scale)

    /** The clocks' line, under the bar. */
    val times: Frame = Frame(columnLeft, seek.bottom + CLOCKS_GAP * scale, seek.width, CLOCKS * CABIN_LINE * scale)

    /** The needle: the seek bar with the room over it, and the clocks. */
    val needle: Frame = Frame(columnLeft, seek.y - SEEK_ABOVE * scale, seek.width, times.bottom - seek.y + SEEK_ABOVE * scale)

    /** The transport's row of keys: previous, play in the middle, next. */
    val transport: Frame = Frame.around(
        width * TRANSPORT_X,
        height * TRANSPORT_LINE,
        (2 * SKIP_KEY + 2 * TRANSPORT_GAP + PLAY_KEY) * scale,
        PLAY_KEY * scale,
    ).inside(safe)

    /** The glass capsule with the lyrics, queue and menu keys. */
    val capsule: Frame = Frame.around(width * CAPSULE_X, height * CAPSULE_LINE, 3 * SMALL_KEY * scale, SMALL_KEY * scale).inside(safe)

    /** The column the words, the needle, the transport and the capsule share: its two edges, from the title's top to the
     * capsule's foot. */
    val column: Frame = Frame(columnLeft, title.y, columnRight - columnLeft, capsule.bottom - title.y)

    /** The speaker key, in the top right corner. */
    val cast: Frame = Frame.around(width * CAST_X, height * CAST_Y, GLASS_KEY * scale, GLASS_KEY * scale).inside(safe)

    /** The visualiser key, to the left of the speaker key on its line. */
    val visual: Frame = Frame(cast.x - (KEYS_GAP + GLASS_KEY) * scale, cast.y, cast.width, cast.height).inside(safe)

    companion object {
        /** The layout for a screen [width] by [height] dp with these insets, as the window reports them. */
        fun of(width: Float, height: Float, left: Float, top: Float, right: Float, bottom: Float) =
            SidewaysLayout(width, height, Frame(left, top, width - left - right, height - top - bottom))

        /** The height of the drawing, at which the pieces have their upright sizes. */
        const val DRAWN_HEIGHT = 390f

        // The places, as shares of the full width (X, LEFT, RIGHT) or height (Y, LINE, TOP).
        const val COVER_X = 0.233f
        const val COVER_Y = 0.5035f
        const val COVER_OF_HEIGHT = 0.667f
        /** Below this width to height, the cover keeps to [COVER_OF_WIDTH] of the width. */
        const val NARROW = 1.8f
        const val COVER_OF_WIDTH = 0.42f
        const val RUN_LEFT = 0.004f
        const val RUN_RIGHT = 0.4625f
        const val COLUMN_LEFT = 0.557f
        const val COLUMN_RIGHT = 0.961f
        /** The top of the title's capitals. */
        const val TITLE_INK_TOP = 0.388f
        /** The middle of the seek bar. */
        const val SEEK_LINE = 0.5556f
        /** The play key's middle. */
        const val TRANSPORT_X = 0.7595f
        const val TRANSPORT_LINE = 0.743f
        const val CAPSULE_X = 0.7575f
        const val CAPSULE_LINE = 0.904f
        const val CAST_X = 0.959f
        const val CAST_Y = 0.0846f

        // The pieces' upright sizes, in dp: the sheet's own ([NowPlayingSheet]).
        const val TITLE = 22f
        const val TITLE_LINE = 28f
        const val LINES_GAP = 2f
        const val ARTIST = 15f
        const val ARTIST_LINE = 20f
        const val WORDS_GAP = 8f
        const val HEART_KEY = 40f
        const val HEART_GLYPH = 15f
        /** How far the heart's key reaches past the words' edge: about the room beside its glyph. */
        const val HEART_REACH = 12f
        const val SEEK_ABOVE = 6f
        const val SEEK_STRIP = 20f
        const val CLOCKS_GAP = 4f
        const val CLOCKS = 11f
        const val SKIP_KEY = 56f
        const val PLAY_KEY = 68f
        const val TRANSPORT_GAP = 24f
        const val SMALL_KEY = 40f
        const val GLASS_KEY = 40f
        const val KEYS_GAP = 10f

        // Cabin, as shares of its size: the top of its capitals and tall letters over the baseline, and its line box's
        // ascent and height.
        const val CABIN_INK = 0.75f
        const val CABIN_ASCENT = 0.965f
        const val CABIN_LINE = 1.215f
    }
}
