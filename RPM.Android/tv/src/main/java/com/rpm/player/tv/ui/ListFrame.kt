package com.rpm.player.tv.ui

import com.rpm.player.core.library.listName
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.res.ResourcesCompat
import coil3.ImageLoader
import com.rpm.player.core.R
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.ListSeed
import com.rpm.player.core.library.TrackList
import com.rpm.player.core.room.RoomPalette
import com.rpm.player.core.room.Rgb
import com.rpm.player.core.room.RpmPalette

/**
 * A generated list's picture as the desktop draws it (CoverOverlay.cs): behind the window of the lettered
 * frame either a room in the list's colours (the five lists about listening: Your mix, On repeat,
 * Favorite tracks, New to you, Loved and forgotten) or the list's picture ([Collection.cover]: the
 * photograph shipped for it, else the one the cloud keeps), then the frame
 * (PlaylistOverlays/Frame.png) recoloured into two inks, and the list's name lettered down the right in
 * Cabin Bold.
 *
 * The inks come from the list's colourway, the three palette colours its seed rolls
 * (SmartPlaylistFace.Cover, PlaylistFigure.Colourway): the frame is the darkest stop of the deepest
 * colour's gradient, and the lettering whichever of the three stands out most against it. A list the
 * computer did not generate shows its picture alone.
 *
 * With [keepFrame] the frame and the lettering are kept in a layer of their own, so a drifting room's 25
 * frames a second do not draw them again; a card that has grown asks for them drawn sharp instead.
 */
@Composable
fun ListPicture(list: TrackList, collection: Collection, images: ImageLoader, drift: Boolean, keepFrame: Boolean, modifier: Modifier) {
    val way = remember(list.id) { Colourway.of(list) }
    Box(modifier) {
        if (way != null && list.kind in ROOMED) {
            // On the desktop only home's Made for you squares are live rooms (CardRoom); every other place
            // a list's picture shows is a still image, so [drift] is the caller's to state.
            CardRoomView(way.room, apart = way.apart, drift = drift, fewest = 32, soften = 0f, modifier = Modifier.fillMaxSize())
        } else {
            val picture = collection.cover(list)
            if (picture != null) {
                CoverImage(picture, images, ContentScale.Crop, Modifier.fillMaxSize(), onShown = {})
            }
        }
        if (way != null) {
            val ink = remember(way) { way.ink() }
            Box(Modifier.fillMaxSize().cachedLayer(cached = keepFrame, light = { 1f })) {
                Image(
                    painter = painterResource(R.drawable.list_frame),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    colorFilter = ColorFilter.colorMatrix(ramp(ink.border, ink.text)),
                    modifier = Modifier.fillMaxSize(),
                )
                Lettering(listName(list.name), ink.text, Modifier.fillMaxSize())
            }
        }
    }
}

/** The lists whose picture is a room rather than a figure (CoverOverlay.Roomed). */
private val ROOMED = setOf("RightNow", "OnRepeat", "FavoriteTracks", "FirstListens", "LovedAndForgotten")

/** Maps the frame's grey to a mix of [border] (black) and [text] (white), alpha kept: the desktop's
 * table filter, which is linear and so a colour matrix. */
private fun ramp(border: Color, text: Color): ColorMatrix = ColorMatrix(
    floatArrayOf(
        text.red - border.red, 0f, 0f, 0f, border.red * 255f,
        0f, text.green - border.green, 0f, 0f, border.green * 255f,
        0f, 0f, text.blue - border.blue, 0f, border.blue * 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

/**
 * The name down the right of the frame: turned a quarter clockwise so it reads top to bottom, sized so
 * the ink of "fg" is 0.08 of the edge, centred at 0.90813 across, starting 0.0375 down, and shrunk to
 * fit when it would run past 0.8375 of the edge.
 */
@Composable
private fun Lettering(name: String, colour: Color, modifier: Modifier) {
    val context = LocalContext.current
    val face = remember { ResourcesCompat.getFont(context, R.font.cabin_bold) }
    Canvas(modifier) {
        if (name.isEmpty()) return@Canvas
        val edge = size.width
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            color = colour.toArgb()
            textSize = edge * CROSS
        }
        val band = Rect()
        paint.getTextBounds(GAUGE, 0, GAUGE.length, band)
        if (band.height() <= 0) return@Canvas
        paint.textSize *= edge * CROSS / band.height()
        var ink = Rect().also { paint.getTextBounds(name, 0, name.length, it) }
        if (ink.width() > edge * RUN) {
            paint.textSize *= edge * RUN / ink.width()
            ink = Rect().also { paint.getTextBounds(name, 0, name.length, it) }
        }
        paint.getTextBounds(GAUGE, 0, GAUGE.length, band)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(edge * CENTRE, edge * TOP)
            native.rotate(90f)
            native.drawText(name, -ink.left.toFloat(), -(band.top + band.bottom) / 2f, paint)
            native.restore()
        }
    }
}

private const val CENTRE = 0.90813f
private const val TOP = 0.0375f
private const val CROSS = 0.08f
private const val RUN = 0.8375f
private const val GAUGE = "fg"

/** Three palette colours, lightest first, that a generated list's seed rolls (PlaylistFigure.Colourway),
 * and how far along the drift its room starts ([apart], the kind's number: CoverRoom.Of). */
private data class Colourway(val hot: Rgb, val mid: Rgb, val deep: Rgb, val apart: Int) {
    /** The room the list paints: mid, hot, deep, the order RoomPalette.From asks for (CoverRoom.Of). */
    val room: RoomPalette get() = RoomPalette.from(mid, hot, deep)

    data class Ink(val border: Color, val text: Color)

    fun ink(): Ink {
        val border = foot(deep)
        var text = hot
        for (candidate in listOf(mid, deep)) if (contrast(candidate, border) > contrast(text, border)) text = candidate
        return Ink(colour(border), colour(text))
    }

    companion object {
        fun of(list: TrackList): Colourway? {
            val kind = list.kind ?: return null
            val seed = ListSeed.of(kind, list.genre, list.decade) ?: return null
            val span = 1 + ((seed shr 11) % 2).toInt()
            val at = (seed % RING.size).toInt()
            val trio = listOf(RING[at], RING[(at + span) % RING.size], RING[((at - span) % RING.size + RING.size) % RING.size])
                .sortedBy { light(it) }
            return Colourway(hot = trio[2], mid = trio[1], deep = trio[0], apart = KINDS.indexOf(kind).coerceAtLeast(0))
        }

        /** SmartPlaylistKind in declaration order: a kind's index is its number on the desktop. */
        private val KINDS = listOf(
            "FavoriteTracks", "MostPlayed", "RecentlyAdded", "RecentlyPlayed", "NeverPlayed", "LovedAndForgotten",
            "PlayedNotLoved", "OnRepeat", "Rediscover", "LateNight", "FirstListens", "KeepSkipping", "Calm",
            "Energetic", "Danceable", "Focus", "Classical", "Decade", "Sleep", "RightNow", "Melancholy", "Happy",
            "Angry", "Romantic", "Zen", "NewYearsEve",
        )

        private val RING get() = RpmPalette.RING

        private fun light(c: Rgb): Double = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b

        /** The darkest stop of the palette gradient for [c] (RpmPalette.RampFor, the ramp's foot). */
        private fun foot(c: Rgb): Rgb = FOOT[RING.indexOf(c)] ?: Rgb(c.r * 0.40f, c.g * 0.40f, c.b * 0.40f)

        private fun contrast(a: Rgb, b: Rgb): Float {
            val one = luminance(a)
            val two = luminance(b)
            return (maxOf(one, two) + 0.05f) / (minOf(one, two) + 0.05f)
        }

        private fun luminance(c: Rgb): Float = 0.2126f * channel(c.r) + 0.7152f * channel(c.g) + 0.0722f * channel(c.b)

        private fun channel(v: Float): Float = if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

        private fun colour(c: Rgb) = Color(c.r, c.g, c.b)

        /** Each palette colour's gradient foot, the darkest of its three stops. */
        private val FOOT: Map<Int, Rgb> = listOf(
            Rgb.of(0x54, 0x13, 0x05), Rgb.of(0x51, 0x2C, 0x06), Rgb.of(0x5F, 0x46, 0x07), Rgb.of(0x45, 0x53, 0x16),
            Rgb.of(0x07, 0x1E, 0x0F), Rgb.of(0x01, 0x2D, 0x53), Rgb.of(0x1C, 0x20, 0x45), Rgb.of(0x44, 0x20, 0x38),
            Rgb.of(0x5D, 0x08, 0x1F), Rgb.of(0x5F, 0x02, 0x03),
        ).withIndex().associate { it.index to it.value }
    }
}
