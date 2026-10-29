package com.rpm.player.phone.shell

import android.os.Process
import android.os.SystemClock
import com.rpm.player.core.diag.Diag
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rpm.player.phone.design.pt
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * How long the launch took, shown on the wait screen for six seconds: the iPhone's `LaunchReadout`
 * (RPMUI/LaunchClock.swift). It is on the screen and not only in the log, so the number can be read on a phone without
 * a console. Time zero is the process's start as the system recorded it, not anything in the app, which starts later.
 *
 * The text is the iPhone's, not a translated string: it is a measurement for whoever builds the app.
 */
@Composable
internal fun LaunchReadout() {
    var firstFrame by remember { mutableStateOf<Double?>(null) }
    var showing by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val seconds = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000.0
        firstFrame = seconds
        Diag.i(TAG, "first frame at %.3fs".format(Locale.ROOT, seconds))
        delay(SHOWN_MS)
        showing = false
    }
    val at = firstFrame ?: return
    if (!showing) return
    BasicText(
        "first frame %.2fs".format(Locale.ROOT, at),
        Modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = pt(11f), color = Color.White),
    )
}

private const val SHOWN_MS = 6_000L

/** For `adb logcat -s rpm.launch`, as the iPhone logs its launch milestones. */
private const val TAG = "rpm.launch"
