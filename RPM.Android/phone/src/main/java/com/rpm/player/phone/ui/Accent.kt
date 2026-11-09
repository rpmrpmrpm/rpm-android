package com.rpm.player.phone.ui

import android.util.LruCache
import com.rpm.player.core.room.GroundLead
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.rpm.player.core.room.CoverColor
import com.rpm.player.core.room.Rgb
import com.rpm.player.core.room.RpmPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * The accent of [picture]: the app's own accent nearest the sleeve's dominant colour, or null for a sleeve with no
 * colour to give (a grey one, or none). It is the iPhone's `CoverArt.accent` (`CoverColor.tint`, snapped by
 * `RPMPalette.nearest`). The picture is read at the 48 pixel square the colours are taken from, off the main thread,
 * once per picture.
 */
@Composable
fun rememberAccent(picture: Any?, images: ImageLoader): Rgb? {
    val context = LocalContext.current
    var accent by remember(picture) { mutableStateOf<Rgb?>(null) }
    LaunchedEffect(picture) {
        if (picture == null) return@LaunchedEffect
        accent = accentOf(context, picture, images)
    }
    return accent
}

/**
 * [picture] with the colour that leads a page standing on it ([GroundLead]), once that colour is read: null until then,
 * so the picture and its colour reach the page together and come in as one fade. The colour is null for a picture with
 * none to give. Read off the main thread from the 48 pixel square, once per picture.
 */
@Composable
fun rememberLead(picture: Any?, images: ImageLoader): Pair<Any, Rgb?>? {
    val context = LocalContext.current
    // A picture read before is answered at once, so a page shown again is drawn on its colour from the first frame.
    var read by remember(picture) { mutableStateOf(picture?.let { p -> LEADS.get(p)?.let { p to it.colour } }) }
    if (picture != null && read == null) LaunchedEffect(picture) {
        val colour = withContext(Dispatchers.Default) {
            val request = ImageRequest.Builder(context).data(picture).size(48).allowHardware(false).build()
            val bitmap = (images.execute(request) as? SuccessResult)?.image?.toBitmap()
            bitmap?.let { CoverColor.read(it) }?.let { GroundLead.of(it.glow, it.dominant) }
        }
        LEADS.put(picture, Led(colour))
        read = picture to colour
    }
    return read
}

/** A leading colour as read, which may be none: a cache cannot hold a null. */
private class Led(val colour: Rgb?)

/** The leading colours read so far, by picture, for the life of the app. */
private val LEADS = LruCache<Any, Led>(64)

/** The same accent read once, for a caller that must have it before it changes what it draws (the stage's record). */
suspend fun accentOf(context: Context, picture: Any, images: ImageLoader): Rgb? = withContext(Dispatchers.Default) {
    val request = ImageRequest.Builder(context).data(picture).size(48).allowHardware(false).build()
    val bitmap = (images.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@withContext null
    CoverColor.read(bitmap).dominant?.let(RpmPalette::nearest)
}

/**
 * A colour lifted to the lightness a thin line needs on the app's dark glass: the iPhone's `GlassInk`, with the same
 * floor. A sleeve can give a near-black, and a line two points wide in that colour cannot be seen, so white is mixed
 * in, in linear light, until Oklab's L reaches 0.54. Eight of the ten accents are drawn as they are.
 */
object GlassInk {
    private const val FLOOR = 0.54

    fun of(colour: Rgb?): Color {
        if (colour == null) return Color.White
        val lifted = if (lightness(colour) >= FLOOR) colour else {
            var below = 0.0
            var above = 1.0
            repeat(16) {
                val middle = (below + above) / 2
                if (lightness(mixed(colour, middle)) < FLOOR) below = middle else above = middle
            }
            mixed(colour, above)
        }
        return Color(lifted.r.coerceIn(0f, 1f), lifted.g.coerceIn(0f, 1f), lifted.b.coerceIn(0f, 1f))
    }

    private fun mixed(colour: Rgb, white: Double): Rgb {
        fun channel(c: Float): Float {
            val light = linear(c)
            return encoded(light + (1 - light) * white).toFloat()
        }
        return Rgb(channel(colour.r), channel(colour.g), channel(colour.b))
    }

    private fun lightness(c: Rgb): Double {
        val r = linear(c.r)
        val g = linear(c.g)
        val b = linear(c.b)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
    }

    private fun linear(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun encoded(light: Double): Double {
        val held = light.coerceIn(0.0, 1.0)
        return if (held <= 0.0031308) held * 12.92 else 1.055 * held.pow(1 / 2.4) - 0.055
    }
}

/** [colour] as a Compose colour. */
fun Rgb.color(): Color = Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
