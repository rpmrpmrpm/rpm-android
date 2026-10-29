package com.rpm.player.core.room

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.rpm.player.core.diag.Diag
import android.view.Choreographer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.rpm.player.core.playback.AudioTap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp
import kotlin.math.sin

/**
 * Rooms drawn as pictures for a screen to draw inside its own window, the way the iPhone draws its rooms: as part of
 * the page, clipped, rounded, scrolled and slid with it, and seen by the glass laid over it.
 *
 * **Why pictures and not a surface.** [RoomView] is a `SurfaceView`, which the system composes behind the window. That
 * is right for a room that fills the screen and stays put (the TV, the phone's landscape stage), and wrong for a room
 * inside a page: a surface cannot be rounded, faded or clipped by the page, it lags a scroll by a frame, and the glass
 * on the page ([backdrop layers] record the page's own drawing) cannot see it. A `TextureView` follows the page, but
 * each one is a view of its own with a surface of its own, made and destroyed as rows scroll, inside Compose content
 * that is otherwise all drawing. A picture is just drawing: any number of places can show it, and Compose clips,
 * fades and scrolls it like any other image.
 *
 * **How.** One thread, one GL context, for every room in the app. Each room ([PaintedRoom]) is drawn by the iPhone's
 * own programs (`rpmCardRoom`, `rpmRoom`, through [RoomShaders.ROOM_FRAGMENT]'s figures) into a small framebuffer of
 * its own, read back, and copied into one of three bitmaps it takes turns with. The screen is told on the main thread
 * and draws the newest bitmap stretched over the room's box. A room is soft gradients only: drawn at a tenth of its
 * pixels and stretched smooth, it loses nothing that can be seen, and its pass and its read-back are a few thousand
 * pixels. Three bitmaps, not two: the one being written is never one the render thread may still be drawing from.
 *
 * **When.** On the display's frame clock, every [EVERY]th frame (30 a second on a 60 Hz screen), and only while some
 * room is [PaintedRoom.running]. All rooms are drawn on the same frames, so two rooms on one screen make the window
 * draw 30 times a second, not 60. With nothing running the thread sleeps. A room that is not running keeps its last
 * picture, as the iPhone's paused room keeps its frame; it is drawn once more only when its size or colours change.
 * While a room that covers the screen runs ([PaintedRoom.covers]: the now playing sheet), every other room stops.
 *
 * A stage room ([stage]) is lit by the music: the three levels and the spectrum's run are read from [tap] on every
 * display frame, because their envelopes are tuned to the display's rate (RoomLevels), even when the room itself is
 * drawn on every other one.
 */
class RoomPainter(private val tap: AudioTap) {
    private val thread = HandlerThread("rpm.room-paint").apply { start() }
    private val gl = Handler(thread.looper)

    // Everything below is the GL thread's alone.
    private val rooms = ArrayList<PaintedRoom>()
    private var display: EGLDisplay? = null
    private var context: EGLContext? = null
    private var surface: EGLSurface? = null
    private var broken = false
    private var card: Pass? = null
    private var stage: Pass? = null
    private val orbits = RoomOrbits()
    private var levels: RoomLevels? = null
    private var readRate = 0
    private val probe = FloatArray(1024)
    private var ticking = false
    private var frames = 0L
    private var lastHeard = 0L
    private val stats = if (Log.isLoggable(TAG, Log.DEBUG)) Stats() else null
    private val tick = Choreographer.FrameCallback { frame() }
    private val main = Handler(Looper.getMainLooper())

    /** Every open room, for the main thread: the pictures of one tick are shown in one post, so rooms drawn on the
     * same frame make the window draw once, not once each. */
    private val open = java.util.concurrent.CopyOnWriteArrayList<PaintedRoom>()
    private val showAll = Runnable { for (room in open) room.show() }
    private val asked = AtomicBoolean(false)
    private val wakeUp = Runnable {
        asked.set(false)
        if (!ticking) {
            ticking = true
            Choreographer.getInstance().postFrameCallback(tick)
        }
    }

    /**
     * A card's room (`RoomView(breathes: true)` on iPhone): the room at full strength, no dimming and no vignette,
     * its clock quickened 2.2 times and its levels three slow swings of its own ([CardRoom]), so it breathes without a
     * record. [seed] picks its pose ([RoomPose]); [zeroNanos] is when its clock starts, on `System.nanoTime`: now for a
     * room that stands alone, one time shared by rooms that must draw the same frame (the banner's cards).
     */
    fun card(seed: String, zeroNanos: Long = System.nanoTime()): PaintedRoom = open(PaintedRoom(this, false, seed, zeroNanos))

    /** The player's room (`RoomView(breathes: false)`): dimmed to 0.62, darker below [PaintedRoom.foot] and in the
     * corners, and lit by the music that is playing. Its spectrum's run comes with each picture. */
    fun stage(seed: String): PaintedRoom = open(PaintedRoom(this, true, seed, System.nanoTime()))

    private fun open(room: PaintedRoom): PaintedRoom {
        open.add(room)
        gl.post { rooms.add(room) }
        wake()
        return room
    }

    internal fun wake() {
        if (asked.compareAndSet(false, true)) gl.post(wakeUp)
    }

    internal fun close(room: PaintedRoom) {
        open.remove(room)
        gl.post {
            rooms.remove(room)
            room.release()
        }
    }

    private fun frame() {
        ticking = false
        if (!ready()) return
        frames++
        val now = System.nanoTime()
        var covered = false
        for (room in rooms) if (room.covers && room.running) covered = true
        var again = false
        var heard = false
        var painted = false
        for (room in rooms) {
            val active = room.running && (room.covers || !covered)
            if (active && room.stage && !heard) {
                listen(now)
                heard = true
            }
            if (room.wide <= 0 || room.high <= 0) continue
            if (active) {
                // A running room is drawn on every EVERYth frame, and at once when it has no picture for its size.
                if (room.fresh() || frames % EVERY == 0L) painted = paint(room, now, true) || painted
                again = true
            } else if (room.owed()) {
                painted = paint(room, now, false) || painted
            }
        }
        if (painted) main.post(showAll)
        if (again) {
            ticking = true
            Choreographer.getInstance().postFrameCallback(tick)
        }
    }

    /** Draws [room] and reads it back into its next bitmap; true when there is a new picture to show. */
    private fun paint(room: PaintedRoom, now: Long, active: Boolean): Boolean {
        val started = System.nanoTime()
        room.fit()
        // The colours dissolve toward a new record at the iPhone's rate (`RoomDissolve.rate`, 2.4 a second) while the
        // room runs. A room that is not running takes the new colours at once, in the one picture it is drawn again.
        val goal = room.palette
        val dt = if (room.paintedAt == 0L) 1f else ((now - room.paintedAt) / 1e9f).coerceIn(0f, 0.25f)
        room.paintedAt = now
        var shown = room.shown
        shown = if (!active || shown == null) goal else shown.eased(goal, 1f - exp(-dt * DISSOLVE))
        if (shown.closeTo(goal)) shown = goal
        room.shown = shown
        room.paintedFor = if (shown == goal) goal else null
        room.paintedSize = room.wide * 65536 + room.high

        val ran = room.lead + (now - room.zero) / 1e9
        val pass: Pass
        val t: Double
        val bass: Float
        val mid: Float
        val air: Float
        if (room.stage) {
            pass = stage ?: return false
            t = ran
            val l = levels
            bass = l?.bass ?: 0f
            mid = l?.middle ?: 0f
            air = l?.air ?: 0f
        } else {
            pass = card ?: return false
            // CardRoomBreath: one quickened clock for both the figures and the swell.
            t = ran * CARD_RATE
            bass = (0.20 + 0.16 * sin(t * 0.53)).toFloat()
            mid = (0.16 + 0.14 * sin(t * 0.71 + 1.7)).toFloat()
            air = (0.12 + 0.11 * sin(t * 0.37 + 3.1)).toFloat()
        }
        orbits.update(t, bass, mid, air)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, room.framebuffer)
        GLES30.glViewport(0, 0, room.wide, room.high)
        GLES30.glUseProgram(pass.program)
        GLES30.glUniform2f(pass.resolution, room.wide.toFloat(), room.high.toFloat())
        GLES30.glUniform1f(pass.time, t.toFloat())
        GLES30.glUniform1f(pass.bass, bass)
        GLES30.glUniform1f(pass.mid, mid)
        GLES30.glUniform1f(pass.air, air)
        GLES30.glUniform3fv(pass.lobes1, RoomOrbits.LOBES1, orbits.lobes1, 0)
        GLES30.glUniform3fv(pass.lobes2, RoomOrbits.LOBES2, orbits.lobes2, 0)
        GLES30.glUniform3fv(pass.lobes3, RoomOrbits.LOBES3, orbits.lobes3, 0)
        uniform(pass.ground, shown.ground)
        uniform(pass.tint, shown.tint)
        uniform(pass.glow, shown.glow)
        uniform(pass.shade, shown.shade)
        GLES30.glUniform1f(pass.foot, room.foot.coerceIn(0.2f, 1f))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        val slot = room.nextSlot()
        val pixels = room.pixels!!
        pixels.position(0)
        GLES30.glReadPixels(0, 0, room.wide, room.high, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        pixels.position(0)
        // RGBA bytes are how an ARGB_8888 bitmap lies in memory. The programs count rows from the picture's top, so
        // the rows GL reads first are the bitmap's first rows and nothing is turned over.
        slot.bitmap.copyPixelsFromBuffer(pixels)
        slot.palette = shown
        if (room.stage) {
            val run = levels?.run
            if (run != null) System.arraycopy(run, 0, slot.run, 0, RoomLevels.BANDS) else slot.run.fill(0f)
        }
        room.publish(slot)
        stats?.paint(now, System.nanoTime() - started, rooms.size)
        return true
    }

    /** One display frame of the music, for the stage rooms. As RoomRenderer's: rebuilt when the tap starts holding
     * another sample rate, and the spectrum's run worked out every time, because the player always draws it. */
    private fun listen(now: Long) {
        if (now == lastHeard) return
        lastHeard = now
        if (readRate > 0 && levels?.rate != readRate) levels = RoomLevels(readRate)
        val current = levels
        if (current == null) {
            readRate = tap.read(probe)
            return
        }
        current.update(now / 1e9, playing = true, withRun = true) { window ->
            val rate = tap.read(window)
            if (rate != 0) readRate = rate
            rate == current.rate
        }
    }

    private fun uniform(location: Int, c: Rgb) = GLES30.glUniform3f(location, c.r, c.g, c.b)

    /** Makes the context and the two programs the first time; false for good if this device cannot. */
    private fun ready(): Boolean {
        if (card != null) return true
        if (broken) return false
        try {
            val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(d, version, 0, version, 1)) { "no EGL display" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val found = IntArray(1)
            val wanted = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(d, wanted, 0, configs, 0, 1, found, 0) && found[0] > 0) { "no EGL config" }
            val config = configs[0]!!
            val c = EGL14.eglCreateContext(d, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(c != EGL14.EGL_NO_CONTEXT) { "no EGL context" }
            val s = EGL14.eglCreatePbufferSurface(d, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(s != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(d, s, s, c)) { "context not made current" }
            display = d
            context = c
            surface = s
            val figures = RoomShaders.ROOM_FRAGMENT.substringBefore("void main()")
            stage = Pass(program(RoomShaders.VERTEX, figures + STAGE_MAIN))
            card = Pass(program(RoomShaders.VERTEX, figures + CARD_MAIN))
            return true
        } catch (e: RuntimeException) {
            Diag.w(TAG, "rooms cannot be painted on this device", e)
            broken = true
            return false
        }
    }

    /** A program and where its uniforms are. One the program does not use is at -1, which GL ignores. */
    private class Pass(val program: Int) {
        private fun at(name: String) = GLES30.glGetUniformLocation(program, name)
        val resolution = at("uResolution")
        val time = at("uTime")
        val bass = at("uBass")
        val mid = at("uMid")
        val air = at("uAir")
        val ground = at("uGround")
        val tint = at("uTint")
        val glow = at("uGlow")
        val shade = at("uShade")
        val foot = at("uFoot")
        val lobes1 = at("uLobes1")
        val lobes2 = at("uLobes2")
        val lobes3 = at("uLobes3")
    }

    private fun program(vertex: String, fragment: String): Int {
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, shader(GLES30.GL_VERTEX_SHADER, vertex))
        GLES30.glAttachShader(program, shader(GLES30.GL_FRAGMENT_SHADER, fragment))
        GLES30.glLinkProgram(program)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { "room program did not link: " + GLES30.glGetProgramInfoLog(program) }
        return program
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) { "room shader did not compile: " + GLES30.glGetShaderInfoLog(shader) }
        return shader
    }

    /** Paint timing, to logcat under `rpm.paint` every 5 seconds, when `adb shell setprop log.tag.rpm.paint DEBUG`
     * was set before the app started. */
    private class Stats {
        private var count = 0
        private var spent = 0L
        private var since = 0L

        fun paint(now: Long, nanos: Long, rooms: Int) {
            if (since == 0L) since = now
            count++
            spent += nanos
            if (now - since < 5_000_000_000L) return
            Diag.d(TAG, "paints/s=%.1f each=%.2fms rooms=%d".format(count / ((now - since) / 1e9), spent / 1e6 / count, rooms))
            count = 0
            spent = 0
            since = now
        }
    }

    companion object {
        private const val TAG = "rpm.paint"

        /** Rooms are drawn on every second display frame: 30 a second at 60 Hz. */
        const val EVERY = 2L

        /** How much quicker a card's room runs than the stage's (`CardRoomBreath.rate`). */
        private const val CARD_RATE = 2.2

        /** How quickly a room's colours close on a new record, per second (`RoomDissolve.rate` on iPhone). */
        private const val DISSOLVE = 2.4f

        /**
         * `rpmCardRoom` (RoomShader.metal) without its grain: the desktop draws its card at a tenth and stretches it,
         * which blurs banding away, and so does this. y counts down from the picture's top here, because the rows
         * GL writes first are the rows the bitmap shows first; the room counts up from the bottom, as on iPhone.
         */
        private const val CARD_MAIN = """
void main() {
    vec2 flipped = vec2(gl_FragCoord.x, uResolution.y - gl_FragCoord.y);
    vec2 p = (flipped - 0.5 * uResolution) / uResolution.y;
    vec3 col = rpmRoom(p, uTime, uBass, uMid, uAir, uGround, uTint, uGlow, uShade);
    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""

        /**
         * `rpmRoom` (RoomShader.metal), formula for formula, without its grain: the wash dimmed to 0.62, dimmed more
         * below the foot (where the text is; 1 means no text and no ramp), and the corners darkened.
         */
        private const val STAGE_MAIN = """
uniform float uFoot;
void main() {
    vec2 flipped = vec2(gl_FragCoord.x, uResolution.y - gl_FragCoord.y);
    vec2 uv = flipped / uResolution;
    vec2 p = (flipped - 0.5 * uResolution) / uResolution.y;
    vec3 col = rpmRoom(p, uTime, uBass, uMid, uAir, uGround, uTint, uGlow, uShade);
    col *= 0.62;
    if (uFoot < 1.0) {
        col *= mix(0.42, 1.0, smoothstep(uFoot - 0.24, uFoot, uv.y));
    }
    float vig = pow(16.0 * uv.x * uv.y * (1.0 - uv.x) * (1.0 - uv.y), 0.20);
    col *= 0.42 + 0.58 * vig;
    fragColor = vec4(max(col, vec3(0.0)), 1.0);
}
"""
    }
}

/**
 * One room [RoomPainter] draws: what a screen sets on it (its colours, whether it runs, its size) and the newest
 * picture of it. Everything a screen calls is for the main thread; the painter reads the settings from its own.
 */
class PaintedRoom internal constructor(
    private val painter: RoomPainter,
    internal val stage: Boolean,
    seed: String,
    internal val zero: Long,
) {
    internal val lead = RoomPose.offset(seed)

    /** The colours the room is heading for. A running room dissolves to them over about a second. */
    @Volatile var palette: RoomPalette = RoomPalette.FALLBACK
        set(value) {
            if (value == field) return
            field = value
            painter.wake()
        }

    /** Whether the room moves. A room that is stopped keeps its last picture. */
    @Volatile var running: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            painter.wake()
        }

    /** Whether this room fills the screen over every other room, which stop while it runs: the player's. */
    @Volatile var covers: Boolean = false

    /** For a stage room: where the text starts, as a share of the height from the top; 1 for no text. */
    @Volatile var foot: Float = 1f

    @Volatile internal var wide = 0
        private set
    @Volatile internal var high = 0
        private set

    /** Sets the picture's own size, in pixels: a small share of the box it is drawn over. */
    fun size(width: Int, height: Int) {
        if (width == wide && height == high) return
        high = height
        wide = width
        painter.wake()
    }

    /** Counts the pictures shown. Read it where the picture is drawn, so a new picture redraws only that. */
    var frame by mutableIntStateOf(0)
        private set

    /** The newest picture, or null before the first. Main thread. */
    var picture: ImageBitmap? = null
        private set

    /** The colours the newest picture was drawn in, as far as its dissolve had come. Main thread. */
    var shownPalette: RoomPalette? = null
        private set

    /** For a stage room: the spectrum's run, 0 to 1 in 128 bands, as it stood when the newest picture was drawn. The
     * array is reused: read it while drawing, do not keep it. Main thread. */
    var run: FloatArray? = null
        private set

    fun close() = painter.close(this)

    // The GL thread's.
    internal var shown: RoomPalette? = null
    internal var paintedFor: RoomPalette? = null
    internal var paintedSize = 0
    internal var paintedAt = 0L
    internal var framebuffer = 0
    private var texture = 0
    private var fitW = 0
    private var fitH = 0
    internal var pixels: ByteBuffer? = null
    private var slots: Array<Slot>? = null
    private var turn = 0

    internal class Slot(w: Int, h: Int) {
        val bitmap: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val image: ImageBitmap = bitmap.asImageBitmap()
        val run = FloatArray(RoomLevels.BANDS)
        @Volatile var palette: RoomPalette? = null
        @Volatile var paint = 0L
    }

    @Volatile private var ready: Slot? = null
    private var paints = 0L
    private var shownPaint = 0L

    /** Shows the newest picture, if it is not the one shown. Main thread. */
    internal fun show() {
        val slot = ready ?: return
        if (slot.paint == shownPaint) return
        shownPaint = slot.paint
        picture = slot.image
        shownPalette = slot.palette
        run = if (stage) slot.run else null
        frame++
    }

    /** Whether the room owes a picture although it may not be running: it has none yet, or its size or colours
     * changed since the last. */
    internal fun owed(): Boolean = fresh() || paintedFor != palette

    /** Whether the room has no picture at its size yet. */
    internal fun fresh(): Boolean = slots == null || paintedSize != wide * 65536 + high

    /** Makes the framebuffer, the read-back buffer and the three bitmaps for the size asked, if that changed. */
    internal fun fit() {
        val w = wide
        val h = high
        if (w == fitW && h == fitH && slots != null) return
        release()
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        slots = Array(3) { Slot(w, h) }
        fitW = w
        fitH = h
    }

    internal fun nextSlot(): Slot {
        val all = slots!!
        turn = (turn + 1) % all.size
        return all[turn]
    }

    internal fun publish(slot: Slot) {
        slot.paint = ++paints
        ready = slot
    }

    /** Lets go of the GL objects. GL thread. */
    internal fun release() {
        if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        texture = 0
        framebuffer = 0
        fitW = 0
        fitH = 0
        slots = null
    }
}
