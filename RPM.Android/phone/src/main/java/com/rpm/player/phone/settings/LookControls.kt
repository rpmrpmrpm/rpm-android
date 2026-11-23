package com.rpm.player.phone.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.controlColour
import com.rpm.player.phone.design.display
import com.rpm.player.phone.ui.BackdropVeil
import com.rpm.player.phone.ui.CoverCorner

/**
 * The two sliders that dim and blur the app's background ([BackdropVeil]), under the backdrop tiles on the Appearance
 * settings and in the welcome's look step (`BackdropVeilSliders` on iPhone). The background behind them changes as
 * they move; the values are kept when the finger lifts.
 */
@Composable
fun BackdropVeilSliders() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Labelled(stringResource(R.string.Settings_BackgroundDim), BackdropVeil.dim, BackdropVeil::changeDim)
        Labelled(stringResource(R.string.Settings_BackgroundBlur), BackdropVeil.blur, BackdropVeil::changeBlur)
    }
}

@Composable
private fun Labelled(said: String, value: Float, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(said, style = display(13f).copy(color = Ink.white(0xB3)))
        LookSlider(value, 0f..1f, said, onChange, onRelease = BackdropVeil::save)
    }
}

/**
 * The settings card that chooses how round the covers are ([CoverCorner]), from square corners to round ones
 * (`CoverCornerCard` on iPhone). See [CoverCornerSlider].
 */
@Composable
fun CoverCornerCard() {
    Card(Glyph.corners, stringResource(R.string.Settings_CoverCorners)) {
        CoverCornerSlider()
    }
}

/**
 * The slider that chooses how round the covers are, with a cover beside it that shows the rounding as it moves
 * (`CoverCornerSlider` on iPhone). It is on the Appearance settings and in the welcome's look step.
 *
 * **The covers of the app change when the finger lifts**, not on every step of the slider. Every cover on every page
 * that is composed reads the setting, and drawing them all again on each step would make the slider stutter. The cover
 * beside the slider follows the finger.
 */
@Composable
fun CoverCornerSlider() {
    // The share while the finger is on the slider; null when it is not.
    var moving by remember { mutableStateOf<Float?>(null) }
    val shown = moving ?: CoverCorner.share
    val label = stringResource(R.string.Settings_CoverCorners)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val shape = RoundedCornerShape(PREVIEW * shown)
        Spacer(
            Modifier
                .size(PREVIEW)
                .clip(shape)
                .background(Brush.linearGradient(listOf(Ink.cyan, SELECTED_WASH)))
                .border(1.dp, Ink.cardBorder, shape),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LookSlider(
                shown,
                0f..CoverCorner.ROUNDEST,
                label,
                onChange = { moving = it },
                onRelease = {
                    moving?.let(CoverCorner::change)
                    moving = null
                },
            )
            Row(Modifier.fillMaxWidth()) {
                val style = display(12f).copy(color = Ink.white(0x88))
                BasicText(stringResource(R.string.Settings_CoverCornersSharp), Modifier.weight(1f), style = style)
                BasicText(stringResource(R.string.Settings_CoverCornersRound), style = style)
            }
        }
    }
}

/** The side of the cover beside the corner slider. */
private val PREVIEW = 56.dp

/** The iPhone's `RPMInk.selectedWash`, opaque, the far end of the preview cover's gradient. */
private val SELECTED_WASH = Color(0xFF0371D0)

/**
 * An iOS slider: a thin track lit up to a white knob, in the tint the iPhone gives its controls: the glass colour, else
 * white at 0x88, as the switches on this page ([controlColour]). A drag along it moves the knob, and a tap puts the knob where it lands; a drag that starts
 * upright scrolls the page instead. [onChange] is told each new value while the finger moves, and [onRelease] when it
 * lifts, which is when a setting is kept.
 */
@Composable
fun LookSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    onChange: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val change by rememberUpdatedState(onChange)
    val release by rememberUpdatedState(onRelease)
    var width by remember { mutableIntStateOf(0) }
    var x by remember { mutableFloatStateOf(0f) }
    val knob = KNOB
    val knobPx = with(LocalDensity.current) { knob.toPx() }
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)

    Spacer(
        modifier
            .fillMaxWidth()
            .height(knob + 6.dp)
            .onSizeChanged { width = it.width }
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value, range)
                setProgress { asked ->
                    change(asked.coerceIn(range.start, range.endInclusive))
                    release()
                    true
                }
            }
            .pointerInput(range) {
                detectTapGestures { at ->
                    change(valueAt(at.x, width.toFloat(), knobPx, range))
                    release()
                }
            }
            .draggable(
                rememberDraggableState { delta ->
                    x += delta
                    change(valueAt(x, width.toFloat(), knobPx, range))
                },
                Orientation.Horizontal,
                onDragStarted = { at ->
                    x = at.x
                    change(valueAt(x, width.toFloat(), knobPx, range))
                },
                onDragStopped = { release() },
            )
            .drawBehind {
                val track = 4.dp.toPx()
                val travel = (size.width - knobPx).coerceAtLeast(0f)
                val centre = knobPx / 2 + travel * fraction
                val y = size.height / 2
                val ends = CornerRadius(track / 2)
                drawRoundRect(TRACK, Offset(knobPx / 2, y - track / 2), Size(travel, track), ends)
                drawRoundRect(controlColour(LIT), Offset(knobPx / 2, y - track / 2), Size(centre - knobPx / 2, track), ends)
                // The knob's soft shadow, then the knob.
                drawCircle(Color.Black.copy(alpha = 0.12f), knobPx / 2 + 1.dp.toPx(), Offset(centre, y + 1.5.dp.toPx()))
                drawCircle(Color.White, knobPx / 2, Offset(centre, y))
            },
    )
}

/** The value at [x] along a slider [width] wide whose knob is [knob] wide: the knob's centre travels between its ends. */
internal fun valueAt(x: Float, width: Float, knob: Float, range: ClosedFloatingPointRange<Float>): Float {
    val travel = (width - knob).coerceAtLeast(1f)
    val fraction = ((x - knob / 2) / travel).coerceIn(0f, 1f)
    return range.start + fraction * (range.endInclusive - range.start)
}

/** The knob of a slider, as large as an iOS slider's. */
private val KNOB = 26.dp

/** The tint of the lit part of the track: the iPhone's control tint. */
private val LIT = Ink.white(0x88)

/** The unlit track, the grey of a switch that is off. */
private val TRACK = Color(0x52787880)
