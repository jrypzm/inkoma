package android.content.res

import android.util.DisplayMetrics

/** System resources: only display metrics and configuration are meaningful on desktop. */
class Resources private constructor() {
    private val metrics = DisplayMetrics()
    private val configuration = Configuration()

    fun getDisplayMetrics(): DisplayMetrics = metrics
    fun getConfiguration(): Configuration = configuration
    fun getString(id: Int): String = ""

    companion object {
        private val system = Resources()

        @JvmStatic fun getSystem(): Resources = system
    }
}

class Configuration {
    @JvmField var densityDpi: Int = 160
    @JvmField var screenWidthDp: Int = 1280
    @JvmField var screenHeightDp: Int = 800
    @JvmField var orientation: Int = 2
    @JvmField var uiMode: Int = 0x10
    @JvmField var locale: java.util.Locale = java.util.Locale.getDefault()

    fun getLocales(): android.os.LocaleList = android.os.LocaleList.getDefault()
}
