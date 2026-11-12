package android.util

class DisplayMetrics {
    @JvmField var widthPixels: Int = screen().first
    @JvmField var heightPixels: Int = screen().second
    @JvmField var density: Float = 1f
    @JvmField var densityDpi: Int = 160
    @JvmField var scaledDensity: Float = 1f
    @JvmField var xdpi: Float = 160f
    @JvmField var ydpi: Float = 160f

    fun setToDefaults() {}

    private fun screen(): Pair<Int, Int> = runCatching {
        val d = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode
        d.width to d.height
    }.getOrDefault(1920 to 1080)

    companion object {
        const val DENSITY_DEFAULT = 160
    }
}
