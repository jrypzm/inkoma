package android.graphics

import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** android.graphics.Bitmap on top of java.awt BufferedImage (used by image-descrambling interceptors). */
class Bitmap internal constructor(internal val image: BufferedImage, private val config: Config) {
    enum class Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, RGBA_F16, HARDWARE }
    enum class CompressFormat { JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS }

    private var recycled = false

    fun getWidth(): Int = image.width
    fun getHeight(): Int = image.height
    fun getConfig(): Config = config
    fun isRecycled(): Boolean = recycled
    fun recycle() {
        recycled = true
    }

    fun getByteCount(): Int = image.width * image.height * 4
    fun hasAlpha(): Boolean = image.colorModel.hasAlpha()

    fun getPixel(x: Int, y: Int): Int = image.getRGB(x, y)
    fun setPixel(x: Int, y: Int, color: Int) = image.setRGB(x, y, color)

    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        image.getRGB(x, y, width, height, pixels, offset, stride)
    }

    fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        image.setRGB(x, y, width, height, pixels, offset, stride)
    }

    fun copy(config: Config, isMutable: Boolean): Bitmap {
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        return Bitmap(out, config)
    }

    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean {
        return when (format) {
            CompressFormat.PNG, CompressFormat.WEBP_LOSSLESS -> ImageIO.write(image, "png", stream)
            else -> {
                val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
                rgb.createGraphics().apply {
                    color = java.awt.Color.WHITE
                    fillRect(0, 0, image.width, image.height)
                    drawImage(image, 0, 0, null)
                    dispose()
                }
                val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = (quality.coerceIn(0, 100) / 100f)
                }
                ImageIO.createImageOutputStream(stream).use { ios ->
                    writer.output = ios
                    writer.write(null, IIOImage(rgb, null, null), param)
                }
                writer.dispose()
                true
            }
        }
    }

    companion object {
        @JvmStatic
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap =
            Bitmap(BufferedImage(width.coerceAtLeast(1), height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB), config)

        @JvmStatic
        fun createBitmap(source: Bitmap): Bitmap = source.copy(source.config, true)

        @JvmStatic
        fun createBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
            val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            out.createGraphics().apply {
                drawImage(source.image, 0, 0, width, height, x, y, x + width, y + height, null)
                dispose()
            }
            return Bitmap(out, source.config)
        }

        @JvmStatic
        fun createScaledBitmap(src: Bitmap, dstWidth: Int, dstHeight: Int, filter: Boolean): Bitmap {
            val out = BufferedImage(dstWidth, dstHeight, BufferedImage.TYPE_INT_ARGB)
            out.createGraphics().apply {
                if (filter) setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(src.image, 0, 0, dstWidth, dstHeight, null)
                dispose()
            }
            return Bitmap(out, src.config)
        }
    }
}

object BitmapFactory {
    class Options {
        @JvmField var inJustDecodeBounds: Boolean = false
        @JvmField var inSampleSize: Int = 1
        @JvmField var inMutable: Boolean = false
        @JvmField var inPreferredConfig: Bitmap.Config = Bitmap.Config.ARGB_8888
        @JvmField var outWidth: Int = 0
        @JvmField var outHeight: Int = 0
        @JvmField var outMimeType: String? = null
    }

    private fun toArgb(src: BufferedImage): BufferedImage {
        if (src.type == BufferedImage.TYPE_INT_ARGB) return src
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply { drawImage(src, 0, 0, null); dispose() }
        return out
    }

    @JvmStatic
    fun decodeStream(stream: InputStream?): Bitmap? = decodeStream(stream, null, null)

    @JvmStatic
    fun decodeStream(stream: InputStream?, outPadding: Rect?, opts: Options?): Bitmap? {
        if (stream == null) return null
        val img = ImageIO.read(stream) ?: return null
        opts?.outWidth = img.width
        opts?.outHeight = img.height
        if (opts?.inJustDecodeBounds == true) return null
        return Bitmap(toArgb(img), opts?.inPreferredConfig ?: Bitmap.Config.ARGB_8888)
    }

    @JvmStatic
    fun decodeByteArray(data: ByteArray, offset: Int, length: Int): Bitmap? =
        decodeStream(ByteArrayInputStream(data, offset, length), null, null)

    @JvmStatic
    fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: Options?): Bitmap? =
        decodeStream(ByteArrayInputStream(data, offset, length), null, opts)
}

class Rect {
    @JvmField var left: Int = 0
    @JvmField var top: Int = 0
    @JvmField var right: Int = 0
    @JvmField var bottom: Int = 0

    constructor()
    constructor(left: Int, top: Int, right: Int, bottom: Int) {
        set(left, top, right, bottom)
    }
    constructor(r: Rect) : this(r.left, r.top, r.right, r.bottom)

    fun set(left: Int, top: Int, right: Int, bottom: Int) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    fun width(): Int = right - left
    fun height(): Int = bottom - top
    fun isEmpty(): Boolean = left >= right || top >= bottom
    fun offset(dx: Int, dy: Int) {
        left += dx; right += dx; top += dy; bottom += dy
    }
    override fun toString(): String = "Rect($left, $top - $right, $bottom)"
}

class RectF {
    @JvmField var left: Float = 0f
    @JvmField var top: Float = 0f
    @JvmField var right: Float = 0f
    @JvmField var bottom: Float = 0f

    constructor()
    constructor(left: Float, top: Float, right: Float, bottom: Float) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom
    }
    fun width(): Float = right - left
    fun height(): Float = bottom - top
}

open class Paint {
    constructor()
    constructor(flags: Int)

    private var colorValue: Int = 0xFF000000.toInt()
    private var textSizeValue: Float = 12f
    private var typefaceValue: Typeface? = Typeface.DEFAULT
    private var antiAlias: Boolean = false
    @JvmField var isFilterBitmap: Boolean = false

    fun getTextSize(): Float = textSizeValue
    fun setTextSize(size: Float) {
        textSizeValue = size
    }
    fun getTypeface(): Typeface? = typefaceValue
    fun setTypeface(typeface: Typeface?): Typeface? {
        typefaceValue = typeface
        return typeface
    }
    fun isAntiAlias(): Boolean = antiAlias

    /** AWT font equivalent of this paint's typeface and text size. */
    /** For android.text layouts (other package). */
    fun awtFontPublic(): java.awt.Font = awtFont()

    internal fun awtFont(): java.awt.Font = (typefaceValue ?: Typeface.DEFAULT).font.deriveFont(textSizeValue)

    fun measureText(text: String): Float {
        val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        return try { g.getFontMetrics(awtFont()).stringWidth(text).toFloat() } finally { g.dispose() }
    }
    fun getColor(): Int = colorValue
    fun setColor(color: Int) {
        colorValue = color
    }
    fun setAntiAlias(aa: Boolean) {
        antiAlias = aa
    }
    fun setFilterBitmap(filter: Boolean) {
        isFilterBitmap = filter
    }

    companion object {
        const val ANTI_ALIAS_FLAG = 1
        const val FILTER_BITMAP_FLAG = 2
    }
}

object Color {
    const val BLACK = -0x1000000
    const val WHITE = -0x1
    const val TRANSPARENT = 0
    @JvmStatic fun alpha(color: Int) = color ushr 24
    @JvmStatic fun red(color: Int) = color shr 16 and 0xFF
    @JvmStatic fun green(color: Int) = color shr 8 and 0xFF
    @JvmStatic fun blue(color: Int) = color and 0xFF
    @JvmStatic fun rgb(r: Int, g: Int, b: Int) = argb(255, r, g, b)
    @JvmStatic fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
}

/** android.graphics.Typeface mapped to AWT logical fonts. */
class Typeface private constructor(internal val font: java.awt.Font, private val styleValue: Int) {
    fun getStyle(): Int = styleValue
    fun isBold(): Boolean = styleValue and BOLD != 0
    fun isItalic(): Boolean = styleValue and ITALIC != 0

    companion object {
        const val NORMAL = 0
        const val BOLD = 1
        const val ITALIC = 2
        const val BOLD_ITALIC = 3

        private fun awtStyle(style: Int) = (if (style and BOLD != 0) java.awt.Font.BOLD else 0) or (if (style and ITALIC != 0) java.awt.Font.ITALIC else 0)
        private fun of(family: String, style: Int) = Typeface(java.awt.Font(family, awtStyle(style), 12), style)

        @JvmField val DEFAULT: Typeface = of(java.awt.Font.SANS_SERIF, NORMAL)
        @JvmField val DEFAULT_BOLD: Typeface = of(java.awt.Font.SANS_SERIF, BOLD)
        @JvmField val SANS_SERIF: Typeface = of(java.awt.Font.SANS_SERIF, NORMAL)
        @JvmField val SERIF: Typeface = of(java.awt.Font.SERIF, NORMAL)
        @JvmField val MONOSPACE: Typeface = of(java.awt.Font.MONOSPACED, NORMAL)

        @JvmStatic fun create(familyName: String?, style: Int): Typeface = when (familyName?.lowercase()) {
            "serif" -> of(java.awt.Font.SERIF, style)
            "monospace" -> of(java.awt.Font.MONOSPACED, style)
            else -> of(java.awt.Font.SANS_SERIF, style)
        }
        @JvmStatic fun create(family: Typeface?, style: Int): Typeface = Typeface((family ?: DEFAULT).font.deriveFont(awtStyle(style)), style)
        @JvmStatic fun defaultFromStyle(style: Int): Typeface = of(java.awt.Font.SANS_SERIF, style)
    }
}

class Canvas(private val bitmap: Bitmap) {
    private val g = bitmap.image.createGraphics()
    private val saved = ArrayDeque<java.awt.geom.AffineTransform>()

    /** For android.text layouts (other package). */
    fun graphicsPublic(): java.awt.Graphics2D = g

    fun save(): Int {
        saved.addLast(g.transform)
        return saved.size
    }

    fun restore() {
        saved.removeLastOrNull()?.let { g.transform = it }
    }

    fun restoreToCount(count: Int) {
        while (saved.size >= count && saved.isNotEmpty()) restore()
    }

    fun getSaveCount(): Int = saved.size + 1
    fun translate(dx: Float, dy: Float) = g.translate(dx.toDouble(), dy.toDouble())
    fun scale(sx: Float, sy: Float) = g.scale(sx.toDouble(), sy.toDouble())
    fun rotate(degrees: Float) = g.rotate(Math.toRadians(degrees.toDouble()))

    fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        g.font = paint.awtFont()
        g.color = java.awt.Color(paint.getColor(), true)
        if (paint.isAntiAlias()) g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.drawString(text, x, y)
    }

    fun getWidth(): Int = bitmap.getWidth()
    fun getHeight(): Int = bitmap.getHeight()

    fun drawBitmap(src: Bitmap, left: Float, top: Float, paint: Paint?) {
        g.drawImage(src.image, left.toInt(), top.toInt(), null)
    }

    fun drawBitmap(src: Bitmap, srcRect: Rect?, dst: Rect, paint: Paint?) {
        val s = srcRect ?: Rect(0, 0, src.getWidth(), src.getHeight())
        if (paint?.isFilterBitmap == true) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        }
        g.drawImage(src.image, dst.left, dst.top, dst.right, dst.bottom, s.left, s.top, s.right, s.bottom, null)
    }

    fun drawBitmap(src: Bitmap, srcRect: Rect?, dst: RectF, paint: Paint?) {
        drawBitmap(src, srcRect, Rect(dst.left.toInt(), dst.top.toInt(), dst.right.toInt(), dst.bottom.toInt()), paint)
    }

    fun drawColor(color: Int) {
        g.composite = AlphaComposite.Src
        g.color = java.awt.Color(color, true)
        g.fillRect(0, 0, bitmap.getWidth(), bitmap.getHeight())
        g.composite = AlphaComposite.SrcOver
    }

    fun drawRect(rect: Rect, paint: Paint) {
        g.color = java.awt.Color(paint.getColor(), true)
        g.fillRect(rect.left, rect.top, rect.width(), rect.height())
    }
}
