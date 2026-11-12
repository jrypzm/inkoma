package inkoma.core

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/** Linux desktop integration: application-menu entry for the AppImage and libnotify notifications. */
object DesktopIntegration {
    private val home = System.getProperty("user.home")
    private val dataHome = File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share")
    private val desktopFile = File(dataHome, "applications/inkoma.desktop")
    private val iconFile = File(dataHome, "icons/hicolor/512x512/apps/inkoma.png")
    private val logger = Logger.getLogger("DesktopIntegration")

    /** Path of the running AppImage (set by the AppImage runtime), or null when run from source. */
    val appImagePath: String? get() = if (Platform.isLinux) System.getenv("APPIMAGE")?.takeIf { File(it).exists() } else null

    fun isMenuEntryInstalled(): Boolean = desktopFile.exists()

    /** Adds Inkoma to the applications menu (Cinnamon/GNOME/KDE read ~/.local/share/applications). */
    fun installMenuEntry(): Result<Unit> = runCatching {
        val exec = appImagePath ?: error("Only available when running the AppImage")
        iconFile.parentFile.mkdirs()
        DesktopIntegration::class.java.getResourceAsStream("/icons/inkoma.png")!!.use { input -> iconFile.outputStream().use { input.copyTo(it) } }
        desktopFile.parentFile.mkdirs()
        desktopFile.writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=Inkoma
            GenericName=Manga reader
            Comment=Free and open source manga reader
            Exec="${exec.replace("\"", "\\\"")}" %U
            Icon=inkoma
            Terminal=false
            Categories=Graphics;Viewer;
            StartupWMClass=inkoma-MainKt
            """.trimIndent() + "\n",
        )
        desktopFile.setExecutable(true)
        runQuietly("update-desktop-database", File(dataHome, "applications").absolutePath)
        runQuietly("gtk-update-icon-cache", "-f", "-t", File(dataHome, "icons/hicolor").absolutePath)
    }

    fun removeMenuEntry() {
        desktopFile.delete()
        iconFile.delete()
        runQuietly("update-desktop-database", File(dataHome, "applications").absolutePath)
    }

    /** Shows a desktop notification through notify-send (libnotify), if available. */
    fun notify(title: String, body: String, iconPath: String? = null) {
        when {
            Platform.isMac -> runQuietly(
                "osascript", "-e",
                "display notification \"${body.replace("\"", "'")}\" with title \"${title.replace("\"", "'")}\"",
            )
            Platform.isWindows -> trayNotify(title, body)
            else -> {
                val icon = iconPath?.takeIf { File(it).exists() } ?: if (iconFile.exists()) iconFile.absolutePath else "inkoma"
                runQuietly("notify-send", "--app-name=Inkoma", "--icon=$icon", title, body)
            }
        }
    }

    private var trayIcon: java.awt.TrayIcon? = null

    /** Windows toast through the system tray (no extra tray icon stays visible longer than needed). */
    @Synchronized
    private fun trayNotify(title: String, body: String) {
        runCatching {
            if (!java.awt.SystemTray.isSupported()) return
            val icon = trayIcon ?: run {
                val image = javax.imageio.ImageIO.read(DesktopIntegration::class.java.getResourceAsStream("/icons/inkoma.png"))
                java.awt.TrayIcon(image, "Inkoma").apply { isImageAutoSize = true }.also {
                    java.awt.SystemTray.getSystemTray().add(it)
                    trayIcon = it
                }
            }
            icon.displayMessage(title, body, java.awt.TrayIcon.MessageType.INFO)
        }
    }

    private fun runQuietly(vararg command: String) {
        try {
            val process = ProcessBuilder(*command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroy()
        } catch (e: Exception) {
            logger.log(Level.FINE, "${command.first()} unavailable", e)
        }
    }
}
