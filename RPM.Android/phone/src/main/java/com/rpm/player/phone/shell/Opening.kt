package com.rpm.player.phone.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.os.SystemClock
import com.rpm.player.core.R
import com.rpm.player.core.opening.SWEEP_MS
import com.rpm.player.core.opening.SweepSurface
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.system
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * The opening screen, as the TV's and the desktop's (Chivan, 2026-11-03): the rpm wordmark on black, dimmed, with
 * a band of light sweeping across it once a second until the app is ready, in place of the launcher icon Android
 * would show. The system draws the dimmed wordmark from the moment the app is tapped (opening_splash.xml for the
 * launch screen, opening_window.xml before Android 12), at the same size and place as here, so nothing moves when
 * the app's first frame comes; [SweepSurface] then lights it.
 *
 * [ready] hands over at the end of a pass, never in the middle of one, and [onSwept] is called then, with the band
 * fading out. The caller fades the rest out with it. [reading] is the line under the logo while the library is read
 * from the cloud with nothing kept on the phone.
 */
@Composable
fun Opening(ready: Boolean, reading: String?, onSwept: () -> Unit, modifier: Modifier) {
    val handOver by rememberUpdatedState(ready)
    val swept by rememberUpdatedState(onSwept)
    val sweep = remember { arrayOfNulls<SweepSurface>(1) }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.wordmark_dim), contentDescription = null, modifier = Modifier.size(WORDMARK_WIDE, WORDMARK_HIGH))
        AndroidView(
            factory = { context -> SweepSurface(context).also { sweep[0] = it } },
            modifier = Modifier.size(WORDMARK_WIDE, WORDMARK_HIGH),
        )
        val lineIn by animateFloatAsState(if (reading != null) 1f else 0f, tween(READING_FADE_MS), label = "reading")
        val line = remember { arrayOfNulls<String>(1) }
        if (reading != null) line[0] = reading
        val shownLine = line[0]
        if (shownLine != null && lineIn > 0f) {
            BasicText(
                shownLine,
                style = system(13f).copy(color = Ink.secondary),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = WORDMARK_HIGH / 2 + READING_GAP)
                    .graphicsLayer { alpha = lineIn },
            )
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { handOver }.first { it }
        val surface = sweep[0]
        if (surface != null) {
            val elapsed = SystemClock.uptimeMillis() - surface.began
            delay(SWEEP_MS - elapsed % SWEEP_MS)
            surface.fadeOut(OPENING_FADE_MS)
        }
        swept()
    }
}

/** The wordmark's size, the same 130 by 50 dp as wordmark_dim.xml and the launch screen's. */
private val WORDMARK_WIDE = 130.dp
private val WORDMARK_HIGH = 50.dp

/** How far under the logo's middle the reading line stands, past the logo's lower half. */
private val READING_GAP = 24.dp

/** How long the reading line takes to come up. */
private const val READING_FADE_MS = 300

/** How long the opening screen takes to fade away once the app is ready. */
const val OPENING_FADE_MS = 600
