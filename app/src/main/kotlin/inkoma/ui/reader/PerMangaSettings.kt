package inkoma.ui.reader

import android.content.Context
import inkoma.Inkoma
import inkoma.core.DualPageSpread
import inkoma.core.ImageScale

/** Per-series reader overrides (two-page spread and scale type); null means "use the global setting". */
object PerMangaSettings {
    private val prefs by lazy { Inkoma.application.getSharedPreferences("reader_per_manga", Context.MODE_PRIVATE) }

    fun spread(mangaId: Long): DualPageSpread? =
        prefs.getString("spread_$mangaId", null)?.let { n -> DualPageSpread.entries.firstOrNull { it.name == n } }

    fun setSpread(mangaId: Long, value: DualPageSpread?) {
        prefs.edit().apply { if (value == null) remove("spread_$mangaId") else putString("spread_$mangaId", value.name) }.apply()
    }

    fun scale(mangaId: Long): ImageScale? =
        prefs.getString("scale_$mangaId", null)?.let { n -> ImageScale.entries.firstOrNull { it.name == n } }

    fun setScale(mangaId: Long, value: ImageScale?) {
        prefs.edit().apply { if (value == null) remove("scale_$mangaId") else putString("scale_$mangaId", value.name) }.apply()
    }
}
