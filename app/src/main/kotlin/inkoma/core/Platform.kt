package inkoma.core

/** Operating system the app runs on. */
object Platform {
    private val os = System.getProperty("os.name").lowercase()
    val isWindows: Boolean = os.contains("win")
    val isMac: Boolean = os.contains("mac") || os.contains("darwin")
    val isLinux: Boolean = !isWindows && !isMac
}
