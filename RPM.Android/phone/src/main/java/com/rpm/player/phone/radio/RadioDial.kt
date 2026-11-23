package com.rpm.player.phone.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rpm.player.core.R
import com.rpm.player.core.radio.DialCard
import com.rpm.player.core.radio.RadioStation
import com.rpm.player.core.radio.StationSign
import com.rpm.player.core.radio.countryName
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.RpmFont
import com.rpm.player.phone.design.pt
import com.rpm.player.phone.design.system
import java.util.Locale

/**
 * The height of the dial in dp, and the bottom of the lit readout at its top (`RadioDial.height`, `readoutBottom`).
 * Both match the iPhone app.
 */
internal const val DIAL_HEIGHT = 176.0
private const val READOUT_BOTTOM = 54.0

private val DIAL_CYAN = Color(0xFF04C8FF)
private val SCALE_INK = Color(0xFFC8EEFF)
private val LABEL_INK = Color(0xFFDDF2FF)
private val MARK_INK = Color(0xFFA8D4EE)

/** How close to the glass edge the needle may get before the card is moved along. */
private const val WIND_MARGIN = 56.0

/**
 * The tuning dial. It matches the iPhone's `RadioDial` (RPMUI/RadioDial.swift). A printed card sits behind
 * lit glass. Each station has its call sign and a square at its place, and a needle crosses the dial.
 * Dragging moves the needle. Letting go tunes the station it stopped on. The card is moved along only when
 * the needle would otherwise leave the glass. The card is never re-centred on the station that is tuned.
 * The arithmetic is in [DialCard].
 *
 * @param tuned the station playing. The needle moves to its place when it is tuned from the list.
 * @param announced what the playing station announces. It is shown under the name while that station is under the
 * needle.
 */
@Composable
internal fun RadioDial(stations: List<RadioStation>, tuned: String?, announced: String?, onTune: (RadioStation) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val shape = RoundedCornerShape(10.dp)
    var at by remember { mutableIntStateOf(0) }
    var wound by remember { mutableDoubleStateOf(0.0) }
    val ids = remember(stations) { stations.map { it.uuid } }
    val signs = remember(stations) { stations.map { StationSign.of(it.display) } }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(DIAL_HEIGHT.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0f to Color(0x38001422),
                    0.55f to Color(0x30045070),
                    1f to Color(0x44000A14),
                ),
            )
            .border(1.dp, Ink.white(0x22), shape),
    ) {
        val glass = maxWidth.value.toDouble()
        val card = remember(signs, glass) { DialCard.of(signs, glass, DIAL_HEIGHT, READOUT_BOTTOM) }
        val under = stations.getOrNull(at)

        // Read by the gesture handler, which can outlive one composition. The card and the list it sees are always the
        // latest.
        fun reveal(station: Int, card: DialCard, count: Int) {
            if (card.scaleWidth <= glass) return
            val mark = card.markOf(station, count)
            val x = mark + wound
            if (x >= WIND_MARGIN && x <= glass - WIND_MARGIN) return
            wound = card.slide(mark, if (x < WIND_MARGIN) WIND_MARGIN else glass - WIND_MARGIN, glass)
        }

        // A new list makes a new card, so start at its left edge. Then point at the station that is playing.
        LaunchedEffect(ids) {
            wound = 0.0
            at = at.coerceIn(0, maxOf(0, stations.size - 1))
        }
        LaunchedEffect(ids, tuned, card) {
            val found = stations.indexOfFirst { tuned != null && it.answersTo(tuned) }
            if (found >= 0) {
                at = found
                reveal(found, card, stations.size)
            }
        }

        val tune by rememberUpdatedState(onTune)
        val current by rememberUpdatedState(stations)
        val cardNow by rememberUpdatedState(card)
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(glass) {
                    // A drag moves the needle across the card. On release, the station under the needle is tuned.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun follow(x: Float) {
                            val found = cardNow.nearest(x / density.density - wound, current.size) ?: return
                            if (found != at) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            at = found
                            reveal(found, cardNow, current.size)
                        }
                        follow(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            follow(change.position.x)
                            change.consume()
                        }
                        current.getOrNull(at)?.let {
                            // The iPhone's `Haptics.commit`: the action is done.
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            tune(it)
                        }
                    }
                },
        ) {
            val unit = density.density
            val slid = wound.toFloat() * unit
            for (x in card.ticks) {
                drawRect(SCALE_INK.copy(alpha = 0.55f), Offset((x.toFloat() - 0.5f) * unit + slid, card.scaleTop.toFloat() * unit), Size(unit, DialCard.SCALE_SPAN.toFloat() * unit))
            }
            for (mark in card.printed) {
                val rowTop = (card.namesTop + mark.row * DialCard.NAME_LINE).toFloat() * unit
                val lit = mark.station == at
                val square = DialCard.SQUARE_SIZE.toFloat() * unit
                drawRect(
                    if (lit) DIAL_CYAN else MARK_INK.copy(alpha = 0.8f),
                    Offset(mark.markX.toFloat() * unit - square / 2 + slid, rowTop + (DialCard.NAME_LINE.toFloat() * unit - square) / 2),
                    Size(square, square),
                )
                val text = measurer.measure(
                    mark.caption,
                    TextStyle(fontFamily = RpmFont.cabin, fontSize = (DialCard.NAME_SIZE / density.fontScale).sp, fontWeight = FontWeight.Medium, color = if (lit) DIAL_CYAN else LABEL_INK.copy(alpha = 0.85f)),
                )
                drawText(text, topLeft = Offset((mark.nameLeft + mark.width).toFloat() * unit - text.size.width + slid, rowTop))
            }
            // A thin line down the whole card, with a dimmer, wider line behind it, and a chevron at its top.
            if (stations.isNotEmpty()) {
                val x = card.markOf(at, stations.size).toFloat() * unit + slid
                val top = (card.namesTop - 3).toFloat() * unit
                val tall = (card.scaleBottom - card.namesTop + 3 + 2).toFloat() * unit
                drawRoundRect(DIAL_CYAN.copy(alpha = 0x33 / 255f), Offset(x - 4.5f * unit, top - 2 * unit), Size(9 * unit, tall + 4 * unit), CornerRadius(4 * unit))
                drawRoundRect(DIAL_CYAN, Offset(x - unit, top), Size(2 * unit, tall), CornerRadius(unit))
                val rise = DialCard.POINTER_RISE.toFloat() * unit
                val chevron = Path().apply {
                    moveTo(x - 5 * unit, top - rise)
                    lineTo(x, top - rise + 7 * unit)
                    lineTo(x + 5 * unit, top - rise)
                }
                drawPath(chevron, DIAL_CYAN, style = Stroke(width = 2 * unit, join = StrokeJoin.Round))
            }
        }
        Readout(under, tuned, announced)
    }
}

/**
 * The lit panel at the top of the dial. It shows the station under the needle, what the station is or what it
 * announces, and its sign.
 */
@Composable
private fun Readout(under: RadioStation?, tuned: String?, announced: String?) {
    Row(
        // As tall as its text, at the top of the band the card leaves it, the badge centred on the two lines.
        Modifier.fillMaxWidth().heightIn(max = READOUT_BOTTOM.dp).padding(start = 12.dp, end = 12.dp, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BasicText(
                under?.display ?: stringResource(R.string.Rpm_Radio_DialEmpty),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = system(14f, FontWeight.SemiBold).copy(color = Ink.white(0xF2)),
            )
            val line = when {
                under == null -> ""
                tuned != null && under.answersTo(tuned) && !announced.isNullOrEmpty() -> announced
                // The country and the quality, spaced as the list's lines are.
                else -> listOfNotNull(
                    under.countryCode?.let { countryName(it, Locale.getDefault()) } ?: under.country?.takeIf { it.isNotBlank() },
                    under.quality,
                ).joinToString("  ·  ")
            }
            BasicText(line, maxLines = 1, overflow = TextOverflow.Ellipsis, style = system(11.5f).copy(color = Ink.white(0x99)))
        }
        if (under != null) {
            val badge = RoundedCornerShape(5.dp)
            Box(
                Modifier.clip(badge).background(Color.Black.copy(alpha = 0x26 / 255f)).border(1.dp, Ink.white(0x1A), badge).padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                BasicText(StationSign.of(under.display), style = system(11f, FontWeight.SemiBold).copy(color = DIAL_CYAN, letterSpacing = pt(1f)))
            }
        }
    }
}
