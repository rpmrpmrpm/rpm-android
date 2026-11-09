package com.rpm.player.phone.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.core.downloads.AlbumDownloads.Standing
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.icon

/**
 * What the download key on a record's page needs, as the iPhone's `DownloadKey` (RPMUI/DownloadKey.swift): where the
 * album stands on this phone, and the two things the person can do.
 *
 * The page is handed null on a borrowed shelf, rather than a key that does nothing: a download is signed as the
 * account's own track, and another person's record is not one.
 */
class DownloadKey(
    /** Where the album stands on the phone, or null when nobody asked for it. */
    val standing: Standing?,
    val onDownload: () -> Unit,
    val onRemove: () -> Unit,
)

/**
 * The key itself, the size of the shuffle key beside it (the iPhone's `DownloadButton`). It has five faces: an arrow
 * to download; a turning arc while the store prepares the chosen rate; a ring that fills while downloading; a tick
 * when every track is on the phone; a warning when some tracks could not be downloaded, which tries again. Removing
 * asks first, because a removed album has to be downloaded again to play offline.
 */
@Composable
fun DownloadButton(key: DownloadKey, modifier: Modifier = Modifier, bare: Boolean = false) {
    var asking by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val standing = key.standing
    val label = when (standing) {
        null -> stringResource(R.string.Rpm_Download_Download)
        is Standing.Downloading -> if (standing.preparing) {
            stringResource(R.string.Rpm_Download_Preparing, standing.have.toString(), standing.of.toString())
        } else {
            stringResource(R.string.Rpm_Download_Progress, standing.have.toString(), standing.of.toString())
        }
        Standing.OnPhone -> stringResource(R.string.Rpm_Download_OnPhone)
        is Standing.Stuck -> stringResource(R.string.Rpm_Download_Stuck)
    }

    val press = {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        when (standing) {
            null, is Standing.Stuck -> key.onDownload()
            is Standing.Downloading, Standing.OnPhone -> asking = true
        }
    }
    val face: @Composable (Color) -> Unit = { ink ->
        when (standing) {
            null -> BasicText(Glyph.download, style = icon(17f).copy(color = ink))
            is Standing.Downloading -> Ring(standing.fraction.toFloat(), standing.preparing)
            Standing.OnPhone -> BasicText(Glyph.downloaded, style = icon(17f).copy(color = ink))
            is Standing.Stuck -> BasicText(Glyph.stuck, style = icon(17f).copy(color = Ink.white(0xB3)))
        }
    }

    if (bare) {
        // Its mark alone, in a box as tall as the Play key beside it, which is the part that answers a tap.
        Box(
            modifier
                .size(56.dp, 50.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = press)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { face(Ink.white(0xF0)) }
    } else {
        GlassButton(
            onClick = press,
            modifier = modifier.width(56.dp).semantics { contentDescription = label },
            stretches = false,
            height = 50.dp,
            radius = 14.dp,
            inset = 0.dp,
        ) { style -> face(style.color) }
    }

    if (asking) {
        val stopping = standing is Standing.Downloading
        ConfirmSheet(
            question = stringResource(if (stopping) R.string.Rpm_Download_StopQuestion else R.string.Phone_Download_RemoveQuestion),
            action = stringResource(if (stopping) R.string.Rpm_Download_Stop else R.string.Rpm_Download_Remove),
            onConfirm = {
                asking = false
                key.onRemove()
            },
            onDismiss = { asking = false },
        )
    }
}

/**
 * The ring of a download under way, 20 points, with a stop mark in its middle like the system's download rings. While
 * nothing transfers yet there is no progress to show, so a quarter arc turns instead, like the system's waiting
 * download. The turn is read in the draw, so it redraws the ring and recomposes nothing.
 */
@Composable
private fun Ring(fraction: Float, preparing: Boolean) {
    val shown by animateFloatAsState(fraction.coerceIn(0.03f, 1f), tween(300, easing = FastOutSlowInEasing), label = "download ring")
    val turn = if (preparing) {
        rememberInfiniteTransition(label = "preparing").animateFloat(
            0f,
            360f,
            infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
            label = "preparing turn",
        )
    } else {
        null
    }
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp)) {
            val line = 2.5.dp.toPx()
            val inset = line / 2
            val arc = Size(size.width - line, size.height - line)
            drawCircle(Ink.white(0x33), radius = size.minDimension / 2 - inset, style = Stroke(line))
            if (turn != null) {
                rotate(turn.value) {
                    drawArc(Color.White, -90f, 90f, false, Offset(inset, inset), arc, style = Stroke(line, cap = StrokeCap.Round))
                }
            } else {
                drawArc(Color.White, -90f, 360f * shown, false, Offset(inset, inset), arc, style = Stroke(line, cap = StrokeCap.Round))
            }
            val mark = 6.dp.toPx()
            drawRoundRect(
                Color.White,
                topLeft = Offset((size.width - mark) / 2, (size.height - mark) / 2),
                size = Size(mark, mark),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
        }
    }
}
