package com.rpm.player.phone.profile

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.rpm.player.phone.design.Ink
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Soft points of light that glow up and fade away around a supporter's photo, over it, and over the name under it. It
 * is the iPhone's `SupporterSparkles`.
 *
 * Each point lives a few seconds: it swells in, drifts upwards a little while it turns, and fades out, then waits and
 * comes back somewhere else. A point is a warm halo, two long thin rays crossed over it, two short ones between them,
 * and a white-hot centre, all added to the light under it, so it glows on the dark page instead of sitting on top.
 *
 * It takes no room in the layout. Put it last in a box centred on the photo, so it draws over the photo; the box that
 * holds the name must draw before it, for the points to glow over the name too.
 *
 * @param photo the photo's diameter. The light reaches a little past the photo on every side, and down over the name.
 */
@Composable
internal fun SupporterSparkles(photo: Dp, modifier: Modifier = Modifier) {
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { seconds.floatValue = (it - start) / 1_000_000_000f }
    }
    Canvas(
        modifier.layout { measurable, _ ->
            val wide = (photo * FIELD_WIDE).roundToPx()
            val high = (photo * (ABOVE + BELOW)).roundToPx()
            val placed = measurable.measure(Constraints.fixed(wide, high))
            // No size of its own: drawn around the centre of the box it is in, which is the photo's centre.
            layout(0, 0) { placed.place(-wide / 2, -(photo * ABOVE).roundToPx()) }
        },
    ) {
        val unit = photo.toPx()
        val centre = Offset(size.width / 2, unit * ABOVE)
        val now = seconds.floatValue
        for (slot in 0 until POINTS) glow(slot, now, centre, unit)
    }
}

/** Draws the point in [slot] as it is at [now], if it is lit. */
private fun DrawScope.glow(slot: Int, now: Float, centre: Offset, unit: Float) {
    // Every slot has its own pace, and they start spread over one round so they never light together.
    val life = 2.6f + 1.4f * noise(slot, 0, 1)
    val round = life + 1.2f + 1.8f * noise(slot, 0, 2)
    val running = now + round * slot / POINTS
    val turn = floor(running / round).toInt()
    val age = (running - turn * round) / life
    if (age >= 1f) return
    // Swells in and fades out smoothly, brightest in the middle of its life.
    val light = sin(age * PI).toFloat().let { it * it }
    if (light < 0.01f) return

    val at = place(slot, turn, centre, unit).let { it.copy(y = it.y - unit * 0.05f * age) }
    val reach = unit * (0.045f + 0.035f * noise(slot, turn, 3)) * (0.75f + 0.25f * light)
    val colour = if (noise(slot, turn, 4) < 0.7f) Ink.gold else WARM_WHITE

    // The halo: wide and faint, the warm air around the point.
    drawCircle(
        Brush.radialGradient(
            0f to colour.copy(alpha = 0.50f * light),
            0.35f to colour.copy(alpha = 0.16f * light),
            1f to Color.Transparent,
            center = at,
            radius = reach * 3.2f,
        ),
        radius = reach * 3.2f,
        center = at,
        blendMode = BlendMode.Plus,
    )
    // The rays, turning slowly through the point's life.
    val angle = 360f * noise(slot, turn, 5) + 25f * age
    rotate(angle, at) {
        drawPath(rays(at, reach * 2.1f, 0.07f), rayBrush(at, reach * 2.1f, colour, light), blendMode = BlendMode.Plus)
        rotate(45f, at) {
            drawPath(rays(at, reach * 0.9f, 0.10f), rayBrush(at, reach * 0.9f, colour, light * 0.7f), blendMode = BlendMode.Plus)
        }
    }
    // The white-hot centre.
    drawCircle(
        Brush.radialGradient(
            0f to Color.White.copy(alpha = light),
            1f to Color.White.copy(alpha = 0f),
            center = at,
            radius = reach * 0.45f,
        ),
        radius = reach * 0.45f,
        center = at,
        blendMode = BlendMode.Plus,
    )
}

/** White at the centre, the point's colour along the rays, and nothing at their tips. */
private fun rayBrush(at: Offset, reach: Float, colour: Color, light: Float) = Brush.radialGradient(
    0f to Color.White.copy(alpha = light),
    0.25f to colour.copy(alpha = 0.85f * light),
    1f to colour.copy(alpha = 0f),
    center = at,
    radius = reach,
)

/** Four rays crossing at [at], [reach] long, narrowing from a waist of [waist] times their length to sharp tips. */
private fun rays(at: Offset, reach: Float, waist: Float): Path = Path().apply {
    val w = reach * waist
    moveTo(at.x, at.y - reach)
    quadraticTo(at.x + w * 0.4f, at.y - w * 0.4f, at.x + reach, at.y)
    quadraticTo(at.x + w * 0.4f, at.y + w * 0.4f, at.x, at.y + reach)
    quadraticTo(at.x - w * 0.4f, at.y + w * 0.4f, at.x - reach, at.y)
    quadraticTo(at.x - w * 0.4f, at.y - w * 0.4f, at.x, at.y - reach)
    close()
}

/**
 * Where a point lights, new for every [turn]: most on the ring around the photo, some over the photo, and some over the
 * name under it. [unit] is the photo's diameter.
 */
private fun place(slot: Int, turn: Int, centre: Offset, unit: Float): Offset {
    val where = noise(slot, turn, 6)
    val a = noise(slot, turn, 7)
    val b = noise(slot, turn, 8)
    return when {
        where < 0.6f -> {
            val angle = a * 2f * PI.toFloat()
            val distance = unit * (0.48f + 0.16f * b)
            Offset(centre.x + cos(angle) * distance, centre.y + sin(angle) * distance)
        }
        where < 0.8f -> {
            val angle = a * 2f * PI.toFloat()
            val distance = unit * 0.38f * b
            Offset(centre.x + cos(angle) * distance, centre.y + sin(angle) * distance)
        }
        else -> Offset(centre.x + unit * (a - 0.5f) * 1.3f, centre.y + unit * (0.66f + 0.12f * b))
    }
}

/** A number from 0 to 1 that is always the same for the same three inputs. */
private fun noise(slot: Int, turn: Int, which: Int): Float {
    var h = slot * 374761393 + turn * 668265263 + which * 1274126177
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFFFF) / 16777216f
}

/** How many points can be lit at once. */
private const val POINTS = 9

/** How far the light reaches, in photo diameters: across, above the photo's centre, and below it. */
private const val FIELD_WIDE = 1.9f
private const val ABOVE = 0.8f
private const val BELOW = 1.0f

private val WARM_WHITE = Color(0xFFFFF3D6)
