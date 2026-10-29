package com.rpm.player.core.room

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.rpm.player.core.diag.Diag
import com.rpm.player.core.playback.AudioTap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/** What the room should be painting, set from outside the GL thread. */
class RoomTarget {
    @Volatile var palette: RoomPalette = RoomPalette.FALLBACK

    /** 0 for the player's spread view, which dims the left of the room and draws the spectrum to the
     * right of the sleeve; 1 for everything else. The room eases to it (the desktop's `uBare`). */
    @Volatile var bare: Float = 1f

    /** How far the picture is pushed right, as a share of the width: the queue drawer's width while it
     * is open, 0 otherwise. The room eases to it at the drawer's own rate (1 - exp(-dt * 11)). */
    @Volatile var push: Float = 0f

    /** How bright the room is drawn, before the spectrum is laid on it: 1 under the collection screens,
     * which lay their own shade over it, and [PLAYER_BRIGHTNESS] in the player, which has none. The room
     * eases to it as it eases to [bare]. */
    @Volatile var brightness: Float = 1f

    /** Whether the room is held still: drawn once in its colours, not lit by the music, and drawn again
     * only when its colours, [bare] or [push] change. Set from the motion level
     * ([com.rpm.player.core.motion.Motion]). */
    @Volatile var still: Boolean = false

    /** The person's picture setting: every colour of the room is multiplied by this. It is 1 for Standard
     * (the TV's `PictureLook`). Not eased: it changes when the setting does. */
    @Volatile var light: Float = 1f

    /** Which animation the room draws ([RoomStyle]), from the TV's settings. A change shows on the next
     * frame, as a cut: a fade between two would draw both, twice the room's pass for its length. */
    @Volatile var style: RoomStyle = RoomStyle.Original
}

/**
 * Draws the room on the GL thread: the room program into an offscreen texture at [scale] of [screen],
 * then that texture over the whole surface with the grain and the player's spectrum. The room is soft
 * gradients only, so drawing it small and scaling it up loses nothing that can be seen. Drawing it
 * small is also what lets a Mali-G31 draw it at all.
 *
 * [screen] is the size [scale] is a share of: the display for the room behind the window, whatever size
 * its surface is (the surface can be the room's own size and stretched by the system, [RoomView]); the
 * picture's size for a picture of it ([RoomStills]).
 *
 * Owns its GL objects and touches them only on the GL thread. Reads the music from [tap] and the
 * colours from [target].
 *
 * Held still ([RoomTarget.still]), the room's clock stops where it was, the music no longer moves it,
 * and the room pass runs only when its colours or [RoomTarget.bare] changed. The present pass still
 * runs on every frame asked for, so the player's spectrum moves. [wantsFrame] tells the pacer when a
 * still room has nothing to draw.
 */
class RoomRenderer(
    private val tap: AudioTap,
    private val target: RoomTarget,
    private val scale: Float,
    private val screen: android.util.Size,
    private val measure: Boolean,
    /** What the room's colours are multiplied by: 1 for the room behind the window, less for a picture of
     * it with the screens' shade included ([RoomStills]). */
    private val dim: Float,
) : GLSurfaceView.Renderer {

    /** Each style's room program and where its uniforms are, built the first time the style is drawn in
     * this GL context: the stills draw one picture in a context of their own, and compiling all four
     * there, or at every start, would be spent on styles not shown. GL thread only. */
    private val passes = arrayOfNulls<RoomPass>(RoomStyle.entries.size)
    private var presentProgram = 0
    private var framebuffer = 0
    private var texture = 0
    private var screenW = 0
    private var screenH = 0
    private var roomW = 0
    private var roomH = 0

    private var uRoom = -1
    private var uNoise = -1
    private var uScreen = -1
    private var uBare = -1
    private var uBands = -1
    private var uBarTint = -1
    private var uBarGlow = -1
    private var noise = 0
    private var bands = 0
    private val bandBytes = java.nio.ByteBuffer.allocateDirect(RoomLevels.BANDS)
    private var bare = 1f
    private var push = 0f
    private var uPush = -1
    private var brightness = 1f
    private var uBright = -1
    private var uDim = -1
    private val orbits = RoomOrbits()
    private val figures = RoomFigures()

    private var startNanos = 0L
    private var lastNanos = 0L
    private var palette = target.palette
    private var levels: RoomLevels? = null
    private var readRate = 0

    private val probe = FloatArray(1024)
    private val stats = FrameStats()

    /** The clock a still room stopped at; negative while the room is live. GL thread only. */
    private var stoppedAt = -1.0
    /** What the room texture was last drawn with; null when it must be drawn again. GL thread only. */
    private var roomDrawn: Drawn? = null
    /** What the last frame was drawn for, when the next frame would draw the same; null otherwise.
     * Written on the GL thread, read by [wantsFrame] on the main thread. */
    @Volatile private var restingOn: Rest? = null

    private data class Rest(val palette: RoomPalette, val bare: Float, val push: Float, val brightness: Float, val light: Float, val style: RoomStyle)

    private data class Drawn(val palette: RoomPalette, val bare: Float, val style: RoomStyle)

    /** One style's room program and its uniforms. [arrays] are where the style's figure arrays are, by
     * the names its program gives them. */
    private class RoomPass(val program: Int, arrayNames: List<String>) {
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
        val bare = at("uBare")
        val arrays = IntArray(arrayNames.size) { at(arrayNames[it]) }
    }

    /** Starts [RoomTarget.bare], [RoomTarget.push] and [RoomTarget.brightness] where the target has them, not
     * eased from the defaults: a picture of the room ([RoomStills]) is one frame, which an easing would leave at
     * the start. GL thread, before the first frame. */
    internal fun settle() {
        bare = target.bare
        push = target.push
        brightness = target.brightness
    }

    /** Whether a frame drawn now would differ from the last one. Always true for a live room. Called by
     * the pacer on the main thread once per display frame. */
    fun wantsFrame(): Boolean {
        val rest = restingOn ?: return true
        return !(target.still && rest.palette == target.palette && rest.bare == target.bare && rest.push == target.push && rest.brightness == target.brightness && rest.light == target.light && rest.style == target.style)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: whatever programs the last one had are gone with it.
        passes.fill(null)
        presentProgram = program(RoomShaders.VERTEX, RoomShaders.PRESENT_FRAGMENT)
        uRoom = GLES30.glGetUniformLocation(presentProgram, "uRoom")
        uNoise = GLES30.glGetUniformLocation(presentProgram, "uNoise")
        noise = makeNoise()
        uScreen = GLES30.glGetUniformLocation(presentProgram, "uScreen")
        uBare = GLES30.glGetUniformLocation(presentProgram, "uBare")
        uBands = GLES30.glGetUniformLocation(presentProgram, "uBands")
        uBarTint = GLES30.glGetUniformLocation(presentProgram, "uTint")
        uBarGlow = GLES30.glGetUniformLocation(presentProgram, "uGlow")
        uPush = GLES30.glGetUniformLocation(presentProgram, "uPush")
        uDim = GLES30.glGetUniformLocation(presentProgram, "uDim")
        uBright = GLES30.glGetUniformLocation(presentProgram, "uBright")
        bands = makeBands()
        startNanos = System.nanoTime()
        lastNanos = startNanos
        roomDrawn = null
        restingOn = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenW = width
        screenH = height
        roomW = (screen.width * scale).roundToInt().coerceAtLeast(16)
        roomH = (screen.height * scale).roundToInt().coerceAtLeast(9)
        makeTarget()
        roomDrawn = null
        restingOn = null
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        // A still room that rested since its last frame starts its easing from one display frame, not
        // from the whole rest, so a change of colour eases and does not jump.
        val dt = if (restingOn != null) 1f / 60f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.25f)
        lastNanos = now
        val seconds = (now - startNanos) / 1e9
        // Read once, so the whole frame and what it says it rested on agree.
        val still = target.still
        val goal = target.palette
        val goalBare = target.bare
        val goalPush = target.push
        val goalBrightness = target.brightness
        val light = target.light
        val style = target.style

        // Eased as the desktop's stage eases it, at 1 - exp(-dt * 5.5) per frame (PegasusStage.cs).
        bare += (goalBare - bare) * (1f - kotlin.math.exp(-dt * 5.5f))
        if (kotlin.math.abs(goalBare - bare) < 0.001f) bare = goalBare
        push += (goalPush - push) * (1f - kotlin.math.exp(-dt * 11f))
        if (kotlin.math.abs(goalPush - push) < 0.0005f) push = goalPush
        brightness += (goalBrightness - brightness) * (1f - kotlin.math.exp(-dt * 5.5f))
        if (kotlin.math.abs(goalBrightness - brightness) < 0.001f) brightness = goalBrightness
        palette = palette.eased(goal, RoomPalette.easeStep(dt))
        if (palette.closeTo(goal)) palette = goal
        if (still) {
            if (stoppedAt < 0) stoppedAt = seconds
            // The spectrum is the one thing a still room still moves.
            if (bare < 0.999f) listen(seconds, withRun = true)
        } else {
            stoppedAt = -1.0
            listen(seconds, withRun = bare < 0.999f)
        }

        val l = levels
        val bass = if (still) 0f else l?.bass ?: 0f
        val mid = if (still) 0f else l?.middle ?: 0f
        val air = if (still) 0f else l?.air ?: 0f
        // Every style's clock runs on while another is shown, so a switch back finds it moving.
        figures.advance(if (still) 0f else dt, bass, mid)
        if (!still || roomDrawn != Drawn(palette, bare, style)) {
            val clock = if (still) stoppedAt else seconds
            val pass = pass(style)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glViewport(0, 0, roomW, roomH)
            GLES30.glUseProgram(pass.program)
            GLES30.glUniform2f(pass.resolution, roomW.toFloat(), roomH.toFloat())
            GLES30.glUniform1f(pass.time, clock.toFloat())
            GLES30.glUniform1f(pass.bass, bass)
            GLES30.glUniform1f(pass.mid, mid)
            GLES30.glUniform1f(pass.air, air)
            figuresFor(style, pass, clock, bass, mid, air)
            uniform(pass.ground, palette.ground)
            uniform(pass.tint, palette.tint)
            uniform(pass.glow, palette.glow)
            uniform(pass.shade, palette.shade)
            GLES30.glUniform1f(pass.bare, bare)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            roomDrawn = if (still) Drawn(palette, bare, style) else null
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, screenW, screenH)
        GLES30.glUseProgram(presentProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(uRoom, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noise)
        GLES30.glUniform1i(uNoise, 1)
        GLES30.glUniform2f(uScreen, screenW.toFloat(), screenH.toFloat())
        GLES30.glUniform1f(uBare, bare)
        GLES30.glUniform1f(uPush, push)
        GLES30.glUniform1f(uDim, dim * light)
        GLES30.glUniform1f(uBright, brightness)
        uniform(uBarTint, palette.tint)
        uniform(uBarGlow, palette.glow)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bands)
        if (bare < 0.999f) uploadBands(l)
        GLES30.glUniform1i(uBands, 2)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        val rests = still && bare == goalBare && push == goalPush && brightness == goalBrightness && palette == goal && bare >= 0.999f
        restingOn = if (rests) Rest(goal, goalBare, goalPush, goalBrightness, light, style) else null
        if (measure) stats.frame(now, System.nanoTime() - now, scale, roomW, roomH, l)
    }

    /** [style]'s program, built the first time it is asked for in this context. A uniform a program
     * does not use is at -1, which GL ignores (the new styles have no use for `uTime`). */
    private fun pass(style: RoomStyle): RoomPass = passes[style.ordinal] ?: when (style) {
        RoomStyle.Original -> RoomPass(program(RoomShaders.VERTEX, RoomShaders.ROOM_FRAGMENT), listOf("uLobes1", "uLobes2", "uLobes3"))
        RoomStyle.Yang -> RoomPass(program(RoomShaders.VERTEX, RoomShaders.YANG_FRAGMENT), listOf("uBig", "uSmall", "uCentre"))
        RoomStyle.Lava -> RoomPass(program(RoomShaders.VERTEX, RoomShaders.LAVA_FRAGMENT), listOf("uBlobs", "uPools", "uBack"))
        RoomStyle.Alchemy -> RoomPass(program(RoomShaders.VERTEX, RoomShaders.RPAMP_FRAGMENT), listOf("uRibbonA", "uRibbonB", "uRibbonC"))
    }.also { passes[style.ordinal] = it }

    /** [style]'s figures at [clock] and these levels, worked out and handed to [pass]'s program. */
    private fun figuresFor(style: RoomStyle, pass: RoomPass, clock: Double, bass: Float, mid: Float, air: Float) {
        val a = pass.arrays
        when (style) {
            RoomStyle.Original -> {
                orbits.update(clock, bass, mid, air)
                GLES30.glUniform3fv(a[0], RoomOrbits.LOBES1, orbits.lobes1, 0)
                GLES30.glUniform3fv(a[1], RoomOrbits.LOBES2, orbits.lobes2, 0)
                GLES30.glUniform3fv(a[2], RoomOrbits.LOBES3, orbits.lobes3, 0)
            }
            RoomStyle.Yang -> {
                figures.yang(clock, bass, mid, air)
                GLES30.glUniform4fv(a[0], RoomFigures.YANG_BIG, figures.big, 0)
                GLES30.glUniform4fv(a[1], RoomFigures.YANG_SMALL, figures.small, 0)
                GLES30.glUniform4fv(a[2], 1, figures.centre, 0)
            }
            RoomStyle.Lava -> {
                figures.lava(bass, mid)
                GLES30.glUniform4fv(a[0], RoomFigures.LAVA_BLOBS, figures.blobs, 0)
                GLES30.glUniform4fv(a[1], 1, figures.pools, 0)
                GLES30.glUniform4fv(a[2], 1, figures.back, 0)
            }
            RoomStyle.Alchemy -> {
                figures.ribbons(bass, mid, air)
                GLES30.glUniform4fv(a[0], RoomFigures.RIBBONS, figures.ribbonA, 0)
                GLES30.glUniform4fv(a[1], RoomFigures.RIBBONS, figures.ribbonB, 0)
                GLES30.glUniform4fv(a[2], RoomFigures.RIBBONS, figures.ribbonC, 0)
            }
        }
    }

    /** One frame of the analysis. Rebuilt when the tap starts holding another sample rate. The
     * spectrum's run is worked out only [withRun]. */
    private fun listen(seconds: Double, withRun: Boolean) {
        if (readRate > 0 && levels?.rate != readRate) levels = RoomLevels(readRate)
        val current = levels
        if (current == null) {
            // Nothing built yet: one read to learn the rate.
            readRate = tap.read(probe)
            return
        }
        current.update(seconds, playing = true, withRun = withRun) { window ->
            val rate = tap.read(window)
            if (rate != 0) readRate = rate
            rate == current.rate
        }
    }

    private fun uniform(location: Int, c: Rgb) = GLES30.glUniform3f(location, c.r, c.g, c.b)

    /** The spectrum's run as one row of 128 texels, one byte each. It is read at texel centres, so nearest. */
    private fun makeBands(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        bandBytes.position(0)
        repeat(RoomLevels.BANDS) { bandBytes.put(0) }
        bandBytes.position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, RoomLevels.BANDS, 1, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, bandBytes)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    /** This frame's run into the bound band texture. All zeros before the analysis is built. */
    private fun uploadBands(levels: RoomLevels?) {
        bandBytes.position(0)
        for (band in 0 until RoomLevels.BANDS) {
            val v = levels?.run?.get(band) ?: 0f
            bandBytes.put((v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
        }
        bandBytes.position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, RoomLevels.BANDS, 1, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, bandBytes)
    }

    /** A 64 x 64 tile of white noise, one byte per texel, repeated across the screen for the grain. The
     * same tile on every launch (a fixed seed), so the grain is always the same. */
    private fun makeNoise(): Int {
        val bytes = java.nio.ByteBuffer.allocateDirect(NOISE * NOISE)
        val random = java.util.Random(0x52504D)
        repeat(NOISE * NOISE) { bytes.put(random.nextInt(256).toByte()) }
        bytes.position(0)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, NOISE, NOISE, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, bytes)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        return ids[0]
    }

    /** The offscreen texture: 10 bits per channel where the driver can render to it, so the room's long
     * gradients do not show bands before the grain is added; 8 bits where it cannot. */
    private fun makeTarget() {
        if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES30.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        if (!attach(GLES30.GL_RGB10_A2, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV)) {
            attach(GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun attach(internal: Int, format: Int, type: Int): Boolean {
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, roomW, roomH, 0, format, type, null)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        return GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
    }

    private fun program(vertex: String, fragment: String): Int {
        val vs = shader(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = shader(GLES30.GL_FRAGMENT_SHADER, fragment)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
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
}

private const val NOISE = 64

/** The room's brightness behind the player: 20% darker than elsewhere, so the lines drawn over it are
 * easy to read. */
const val PLAYER_BRIGHTNESS = 0.8f

/**
 * Frame timing, written to logcat under `rpm.room` every 5 seconds in a debuggable build: the frame
 * rate the screen actually reached, the 95th-percentile frame interval, the time this thread spent
 * issuing a frame, and the levels, which say whether the tap is hearing anything. Allocation-free per
 * frame.
 */
private class FrameStats {
    private val intervals = LongArray(512)
    private var count = 0
    private var issueTotal = 0L
    private var previous = 0L
    private var windowStart = 0L

    fun frame(now: Long, issueNanos: Long, scale: Float, w: Int, h: Int, levels: RoomLevels?) {
        if (previous != 0L && count < intervals.size) intervals[count++] = now - previous
        previous = now
        issueTotal += issueNanos
        if (windowStart == 0L) windowStart = now
        if (now - windowStart < 5_000_000_000L || count == 0) return

        val sorted = intervals.copyOf(count).also { it.sort() }
        val p95 = sorted[(count * 95 / 100).coerceAtMost(count - 1)] / 1e6
        val fps = count / ((now - windowStart) / 1e9)
        Diag.d(
            "rpm.room",
            "scale=%.2f %dx%d fps=%.1f p95=%.1fms issue=%.2fms bass=%.2f mid=%.2f air=%.2f".format(
                scale, w, h, fps, p95, issueTotal / 1e6 / count,
                levels?.bass ?: 0f, levels?.middle ?: 0f, levels?.air ?: 0f,
            ),
        )
        count = 0
        issueTotal = 0
        windowStart = now
    }
}
