package inkoma.source

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import inkoma.extension.Extension
import java.util.concurrent.ConcurrentHashMap

/** Placeholder for sources whose extension is not installed (Mihon's StubSource). */
class StubSource(override val id: Long, override val lang: String, override val name: String) : Source {
    override val supportsLatest: Boolean = false
    private fun notInstalled(): Nothing = throw SourceNotInstalledException()
    override suspend fun getPopularManga(page: Int): MangasPage = notInstalled()
    override suspend fun getLatestUpdates(page: Int): MangasPage = notInstalled()
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = notInstalled()
    override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate = notInstalled()
    override suspend fun getPageList(chapter: SChapter): List<Page> = notInstalled()
    override fun toString(): String = if (name.isNotBlank()) "$name (${lang.uppercase()})" else id.toString()
}

class SourceNotInstalledException : Exception("Source not installed")

class SourceManager(private val localSource: LocalSource) {
    private val sources = ConcurrentHashMap<Long, Source>()
    private val stubs = ConcurrentHashMap<Long, StubSource>()
    private val sourceToExtension = ConcurrentHashMap<Long, String>()

    private val _catalogueSources = MutableStateFlow<List<CatalogueSource>>(emptyList())
    val catalogueSources: StateFlow<List<CatalogueSource>> = _catalogueSources.asStateFlow()

    /** Name/lang memory for stub sources, persisted by the caller (Mihon keeps this in the sources table). */
    var stubInfoProvider: (Long) -> Pair<String, String>? = { null }

    init {
        sources[LocalSource.ID] = localSource
        publish()
    }

    fun onExtensionsChanged(extensions: List<Extension.Loaded>) {
        sources.keys.removeIf { it != LocalSource.ID }
        sourceToExtension.clear()
        extensions.forEach { ext ->
            ext.sources.forEach { src ->
                sources[src.id] = src
                sourceToExtension[src.id] = ext.pkgName
            }
        }
        publish()
    }

    private fun publish() {
        _catalogueSources.value = sources.values.filterIsInstance<CatalogueSource>().sortedBy { it.name.lowercase() }
    }

    fun get(id: Long): Source? = sources[id]

    fun getOrStub(id: Long): Source = sources[id] ?: stubs.getOrPut(id) {
        val info = stubInfoProvider(id)
        StubSource(id, info?.second ?: "", info?.first ?: "")
    }

    fun extensionPkgOf(sourceId: Long): String? = sourceToExtension[sourceId]

    fun getOnlineSources(): List<Source> = sources.values.filter { it.id != LocalSource.ID }

    fun isStub(id: Long): Boolean = sources[id] == null
}
