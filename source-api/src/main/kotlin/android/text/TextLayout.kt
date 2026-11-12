package android.text

import android.graphics.Canvas
import android.graphics.Paint
import java.awt.RenderingHints
import java.awt.image.BufferedImage

open class TextPaint : Paint {
    constructor() : super()
    constructor(flags: Int) : super(flags)
}

/** Base of android.text layouts: lines of text wrapped to a width, drawable onto a Canvas. */
abstract class Layout protected constructor(
    private val text: CharSequence,
    private val paint: TextPaint,
    private val width: Int,
    private val alignment: Alignment,
    private val spacingMult: Float,
    private val spacingAdd: Float,
) {
    enum class Alignment { ALIGN_NORMAL, ALIGN_OPPOSITE, ALIGN_CENTER }

    private val metrics = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().let { g ->
        try { g.getFontMetrics(paint.awtFontPublic()) } finally { g.dispose() }
    }

    /** Greedy word wrap honouring explicit line breaks, as StaticLayout does for plain text. */
    private val lines: List<String> = buildList {
        for (paragraph in text.toString().split('\n')) {
            var line = StringBuilder()
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && metrics.stringWidth(candidate) > width) {
                    add(line.toString())
                    line = StringBuilder(word)
                } else {
                    line = StringBuilder(candidate)
                }
            }
            add(line.toString())
        }
    }

    private val lineHeight: Int get() = (metrics.height * spacingMult + spacingAdd).toInt().coerceAtLeast(1)

    fun getText(): CharSequence = text
    fun getPaint(): TextPaint = paint
    fun getWidth(): Int = width
    fun getLineCount(): Int = lines.size
    fun getHeight(): Int = lines.size * lineHeight
    fun getLineTop(line: Int): Int = line * lineHeight
    fun getLineBottom(line: Int): Int = (line + 1) * lineHeight
    fun getLineWidth(line: Int): Float = metrics.stringWidth(lines[line]).toFloat()
    fun getAlignment(): Alignment = alignment

    fun draw(canvas: Canvas) {
        val g = canvas.graphicsPublic()
        g.font = paint.awtFontPublic()
        g.color = java.awt.Color(paint.getColor(), true)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        lines.forEachIndexed { i, line ->
            val w = metrics.stringWidth(line)
            val x = when (alignment) {
                Alignment.ALIGN_NORMAL -> 0
                Alignment.ALIGN_OPPOSITE -> width - w
                Alignment.ALIGN_CENTER -> (width - w) / 2
            }
            g.drawString(line, x, i * lineHeight + metrics.ascent)
        }
    }
}

class StaticLayout(
    source: CharSequence,
    paint: TextPaint,
    width: Int,
    align: Alignment,
    spacingmult: Float,
    spacingadd: Float,
    includepad: Boolean,
) : Layout(source, paint, width, align, spacingmult, spacingadd)
