package eu.kanade.tachiyomi

/** Exposed to extensions (extensions-lib 1.4+). The host sets the values at startup via [configure]. */
@Suppress("UNUSED")
object AppInfo {
    @Volatile
    private var code: Int = 14

    @Volatile
    private var name: String = "0.19.3"

    fun configure(versionCode: Int, versionName: String) {
        code = versionCode
        name = versionName
    }

    fun getVersionCode(): Int = code
    fun getVersionName(): String = name
    fun getSupportedImageMimeTypes(): List<String> = listOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp")
}
