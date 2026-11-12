package inkoma.core

import java.io.File

/** Per-OS data and cache directories used by the app. */
object AppDirs {
    private val home: String = System.getProperty("user.home")

    private val dataBase: File = when {
        Platform.isWindows -> File(System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: "$home/AppData/Roaming")
        Platform.isMac -> File("$home/Library/Application Support")
        else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share")
    }

    private val cacheBase: File = when {
        Platform.isWindows -> File(System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: "$home/AppData/Local")
        Platform.isMac -> File("$home/Library/Caches")
        else -> File(System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.cache")
    }

    /** Linux: ~/.local/share/inkoma; Windows: %APPDATA%\Inkoma; macOS: ~/Library/Application Support/Inkoma. */
    val data: File = File(dataBase, if (Platform.isLinux) "inkoma" else "Inkoma").apply { mkdirs() }

    val cache: File = when {
        Platform.isWindows -> File(cacheBase, "Inkoma/Cache")
        Platform.isMac -> File(cacheBase, "Inkoma")
        else -> File(cacheBase, "inkoma")
    }.apply { mkdirs() }

    val extensions: File get() = File(data, "extensions").apply { mkdirs() }
    val defaultDownloads: File get() = File(data, "downloads")
    val autoBackups: File get() = File(data, "autobackup").apply { mkdirs() }
    val local: File get() = File(data, "local").apply { mkdirs() }
    val covers: File get() = File(data, "covers").apply { mkdirs() }
    val database: File get() = File(data, "inkoma.db")
}
