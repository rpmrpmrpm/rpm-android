package com.rpm.player.tv

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import com.rpm.player.core.diag.Diag
import android.view.Display
import com.rpm.player.tv.ui.ScreenResolution
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import android.view.Window
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Draws the screens at a height other than the window's ([ScreenResolution]). A larger height suits a
 * box that lays apps out at fewer pixels than it sends to the television (the Shield: a 3840 x 2160
 * output, apps drawn at 1920 x 1080 and scaled up by the system, so covers and text come out blurry).
 * A smaller height draws fewer pixels on a slow box.
 *
 * The screens go on a private virtual display of that size, shown through a [SurfaceView] whose
 * buffer is that size. The system composes a surface's buffer at the output's pixels, but it scales
 * the window's. Keys reach the activity's window, which has the focus, and are handed to the screens'
 * window ([dispatchKey]).
 *
 * The on-screen keyboard serves only the focused window, which is the activity's, so a text field on
 * the virtual display would never get it. [KeyboardStandIn], a view in the activity's window, takes
 * the keyboard while a text field on the screens is being edited and hands over that field's own
 * input connection.
 *
 * The virtual display and its window live as long as the activity. The surface comes and goes as the
 * activity is shown and hidden, and the display is pointed at each new surface, so the screens keep
 * their state.
 */
class SharpHost private constructor(
    private val activity: ComponentActivity,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val content: @Composable (screensWindow: Window) -> Unit,
) {
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var screens: ComposeView? = null
    private val standIn = KeyboardStandIn(activity) { screens?.getChildAt(0) }

    /** Whether the activity's window has the focus. The virtual display's window never has it, and a
     * text field draws its cursor only in a focused window, so the screens are given this value
     * instead ([LocalWindowInfo]). */
    private val focused = mutableStateOf(activity.window.decorView.hasWindowFocus())

    private fun attach() {
        val surface = SurfaceView(activity)
        surface.holder.setFixedSize(width, height)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                val shown = display
                if (shown != null) {
                    shown.surface = holder.surface
                    return
                }
                val manager = activity.getSystemService(DisplayManager::class.java)
                val made = manager.createVirtualDisplay(
                    "rpm-screens", width, height, densityDpi, holder.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                )
                display = made
                show(made.display)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                display?.surface = null
            }
        })
        activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener { focused.value = it }
        val root = FrameLayout(activity)
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(standIn, FrameLayout.LayoutParams(1, 1))
        activity.setContentView(root)
    }

    private fun show(on: Display) {
        val p = Presentation(activity, on)
        val window = p.window ?: error("a presentation has a window")
        // Black where nothing on the screens draws, as in the activity's window. The presentation's
        // theme background is near-white, which would show until the player's room draws its first frame.
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
        val compose = ComposeView(p.context).apply {
            setContent {
                // The stand-in shows and hides the keyboard, because the keyboard does not serve the
                // screens' own view.
                val own = LocalWindowInfo.current
                val info = remember(own) {
                    object : WindowInfo {
                        override val isWindowFocused: Boolean get() = focused.value
                        override val containerSize: IntSize get() = own.containerSize
                    }
                }
                CompositionLocalProvider(LocalSoftwareKeyboardController provides standIn.controller, LocalWindowInfo provides info) { content(window) }
            }
        }
        p.window?.decorView?.let(::ownedByActivity)
        ownedByActivity(compose)
        p.setContentView(compose)
        p.setCancelable(false)
        p.show()
        presentation = p
        screens = compose
        // Before every frame of the screens, check whether a text field there is being edited.
        compose.viewTreeObserver.addOnPreDrawListener(ViewTreeObserver.OnPreDrawListener {
            standIn.follow()
            true
        })
        Diag.i(TAG, "screens at ${width}x$height, $densityDpi dpi")
    }

    private fun ownedByActivity(view: View) {
        view.setViewTreeLifecycleOwner(activity)
        view.setViewTreeSavedStateRegistryOwner(activity)
        view.setViewTreeViewModelStoreOwner(activity)
        view.setViewTreeOnBackPressedDispatcherOwner(activity)
    }

    /** Hands a key to the screens' window. False when it is not shown yet or nothing in it took the key. */
    fun dispatchKey(event: KeyEvent): Boolean {
        val handled = presentation?.window?.superDispatchKeyEvent(event) ?: false
        standIn.follow()
        return handled
    }

    fun release() {
        standIn.follow(editing = false)
        presentation?.dismiss()
        presentation = null
        screens = null
        display?.release()
        display = null
    }

    /**
     * A view in the activity's window that the on-screen keyboard serves in place of the screens' text
     * fields. [follow] compares what the screens' Compose view reports ([View.onCheckIsTextEditor]: a
     * text field there has an input session) with its own last state. On a change it takes the keyboard
     * or gives it back. The connection it hands the keyboard is the Compose view's own, so typing goes
     * to the field as if the keyboard served it.
     */
    private class KeyboardStandIn(context: Context, private val editor: () -> View?) : View(context) {
        private var editing = false
        private val keyboard = context.getSystemService(InputMethodManager::class.java)

        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        fun follow(editing: Boolean = editor()?.onCheckIsTextEditor() == true) {
            if (editing == this.editing) return
            this.editing = editing
            if (editing) {
                requestFocus()
                keyboard.restartInput(this)
                keyboard.showSoftInput(this, 0)
            } else {
                keyboard.hideSoftInputFromWindow(windowToken, 0)
                clearFocus()
                keyboard.restartInput(this)
            }
        }

        /** What the screens' `LocalSoftwareKeyboardController` does: shows or hides the keyboard over a
         * field being edited. A field with no input session has nothing to type into, so show waits for
         * [follow]. */
        val controller = object : SoftwareKeyboardController {
            override fun show() {
                if (editing) keyboard.showSoftInput(this@KeyboardStandIn, 0)
            }

            override fun hide() {
                keyboard.hideSoftInputFromWindow(windowToken, 0)
            }
        }

        override fun onCheckIsTextEditor(): Boolean = editing

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
            if (editing) editor()?.onCreateInputConnection(outAttrs) else null
    }

    companion object {
        private const val TAG = "rpm.sharp"

        /**
         * A host that draws [content] [height] pixels high, as wide as the window's shape gives, at the
         * density that keeps the window's layout.
         */
        fun at(activity: ComponentActivity, height: Int, content: @Composable (screensWindow: Window) -> Unit): SharpHost {
            val metrics = activity.resources.displayMetrics
            val windowHeight = minOf(metrics.widthPixels, metrics.heightPixels)
            val share = height.toFloat() / windowHeight
            val host = SharpHost(
                activity,
                Math.round(metrics.widthPixels * share),
                Math.round(metrics.heightPixels * share),
                Math.round(metrics.densityDpi * share),
                content,
            )
            host.attach()
            return host
        }
    }
}
