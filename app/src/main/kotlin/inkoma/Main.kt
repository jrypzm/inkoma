package inkoma

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.launch
import inkoma.ui.InkomaApp
import inkoma.ui.AppKeyEvents
import java.util.logging.Level
import java.util.logging.Logger

fun main() {
    Logger.getLogger("").level = if (System.getenv("INKOMA_DEBUG") != null) Level.FINE else Level.WARNING
    Inkoma.init()

    // Rendering backend: software rasterizer by default (no GPU driver mapped into the process: lower RSS).
    // Settings › Advanced › GPU rendering (or INKOMA_RENDER_API=OPENGL) switches to OpenGL; Skiko falls back to
    // software automatically if OpenGL is unavailable. Must be set before any Compose/Skiko class loads.
    // Windows (Direct3D) and macOS (Metal) use Skiko's native GPU backend; Linux defaults to software (see above).
    val renderApi = System.getenv("INKOMA_RENDER_API")
        ?: if (inkoma.core.Platform.isLinux) (if (Inkoma.preferences.gpuRendering.get()) "OPENGL" else "SOFTWARE_FAST") else null
    if (renderApi != null) System.setProperty("skiko.renderApi", renderApi)
    System.setProperty("compose.application.configure.swing.globals", "true")
    Inkoma.downloadManager.restoreQueue()
    Inkoma.scope.launch {
        Inkoma.extensionManager.loadInstalled()
        Inkoma.extensionManager.refreshAvailable()
        Inkoma.libraryUpdater.startScheduler()
        Inkoma.backupManager.startAutoBackupScheduler()
    }

    application {
        val state = rememberWindowState(size = DpSize(1280.dp, 800.dp))
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "Inkoma",
            icon = painterResource("icons/inkoma.png"),
            onPreviewKeyEvent = { AppKeyEvents.dispatch(it) },
        ) {
            InkomaApp(
                isFullscreen = state.placement == WindowPlacement.Fullscreen,
                setFullscreen = { full -> state.placement = if (full) WindowPlacement.Fullscreen else WindowPlacement.Floating },
                minimize = { state.isMinimized = true },
                close = ::exitApplication,
            )
        }
    }
}
