package com.rpm.player.phone.stage

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import com.rpm.player.core.diag.Diag
import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rpm.player.core.playback.AudioTap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One visualiser, drawn with OpenGL ES 3 on the stage's GL thread. Everything it is called with runs on that thread,
 * so a look keeps its state in plain fields.
 *
 * The iPhone draws the same looks in Metal and SwiftUI (`RPMStages`, `MarchShaders.metal`, `Bloom.metal`). The
 * arithmetic is ported figure for figure; only the way it reaches the screen differs.
 */
interface StageRenderer {
    /**
     * The share of the screen's pixels the look is drawn at before the system stretches it to the screen. The
     * marched looks are a ray march per pixel and run at a fraction; the drawn ones are sharp enough at more.
     */
    val scale: Float

    /** Makes the GL objects. Called again if the surface was lost. */
    fun created()

    /**
     * A frame of the samples being heard, at about 40 Hz. [count] is 0 for silence, and every look handles that.
     * [now] is seconds since the stage's clock started, and [since] how long the last frame took.
     */
    fun hear(frame: FloatArray, count: Int, now: Double, since: Double)

    /** Draws one frame into a [width] by [height] surface. */
    fun draw(width: Int, height: Int, now: Double, since: Double)

    /** The look has left the screen. Called on the main thread; GL objects go with the surface. */
    fun released() {}
}

/** Which visualisers this build draws. A look is listed in the picker only once it is here ([StageLook.isDrawn]). */
object StageLooks {
    fun drawn(look: StageLook): Boolean = look != StageLook.Artwork

    /** A new renderer for [look], or null for the artwork, which is not drawn here. Making one makes no GL objects. */
    fun make(look: StageLook): StageRenderer? = when (look) {
        StageLook.Artwork -> null
        StageLook.Ribbon -> RibbonLook()
        StageLook.Blocks -> BlockLook()
        StageLook.Plate -> PlateLook()
        StageLook.Lattice -> MarchedLook(MarchedLook.Kind.Lattice)
        StageLook.Kaleidoscope -> MarchedLook(MarchedLook.Kind.Kaleidoscope)
        StageLook.Tunnel -> MarchedLook(MarchedLook.Kind.Tunnel)
        StageLook.Butterfly -> ButterflyLook()
        StageLook.Bloom -> BloomLook()
    }
}

/**
 * The visualiser [look], drawn over the whole stage and listening to [tap]: a `GLSurfaceView` that draws one frame
 * each time the display shows one, while the screen is resumed, and stops when it pauses or leaves. Only one look
 * runs at a time, and only while the stage is on screen: running the clock is what makes a look cost anything.
 *
 * The surface is the look's own reduced size ([StageRenderer.scale]) and the system stretches it to the screen, so a
 * ray march costs a fraction of the screen's pixels.
 *
 * Asked for a frame from the display's own clock, not drawn continuously, for the reason the room gives
 * (`RoomView`): drawn continuously, the GL thread queues frames ahead of the display and the window's own frames
 * wait behind them.
 */
@Composable
fun StageSurface(look: StageLook, tap: AudioTap, modifier: Modifier) {
    // A new surface for every look: the old one's GL objects go with it.
    key(look) {
        val made = remember { StageLooks.make(look) }
        if (made != null) LookSurface(made, tap, modifier)
    }
}

@Composable
private fun LookSurface(made: StageRenderer, tap: AudioTap, modifier: Modifier) {
    val metrics = LocalContext.current.resources.displayMetrics
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    run {
        val view = remember { arrayOfNulls<GLSurfaceView>(1) }
        val clock = remember { StageClock(made, tap) }
        val pacer = remember { StagePacer { view[0]?.requestRender() } }
        val width = (metrics.widthPixels * made.scale).roundToInt().coerceAtLeast(16)
        val height = (metrics.heightPixels * made.scale).roundToInt().coerceAtLeast(16)
        AndroidView(
            modifier = modifier,
            factory = { context ->
                GLSurfaceView(context).apply {
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 0, 0, 0)
                    preserveEGLContextOnPause = true
                    setRenderer(clock)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    view[0] = this
                }
            },
            update = { v -> v.holder.setFixedSize(width, height) },
        )
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        view[0]?.onResume()
                        pacer.start()
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        pacer.stop()
                        view[0]?.onPause()
                    }
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                pacer.stop()
                view[0]?.onPause()
                made.released()
            }
        }
    }
}

/**
 * The frame clock the looks run on, and the audio tap they listen to: the iPhone's `StageClock`. It keeps the seconds
 * since it started and how long the last frame took, and hands the look a frame of what is being heard forty times a
 * second, not sixty: every look smooths the spectrum with its own attack and release, and none changes within 25 ms.
 *
 * With `adb shell setprop log.tag.rpm.stage INFO` it reports to logcat how many frames a look really gets and how
 * long its listening and drawing take on the GL thread. A look that has dropped to a third of the screen's rate does
 * not show it: sand drifting and sand drifting slowly look the same. The GPU's own time is not in these figures;
 * `dumpsys gfxinfo` does not see a surface view either, so a slow GPU shows as the frame rate falling.
 */
private class StageClock(private val look: StageRenderer, private val tap: AudioTap) : GLSurfaceView.Renderer {
    /** One buffer, refilled each time, so an hour of listening makes no garbage. */
    private val frame = FloatArray(FRAME)
    private var began = 0L
    private var lastFrame = 0.0
    private var lastListen = -LISTEN
    private var width = 1
    private var height = 1

    private val measure = Log.isLoggable(TAG, Log.INFO)
    private var counted = 0
    private var countedFrom = 0.0
    private var listening = 0L
    private var drawing = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        look.created()
        if (began == 0L) began = SystemClock.elapsedRealtimeNanos()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = (SystemClock.elapsedRealtimeNanos() - began) / 1e9
        // The longest step a look is given: a phone that paused for a second must not move a look a second at once.
        val since = if (lastFrame > 0) min(LONGEST_FRAME, now - lastFrame) else 0.0
        lastFrame = now

        val heard = System.nanoTime()
        if (now - lastListen >= LISTEN) {
            lastListen = now
            val rate = tap.read(frame)
            look.hear(frame, if (rate > 0) FRAME else 0, now, since)
        }
        val drew = System.nanoTime()
        GLES30.glViewport(0, 0, width, height)
        look.draw(width, height, now, since)
        val done = System.nanoTime()

        if (measure) {
            listening += drew - heard
            drawing += done - drew
            counted++
            if (now - countedFrom >= 2) {
                Diag.i(
                    TAG,
                    "%s %dx%d: %.1f frames a second, %.2f ms listening, %.2f ms drawing".format(
                        look.javaClass.simpleName, width, height, counted / (now - countedFrom),
                        listening / 1e6 / counted, drawing / 1e6 / counted,
                    ),
                )
                counted = 0
                countedFrom = now
                listening = 0
                drawing = 0
            }
        }
    }

    companion object {
        /** How often the music is measured, in seconds. */
        const val LISTEN = 0.025

        /** Samples handed to a look each time: the iPhone's 2048. */
        const val FRAME = 2048

        private const val LONGEST_FRAME = 0.05
        private const val TAG = "rpm.stage"
    }
}

/**
 * Asks for one frame on every display frame, from the main thread's `Choreographer`, between [start] and [stop]. The
 * room's pacer, for the stage.
 */
private class StagePacer(private val ask: () -> Unit) : Choreographer.FrameCallback {
    private var running = false

    fun start() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        ask()
        Choreographer.getInstance().postFrameCallback(this)
    }
}

// ── the GL helpers every look uses ──

/** Compiles and links a program from GLSL ES 3.00 sources, and logs what the driver said when it refuses. */
internal fun program(vertex: String, fragment: String): Int {
    fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Diag.e("rpm.stage", "shader: " + GLES30.glGetShaderInfoLog(shader))
        return shader
    }
    val made = GLES30.glCreateProgram()
    GLES30.glAttachShader(made, compile(GLES30.GL_VERTEX_SHADER, vertex))
    GLES30.glAttachShader(made, compile(GLES30.GL_FRAGMENT_SHADER, fragment))
    GLES30.glLinkProgram(made)
    val ok = IntArray(1)
    GLES30.glGetProgramiv(made, GLES30.GL_LINK_STATUS, ok, 0)
    if (ok[0] == 0) Diag.e("rpm.stage", "program: " + GLES30.glGetProgramInfoLog(made))
    return made
}

/**
 * The vertex stage of every full-screen pass: one triangle that covers the frame, made from the vertex number alone,
 * so no buffer is bound. In the fragment stages `gl_FragCoord` counts up from the bottom, which is the way the
 * iPhone's shaders turn SwiftUI's position round before they use it.
 */
internal const val FULL_FRAME = """#version 300 es
void main() {
    vec2 at = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(at * 2.0 - 1.0, 0.0, 1.0);
}
"""

/** A vertex array with nothing in it, for the full-frame pass (ES 3 wants one bound). */
internal fun emptyVertexArray(): Int = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]

/** Draws the full-frame triangle with whatever program is bound. */
internal fun drawFullFrame(emptyArray: Int) {
    GLES30.glBindVertexArray(emptyArray)
    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
}

/** A direct float buffer of [count] floats, in the order the GPU reads. */
internal fun floats(count: Int): FloatBuffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

/** A buffer object, for vertices that change every frame. */
internal fun arrayBuffer(): Int = IntArray(1).also { GLES30.glGenBuffers(1, it, 0) }[0]

/** A single-channel byte texture of [width] by [height], filtered linearly and clamped at the edges. */
internal fun byteTexture(width: Int, height: Int): Int {
    val name = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, name)
    GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, width, height, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    return name
}

/** Writes [values], 0 to 1, into a byte texture made by [byteTexture], through [bytes] (one byte each, reused). */
internal fun uploadBytes(texture: Int, width: Int, height: Int, values: FloatArray, bytes: ByteBuffer) {
    bytes.clear()
    for (i in 0 until width * height) bytes.put((values[i] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
    bytes.flip()
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
    GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, bytes)
}

/** A direct byte buffer of [count] bytes. */
internal fun bytes(count: Int): ByteBuffer = ByteBuffer.allocateDirect(count).order(ByteOrder.nativeOrder())
