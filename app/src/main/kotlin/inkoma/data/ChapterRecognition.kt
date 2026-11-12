package inkoma.data

/** Port of Mihon's ChapterRecognition: extracts a chapter number from its name. */
object ChapterRecognition {
    private const val NUMBER_PATTERN = """([0-9]+)(\.[0-9]+)?(\.?[a-z]+)?"""

    private val unwanted = Regex("""\b(?:v|ver|vol|version|volume|season|s)[^a-z]?[0-9]+""")
    private val unwantedWhiteSpace = Regex("""\s(?=extra|special|omake)""")
    private val basic = Regex("""(?<=ch\.) *$NUMBER_PATTERN""")
    private val number = Regex(NUMBER_PATTERN)

    fun parse(mangaTitle: String, chapterName: String, chapterNumber: Double? = null): Double {
        if (chapterNumber != null && (chapterNumber == -2.0 || chapterNumber > -1.0)) return chapterNumber

        val cleanChapterName = chapterName.lowercase()
            .replace(mangaTitle.lowercase(), "").trim()
            .replace(',', '.')
            .replace('-', '.')
            .replace(unwantedWhiteSpace, "")

        val numberMatch = number.findAll(cleanChapterName)
        when {
            numberMatch.none() -> return chapterNumber ?: -1.0
            numberMatch.count() > 1 -> {
                val name = unwanted.replace(cleanChapterName, "")
                basic.find(name)?.let { return fromMatch(it) }
                number.find(name)?.let { return fromMatch(it) }
            }
        }
        return fromMatch(numberMatch.first())
    }

    private fun fromMatch(match: MatchResult): Double {
        val initial = match.groups[1]!!.value.toDouble()
        return initial + checkForDecimal(match.groups[2]?.value, match.groups[3]?.value)
    }

    private fun checkForDecimal(decimal: String?, alpha: String?): Double {
        if (!decimal.isNullOrEmpty()) return decimal.toDouble()
        if (!alpha.isNullOrEmpty()) {
            if (alpha.contains("extra")) return 0.99
            if (alpha.contains("omake")) return 0.98
            if (alpha.contains("special")) return 0.97
            val trimmed = alpha.trimStart('.')
            if (trimmed.length == 1) return parseAlphaPostFix(trimmed[0])
        }
        return 0.0
    }

    private fun parseAlphaPostFix(alpha: Char): Double {
        val n = alpha.code - ('a'.code - 1)
        if (n >= 10) return 0.0
        return n / 10.0
    }
}
