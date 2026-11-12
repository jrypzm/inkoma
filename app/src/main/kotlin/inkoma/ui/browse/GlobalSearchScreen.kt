package inkoma.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import inkoma.Inkoma
import inkoma.domain.Manga
import inkoma.ui.components.CommonMangaItemDefaults
import inkoma.ui.components.InLibraryBadge
import inkoma.ui.components.MangaComfortableGridItem
import inkoma.ui.components.SearchToolbar
import inkoma.ui.manga.MangaScreen
import inkoma.ui.navigation.LocalNavigator
import inkoma.ui.navigation.Screen
import inkoma.ui.theme.InkomaIcons

/** Mihon's GlobalSearchScreen: queries every enabled source (5 at a time) and lists results per source. */
class GlobalSearchScreen(initialQuery: String) : Screen() {
    private sealed interface SourceResult {
        data object Loading : SourceResult
        data class Success(val mangas: List<Manga>) : SourceResult
        data class Error(val message: String) : SourceResult
    }

    private var query by mutableStateOf<String?>(initialQuery)
    private var onlyWithResults by mutableStateOf(false)
    private var pinnedOnly by mutableStateOf(false)
    private val results = mutableStateMapOf<Long, SourceResult>()
    private var job: Job? = null

    private fun sources(): List<CatalogueSource> {
        val p = Inkoma.preferences
        val langs = p.enabledLanguages.get()
        val disabled = p.disabledSources.get()
        val pinned = p.pinnedSources.get()
        return Inkoma.sourceManager.catalogueSources.value
            .filter { it.lang in langs && it.id.toString() !in disabled && (!pinnedOnly || it.id.toString() in pinned) }
            .sortedWith(compareBy({ it.id.toString() !in pinned }, { it.name.lowercase() }))
    }

    private fun search(q: String) {
        job?.cancel()
        results.clear()
        if (q.isBlank()) return
        val list = sources()
        list.forEach { results[it.id] = SourceResult.Loading }
        val limit = Semaphore(5)
        job = screenScope.launch {
            list.forEach { src ->
                launch {
                    limit.withPermit {
                        results[src.id] = try {
                            val page = withContext(Dispatchers.IO) { src.getSearchManga(1, q, src.getFilterList()) }
                            SourceResult.Success(withContext(Dispatchers.IO) { page.mangas.map { Inkoma.mangaInteractor.networkToLocal(src.id, it) } })
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            SourceResult.Error(inkoma.core.ErrorMessages.describe(e))
                        }
                    }
                }
            }
        }
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val favorites by remember { Inkoma.mangaRepository.observeFavoriteKeys() }.collectAsState(emptySet())
        LaunchedEffect(Unit) { if (results.isEmpty()) query?.let { search(it) } }
        Scaffold(
            topBar = {
                Column {
                    SearchToolbar(
                        title = "Global search",
                        searchQuery = query ?: "",
                        onChangeSearchQuery = { query = it ?: "" },
                        onSearch = { search(it) },
                        navigateUp = { navigator.pop() },
                    )
                    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = pinnedOnly, onClick = { pinnedOnly = !pinnedOnly; query?.let { search(it) } }, label = { Text("Pinned sources") },
                            leadingIcon = { Icon(InkomaIcons.PushPin, null) })
                        FilterChip(selected = onlyWithResults, onClick = { onlyWithResults = !onlyWithResults }, label = { Text("Has results") })
                    }
                }
            },
        ) { padding ->
            val sourceList = sources().filter { it.id in results }
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(sourceList, key = { it.id }) { src ->
                    val r = results[src.id]
                    if (onlyWithResults && (r !is SourceResult.Success || r.mangas.isEmpty())) return@items
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().clickable { navigator.push(BrowseSourceScreen(src.id, BrowseSourceScreen.Listing.Popular, query)) }
                                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(src.name, style = MaterialTheme.typography.titleMedium)
                                Text(languageName(src.lang), style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(InkomaIcons.ChevronRight, null)
                        }
                        when (r) {
                            SourceResult.Loading, null -> Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            is SourceResult.Error -> Text(r.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            is SourceResult.Success -> if (r.mangas.isEmpty()) {
                                Text("No results found", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                            } else {
                                LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                                    items(r.mangas, key = { it.id }) { m ->
                                        val inLib = (m.source to m.url) in favorites
                                        Box(Modifier.width(128.dp)) {
                                            MangaComfortableGridItem(
                                                m.thumbnailUrl, m.source, m.title, { navigator.push(MangaScreen(m.id)) }, { navigator.push(MangaScreen(m.id)) },
                                                coverAlpha = if (inLib) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
                                                badgesStart = { InLibraryBadge(inLib) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
