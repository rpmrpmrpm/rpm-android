package com.rpm.player.core.room

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import com.rpm.player.core.diag.Diag
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.rpm.player.core.playback.AudioTap
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The still room as a picture, for a screen to draw inside the window instead of the room's surface
 * behind it ([RoomPicture]).
 *
 * The surface behind the window makes two full-screen layers. The Amiko's display hardware has one plane
 * for them, so SurfaceFlinger blends the two on the GPU every time the window draws, on top of the
 * window's own drawing. A still room has nothing to animate, so it is drawn once into a picture here,
 * and the window is one layer that the hardware shows as it is.
 *
 * Each picture is drawn by a new [RoomRenderer] in a GL context of its own on [thread], into an
 * offscreen surface the size of the window, and read back. It uses the same programs, the same still
 * pose (a still room stops at its first frame's clock) and the same grain. A new renderer starts at
 * the colours asked for, where a kept one would ease towards them. The context is made and destroyed
 * for each picture, which is asked for only when the room's colours change, so nothing is left behind.
 */
class RoomStills(private val tap: AudioTap) {
    private val thread = HandlerThread("rpm.room-still").apply { start() }
    private val dispatcher = Handler(thread.looper).asCoroutineDispatcher()

    /** The still room in [palette] and [style], [dim]med, [width] by [height]; null when no GL context could be made.
     * [bare] is [RoomTarget.bare]: 0 for the player's spread view, which dims the left of its room, so a picture of
     * the player's room (the queue drawer's glass) is dimmed where the room behind it is. */
    suspend fun picture(palette: RoomPalette, style: RoomStyle, width: Int, height: Int, dim: Float, bare: Float = 1f): Bitmap? =
        withContext(dispatcher) { draw(palette, style, width, height, dim, bare) }

    private fun draw(palette: RoomPalette, style: RoomStyle, width: Int, height: Int, dim: Float, bare: Float): Bitmap? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        // Not terminated afterwards: the display is the process's, and the window's renderer uses it too.
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        val configs = arrayOfNulls<EGLConfig>(1)
        val found = IntArray(1)
        val wanted = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        if (!EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, found, 0) || found[0] == 0) return null
        val config = configs[0] ?: return null
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) return null
        val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        try {
            if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) return null
            val target = RoomTarget().also {
                it.palette = palette
                it.style = style
                it.still = true
                it.bare = bare
            }
            val renderer = RoomRenderer(tap, target, RoomTuning.scale, android.util.Size(width, height), measure = false, dim = dim)
            renderer.settle()
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, width, height)
            renderer.onDrawFrame(null)
            val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
            upsideDown(pixels, width * 4, height)
            pixels.position(0)
            // RGBA bytes are how an ARGB_8888 bitmap lies in memory.
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(pixels) }
        } catch (e: RuntimeException) {
            Diag.w(TAG, "still room not drawn", e)
            return null
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }
    }

    /** GL reads rows from bottom to top; a bitmap's rows run from top to bottom. */
    private fun upsideDown(pixels: ByteBuffer, row: Int, rows: Int) {
        val top = ByteArray(row)
        val bottom = ByteArray(row)
        for (i in 0 until rows / 2) {
            val j = rows - 1 - i
            pixels.position(i * row); pixels.get(top)
            pixels.position(j * row); pixels.get(bottom)
            pixels.position(i * row); pixels.put(bottom)
            pixels.position(j * row); pixels.put(top)
        }
    }

    private companion object {
        const val TAG = "rpm.room"
    }
}

/**
 * The still room as a picture filling [modifier]'s box ([RoomStills]), in [palette] and [style], [dim]med.
 * A change of colours or of style draws the new picture and fades it in over the last one in [FADE_MS], where the room's
 * surface eases its colours over about a second. [onShown] is told true in the composition that first
 * draws a picture, so the caller can stop drawing what the picture replaces in the same frame. Nothing
 * is drawn before then.
 */
@Composable
fun RoomPicture(stills: RoomStills, palette: RoomPalette, style: RoomStyle, dim: Float, onShown: () -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        var shown by remember { mutableStateOf<ImageBitmap?>(null) }
        var leaving by remember { mutableStateOf<ImageBitmap?>(null) }
        val fade = remember { Animatable(1f) }
        val tell by rememberUpdatedState(onShown)

        LaunchedEffect(palette, style, width, height, dim) {
            if (width <= 0 || height <= 0) return@LaunchedEffect
            val next = stills.picture(palette, style, width, height, dim)?.asImageBitmap() ?: return@LaunchedEffect
            val before = shown
            if (before == null) {
                shown = next
                tell()
                return@LaunchedEffect
            }
            // A change in the middle of a fade fades from what was arriving.
            leaving = before
            shown = next
            fade.snapTo(0f)
            fade.animateTo(1f, tween(FADE_MS))
            leaving = null
        }

        Canvas(Modifier.matchParentSize()) {
            leaving?.let { drawImage(it) }
            shown?.let { drawImage(it, alpha = fade.value) }
        }
    }
}

private const val FADE_MS = 600
