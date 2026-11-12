package inkoma.extension

import eu.kanade.tachiyomi.source.Source
import inkoma.domain.ExtensionStore

enum class ContentWarning { SAFE, MIXED, NSFW }

sealed interface Extension {
    val name: String
    val pkgName: String
    val versionName: String
    val versionCode: Long
    val libVersion: Double?
    val contentWarning: ContentWarning

    data class Available(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val contentWarning: ContentWarning,
        val lang: String,
        val apkUrl: String,
        val iconUrl: String,
        val sources: List<AvailableSource>,
        val store: ExtensionStore,
    ) : Extension

    data class AvailableSource(val id: Long, val name: String, val lang: String, val baseUrl: String)

    sealed interface Installed : Extension {
        val signatures: List<String>
        val store: ExtensionStore?
        val iconPath: String?
    }

    data class Loaded(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val contentWarning: ContentWarning,
        override val signatures: List<String>,
        override val store: ExtensionStore?,
        override val iconPath: String?,
        val lang: String,
        val sources: List<Source>,
        val classLoader: ClassLoader,
        val hasUpdate: Boolean = false,
        val isObsolete: Boolean = false,
    ) : Installed

    data class NotLoaded(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double?,
        override val contentWarning: ContentWarning,
        override val signatures: List<String>,
        override val store: ExtensionStore?,
        override val iconPath: String?,
        val reason: Reason,
    ) : Installed {
        sealed interface Reason {
            data object Malformed : Reason
            data object UnsupportedLibVersion : Reason
            data object Unsigned : Reason
            data class Untrusted(val signature: String) : Reason
            data object Filtered : Reason
            data class Failed(val message: String, val stackTrace: String) : Reason
        }
    }
}

enum class InstallStep { Idle, Pending, Downloading, Installing, Installed, Error }
