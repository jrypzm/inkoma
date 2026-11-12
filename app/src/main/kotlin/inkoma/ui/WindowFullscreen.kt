package inkoma.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import inkoma.core.Platform
import java.awt.Frame
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.logging.Logger
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * True fullscreen without a title bar, on every OS.
 *
 * Compose's `WindowPlacement.Fullscreen` hands a *decorated* window to AWT's exclusive full-screen mode on Windows and
 * Linux. On Windows that keeps the title bar and borders and does not cover the taskbar. Instead, on Windows and Linux
 * the window is turned into a borderless window that covers the whole monitor (the standard "borderless fullscreen"
 * used by browsers and games); on Linux the window manager additionally gets the fullscreen hint so panels and docks
 * hide. macOS keeps its native full-screen space, which has no title bar already.
 */
class WindowFullscreen(private val window: ComposeWindow, private val state: WindowState) {
    /** Borderless fullscreen on Windows/Linux; on macOS the native state (it can also change from the green button). */
    private var borderless by mutableStateOf(false)
    val isFullscreen: Boolean get() = if (Platform.isMac) state.placement == WindowPlacement.Fullscreen else borderless

    private val logger = Logger.getLogger("WindowFullscreen")
    private var lastNormalBounds: Rectangle? = null
    private var savedExtendedState = Frame.NORMAL
    private var switching = false

    init {
        // Remember the last floating (not maximized, not fullscreen) bounds to restore them later.
        window.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = remember()
            override fun componentMoved(e: ComponentEvent) = remember()
            private fun remember() {
                if (!isFullscreen && !switching && window.extendedState == Frame.NORMAL && window.isShowing) {
                    lastNormalBounds = window.bounds
                }
            }
        })
    }

    fun toggle() = set(!isFullscreen)

    fun set(fullscreen: Boolean) {
        if (fullscreen == isFullscreen || switching) return
        if (Platform.isMac) {
            state.placement = if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
            return
        }
        borderless = fullscreen
        // Never swap the native window while an input event of that window is still being dispatched.
        SwingUtilities.invokeLater { runCatching { if (fullscreen) enter() else exit() }.onFailure { recover(it) } }
    }

    /** Minimizes the window. macOS cannot minimize a full-screen space, so it leaves full screen first. */
    fun minimize() {
        if (Platform.isMac && isFullscreen) {
            set(false)
            Timer(800) { state.isMinimized = true }.apply { isRepeats = false }.start()
        } else {
            state.isMinimized = true
        }
    }

    private fun enter() {
        switching = true
        try {
            savedExtendedState = window.extendedState and Frame.ICONIFIED.inv()
            if (savedExtendedState == Frame.NORMAL) lastNormalBounds = window.bounds
            val screen = window.graphicsConfiguration.bounds
            rebuild(undecorated = true) {
                window.extendedState = Frame.NORMAL
                window.bounds = screen
            }
            if (Platform.isLinux) runCatching { window.graphicsConfiguration.device.fullScreenWindow = window }
        } finally {
            switching = false
        }
    }

    private fun exit() {
        switching = true
        try {
            if (Platform.isLinux) runCatching { if (window.graphicsConfiguration.device.fullScreenWindow === window) window.graphicsConfiguration.device.fullScreenWindow = null }
            rebuild(undecorated = false) {
                lastNormalBounds?.let { window.bounds = it }
            }
            if (savedExtendedState and Frame.MAXIMIZED_BOTH != 0) window.extendedState = Frame.MAXIMIZED_BOTH
        } finally {
            switching = false
        }
    }

    /**
     * AWT only allows changing the decorations of a window without a native peer. The peer is dropped and recreated
     * (as JFrame.dispose() + setVisible(true) do) without calling ComposeWindow.dispose(), which would end the
     * composition: Compose and Skia reattach to the new peer and the UI state is kept.
     */
    private fun rebuild(undecorated: Boolean, configure: () -> Unit) {
        // The Compose content component receives keyboard input; give the focus back to it on the new peer
        // (focusing the frame itself would leave keys such as F11 and the reader shortcuts without a receiver).
        val focusOwner = window.mostRecentFocusOwner
        window.isVisible = false
        window.removeNotify()
        window.isUndecorated = undecorated
        configure()
        window.isVisible = true
        window.toFront()
        val target = focusOwner?.takeIf { it !== window } ?: return
        // The new peer is not the focused window yet: focus the content once it is (and ask right away as well).
        window.addWindowFocusListener(object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent) {
                window.removeWindowFocusListener(this)
                if (target.isShowing) target.requestFocusInWindow()
            }
        })
        SwingUtilities.invokeLater { if (target.isShowing) target.requestFocus() }
    }

    private fun recover(error: Throwable) {
        logger.warning("Fullscreen switch failed: ${error.message}")
        switching = false
        borderless = window.isUndecorated
        if (!window.isVisible) window.isVisible = true
    }
}
