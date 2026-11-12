package inkoma.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import inkoma.Inkoma
import inkoma.core.LibraryDisplayMode
import inkoma.core.LibrarySort
import inkoma.core.TriState
import inkoma.domain.Category
import inkoma.domain.LibraryManga
import inkoma.source.LocalSource
import inkoma.ui.LocalSnackbar
import inkoma.ui.components.AppBar
import inkoma.ui.components.CheckboxItem
import inkoma.ui.components.CommonMangaItemDefaults
import inkoma.ui.components.DownloadsBadge
import inkoma.ui.components.EmptyScreen
import inkoma.ui.components.HeadingItem
import inkoma.ui.components.LabeledCheckbox
import inkoma.ui.components.LanguageBadge
import inkoma.ui.components.MangaComfortableGridItem
import inkoma.ui.components.MangaCompactGridItem
import inkoma.ui.components.MangaListItem
import inkoma.ui.components.RadioItem
import inkoma.ui.components.SearchToolbar
import inkoma.ui.components.SliderPreferenceWidget
import inkoma.ui.components.SortItem
import inkoma.ui.components.TabbedSheet
import inkoma.ui.components.TriStateItem
import inkoma.ui.components.UnreadBadge
import inkoma.ui.manga.MangaScreen
import inkoma.ui.navigation.LocalNavigator
import inkoma.ui.reader.ReaderScreen
import inkoma.ui.theme.InkomaIcons

class LibraryTab {
    private var searchQuery by mutableStateOf<String?>(null)
    private var selectedCategoryIndex by mutableStateOf(Inkoma.preferences.lastUsedCategory.get())
    private val selection = mutableStateListOf<Long>()

    fun onReselect() {
        Inkoma.libraryUpdater.start(null)
    }

    private fun TriState.test(value: Boolean): Boolean = when (this) {
        TriState.DISABLED -> true
        TriState.ENABLED_IS -> value
        TriState.ENABLED_NOT -> !value
    }

    private fun applyFilters(items: List<LibraryManga>, downloadedCounts: Map<Long, Int>): List<LibraryManga> {
        val p = Inkoma.preferences
        val q = searchQuery?.trim().orEmpty()
        return items.filter { item ->
            val m = item.manga
            val downloaded = m.source == LocalSource.ID || (downloadedCounts[m.id] ?: 0) > 0
            p.filterDownloaded.get().test(downloaded) &&
                p.filterUnread.get().test(item.unreadCount > 0) &&
                p.filterStarted.get().test(item.hasStarted) &&
                p.filterBookmarked.get().test(item.hasBookmarks) &&
                p.filterCompleted.get().test(m.status.toInt() == eu.kanade.tachiyomi.source.model.SManga.COMPLETED) &&
                (q.isEmpty() || m.title.contains(q, true) || m.author?.contains(q, true) == true || m.artist?.contains(q, true) == true ||
                    m.genre?.any { it.equals(q, true) } == true || m.description?.contains(q, true) == true)
        }
    }

    private fun sort(items: List<LibraryManga>): List<LibraryManga> {
        val p = Inkoma.preferences
        val cmp: Comparator<LibraryManga> = when (p.librarySort.get()) {
            LibrarySort.ALPHABETICAL -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.manga.title }
            LibrarySort.LAST_READ -> compareBy { it.lastRead }
            LibrarySort.LAST_UPDATE -> compareBy { it.manga.lastUpdate }
            LibrarySort.UNREAD_COUNT -> compareBy { it.unreadCount }
            LibrarySort.TOTAL_CHAPTERS -> compareBy { it.totalChapters }
            LibrarySort.LATEST_CHAPTER -> compareBy { it.latestUpload }
            LibrarySort.CHAPTER_FETCH_DATE -> compareBy { it.chapterFetchedAt }
            LibrarySort.DATE_ADDED -> compareBy { it.manga.dateAdded }
            LibrarySort.RANDOM -> compareBy { (it.manga.id * 2654435761L) xor p.lastLibraryUpdate.get() }
        }
        val sorted = items.sortedWith(cmp.thenBy(String.CASE_INSENSITIVE_ORDER) { it.manga.title })
        return if (p.librarySortAscending.get()) sorted else sorted.reversed()
    }

    @Composable
    fun Content() {
        val navigator = LocalNavigator.current
        val snackbar = LocalSnackbar.current
        val scope = rememberCoroutineScope()
        val p = Inkoma.preferences
        val library by remember { Inkoma.libraryRepository.observeLibrary() }.collectAsState(null)
        val categories by remember { Inkoma.categoryRepository.observeAll() }.collectAsState(emptyList())
        val cacheVersion by Inkoma.downloadManager.cacheVersion.collectAsState()
        val update by Inkoma.libraryUpdater.progress.collectAsState()
        // Recompose on any relevant preference change.
        val displayMode by p.libraryDisplayMode.changes().collectAsState(p.libraryDisplayMode.get())
        val columns by p.libraryColumns.changes().collectAsState(p.libraryColumns.get())
        val showTabs by p.categoryTabs.changes().collectAsState(p.categoryTabs.get())
        val showCount by p.categoryNumberOfItems.changes().collectAsState(p.categoryNumberOfItems.get())
        val unreadBadge by p.unreadBadge.changes().collectAsState(p.unreadBadge.get())
        val downloadBadge by p.downloadBadge.changes().collectAsState(p.downloadBadge.get())
        val localBadge by p.localBadge.changes().collectAsState(p.localBadge.get())
        val langBadge by p.languageBadge.changes().collectAsState(p.languageBadge.get())
        val sourceIconBadge by p.sourceIconBadge.changes().collectAsState(p.sourceIconBadge.get())
        val continueButton by p.showContinueReadingButton.changes().collectAsState(p.showContinueReadingButton.get())
        val filterTick = listOf(p.filterDownloaded, p.filterUnread, p.filterStarted, p.filterBookmarked, p.filterCompleted)
            .map { it.changes().collectAsState(it.get()).value }
        val sortMode by p.librarySort.changes().collectAsState(p.librarySort.get())
        val sortAsc by p.librarySortAscending.changes().collectAsState(p.librarySortAscending.get())

        var showSettings by remember { mutableStateOf(false) }
        var showMenu by remember { mutableStateOf(false) }
        var showCategoryDialog by remember { mutableStateOf(false) }
        var showDeleteDialog by remember { mutableStateOf(false) }

        val lib = library
        val downloadedCounts = remember(lib, cacheVersion) {
            lib?.associate { it.manga.id to if (downloadBadge || p.filterDownloaded.get() != TriState.DISABLED) Inkoma.downloadManager.downloadedCount(it.manga) else 0 }.orEmpty()
        }
        val grouped: List<Pair<Category, List<LibraryManga>>> = remember(lib, categories, downloadedCounts, searchQuery, filterTick, sortMode, sortAsc) {
            if (lib == null) return@remember emptyList()
            val filtered = applyFilters(lib, downloadedCounts)
            val byCat = filtered.groupBy { it.categoryId }
            categories.mapNotNull { cat ->
                val items = sort(byCat[cat.id].orEmpty())
                if (cat.isSystemCategory && items.isEmpty() && categories.size > 1) null else cat to items
            }
        }
        val pageIndex = selectedCategoryIndex.coerceIn(0, (grouped.size - 1).coerceAtLeast(0))
        val current = grouped.getOrNull(pageIndex)
        val allItems = lib.orEmpty()

        Scaffold(
            topBar = {
                Column {
                    if (selection.isNotEmpty()) {
                        AppBar(
                            title = null,
                            actionModeCounter = selection.size,
                            onCancelActionMode = { selection.clear() },
                            actionModeActions = {
                                IconButton(onClick = { selection.clear(); selection.addAll(current?.second.orEmpty().map { it.manga.id }.distinct()) }) {
                                    Icon(InkomaIcons.SelectAll, "Select all")
                                }
                                IconButton(onClick = {
                                    val inCat = current?.second.orEmpty().map { it.manga.id }.toSet()
                                    val newSel = inCat - selection.toSet()
                                    selection.clear(); selection.addAll(newSel)
                                }) { Icon(InkomaIcons.FlipToBack, "Invert selection") }
                            },
                        )
                    } else {
                        SearchToolbar(
                            title = buildString {
                                append("Library")
                                if (showCount && !showTabs) append(" (${current?.second?.size ?: 0})")
                            },
                            searchQuery = searchQuery,
                            onChangeSearchQuery = { searchQuery = it },
                            actions = {
                                IconButton(onClick = { showSettings = true }) { Icon(InkomaIcons.FilterList, "Filter") }
                                Box {
                                    IconButton(onClick = { showMenu = true }) { Icon(InkomaIcons.MoreVert, "More") }
                                    DropdownMenu(showMenu, onDismissRequest = { showMenu = false }) {
                                        DropdownMenuItem(text = { Text("Update library") }, onClick = {
                                            showMenu = false
                                            val started = Inkoma.libraryUpdater.start(null)
                                            scope.launch { snackbar.showSnackbar(if (started) "Updating library" else "Update already in progress") }
                                        })
                                        DropdownMenuItem(text = { Text("Update category") }, onClick = {
                                            showMenu = false
                                            current?.first?.let { Inkoma.libraryUpdater.start(it) }
                                        })
                                        DropdownMenuItem(text = { Text("Open random entry") }, onClick = {
                                            showMenu = false
                                            current?.second?.randomOrNull()?.let { navigator.push(MangaScreen(it.manga.id)) }
                                        })
                                    }
                                }
                            },
                        )
                    }
                    if (showTabs && grouped.size > 1) {
                        PrimaryScrollableTabRow(selectedTabIndex = pageIndex, edgePadding = 0.dp) {
                            grouped.forEachIndexed { i, (cat, items) ->
                                Tab(
                                    selected = i == pageIndex,
                                    onClick = {
                                        selectedCategoryIndex = i
                                        p.lastUsedCategory.set(i)
                                    },
                                    text = { Text((if (cat.isSystemCategory) "Default" else cat.name) + if (showCount) " (${items.size})" else "") },
                                )
                            }
                        }
                    }
                }
            },
            bottomBar = {
                if (selection.isNotEmpty()) {
                    SelectionBar(
                        onChangeCategory = { showCategoryDialog = true },
                        onMarkRead = { markSelection(allItems, true) },
                        onMarkUnread = { markSelection(allItems, false) },
                        onDownload = { downloadSelection(allItems, it) },
                        onDelete = { showDeleteDialog = true },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    lib == null -> {}
                    lib.isEmpty() -> EmptyScreen("Your library is empty")
                    current == null || current.second.isEmpty() -> EmptyScreen(if (searchQuery.isNullOrEmpty()) "This category is empty" else "No results found")
                    else -> {
                        val items = current.second.distinctBy { it.manga.id }
                        val onClick: (LibraryManga) -> Unit = { item ->
                            if (selection.isNotEmpty()) toggle(item.manga.id) else navigator.push(MangaScreen(item.manga.id))
                        }
                        val onLong: (LibraryManga) -> Unit = { toggle(it.manga.id) }
                        val onContinue: ((LibraryManga) -> Unit)? = if (continueButton) {
                            { item ->
                                scope.launch {
                                    val next = withContext(Dispatchers.IO) { Inkoma.mangaInteractor.nextUnread(item.manga.id) }
                                    if (next != null) navigator.push(ReaderScreen(item.manga.id, next.id)) else snackbar.showSnackbar("No next chapter")
                                }
                            }
                        } else {
                            null
                        }
                        val badgesStart: @Composable (LibraryManga) -> (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit) = { item ->
                            {
                                if (downloadBadge) DownloadsBadge(downloadedCounts[item.manga.id] ?: 0)
                                if (unreadBadge) UnreadBadge(item.unreadCount)
                            }
                        }
                        val badgesEnd: @Composable (LibraryManga) -> (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit) = { item ->
                            {
                                val isLocal = item.manga.source == LocalSource.ID
                                if (sourceIconBadge && !isLocal) {
                                    inkoma.ui.browse.SourceIcon(
                                        item.manga.source,
                                        Modifier.size(18.dp).background(MaterialTheme.colorScheme.surface),
                                    )
                                }
                                if ((localBadge && isLocal) || langBadge) {
                                    LanguageBadge(isLocal && localBadge, if (langBadge) Inkoma.sourceManager.get(item.manga.source)?.lang else null)
                                }
                            }
                        }
                        if (displayMode == LibraryDisplayMode.LIST) {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(items, key = { it.manga.id }) { item ->
                                    MangaListItem(
                                        coverUrl = item.manga.thumbnailUrl, sourceId = item.manga.source, title = item.manga.title,
                                        onClick = { onClick(item) }, onLongClick = { onLong(item) },
                                        isSelected = item.manga.id in selection, coverLastModified = item.manga.coverLastModified, persistent = true,
                                        badge = { badgesStart(item)(this); badgesEnd(item)(this) },
                                        onClickContinueReading = onContinue?.takeIf { item.unreadCount > 0 }?.let { f -> { f(item) } },
                                    )
                                }
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(8.dp),
                                verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
                                horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
                            ) {
                                items(items, key = { it.manga.id }) { item ->
                                    val cont = onContinue?.takeIf { item.unreadCount > 0 }?.let { f -> { f(item) } }
                                    if (displayMode == LibraryDisplayMode.COMFORTABLE_GRID) {
                                        MangaComfortableGridItem(
                                            coverUrl = item.manga.thumbnailUrl, sourceId = item.manga.source, title = item.manga.title,
                                            onClick = { onClick(item) }, onLongClick = { onLong(item) }, isSelected = item.manga.id in selection,
                                            coverLastModified = item.manga.coverLastModified, persistent = true,
                                            badgesStart = badgesStart(item), badgesEnd = badgesEnd(item), onClickContinueReading = cont,
                                        )
                                    } else {
                                        MangaCompactGridItem(
                                            coverUrl = item.manga.thumbnailUrl, sourceId = item.manga.source,
                                            title = if (displayMode == LibraryDisplayMode.COVER_ONLY_GRID) null else item.manga.title,
                                            onClick = { onClick(item) }, onLongClick = { onLong(item) }, isSelected = item.manga.id in selection,
                                            coverLastModified = item.manga.coverLastModified, persistent = true,
                                            badgesStart = badgesStart(item), badgesEnd = badgesEnd(item), onClickContinueReading = cont,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showSettings) LibrarySettingsSheet(onDismiss = { showSettings = false })
        if (showCategoryDialog) {
            ChangeCategoryDialog(
                categories = categories.filterNot { it.isSystemCategory },
                initialSelection = selection.flatMap { id -> allItems.filter { it.manga.id == id }.map { it.categoryId } }.toSet(),
                onDismiss = { showCategoryDialog = false },
                onConfirm = { ids ->
                    selection.forEach { Inkoma.categoryRepository.setMangaCategories(it, ids) }
                    selection.clear()
                },
                onEditCategories = { navigator.push(inkoma.ui.category.CategoryScreen()) },
            )
        }
        if (showDeleteDialog) {
            var removeFromLibrary by remember { mutableStateOf(true) }
            var deleteDownloads by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Remove") },
                text = {
                    Column {
                        LabeledCheckbox("From library", removeFromLibrary) { removeFromLibrary = it }
                        LabeledCheckbox("Downloaded chapters", deleteDownloads) { deleteDownloads = it }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val mangas = selection.mapNotNull { id -> allItems.firstOrNull { it.manga.id == id }?.manga }
                        scope.launch(Dispatchers.IO) {
                            mangas.forEach { m ->
                                if (deleteDownloads) Inkoma.downloadManager.deleteManga(m)
                                if (removeFromLibrary) Inkoma.mangaInteractor.setFavorite(m, false, null)
                            }
                        }
                        selection.clear()
                        showDeleteDialog = false
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } },
            )
        }
    }

    private fun toggle(id: Long) {
        if (id in selection) selection.remove(id) else selection.add(id)
    }

    private fun markSelection(all: List<LibraryManga>, read: Boolean) {
        val ids = selection.toList()
        selection.clear()
        Inkoma.scope.launch(Dispatchers.IO) {
            ids.forEach { id ->
                val manga = all.firstOrNull { it.manga.id == id }?.manga ?: return@forEach
                Inkoma.mangaInteractor.setRead(manga, Inkoma.chapterRepository.getByMangaId(id), read)
            }
        }
    }

    /** amount: number of next unread chapters, -1 = all unread. */
    private fun downloadSelection(all: List<LibraryManga>, amount: Int) {
        val ids = selection.toList()
        selection.clear()
        Inkoma.scope.launch(Dispatchers.IO) {
            ids.forEach { id ->
                val manga = all.firstOrNull { it.manga.id == id }?.manga ?: return@forEach
                val unread = Inkoma.chapterRepository.getByMangaId(id).filter { !it.read }.sortedByDescending { it.sourceOrder }
                Inkoma.downloadManager.downloadChapters(manga, if (amount < 0) unread else unread.take(amount))
            }
        }
    }
}

@Composable
private fun SelectionBar(
    onChangeCategory: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onDownload: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    var downloadMenu by remember { mutableStateOf(false) }
    androidx.compose.material3.BottomAppBar(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = onChangeCategory) { Icon(InkomaIcons.Label, "Set categories") }
            IconButton(onClick = onMarkRead) { Icon(InkomaIcons.DoneAll, "Mark as read") }
            IconButton(onClick = onMarkUnread) { Icon(InkomaIcons.Done, "Mark as unread") }
            Box {
                IconButton(onClick = { downloadMenu = true }) { Icon(InkomaIcons.Download, "Download") }
                DropdownMenu(downloadMenu, onDismissRequest = { downloadMenu = false }) {
                    listOf(1 to "Next chapter", 5 to "Next 5 chapters", 10 to "Next 10 chapters", 25 to "Next 25 chapters", -1 to "Unread").forEach { (n, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { downloadMenu = false; onDownload(n) })
                    }
                }
            }
            IconButton(onClick = onDelete) { Icon(InkomaIcons.Delete, "Delete") }
        }
    }
}

@Composable
fun ChangeCategoryDialog(
    categories: List<Category>,
    initialSelection: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
    onEditCategories: () -> Unit,
) {
    var selected by remember { mutableStateOf(initialSelection) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set categories") },
        text = {
            if (categories.isEmpty()) {
                Text("You have no categories. Tap the edit button to create one for organizing your library.")
            } else {
                LazyColumn {
                    items(categories) { cat ->
                        LabeledCheckbox(cat.name, cat.id in selected) { checked -> selected = if (checked) selected + cat.id else selected - cat.id }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.toList()); onDismiss() }) { Text("OK") }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = { onDismiss(); onEditCategories() }) { Text("Edit") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun LibrarySettingsSheet(onDismiss: () -> Unit) {
    val p = Inkoma.preferences
    TabbedSheet(onDismissRequest = onDismiss, tabTitles = listOf("Filter", "Sort", "Display")) { tab ->
        when (tab) {
            0 -> {
                listOf(
                    "Downloaded" to p.filterDownloaded,
                    "Unread" to p.filterUnread,
                    "Started" to p.filterStarted,
                    "Bookmarked" to p.filterBookmarked,
                    "Completed" to p.filterCompleted,
                ).forEach { (label, pref) ->
                    val state by pref.changes().collectAsState(pref.get())
                    TriStateItem(label, state) {
                        pref.set(TriState.entries[(state.ordinal + 1) % TriState.entries.size])
                    }
                }
            }
            1 -> {
                val mode by p.librarySort.changes().collectAsState(p.librarySort.get())
                val asc by p.librarySortAscending.changes().collectAsState(p.librarySortAscending.get())
                listOf(
                    LibrarySort.ALPHABETICAL to "Alphabetically",
                    LibrarySort.TOTAL_CHAPTERS to "Total chapters",
                    LibrarySort.LAST_READ to "Last read",
                    LibrarySort.LAST_UPDATE to "Last update check",
                    LibrarySort.UNREAD_COUNT to "Unread count",
                    LibrarySort.LATEST_CHAPTER to "Latest chapter",
                    LibrarySort.CHAPTER_FETCH_DATE to "Chapter fetch date",
                    LibrarySort.DATE_ADDED to "Date added",
                    LibrarySort.RANDOM to "Random",
                ).forEach { (s, label) ->
                    SortItem(label, if (mode == s) !asc else null) {
                        if (mode == s) p.librarySortAscending.set(!asc) else p.librarySort.set(s)
                    }
                }
            }
            else -> {
                val mode by p.libraryDisplayMode.changes().collectAsState(p.libraryDisplayMode.get())
                HeadingItem("Display mode")
                listOf(
                    LibraryDisplayMode.COMPACT_GRID to "Compact grid",
                    LibraryDisplayMode.COMFORTABLE_GRID to "Comfortable grid",
                    LibraryDisplayMode.COVER_ONLY_GRID to "Cover-only grid",
                    LibraryDisplayMode.LIST to "List",
                ).forEach { (m, label) -> RadioItem(label, mode == m) { p.libraryDisplayMode.set(m) } }
                if (mode != LibraryDisplayMode.LIST) {
                    val cols by p.libraryColumns.changes().collectAsState(p.libraryColumns.get())
                    SliderPreferenceWidget("Grid size", cols, 0..10, if (cols == 0) "Default" else "$cols per row") { p.libraryColumns.set(it) }
                }
                HeadingItem("Badges")
                listOf(
                    "Downloaded chapters" to p.downloadBadge,
                    "Unread chapters" to p.unreadBadge,
                    "Local source" to p.localBadge,
                    "Language" to p.languageBadge,
                    "Source icon" to p.sourceIconBadge,
                ).forEach { (label, pref) ->
                    val v by pref.changes().collectAsState(pref.get())
                    CheckboxItem(label, v) { pref.set(!v) }
                }
                HeadingItem("Tabs")
                listOf("Show category tabs" to p.categoryTabs, "Show number of items" to p.categoryNumberOfItems).forEach { (label, pref) ->
                    val v by pref.changes().collectAsState(pref.get())
                    CheckboxItem(label, v) { pref.set(!v) }
                }
                HeadingItem("Other")
                val cont by p.showContinueReadingButton.changes().collectAsState(p.showContinueReadingButton.get())
                CheckboxItem("Show continue reading button", cont) { p.showContinueReadingButton.set(!cont) }
            }
        }
    }
}
