package inkoma.ui

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import inkoma.ui.browse.SourceCategories
import inkoma.ui.reader.KeyBindings
import inkoma.ui.reader.ReaderAction
import inkoma.ui.reader.ReaderVisuals
import inkoma.ui.theme.SeedColors
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiFeaturesTest {
    private fun bitmap(w: Int, h: Int, border: Color, centre: Color): ImageBitmap {
        val bmp = ImageBitmap(w, h)
        val canvas = Canvas(bmp)
        canvas.drawRect(Rect(0f, 0f, w.toFloat(), h.toFloat()), Paint().apply { color = border })
        canvas.drawRect(Rect(w * 0.2f, h * 0.2f, w * 0.8f, h * 0.8f), Paint().apply { color = centre })
        return bmp
    }

    @Test
    fun keyBindingsRoundTripAndDefaults() {
        val defaults = KeyBindings.parse("")
        assertEquals(ReaderAction.entries.size, defaults.size)
        assertEquals(ReaderAction.NEXT_CHAPTER, KeyBindings.actionFor(Key.N, defaults))
        val custom = defaults.toMutableMap().apply { this[ReaderAction.NEXT_CHAPTER] = listOf(Key.J) }
        val raw = KeyBindings.serialize(custom)
        assertTrue(raw.startsWith("NEXT_CHAPTER="), raw)
        val parsed = KeyBindings.parse(raw)
        assertEquals(listOf(Key.J), parsed[ReaderAction.NEXT_CHAPTER])
        assertEquals(ReaderAction.TOGGLE_MENU.defaults, parsed[ReaderAction.TOGGLE_MENU])
        assertEquals("", KeyBindings.serialize(defaults), "defaults are not stored")
        assertEquals(defaults, KeyBindings.parse("garbage;;NOPE=1,2;NEXT_PAGE=x"))
    }

    @Test
    fun filtersAreNeutralByDefaultAndInvertFlips() {
        assertNull(ReaderVisuals.colorMatrix(0, 0, 0, grayscale = false, invert = false))
        val inv = ReaderVisuals.colorMatrix(0, 0, 0, grayscale = false, invert = true)!!.values
        assertEquals(-1f, inv[0]); assertEquals(255f, inv[4]); assertEquals(1f, inv[18])
        val gray = ReaderVisuals.colorMatrix(0, 0, 0, grayscale = true, invert = false)!!.values
        assertTrue(abs(gray[0] - gray[5]) < 0.01f && abs(gray[1] - gray[6]) < 0.01f, "grey rows equal")
    }

    @Test
    fun smartBackgroundUsesUniformBordersOnly() {
        val edge = ReaderVisuals.edgeColor(bitmap(200, 300, Color.White, Color.Black))
        assertNotNull(edge)
        assertTrue(edge.red > 0.95f && edge.green > 0.95f && edge.blue > 0.95f, "white border detected: $edge")
        // Half black / half white border is too varied: keep the normal background.
        val mixed = ImageBitmap(200, 300)
        Canvas(mixed).apply {
            drawRect(Rect(0f, 0f, 100f, 300f), Paint().apply { color = Color.Black })
            drawRect(Rect(100f, 0f, 200f, 300f), Paint().apply { color = Color.White })
        }
        assertNull(ReaderVisuals.edgeColor(mixed))
    }

    @Test
    fun webtoonDetection() {
        assertTrue(ReaderVisuals.looksLikeWebtoon(800, 12000))
        assertFalse(ReaderVisuals.looksLikeWebtoon(800, 1200))
        assertFalse(ReaderVisuals.looksLikeWebtoon(0, 100))
    }

    @Test
    fun coverSeedIsTheColourfulHueAndNullForGreyscale() {
        val red = SeedColors.dominantColor(bitmap(120, 180, Color(0xFFD32F2F), Color(0xFFE53935)))
        assertNotNull(red)
        val hue = SeedColors.toHsl(red).h
        assertTrue(hue < 15f || hue > 345f, "red hue, got $hue")
        assertNull(SeedColors.dominantColor(bitmap(120, 180, Color.White, Color.DarkGray)))
    }

    @Test
    fun hslRoundTrip() {
        listOf(Color(0xFF0058CA), Color(0xFF006E1B), Color(0xFFE8740C), Color(0xFF808080)).forEach { c ->
            val h = SeedColors.toHsl(c)
            val back = SeedColors.fromHsl(h.h, h.s, h.l)
            assertTrue(abs(back.red - c.red) < 0.01f && abs(back.green - c.green) < 0.01f && abs(back.blue - c.blue) < 0.01f, "$c -> $back")
        }
    }

    @Test
    fun sourceCategoriesParse() {
        val parsed = SourceCategories.parse(setOf("123\tFavourites", "456\tRaw scans", "bad", "x\tY"))
        assertEquals(mapOf(123L to "Favourites", 456L to "Raw scans"), parsed)
    }
}
