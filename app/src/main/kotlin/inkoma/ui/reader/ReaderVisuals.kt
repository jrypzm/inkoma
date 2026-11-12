package inkoma.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import inkoma.Inkoma
import inkoma.core.ScalingQuality

/** Image filters, scaling quality and smart background for the reader. */
object ReaderVisuals {
    /**
     * Colour matrix for brightness (-100..100), contrast (-100..100), warmth (0..100), grayscale and invert.
     * Returns null when every filter is neutral so images are drawn untouched.
     */
    fun colorMatrix(brightness: Int, contrast: Int, warmth: Int, grayscale: Boolean, invert: Boolean): ColorMatrix? {
        if (brightness == 0 && contrast == 0 && warmth == 0 && !grayscale && !invert) return null
        val result = ColorMatrix()
        if (grayscale) result.timesAssign(ColorMatrix().apply { setToSaturation(0f) })
        if (invert) {
            result.timesAssign(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
        }
        if (contrast != 0) {
            val c = 1f + contrast / 100f
            val t = 128f * (1f - c)
            result.timesAssign(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        }
        if (brightness != 0) {
            val b = brightness * 2.55f
            result.timesAssign(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, b, 0f, 1f, 0f, 0f, b, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f)))
        }
        if (warmth != 0) {
            // Night tint: keep red, reduce green slightly and blue more.
            val w = warmth / 100f
            result.timesAssign(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f - 0.15f * w, 0f, 0f, 0f, 0f, 0f, 1f - 0.45f * w, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        }
        return result
    }

    @Composable
    fun rememberColorFilter(): ColorFilter? {
        val p = Inkoma.preferences
        val brightness by p.filterBrightness.changes().collectAsState(p.filterBrightness.get())
        val contrast by p.filterContrast.changes().collectAsState(p.filterContrast.get())
        val warmth by p.filterWarmth.changes().collectAsState(p.filterWarmth.get())
        val gray by p.filterGrayscale.changes().collectAsState(p.filterGrayscale.get())
        val invert by p.filterInvert.changes().collectAsState(p.filterInvert.get())
        return colorMatrix(brightness, contrast, warmth, gray, invert)?.let { ColorFilter.colorMatrix(it) }
    }

    @Composable
    fun rememberFilterQuality(): FilterQuality {
        val q by Inkoma.preferences.scalingQuality.changes().collectAsState(Inkoma.preferences.scalingQuality.get())
        return when (q) {
            ScalingQuality.FAST -> FilterQuality.Low
            ScalingQuality.SMOOTH -> FilterQuality.Medium
            ScalingQuality.HIGH -> FilterQuality.High
        }
    }

    /**
     * Average colour of the page's outer border (2% inset strips on all four sides). Returns null when the border
     * is too varied (a busy illustration), so the reader keeps its normal background.
     */
    fun edgeColor(bitmap: ImageBitmap): Color? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 8 || h < 8) return null
        val pixels = IntArray(w * h)
        bitmap.readPixels(pixels)
        val inset = maxOf(1, minOf(w, h) / 50)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var n = 0
        val samples = ArrayList<Int>()
        fun add(x: Int, y: Int) {
            val c = pixels[y * w + x]
            r += (c shr 16 and 0xFF); g += (c shr 8 and 0xFF); b += (c and 0xFF); n++
            samples.add(c)
        }
        val stepX = maxOf(1, w / 64)
        val stepY = maxOf(1, h / 64)
        for (x in 0 until w step stepX) { add(x, inset); add(x, h - 1 - inset) }
        for (y in 0 until h step stepY) { add(inset, y); add(w - 1 - inset, y) }
        if (n == 0) return null
        val ar = r / n
        val ag = g / n
        val ab = b / n
        val variance = samples.sumOf { c ->
            val dr = (c shr 16 and 0xFF) - ar
            val dg = (c shr 8 and 0xFF) - ag
            val db = (c and 0xFF) - ab
            dr * dr + dg * dg + db * db
        } / n
        if (variance > 40.0 * 40.0) return null
        return Color((ar / 255).toFloat(), (ag / 255).toFloat(), (ab / 255).toFloat())
    }

    /** Long vertical strips (height > 2.5 × width) indicate a webtoon. */
    fun looksLikeWebtoon(width: Int, height: Int): Boolean = width > 0 && height > width * 2.5
}
