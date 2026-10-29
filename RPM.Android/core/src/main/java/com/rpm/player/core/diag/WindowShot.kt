package com.rpm.player.core.diag

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** A picture of the activity's window as it is now, for a feedback report. */
object WindowShot {
    /**
     * The window as PNG, the long edge at most [ShotFit.MAX_EDGE]. A picture over [ShotFit.MAX_BYTES] is drawn smaller
     * until it fits, at most three times; null when it never fits or the window cannot be read. `PixelCopy` reads
     * what the compositor shows, so the GL surfaces (the room, the stage) are in the picture. Call it before the
     * feedback screen opens over the window.
     */
    suspend fun capture(activity: Activity): ByteArray? {
        val view = activity.window?.decorView ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val copied = suspendCancellableCoroutine { done ->
            PixelCopy.request(activity.window, full, { result -> done.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
        }
        if (!copied) return null

        return withContext(Dispatchers.Default) {
            var scale = ShotFit.scale(full.width, full.height, ShotFit.MAX_EDGE)
            repeat(4) {
                val w = (full.width * scale).toInt().coerceAtLeast(1)
                val h = (full.height * scale).toInt().coerceAtLeast(1)
                val small = if (scale < 1.0) Bitmap.createScaledBitmap(full, w, h, true) else full
                val png = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                if (small !== full) small.recycle()
                if (png.size <= ShotFit.MAX_BYTES) return@withContext png.also { full.recycle() }
                scale *= 0.75
            }
            full.recycle()
            null
        }
    }
}
