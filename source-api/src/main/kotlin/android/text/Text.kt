package android.text

import org.jsoup.Jsoup

interface Spanned : CharSequence

interface Editable : CharSequence {
    fun clear()
    fun append(text: CharSequence): Editable
}

interface TextWatcher {
    fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int)
    fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int)
    fun afterTextChanged(s: Editable)
}

class SpannableStringBuilder(text: CharSequence = "") : Editable, Spanned {
    private val sb = StringBuilder(text)
    override val length: Int get() = sb.length
    override fun get(index: Int): Char = sb[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = sb.subSequence(startIndex, endIndex)
    override fun clear() = sb.setLength(0)
    override fun append(text: CharSequence): Editable = apply { sb.append(text) }
    override fun toString(): String = sb.toString()
}

private class PlainSpanned(private val text: String) : Spanned {
    override val length: Int get() = text.length
    override fun get(index: Int): Char = text[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)
    override fun toString(): String = text
}

object Html {
    const val FROM_HTML_MODE_LEGACY = 0
    const val FROM_HTML_MODE_COMPACT = 63

    @JvmStatic
    fun fromHtml(source: String?): Spanned = fromHtml(source, FROM_HTML_MODE_LEGACY)

    @JvmStatic
    fun fromHtml(source: String?, flags: Int): Spanned {
        if (source == null) return PlainSpanned("")
        val doc = Jsoup.parse(source)
        doc.outputSettings().prettyPrint(false)
        doc.select("br").before("\\n")
        doc.select("p, div, li").after("\\n")
        val text = doc.text().replace("\\n", "\n").lines().joinToString("\n") { it.trim() }.trim()
        return PlainSpanned(text)
    }

    @JvmStatic
    fun escapeHtml(text: CharSequence): String = buildString {
        for (c in text) {
            when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }
}

object TextUtils {
    @JvmStatic fun isEmpty(str: CharSequence?): Boolean = str.isNullOrEmpty()
    @JvmStatic fun isDigitsOnly(str: CharSequence): Boolean = str.all { it.isDigit() }
    @JvmStatic fun join(delimiter: CharSequence, tokens: Array<Any?>): String = tokens.joinToString(delimiter)
    @JvmStatic fun join(delimiter: CharSequence, tokens: Iterable<*>): String = tokens.joinToString(delimiter)
    @JvmStatic fun htmlEncode(s: String): String = Html.escapeHtml(s)
}

object InputType {
    const val TYPE_CLASS_TEXT = 1
    const val TYPE_CLASS_NUMBER = 2
    const val TYPE_TEXT_VARIATION_URI = 16
    const val TYPE_TEXT_VARIATION_PASSWORD = 128
    const val TYPE_NUMBER_FLAG_DECIMAL = 8192
    const val TYPE_NUMBER_FLAG_SIGNED = 4096
    const val TYPE_TEXT_FLAG_MULTI_LINE = 131072
}
