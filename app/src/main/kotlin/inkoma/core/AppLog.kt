package inkoma.core

import java.io.File
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

/** Writes warnings and errors to <data>/logs/inkoma.log (kept small: 1 MB, 2 files), so problems can be reported. */
object AppLog {
    val file: File get() = File(AppDirs.data, "logs/inkoma.log")

    fun install(level: Level) {
        runCatching {
            file.parentFile.mkdirs()
            System.setProperty("java.util.logging.SimpleFormatter.format", "%1\$tF %1\$tT %4\$s %3\$s: %5\$s%6\$s%n")
            val handler = FileHandler(File(file.parentFile, "inkoma%g.log").absolutePath, 1_000_000, 2, true).apply {
                formatter = SimpleFormatter()
                this.level = level
            }
            Logger.getLogger("").addHandler(handler)
        }
    }

    /** Exception type, message and causes, for error screens and bug reports. */
    fun details(e: Throwable): String = generateSequence(e) { it.cause }.take(5)
        .joinToString("\nCaused by: ") { "${it.javaClass.name}: ${it.message ?: ""}".trimEnd(' ', ':') }
}
