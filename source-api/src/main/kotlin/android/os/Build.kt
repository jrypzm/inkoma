package android.os

object Build {
    @JvmField val MANUFACTURER: String = "Linux"
    @JvmField val MODEL: String = "Desktop"
    @JvmField val BRAND: String = "Linux"
    @JvmField val DEVICE: String = "desktop"
    @JvmField val PRODUCT: String = "desktop"
    @JvmField val FINGERPRINT: String = "linux/desktop"
    @JvmField val ID: String = "UP1A.231005.007"
    @JvmField val DISPLAY: String = "UP1A.231005.007"
    @JvmField val HARDWARE: String = "x86_64"
    @JvmField val BOARD: String = "desktop"
    @JvmField val TYPE: String = "user"
    @JvmField val TAGS: String = "release-keys"
    @JvmField val SUPPORTED_ABIS: Array<String> = arrayOf("x86_64")

    object VERSION {
        @JvmField val SDK_INT: Int = 34
        @JvmField val RELEASE: String = "14"
    }

    object VERSION_CODES {
        const val N = 24
        const val O = 26
        const val P = 28
        const val Q = 29
        const val R = 30
        const val S = 31
        const val TIRAMISU = 33
        const val UPSIDE_DOWN_CAKE = 34
    }
}
