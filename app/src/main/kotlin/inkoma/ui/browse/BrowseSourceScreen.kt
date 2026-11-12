package inkoma.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import inkoma.Inkoma
import inkoma.core.LibraryDisplayMode
import inkoma.core.TriState
import inkoma.domain.Manga
import inkoma.ui.LocalSnackbar
import inkoma.ui.components.AdaptiveSheet
import inkoma.ui.components.CheckboxItem
import inkoma.ui.components.CommonMangaItemDefaults
import inkoma.ui.components.EmptyScreen
import inkoma.ui.components.HeadingItem
import inkoma.ui.components.InLibraryBadge
import inkoma.ui.components.MangaComfortableGridItem
import inkoma.ui.components.MangaCompactGridItem
import inkoma.ui.components.MangaListItem
import inkoma.ui.components.SearchToolbar
import inkoma.ui.components.SortItem
import inkoma.ui.components.TriStateItem
import inkoma.ui.library.ChangeCategoryDialog
import inkoma.ui.manga.MangaScreen
import inkoma.ui.navigation.LocalNavigator
import inkoma.ui.navigation.Screen
import inkoma.ui.theme.InkomaIcons

/** Mihon's BrowseSourceScreen: popular / latest / search with filters, infinite paging. */
class BrowseSourceScreen(private val sourceId: Long, listing: Listing, initialQuery: String? = null) : Screen() {
    sealed interface Listing {
        data object Popular : Listing
        data object Latest : Listing
        data class Search(val query: String, val filters: FilterList) : Listing
    }

    private val source = Inkoma.sourceManager.get(sourceId) as? CatalogueSource
    private var listing by mutableStateOf(if (initialQuery != null) Listing.Search(initialQuery, source?.getFilterList() ?: FilterList()) else listing)
    private var filters by mutableStateOf(source?.getFilterList() ?: FilterList())
    private var searchQuery by mutableStateOf(initialQuery)
    private val results = mutableStateListOf<Manga>()
    private var page = 1
    private var hasNextPage by mutableStateOf(true)
    private var loading by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var job: Job? = null
    private var filterRevision by mutableIntStateOf(0)

    private fun reset() {
        job?.cancel()
        results.clear()
        page = 1
        hasNextPage = true
        error = null
        loadNext()
    }

    fun loadNext() {
        val src = source ?: return
        if (loading || !hasNextPage) return
        loading = true
        job = screenScope.launch {
            try {
                val mp = withContext(Dispatchers.IO) {
                    when (val l = listing) {
                        Listing.Popular -> src.getPopularManga(page)
                        Listing.Latest -> src.getLatestUpdates(page)
                        is Listing.Search -> src.getSearchManga(page, l.query, l.filters)
                    }
                }
                val local = withContext(Dispatchers.IO) { mp.mangas.map { Inkoma.mangaInteractor.networkToLocal(sourceId, it) } }
                val existing = results.map { it.id }.toSet()
                results.addAll(local.filter { it.id !in existing })
                hasNextPage = mp.hasNextPage && mp.mangas.isNotEmpty()
                page++
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = inkoma.core.ErrorMessages.describe(e)
            } finally {
                loading = false
            }
        }
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val snackbar = LocalSnackbar.current
        val scope = rememberCoroutineScope()
        val uri = LocalUriHandler.current
        val p = Inkoma.preferences
        val displayMode by p.sourceDisplayMode.changes().collectAsState(p.sourceDisplayMode.get())
        val favorites by remember { Inkoma.mangaRepository.observeFavoriteKeys() }.collectAsState(emptySet())
        val hideInLibrary by p.hideInLibraryItems.changes().collectAsState(p.hideInLibraryItems.get())
        val categories by remember { Inkoma.categoryRepository.observeAll() }.collectAsState(emptyList())
        var showFilters by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var displayMenu by remember { mutableStateOf(false) }
        var pendingFavorite by remember { mutableStateOf<Manga?>(null) }
        LaunchedEffect(Unit) { if (results.isEmpty() && error == null) loadNext() }

        val src = source
        if (src == null) {
            EmptyScreen("Source not installed")
            return
        }
        Scaffold(
            topBar = {
                Column {
                    SearchToolbar(
                        title = src.name,
                        searchQuery = searchQuery,
                        onChangeSearchQuery = { searchQuery = it },
                        onSearch = { q ->
                            listing = Listing.Search(q, filters)
                            reset()
                        },
                        navigateUp = { navigator.pop() },
                        actions = {
                            Box {
                                IconButton(onClick = { displayMenu = true }) { Icon(InkomaIcons.ViewModule, "Display mode") }
                                DropdownMenu(displayMenu, onDismissRequest = { displayMenu = false }) {
                                    listOf(LibraryDisplayMode.COMPACT_GRID to "Compact grid", LibraryDisplayMode.COMFORTABLE_GRID to "Comfortable grid", LibraryDisplayMode.LIST to "List").forEach { (m, l) ->
                                        DropdownMenuItem(text = { Text(l) }, onClick = { p.sourceDisplayMode.set(m); displayMenu = false })
                                    }
                                }
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(InkomaIcons.MoreVert, "More") }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    (src as? HttpSource)?.let { http ->
                                        DropdownMenuItem(text = { Text("Open in WebView") }, onClick = { menu = false; runCatching { uri.openUri(http.getHomeUrl()) } })
                                    }
                                    if (src is ConfigurableSource) {
                                        DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; navigator.push(SourcePreferencesScreen(src.id)) })
                                    }
                                }
                            }
                        },
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = listing == Listing.Popular, onClick = { listing = Listing.Popular; searchQuery = null; reset() }, label = { Text("Popular") })
                        if (src.supportsLatest) {
                            FilterChip(selected = listing == Listing.Latest, onClick = { listing = Listing.Latest; searchQuery = null; reset() }, label = { Text("Latest") })
                        }
                        if (filters.isNotEmpty()) {
                            FilterChip(selected = listing is Listing.Search, onClick = { showFilters = true }, label = { Text("Filter") },
                                leadingIcon = { Icon(InkomaIcons.FilterList, null) })
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                val visible = if (hideInLibrary) results.filterNot { (it.source to it.url) in favorites } else results.toList()
                when {
                    visible.isEmpty() && loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    visible.isEmpty() && error != null -> EmptyScreen(error ?: "") {
                        Row {
                            TextButton(onClick = { reset() }) { Text("Retry") }
                            (src as? HttpSource)?.let { http -> TextButton(onClick = { runCatching { uri.openUri(http.getHomeUrl()) } }) { Text("Open in WebView") } }
                        }
                    }
                    visible.isEmpty() -> EmptyScreen("No results found")
                    else -> {
                        val onClick: (Manga) -> Unit = { navigator.push(MangaScreen(it.id)) }
                        val onLong: (Manga) -> Unit = { m ->
                            val inLib = (m.source to m.url) in favorites
                            if (inLib) {
                                Inkoma.mangaInteractor.setFavorite(m, false, null)
                            } else if (categories.any { !it.isSystemCategory } && p.defaultCategory.get() == -1) {
                                pendingFavorite = m
                            } else {
                                Inkoma.mangaInteractor.setFavorite(m, true, null)
                                scope.launch { snackbar.showSnackbar("Added to library") }
                            }
                        }
                        val footer: @Composable () -> Unit = {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                when {
                                    loading -> CircularProgressIndicator()
                                    error != null -> Button(onClick = { loadNext() }) { Text("Retry") }
                                    hasNextPage -> LaunchedEffect(results.size) { loadNext() }
                                }
                            }
                        }
                        if (displayMode == LibraryDisplayMode.LIST) {
                            LazyColumn(Modifier.fillMaxSize()) {
                                itemsIndexed(visible, key = { _, m -> m.id }) { _, m ->
                                    val inLib = (m.source to m.url) in favorites
                                    MangaListItem(m.thumbnailUrl, m.source, m.title, { onClick(m) }, { onLong(m) },
                                        coverAlpha = if (inLib) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
                                        badge = { InLibraryBadge(inLib) })
                                }
                                item { footer() }
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(128.dp),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(8.dp),
                                verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
                                horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
                            ) {
                                itemsIndexed(visible, key = { _, m -> m.id }) { _, m ->
                                    val inLib = (m.source to m.url) in favorites
                                    val alpha = if (inLib) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f
                                    if (displayMode == LibraryDisplayMode.COMFORTABLE_GRID) {
                                        MangaComfortableGridItem(m.thumbnailUrl, m.source, m.title, { onClick(m) }, { onLong(m) }, coverAlpha = alpha, badgesStart = { InLibraryBadge(inLib) })
                                    } else {
                                        MangaCompactGridItem(m.thumbnailUrl, m.source, m.title, { onClick(m) }, { onLong(m) }, coverAlpha = alpha, badgesStart = { InLibraryBadge(inLib) })
                                    }
                                }
                                item(span = { GridItemSpan(maxLineSpan) }) { footer() }
                            }
                        }
                    }
                }
            }
        }

        if (showFilters) {
            FilterSheet(
                filters = filters,
                revision = filterRevision,
                onChanged = { filterRevision++ },
                onReset = { filters = src.getFilterList(); filterRevision++ },
                onFilter = {
                    showFilters = false
                    listing = Listing.Search(searchQuery.orEmpty(), filters)
                    reset()
                },
                onDismiss = { showFilters = false },
            )
        }
        pendingFavorite?.let { m ->
            ChangeCategoryDialog(
                categories = categories.filterNot { it.isSystemCategory },
                initialSelection = emptySet(),
                onDismiss = { pendingFavorite = null },
                onConfirm = { ids -> Inkoma.mangaInteractor.setFavorite(m, true, ids) },
                onEditCategories = { navigator.push(inkoma.ui.category.CategoryScreen()) },
            )
        }
    }
}

/** Renders a source's FilterList (Mihon's SourceFilterDialog). State is written back into the filter objects. */
@Composable
fun FilterSheet(filters: FilterList, revision: Int, onChanged: () -> Unit, onReset: () -> Unit, onFilter: () -> Unit, onDismiss: () -> Unit) {
    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onReset) { Text("Reset") }
                Box(Modifier.weight(1f))
                Button(onClick = onFilter) { Text("Filter") }
            }
            HorizontalDivider()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                key(revision) { filters.forEach { f -> FilterItem(f, onChanged) } }
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun FilterItem(filter: Filter<*>, onChanged: () -> Unit) {
    when (filter) {
        is Filter.Header -> HeadingItem(filter.name)
        is Filter.Separator -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
        is Filter.CheckBox -> CheckboxItem(filter.name, filter.state) { filter.state = !filter.state; onChanged() }
        is Filter.TriState -> TriStateItem(
            filter.name,
            when (filter.state) { Filter.TriState.STATE_INCLUDE -> TriState.ENABLED_IS; Filter.TriState.STATE_EXCLUDE -> TriState.ENABLED_NOT; else -> TriState.DISABLED },
        ) {
            filter.state = when (filter.state) { Filter.TriState.STATE_IGNORE -> Filter.TriState.STATE_INCLUDE; Filter.TriState.STATE_INCLUDE -> Filter.TriState.STATE_EXCLUDE; else -> Filter.TriState.STATE_IGNORE }
            onChanged()
        }
        is Filter.Text -> {
            var text by remember(filter) { mutableStateOf(filter.state) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; filter.state = it; onChanged() },
                label = { Text(filter.name) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        is Filter.Select<*> -> {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                OutlinedTextField(
                    value = filter.values.getOrNull(filter.state)?.toString() ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(filter.name) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    filter.values.forEachIndexed { i, v ->
                        DropdownMenuItem(text = { Text(v.toString()) }, onClick = { filter.state = i; expanded = false; onChanged() })
                    }
                }
            }
        }
        is Filter.Sort -> {
            var open by remember { mutableStateOf(true) }
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 24.dp, vertical = 12.dp)) {
                Text(filter.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(if (open) InkomaIcons.ArrowDropUp else InkomaIcons.ArrowDropDown, null)
            }
            if (open) {
                filter.values.forEachIndexed { i, label ->
                    val sel = filter.state
                    SortItem(label, if (sel?.index == i) !sel.ascending else null) {
                        filter.state = Filter.Sort.Selection(i, if (sel?.index == i) !sel.ascending else false)
                        onChanged()
                    }
                }
            }
        }
        is Filter.Group<*> -> {
            var open by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 24.dp, vertical = 12.dp)) {
                Text(filter.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(if (open) InkomaIcons.ArrowDropUp else InkomaIcons.ArrowDropDown, null)
            }
            if (open) {
                Column(Modifier.padding(start = 8.dp)) {
                    (filter.state as List<*>).filterIsInstance<Filter<*>>().forEach { FilterItem(it, onChanged) }
                }
            }
        }
    }
}
