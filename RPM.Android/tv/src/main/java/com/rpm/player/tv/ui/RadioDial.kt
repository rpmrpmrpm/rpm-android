package com.rpm.player.tv.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.rpm.player.core.R
import com.rpm.player.core.radio.StationLogo
import kotlinx.coroutines.launch

/** dp per dial unit: the desktop scales its 176-high dial by `clamp(0.28 h / 176, 1, 4)` (1.72 on a
 * 1080 window), and a desktop pixel is half a dp here. */
val DIAL_U: Dp = (1080f * 0.28f / 176f).coerceIn(1f, 4f).let { (it * 0.5f).dp }

/** What the dial shows: the stations in the tuner's order, which one the needle is on, and the readout. */
class DialFace(
    val names: List<String>,
    /** The station under the needle, or -1 for none. */
    val at: Int,
    /** The station playing, whose tick and name are lit; -1 for none. */
    val tuned: Int,
    val name: String,
    val status: String,
    val badge: StationLogo?,
    val reconnecting: Boolean,
    /** The cover of the song the station is playing, drawn over the badge once it is in, as on the mini player. */
    val cover: String? = null,
)

/**
 * The tuner's dial (Main/LeftPane/Controls/RadioDial.cs): a glass card of the stations' names over a
 * scale of ticks. There is one tick per station, in list order and evenly spaced (there are no frequencies).
 * A cyan needle points at a station. In the top left is a readout of the station's name and what it is
 * playing, with the playing station's logo before it. Names are packed into up to six rows. A name that fits
 * no row is left out, and its tick stays. With too many stations for a tick each, the scale is a plain ruler.
 *
 * The needle and the card move together over 260 ms, easing out, toward a new station. The card moves
 * only when the needle would come within `min(56, w / 3)` of an edge. A new list puts the card back at
 * its start without moving smoothly. Every figure is in dial units ([DIAL_U]). The dial is amber while reconnecting.
 */
@Composable
fun RadioDial(face: DialFace, images: ImageLoader, modifier: Modifier) {
    val context = LocalContext.current
    val regular = remember { ResourcesCompat.getFont(context, R.font.selawik_regular) ?: Typeface.DEFAULT }
    val semibold = remember { ResourcesCompat.getFont(context, R.font.selawik_semibold) ?: Typeface.DEFAULT }
    val needleX = remember { Animatable(0f) }
    val scroll = remember { Animatable(0f) }
    var badgeShown by remember(face.badge) { mutableStateOf(false) }
    // True when the badge could not be loaded. Only then does the readout take its place.
    var badgeMissing by remember(face.badge) { mutableStateOf(false) }
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(DIAL_U * 10f))
            .background(GLASS, RoundedCornerShape(DIAL_U * 10f))
            .border(1.px, Color(0x22FFFFFF), RoundedCornerShape(DIAL_U * 10f)),
    ) {
        val u = with(density) { DIAL_U.toPx() }
        val w = constraints.maxWidth / u
        val layout = remember(face.names, w) { DialLayout.of(face.names, w) }

        // A new list moves the card to its start at once. A new station moves the needle smoothly and, when needed, the card.
        LaunchedEffect(layout) {
            scroll.snapTo(0f)
            needleX.snapTo(layout.xOf(face.at))
        }
        LaunchedEffect(face.at, layout) {
            val target = layout.xOf(face.at)
            val margin = minOf(56f, w / 3)
            val shown = target - scroll.targetValue
            val wanted = when {
                layout.scaleWidth <= w -> 0f
                shown < margin -> (target - margin).coerceIn(0f, layout.scaleWidth - w)
                shown > w - margin -> (target - (w - margin)).coerceIn(0f, layout.scaleWidth - w)
                else -> scroll.targetValue
            }
            launch { needleX.animateTo(target, tween(GLIDE_MS, easing = DialOut)) }
            launch { scroll.animateTo(wanted, tween(GLIDE_MS, easing = DialOut)) }
        }

        Canvas(Modifier.fillMaxSize()) {
            val ink = if (face.reconnecting) AMBER else CYAN
            val off = scroll.value
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.scale(u, u)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = regular
                    textSize = 9f
                    letterSpacing = 0.06f
                    textAlign = Paint.Align.RIGHT
                }
                // The names, each directly left of its square, in the row it was packed into.
                for (p in layout.placed) {
                    val x = p.x - off
                    if (x < -60 || x > w + 60) continue
                    val lit = p.index == face.tuned
                    val rowTop = layout.namesTop + p.row * 11f
                    paint.color = (if (lit) ink else NAME).copy(alpha = if (lit) 1f else 0.62f).toArgb()
                    native.drawText(face.names[p.index], x - 3.25f - 4f, rowTop + 9f, paint)
                    val square = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (if (lit) ink else SQUARE).copy(alpha = if (lit) 1f else 0.7f).toArgb() }
                    native.drawRect(x - 3.25f, rowTop + 2.25f, x + 3.25f, rowTop + 8.75f, square)
                }
                // The scale.
                val tick = Paint()
                for (t in layout.ticks) {
                    val x = t.x - off
                    if (x < -2 || x > w + 2) continue
                    val lit = t.index >= 0 && t.index == face.tuned
                    val height = when {
                        lit -> 16f
                        t.major -> 16f * 0.62f
                        else -> 16f * 0.36f
                    }
                    tick.color = (if (lit) ink else TICK).copy(alpha = if (lit) 1f else if (t.major) 0.55f else 0.28f).toArgb()
                    native.drawRect(x - 0.5f, layout.scaleBottom - height, x + 0.5f, layout.scaleBottom, tick)
                }
                // The readout. It starts after the badge's place while a badge is shown or on its way, so the name
                // does not move right when the badge arrives.
                val left = if (face.badge != null && !badgeMissing) 46f else 12f
                val room = w - left - 12f
                val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = semibold; textSize = 14f; color = Color(0xF2FFFFFF).toArgb() }
                val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular; textSize = 11.5f; color = Color(0x99FFFFFF).toArgb() }
                if (face.name.isNotEmpty()) {
                    native.drawText(ellipsized(face.name, namePaint, room), left, 11f - namePaint.ascent(), namePaint)
                    native.drawText(ellipsized(face.status, statusPaint, room), left, 32f - statusPaint.ascent(), statusPaint)
                } else {
                    native.drawText(ellipsized(face.status, statusPaint, room), left, 11f - statusPaint.ascent(), statusPaint)
                }
                native.restore()
            }
            // The needle: its glow, the hairline, and the chevron pointing down at the top.
            val x = (needleX.value - off) * u
            val top = (layout.namesTop - 3f) * u
            val bottom = (layout.scaleBottom + 2f) * u
            drawRoundRect(ink.copy(alpha = 0x33 / 255f), Offset(x - 4.5f * u, top - 2 * u), Size(9 * u, bottom - top + 4 * u), CornerRadius(4 * u))
            drawRoundRect(ink, Offset(x - u, top), Size(2 * u, bottom - top), CornerRadius(u))
            val chevronTop = top - 13 * u
            drawPath(
                Path().apply {
                    moveTo(x - 5 * u, chevronTop)
                    lineTo(x, chevronTop + 7 * u)
                    lineTo(x + 5 * u, chevronTop)
                },
                ink,
                style = Stroke(width = 2 * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        // The playing station's logo before the readout, shown only once it has loaded.
        face.badge?.let { logo ->
            val shape = RoundedCornerShape(DIAL_U * 5f)
            Box(
                Modifier
                    .offset(DIAL_U * 12f, DIAL_U * 8f)
                    .size(DIAL_U * 26f)
                    .clip(shape)
                    .background(if (badgeShown) Color(0x26000000) else Color.Transparent)
                    .border(1.px, if (badgeShown) Color(0x1AFFFFFF) else Color.Transparent, shape),
            ) {
                StationMark(
                    logo,
                    images,
                    inset = 2f / 26f, fadeInMs = 0,
                    onState = {
                        badgeShown = it is AsyncImagePainter.State.Success
                        if (it is AsyncImagePainter.State.Error) badgeMissing = true
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                SongCoverOver(face.cover, images, Modifier.fillMaxSize())
            }
        }
    }
}

private fun ellipsized(text: String, paint: Paint, room: Float): String {
    if (paint.measureText(text) <= room) return text
    var end = text.length
    while (end > 0 && paint.measureText(text, 0, end) + paint.measureText("…") > room) end--
    return text.substring(0, end).trimEnd() + "…"
}

/**
 * Where everything on the dial goes, worked out once per list and width (RadioDial.cs, Layout): the
 * scale's width from the names' estimated width, each station's place, the rows the names are packed into,
 * and the ticks.
 */
private class DialLayout(
    val scaleWidth: Float,
    val count: Int,
    val placed: List<Placed>,
    val ticks: List<Tick>,
    val namesTop: Float,
    val scaleBottom: Float,
) {
    class Placed(val index: Int, val x: Float, val row: Int)
    class Tick(val index: Int, val x: Float, val major: Boolean)

    /** Where station [i] sits on the card. For no station (a negative [i]) it is the start of the scale. */
    fun xOf(i: Int): Float = if (i < 0 || count == 0) 16f else 16f + (i + 0.5f) / count * (scaleWidth - 32f)

    companion object {
        private const val MAX_ROWS = 6

        fun of(names: List<String>, w: Float): DialLayout {
            val n = names.size
            val widths = names.map { maxOf(8f, it.length * 6.3f) }
            val ink = widths.sumOf { (it + 10.5f + 7f).toDouble() }.toFloat()
            val scaleWidth = (ink / MAX_ROWS + 32f).coerceIn(w, 6 * w)
            fun x(i: Int) = 16f + (i + 0.5f) / maxOf(1, n) * (scaleWidth - 32f)
            val pitch = if (n == 0) 0f else (scaleWidth - 32f) / n
            val perStation = n > 0 && pitch >= 4f

            val placed = ArrayList<Placed>()
            var rowsUsed = 1
            if (perStation) {
                val rowEnds = FloatArray(MAX_ROWS) { Float.NEGATIVE_INFINITY }
                for (i in 0 until n) {
                    val right = x(i) - 3.25f - 4f
                    val leftEdge = right - widths[i]
                    val row = (0 until MAX_ROWS).firstOrNull { leftEdge >= rowEnds[it] } ?: continue
                    rowEnds[row] = x(i) + 3.25f + 7f
                    rowsUsed = maxOf(rowsUsed, row + 1)
                    placed += Placed(i, x(i), row)
                }
            }
            val ticks = if (perStation) {
                (0 until n).map { Tick(it, x(it), it % 5 == 0) }
            } else {
                val count = maxOf(8, ((scaleWidth - 32f) / 11f).toInt())
                (0 until count).map { Tick(-1, 16f + (it + 0.5f) / count * (scaleWidth - 32f), it % 5 == 0) }
            }
            val room = 114f
            val block = 13f + rowsUsed * 11f + 16f
            val top = 54f + maxOf(0f, (room - block) / 2f) + 13f
            val scaleTop = top + rowsUsed * 11f
            return DialLayout(scaleWidth, n, placed, ticks, namesTop = top, scaleBottom = scaleTop + 16f)
        }
    }
}

private val GLASS = Brush.verticalGradient(0f to Color(0x38001422), 0.55f to Color(0x30045070), 1f to Color(0x44000A14))
private val CYAN = Color(0xFF04C8FF)
private val AMBER = Color(0xFFFFA82B)
private val NAME = Color(0xFFDDF2FF)
private val SQUARE = Color(0xFFA8D4EE)
private val TICK = Color(0xFFC8EEFF)
private const val GLIDE_MS = 260
private val DialOut = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
