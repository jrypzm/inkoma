package inkoma.core

import java.util.Properties

object BuildInfo {
    private val props = Properties().apply {
        BuildInfo::class.java.getResourceAsStream("/app.properties")?.use { load(it) }
    }
    val versionName: String = props.getProperty("versionName", "0.19.3")
    val versionCode: Int = props.getProperty("versionCode", "14").toIntOrNull() ?: 14
}
