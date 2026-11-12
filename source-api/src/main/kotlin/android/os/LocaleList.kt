package android.os

import java.util.Locale

/** android.os.LocaleList backed by the JVM default locale (used by extensions for Accept-Language). */
class LocaleList(vararg locales: Locale) {
    private val list: List<Locale> = locales.toList()

    fun get(index: Int): Locale? = list.getOrNull(index)
    fun size(): Int = list.size
    fun isEmpty(): Boolean = list.isEmpty()
    fun indexOf(locale: Locale?): Int = list.indexOf(locale)
    fun toLanguageTags(): String = list.joinToString(",") { it.toLanguageTag() }
    fun getFirstMatch(supportedLocales: Array<String>?): Locale? =
        list.firstOrNull { l -> supportedLocales?.any { Locale.forLanguageTag(it).language == l.language } == true } ?: list.firstOrNull()

    override fun toString(): String = list.joinToString(",", "[", "]")
    override fun equals(other: Any?): Boolean = other is LocaleList && other.list == list
    override fun hashCode(): Int = list.hashCode()

    companion object {
        @JvmStatic fun getDefault(): LocaleList = LocaleList(Locale.getDefault())
        @JvmStatic fun getAdjustedDefault(): LocaleList = getDefault()
        @JvmStatic fun getEmptyLocaleList(): LocaleList = LocaleList()
        @JvmStatic fun forLanguageTags(list: String?): LocaleList =
            LocaleList(*(list ?: "").split(',').filter { it.isNotBlank() }.map { Locale.forLanguageTag(it.trim()) }.toTypedArray())
        @JvmStatic fun setDefault(locales: LocaleList) {
            locales.get(0)?.let { Locale.setDefault(it) }
        }
    }
}
