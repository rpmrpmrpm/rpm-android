package com.rpm.player.core.opening

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.rpm.player.core.R

/**
 * The opening's band of light: the white wordmark, kept only under a gradient that rides across it once a
 * second, drawn into a surface over the window by a thread of its own and paced by that thread's own
 * Choreographer. Nothing it draws waits for the main thread. The TV's opening and the phone's both draw it.
 *
 * The band is [BAND_OF_WIDTH] of the wordmark wide, and its light falls from the middle to nothing at either
 * edge along a smootherstep, with no corner anywhere for the eye to find. It draws no dimmed wordmark of its
 * own: that is under it, in the window and in the opening screen ([R.drawable.wordmark_dim]).
 */
@SuppressLint("ViewConstructor")
class SweepSurface(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    /** When the sweep started, on the uptime clock: a pass ends every [SWEEP_MS] from here. */
    val began: Long = SystemClock.uptimeMillis()

    /** When the band began to fade out, or 0 while it has not. Written on the main thread, read on the
     * drawing one. */
    @Volatile private var fadeFrom = 0L
    @Volatile private var fadeFor = 1L

    private var drawing: HandlerThread? = null
    private var painter: Painter? = null

    init {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
    }

    /** Fades the band out over [millis], as the screen under it fades. */
    fun fadeOut(millis: Int) {
        fadeFor = millis.toLong().coerceAtLeast(1)
        fadeFrom = SystemClock.uptimeMillis()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        stop()
        if (width <= 0 || height <= 0) return
        val mark = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        context.getDrawable(R.drawable.wordmark)?.apply {
            setBounds(0, 0, width, height)
            draw(Canvas(mark))
        }
        val thread = HandlerThread("rpm-opening").apply { start() }
        val drawer = Painter(holder, mark, width.toFloat(), height.toFloat())
        drawing = thread
        painter = drawer
        Handler(thread.looper).post { drawer.start() }
    }

    /** The surface is released as soon as this returns, so no frame may still be on its way to it. */
    override fun surfaceDestroyed(holder: SurfaceHolder) = stop()

    /**
     * Stops the drawing and waits for the thread to end, frame in hand included. The wait has no short
     * limit: a frame posted to a surface that is released under it aborts the app on the render thread
     * (EGL_BAD_ALLOC in swapBuffers, seen on a low-end TV box with a 200 ms limit). A frame takes
     * milliseconds; [STOP_WAIT_MS] only keeps a stuck thread from holding the main one for ever.
     */
    private fun stop() {
        painter?.live = false
        painter = null
        val thread = drawing ?: return
        drawing = null
        thread.quitSafely()
        thread.join(STOP_WAIT_MS)
    }

    private inner class Painter(
        private val holder: SurfaceHolder,
        private val mark: Bitmap,
        private val wide: Float,
        private val high: Float,
    ) : Choreographer.FrameCallback {
        private val choreographer = Choreographer.getInstance()
        private val markPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val bandPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = LinearGradient(0f, 0f, BAND_OF_WIDTH * wide, 0f, BAND_COLOURS, BAND_STOPS, Shader.TileMode.CLAMP)
        }
        private val place = Matrix()

        /** False once the surface is going: no frame is begun after that. */
        @Volatile var live = true

        fun start() = choreographer.postFrameCallback(this)

        override fun doFrame(frameTimeNanos: Long) {
            if (!live) return
            val now = SystemClock.uptimeMillis()
            val from = fadeFrom
            val shown = if (from == 0L) 1f else (1f - (now - from) / fadeFor.toFloat()).coerceIn(0f, 1f)
            val pass = quadInOut(((now - began) % SWEEP_MS) / SWEEP_MS.toFloat())
            if (!draw(pass, shown)) return
            if (shown > 0f) choreographer.postFrameCallback(this)
        }

        /**
         * One frame: cleared, the white wordmark, then everything outside the band taken away. False when the
         * surface has gone.
         *
         * Drawn on a software canvas, 260 by 100 pixels a frame. A hardware one (lockHardwareCanvas) gives the
         * surface a renderer of its own on the app's render thread, and on a low-end TV box that thread then aborts
         * the app with EGL_BAD_ALLOC within seconds of launching, in about one launch in three.
         */
        private fun draw(pass: Float, shown: Float): Boolean {
            val canvas = try {
                holder.lockCanvas()
            } catch (_: Exception) {
                null
            } ?: return false
            try {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                markPaint.alpha = (255 * shown).toInt()
                canvas.drawBitmap(mark, 0f, 0f, markPaint)
                // The band rides from wholly before the first letter to wholly after the last.
                val band = BAND_OF_WIDTH * wide
                place.setTranslate(-band + pass * (wide + band), 0f)
                bandPaint.shader.setLocalMatrix(place)
                canvas.drawRect(0f, 0f, wide, high, bandPaint)
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
            return true
        }
    }

    private companion object {
        /** The longest the main thread waits for the drawing thread to end when the surface goes. */
        const val STOP_WAIT_MS = 3_000L
    }
}

/** One pass of the band across the wordmark. */
const val SWEEP_MS = 1000L

/** The band's width, as a share of the wordmark's. */
private const val BAND_OF_WIDTH = 0.6f

/** The band's light across its width: 1 - smootherstep(distance from the middle), white at the middle and
 * nothing at either edge, flat at all three. */
private val BAND_STOPS = FloatArray(17) { it / 16f }
private val BAND_COLOURS = IntArray(BAND_STOPS.size) { i ->
    val x = kotlin.math.abs(2 * BAND_STOPS[i] - 1)
    val light = 1 - x * x * x * (x * (x * 6 - 15) + 10)
    android.graphics.Color.argb((255 * light).toInt(), 255, 255, 255)
}

/** The pass's pace: slow out of the start, slow into the end. */
private fun quadInOut(t: Float) = if (t < 0.5f) 2 * t * t else 1 - 2 * (1 - t) * (1 - t)
