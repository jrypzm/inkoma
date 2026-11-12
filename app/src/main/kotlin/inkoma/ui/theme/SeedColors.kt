package inkoma.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/** HSL helpers and a small tonal-palette generator used for custom themes and cover-based colours. */
object SeedColors {
    data class Hsl(val h: Float, val s: Float, val l: Float)

    fun toHsl(color: Color): Hsl {
        val r = color.red
        val g = color.green
        val b = color.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return Hsl(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f
        return Hsl(h, s, l)
    }

    fun fromHsl(h: Float, s: Float, l: Float): Color {
        val hh = ((h % 360f) + 360f) % 360f
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((hh / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            hh < 60 -> Triple(c, x, 0f)
            hh < 120 -> Triple(x, c, 0f)
            hh < 180 -> Triple(0f, c, x)
            hh < 240 -> Triple(0f, x, c)
            hh < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
    }

    /** Material-3-like scheme built around [seed]; neutral roles keep a faint tint of the seed hue. */
    fun scheme(seed: Color, dark: Boolean, base: ColorScheme): ColorScheme {
        val hsl = toHsl(seed)
        val h = hsl.h
        val s = hsl.s.coerceIn(0.25f, 0.85f)
        val s2 = (s * 0.45f).coerceAtMost(0.4f)
        val tertiaryHue = h + 60f
        fun c(hue: Float, sat: Float, light: Float) = fromHsl(hue, sat, light)
        return if (dark) {
            base.copy(
                primary = c(h, s, 0.78f), onPrimary = c(h, s, 0.18f),
                primaryContainer = c(h, s, 0.30f), onPrimaryContainer = c(h, s, 0.90f), inversePrimary = c(h, s, 0.40f),
                secondary = c(h, s2, 0.76f), onSecondary = c(h, s2, 0.18f),
                secondaryContainer = c(h, s2, 0.28f), onSecondaryContainer = c(h, s2, 0.90f),
                tertiary = c(tertiaryHue, s * 0.8f, 0.76f), onTertiary = c(tertiaryHue, s * 0.8f, 0.18f),
                tertiaryContainer = c(tertiaryHue, s * 0.8f, 0.28f), onTertiaryContainer = c(tertiaryHue, s * 0.8f, 0.90f),
                background = c(h, 0.10f, 0.08f), onBackground = c(h, 0.08f, 0.90f),
                surface = c(h, 0.10f, 0.08f), onSurface = c(h, 0.08f, 0.90f),
                surfaceVariant = c(h, 0.10f, 0.14f), onSurfaceVariant = c(h, 0.10f, 0.78f),
                surfaceTint = c(h, s, 0.78f),
                surfaceContainerLowest = c(h, 0.10f, 0.06f), surfaceContainerLow = c(h, 0.10f, 0.10f),
                surfaceContainer = c(h, 0.10f, 0.12f), surfaceContainerHigh = c(h, 0.10f, 0.16f),
                surfaceContainerHighest = c(h, 0.10f, 0.20f),
                outline = c(h, 0.08f, 0.56f), outlineVariant = c(h, 0.08f, 0.28f),
            )
        } else {
            base.copy(
                primary = c(h, s, 0.38f), onPrimary = Color.White,
                primaryContainer = c(h, s, 0.88f), onPrimaryContainer = c(h, s, 0.12f), inversePrimary = c(h, s, 0.78f),
                secondary = c(h, s2, 0.40f), onSecondary = Color.White,
                secondaryContainer = c(h, s2, 0.88f), onSecondaryContainer = c(h, s2, 0.12f),
                tertiary = c(tertiaryHue, s * 0.8f, 0.36f), onTertiary = Color.White,
                tertiaryContainer = c(tertiaryHue, s * 0.8f, 0.88f), onTertiaryContainer = c(tertiaryHue, s * 0.8f, 0.12f),
                background = c(h, 0.30f, 0.985f), onBackground = c(h, 0.10f, 0.10f),
                surface = c(h, 0.30f, 0.985f), onSurface = c(h, 0.10f, 0.10f),
                surfaceVariant = c(h, 0.25f, 0.94f), onSurfaceVariant = c(h, 0.10f, 0.30f),
                surfaceTint = c(h, s, 0.38f),
                surfaceContainerLowest = c(h, 0.25f, 0.97f), surfaceContainerLow = c(h, 0.25f, 0.96f),
                surfaceContainer = c(h, 0.25f, 0.94f), surfaceContainerHigh = c(h, 0.25f, 0.92f),
                surfaceContainerHighest = c(h, 0.25f, 0.90f),
                outline = c(h, 0.08f, 0.48f), outlineVariant = c(h, 0.10f, 0.80f),
            )
        }
    }

    /**
     * Most prominent "colourful" colour of an image: hue histogram weighted by saturation and brightness.
     * Returns null for (near) greyscale images, where a tinted theme would look wrong.
     */
    fun dominantColor(bitmap: ImageBitmap): Color? {
        val stepX = maxOf(1, bitmap.width / 48)
        val stepY = maxOf(1, bitmap.height / 48)
        val w = bitmap.width / stepX
        val hgt = bitmap.height / stepY
        if (w == 0 || hgt == 0) return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.readPixels(pixels)
        val buckets = DoubleArray(36)
        val sumR = DoubleArray(36)
        val sumG = DoubleArray(36)
        val sumB = DoubleArray(36)
        var colourful = 0.0
        var total = 0.0
        for (yy in 0 until hgt) {
            for (xx in 0 until w) {
                val argb = pixels[yy * stepY * bitmap.width + xx * stepX]
                val col = Color(argb)
                val hsl = toHsl(col)
                val weight = hsl.s * (1f - kotlin.math.abs(hsl.l - 0.5f) * 2f)
                total += 1
                if (weight < 0.15f) continue
                colourful += weight
                val b = (hsl.h / 10f).toInt().coerceIn(0, 35)
                buckets[b] += weight
                sumR[b] += col.red * weight
                sumG[b] += col.green * weight
                sumB[b] += col.blue * weight
            }
        }
        if (total == 0.0 || colourful / total < 0.04) return null
        val best = buckets.indices.maxBy { buckets[it] }
        val wsum = buckets[best]
        return Color((sumR[best] / wsum).toFloat(), (sumG[best] / wsum).toFloat(), (sumB[best] / wsum).toFloat())
    }

    fun argb(color: Color): Int = color.toArgb()
}

/** Re-themes [content] around [seed] (e.g. a cover's dominant colour), keeping the current light/dark mode. */
@Composable
fun SeedTheme(seed: Color?, content: @Composable () -> Unit) {
    if (seed == null) {
        content()
        return
    }
    val base = MaterialTheme.colorScheme
    val dark = base.background.luminance() < 0.5f
    MaterialTheme(colorScheme = SeedColors.scheme(seed, dark, base), typography = MaterialTheme.typography, content = content)
}

/** Dominant colour of a manga cover for cover-based theming (null when disabled or the cover is greyscale). */
@Composable
fun rememberCoverSeed(url: String?, sourceId: Long): Color? {
    val enabled by inkoma.Inkoma.preferences.coverColors.changes()
        .collectAsState(inkoma.Inkoma.preferences.coverColors.get())
    val seed by produceState<Color?>(null, url, enabled) {
        value = if (!enabled || url.isNullOrBlank()) {
            null
        } else {
            inkoma.ui.components.ImageLoader.load(url, sourceId, 480, persistent = false)
                ?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { SeedColors.dominantColor(it) } }
        }
    }
    return seed
}
