package inkoma.ui.manga

import inkoma.ui.components.inkomaClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.FabPosition
import androidx.compose.material3.VerticalDivider
import inkoma.ui.theme.SeedTheme
import inkoma.ui.theme.rememberCoverSeed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import inkoma.Inkoma
import inkoma.core.TriState
import inkoma.domain.Chapter
import inkoma.domain.Manga
import inkoma.download.Download
import inkoma.source.LocalSource
import inkoma.ui.LocalSnackbar
import inkoma.ui.components.AppBar
import inkoma.ui.components.BOOK_RATIO
import inkoma.ui.components.HeadingItem
import inkoma.ui.components.LabeledCheckbox
import inkoma.ui.components.MangaCover
import inkoma.ui.components.RadioItem
import inkoma.ui.components.SortItem
import inkoma.ui.components.TabbedSheet
import inkoma.ui.components.TriStateItem
import inkoma.ui.components.formatChapterNumber
import inkoma.ui.components.relativeDateText
import inkoma.ui.components.selectedBackground
import inkoma.ui.library.ChangeCategoryDialog
import inkoma.ui.navigation.LocalNavigator
import inkoma.ui.navigation.Screen
import inkoma.ui.reader.ReaderScreen
import inkoma.ui.theme.InkomaIcons

class MangaScreen(private val mangaId: Long) : Screen() {
    private val selection = mutableStateListOf<Long>()
    private var refreshing by mutableStateOf(false)
    private var initialFetchDone = false

    private fun refresh(manga: Manga, manual: Boolean, onError: (String) -> Unit) {
        if (refreshing) return
        refreshing = true
        screenScope.launch {
            try {
                Inkoma.mangaInteractor.refresh(manga, fetchDetails = true, fetchChapters = true, manual = manual)
            } catch (e: Throwable) {
                onError(e.message ?: e.javaClass.simpleName)
            } finally {
                refreshing = false
            }
        }
    }

    private fun flag(flags: Long, mask: Long, show: Long, hide: Long): TriState = when (flags and mask) {
        show -> TriState.ENABLED_IS
        hide -> TriState.ENABLED_NOT
        else -> TriState.DISABLED
    }

    private fun applyChapterFlags(manga: Manga, chapters: List<Chapter>, downloaded: (Chapter) -> Boolean, excluded: Set<String>): List<Chapter> {
        val f = manga.chapterFlags
        val unread = flag(f, Manga.CHAPTER_UNREAD_MASK, Manga.CHAPTER_SHOW_UNREAD, Manga.CHAPTER_SHOW_READ)
        val dl = flag(f, Manga.CHAPTER_DOWNLOADED_MASK, Manga.CHAPTER_SHOW_DOWNLOADED, Manga.CHAPTER_SHOW_NOT_DOWNLOADED)
        val bm = flag(f, Manga.CHAPTER_BOOKMARKED_MASK, Manga.CHAPTER_SHOW_BOOKMARKED, Manga.CHAPTER_SHOW_NOT_BOOKMARKED)
        fun TriState.ok(v: Boolean) = when (this) { TriState.DISABLED -> true; TriState.ENABLED_IS -> v; TriState.ENABLED_NOT -> !v }
        val filtered = chapters.filter {
            unread.ok(!it.read) && dl.ok(downloaded(it)) && bm.ok(it.bookmark) && (it.scanlator == null || it.scanlator !in excluded)
        }
        val asc = f and Manga.CHAPTER_SORT_DIR_MASK == Manga.CHAPTER_SORT_ASC
        val cmp: Comparator<Chapter> = when (f and Manga.CHAPTER_SORTING_MASK) {
            Manga.CHAPTER_SORTING_NUMBER -> compareBy { it.chapterNumber }
            Manga.CHAPTER_SORTING_UPLOAD_DATE -> compareBy { it.dateUpload }
            Manga.CHAPTER_SORTING_ALPHABET -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            else -> compareByDescending { it.sourceOrder }
        }
        val sorted = filtered.sortedWith(cmp)
        return if (asc) sorted else sorted.reversed()
    }

    @Composable
    override fun Content() {
        val mangaForTheme by remember { Inkoma.mangaRepository.observeById(mangaId) }.collectAsState(null)
        val seed = rememberCoverSeed(mangaForTheme?.thumbnailUrl, mangaForTheme?.source ?: 0L)
        SeedTheme(seed) { Body() }
    }

    private var coverAspect by mutableStateOf<Float?>(null)

    @OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
    @Composable
    private fun Body() {
        val navigator = LocalNavigator.current
        val snackbar = LocalSnackbar.current
        val scope = rememberCoroutineScope()
        val uriHandler = LocalUriHandler.current
        val clipboard = LocalClipboardManager.current
        val manga by remember { Inkoma.mangaRepository.observeById(mangaId) }.collectAsState(null)
        val chapters by remember { Inkoma.chapterRepository.observeByMangaId(mangaId) }.collectAsState(null)
        val excluded by remember { Inkoma.mangaRepository.observeExcludedScanlators(mangaId) }.collectAsState(emptySet())
        val queue by Inkoma.downloadManager.queue.collectAsState()
        val cacheVersion by Inkoma.downloadManager.cacheVersion.collectAsState()
        val categories by remember { Inkoma.categoryRepository.observeAll() }.collectAsState(emptyList())
        val twoPane by Inkoma.preferences.twoPaneManga.changes().collectAsState(Inkoma.preferences.twoPaneManga.get())
        val panorama by Inkoma.preferences.panoramaCovers.changes().collectAsState(Inkoma.preferences.panoramaCovers.get())
        val fabPos by Inkoma.preferences.resumeButtonPosition.changes().collectAsState(Inkoma.preferences.resumeButtonPosition.get())

        var showSettings by remember { mutableStateOf(false) }
        var showMenu by remember { mutableStateOf(false) }
        var showCategories by remember { mutableStateOf(false) }
        var showScanlators by remember { mutableStateOf(false) }
        var descriptionExpanded by remember { mutableStateOf(false) }

        val m = manga
        val chs = chapters
        LaunchedEffect(m?.id, chs?.size) {
            if (m != null && chs != null && !initialFetchDone) {
                initialFetchDone = true
                if (!m.initialized || chs.isEmpty()) refresh(m, false) { scope.launch { snackbar.showSnackbar(it) } }
            }
        }
        if (m == null || chs == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        val source = Inkoma.sourceManager.getOrStub(m.source)
        LaunchedEffect(m.thumbnailUrl) {
            coverAspect = m.thumbnailUrl?.let { url ->
                inkoma.ui.components.ImageLoader.load(url, m.source, 480, persistent = m.favorite)?.let { it.width.toFloat() / it.height }
            }
        }
        val downloadedIds = remember(chs, cacheVersion) { chs.filter { Inkoma.downloadManager.isChapterDownloaded(m, it) }.map { it.id }.toSet() }
        val queueById = queue.associateBy { it.chapter.id }
        val visible = remember(m, chs, downloadedIds, excluded) { applyChapterFlags(m, chs, { it.id in downloadedIds }, excluded) }
        val nextChapter = remember(chs) { chs.filter { !it.read }.maxByOrNull { it.sourceOrder } }
        val showNumber = m.chapterFlags and Manga.CHAPTER_DISPLAY_MASK == Manga.CHAPTER_DISPLAY_NUMBER
        val selected = chs.filter { it.id in selection }

        Scaffold(
            topBar = {
                AppBar(
                    title = m.title,
                    navigateUp = { navigator.pop() },
                    actionModeCounter = selection.size,
                    onCancelActionMode = { selection.clear() },
                    actionModeActions = {
                        IconButton(onClick = { selection.clear(); selection.addAll(visible.map { it.id }) }) { Icon(InkomaIcons.SelectAll, "Select all") }
                        IconButton(onClick = {
                            val inv = visible.map { it.id }.filterNot { it in selection }
                            selection.clear(); selection.addAll(inv)
                        }) { Icon(InkomaIcons.FlipToBack, "Invert") }
                    },
                    actions = {
                        if (m.source != LocalSource.ID) {
                            Box {
                                var dlMenu by remember { mutableStateOf(false) }
                                IconButton(onClick = { dlMenu = true }) { Icon(InkomaIcons.Download, "Download") }
                                DropdownMenu(dlMenu, onDismissRequest = { dlMenu = false }) {
                                    val unread = chs.filter { !it.read }.sortedByDescending { it.sourceOrder }
                                    listOf(1 to "Next chapter", 5 to "Next 5 chapters", 10 to "Next 10 chapters", 25 to "Next 25 chapters").forEach { (n, label) ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = { dlMenu = false; Inkoma.downloadManager.downloadChapters(m, unread.take(n)) })
                                    }
                                    DropdownMenuItem(text = { Text("Unread") }, onClick = { dlMenu = false; Inkoma.downloadManager.downloadChapters(m, unread) })
                                    DropdownMenuItem(text = { Text("All") }, onClick = { dlMenu = false; Inkoma.downloadManager.downloadChapters(m, chs) })
                                }
                            }
                        }
                        IconButton(onClick = { showSettings = true }) { Icon(InkomaIcons.FilterList, "Filter") }
                        Box {
                            IconButton(onClick = { showMenu = true }) { Icon(InkomaIcons.MoreVert, "More") }
                            DropdownMenu(showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(text = { Text("Refresh") }, onClick = {
                                    showMenu = false
                                    refresh(m, true) { scope.launch { snackbar.showSnackbar(it) } }
                                })
                                if (m.favorite) {
                                    DropdownMenuItem(text = { Text("Edit categories") }, onClick = { showMenu = false; showCategories = true })
                                }
                                val url = runCatching { (source as? HttpSource)?.getMangaUrl(m.toSManga()) }.getOrNull()
                                if (url != null) {
                                    DropdownMenuItem(text = { Text("Copy link") }, onClick = { showMenu = false; clipboard.setText(AnnotatedString(url)) })
                                }
                                if (chs.mapNotNull { it.scanlator }.distinct().size > 1) {
                                    DropdownMenuItem(text = { Text("Filter scanlators") }, onClick = { showMenu = false; showScanlators = true })
                                }
                            }
                        }
                    },
                )
            },
            floatingActionButtonPosition = if (fabPos == inkoma.core.FabPosition.START) FabPosition.Start else FabPosition.End,
            floatingActionButton = {
                if (selection.isEmpty() && nextChapter != null) {
                    val started = chs.any { it.read || it.lastPageRead > 0 }
                    ExtendedFloatingActionButton(
                        text = { Text(if (started) "Resume" else "Start") },
                        icon = { Icon(InkomaIcons.PlayArrow, null) },
                        onClick = { navigator.push(ReaderScreen(m.id, nextChapter.id)) },
                    )
                }
            },
            bottomBar = {
                if (selection.isNotEmpty()) {
                    BottomAppBar(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            val anyUnbookmarked = selected.any { !it.bookmark }
                            IconButton(onClick = { Inkoma.mangaInteractor.setBookmark(selected, anyUnbookmarked); selection.clear() }) {
                                Icon(if (anyUnbookmarked) InkomaIcons.BookmarkBorder else InkomaIcons.Bookmark, "Bookmark")
                            }
                            val anyUnread = selected.any { !it.read }
                            IconButton(onClick = { Inkoma.mangaInteractor.setRead(m, selected, anyUnread); selection.clear() }) {
                                Icon(if (anyUnread) InkomaIcons.DoneAll else InkomaIcons.Done, if (anyUnread) "Mark as read" else "Mark as unread")
                            }
                            if (selected.size == 1) {
                                IconButton(onClick = { Inkoma.mangaInteractor.markPreviousRead(m, chs, selected.first()); selection.clear() }) {
                                    Icon(InkomaIcons.ArrowDropDown, "Mark previous as read")
                                }
                            }
                            if (m.source != LocalSource.ID) {
                                IconButton(onClick = { Inkoma.downloadManager.downloadChapters(m, selected); selection.clear() }) { Icon(InkomaIcons.Download, "Download") }
                                IconButton(onClick = { Inkoma.downloadManager.deleteChapters(m, selected); selection.clear() }) { Icon(InkomaIcons.Delete, "Delete") }
                            }
                        }
                    }
                }
            },
        ) { padding ->
            val infoItems: LazyListScope.() -> Unit = {
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        val wide = coverAspect != null && coverAspect!! > 1.05f && panorama
                        MangaCover(
                            m.thumbnailUrl,
                            m.source,
                            if (wide) Modifier.width(240.dp).aspectRatio(coverAspect!!) else Modifier.width(120.dp).height(120.dp / BOOK_RATIO),
                            m.coverLastModified,
                            persistent = m.favorite,
                        )
                        Column(Modifier.padding(start = 16.dp).weight(1f)) {
                            Text(m.title, style = MaterialTheme.typography.titleLarge)
                            val authors = listOfNotNull(m.author, m.artist?.takeIf { it != m.author }).joinToString(", ")
                            if (authors.isNotBlank()) Text(authors, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                            Text(
                                listOf(statusText(m.status.toInt()), source.toString()).joinToString(" • "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(
                            title = if (m.favorite) "In library" else "Add to library",
                            icon = if (m.favorite) InkomaIcons.Favorite else InkomaIcons.FavoriteBorder,
                            highlighted = m.favorite,
                        ) {
                            if (m.favorite) {
                                Inkoma.mangaInteractor.setFavorite(m, false, null)
                            } else {
                                val cats = categories.filterNot { it.isSystemCategory }
                                val def = Inkoma.preferences.defaultCategory.get()
                                if (cats.isNotEmpty() && def == -1) {
                                    showCategories = true
                                } else {
                                    Inkoma.mangaInteractor.setFavorite(m, true, null)
                                    scope.launch(Dispatchers.IO) {
                                        Inkoma.mangaRepository.getDuplicateFavorites(m.title, m.id).firstOrNull()?.let {
                                            snackbar.showSnackbar("You have an entry in your library with the same name")
                                        }
                                    }
                                }
                            }
                        }
                        val url = runCatching { (source as? HttpSource)?.getMangaUrl(m.toSManga()) }.getOrNull()
                        if (url != null) {
                            ActionButton("WebView", InkomaIcons.Public, false) { runCatching { uriHandler.openUri(url) } }
                        }
                    }
                }
                item {
                    val desc = m.description?.trim().orEmpty()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        if (desc.isNotEmpty()) {
                            Text(
                                desc,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.combinedClickable(onClick = { descriptionExpanded = !descriptionExpanded }),
                            )
                        }
                        val genres = m.genre.orEmpty()
                        if (genres.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                                genres.forEach { g -> SuggestionChip(onClick = {}, label = { Text(g, style = MaterialTheme.typography.bodySmall) }) }
                            }
                        }
                    }
                }
            }
            val chapterItems: LazyListScope.() -> Unit = {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (refreshing) "Updating…" else "${visible.size} chapters",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                items(visible, key = { it.id }) { ch ->
                    ChapterRow(
                        chapter = ch,
                        title = if (showNumber && ch.isRecognizedNumber) "Chapter ${formatChapterNumber(ch.chapterNumber)}" else ch.name,
                        selected = ch.id in selection,
                        downloaded = ch.id in downloadedIds,
                        download = queueById[ch.id],
                        isLocal = m.source == LocalSource.ID,
                        onClick = {
                            if (selection.isNotEmpty()) {
                                if (ch.id in selection) selection.remove(ch.id) else selection.add(ch.id)
                            } else {
                                navigator.push(ReaderScreen(m.id, ch.id))
                            }
                        },
                        onLongClick = {
                            if (selection.isNotEmpty()) {
                                // Range selection like Mihon.
                                val idxs = selection.map { id -> visible.indexOfFirst { it.id == id } }.filter { it >= 0 }
                                val target = visible.indexOf(ch)
                                val anchor = idxs.minByOrNull { kotlin.math.abs(it - target) } ?: target
                                val range = if (anchor <= target) anchor..target else target..anchor
                                range.forEach { i -> visible[i].id.let { if (it !in selection) selection.add(it) } }
                            } else {
                                selection.add(ch.id)
                            }
                        },
                        onDownloadClick = {
                            when {
                                queueById[ch.id] != null -> Inkoma.downloadManager.cancel(listOf(queueById.getValue(ch.id)))
                                ch.id in downloadedIds -> Inkoma.downloadManager.deleteChapters(m, listOf(ch))
                                else -> Inkoma.downloadManager.downloadChapters(m, listOf(ch))
                            }
                        },
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
            BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                // Wide windows: details on the left, chapters on the right (Mihon's tablet layout).
                if (twoPane && maxWidth >= 900.dp) {
                    Row(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.weight(0.42f).fillMaxHeight()) { infoItems() }
                        VerticalDivider()
                        LazyColumn(Modifier.weight(0.58f).fillMaxHeight()) { chapterItems() }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        infoItems()
                        chapterItems()
                    }
                }
            }
        }

        if (showSettings) ChapterSettingsSheet(m) { showSettings = false }
        if (showCategories) {
            ChangeCategoryDialog(
                categories = categories.filterNot { it.isSystemCategory },
                initialSelection = Inkoma.categoryRepository.getForManga(m.id).map { it.id }.toSet(),
                onDismiss = { showCategories = false },
                onConfirm = { ids ->
                    if (!m.favorite) Inkoma.mangaInteractor.setFavorite(m, true, ids) else Inkoma.categoryRepository.setMangaCategories(m.id, ids)
                },
                onEditCategories = { navigator.push(inkoma.ui.category.CategoryScreen()) },
            )
        }
        if (showScanlators) {
            val all = chs.mapNotNull { it.scanlator }.distinct().sorted()
            var exc by remember { mutableStateOf(excluded) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showScanlators = false },
                title = { Text("Filter scanlators") },
                text = {
                    LazyColumn { items(all) { s -> LabeledCheckbox(s, s !in exc) { show -> exc = if (show) exc - s else exc + s } } }
                },
                confirmButton = { TextButton(onClick = { Inkoma.mangaRepository.setExcludedScanlators(m.id, exc); showScanlators = false }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { exc = emptySet() }) { Text("Reset") } },
            )
        }
    }
}

fun statusText(status: Int): String = when (status) {
    SManga.ONGOING -> "Ongoing"
    SManga.COMPLETED -> "Completed"
    SManga.LICENSED -> "Licensed"
    SManga.PUBLISHING_FINISHED -> "Publishing finished"
    SManga.CANCELLED -> "Cancelled"
    SManga.ON_HIATUS -> "On hiatus"
    else -> "Unknown"
}

@Composable
private fun ActionButton(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, highlighted: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(
    chapter: Chapter,
    title: String,
    selected: Boolean,
    downloaded: Boolean,
    download: Download?,
    isLocal: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownloadClick: () -> Unit,
) {
    val textAlpha = if (chapter.read) 0.38f else 1f
    Row(
        Modifier.fillMaxWidth().selectedBackground(selected).inkomaClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).alpha(textAlpha)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chapter.bookmark) {
                    Icon(InkomaIcons.Bookmark, "Bookmarked", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (chapter.bookmark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            val sub = buildList {
                if (chapter.dateUpload > 0) add(relativeDateText(chapter.dateUpload, Inkoma.preferences.relativeTime.get(), Inkoma.preferences.dateFormat.get()))
                if (!chapter.read && chapter.lastPageRead > 0) add("Page ${chapter.lastPageRead + 1}")
                chapter.scanlator?.let { add(it) }
            }.joinToString(" • ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (!isLocal) {
            IconButton(onClick = onDownloadClick) {
                when {
                    download != null && download.status == Download.Status.DOWNLOADING ->
                        CircularProgressIndicator(progress = { download.progress / 100f }, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    download != null && download.status == Download.Status.ERROR ->
                        Icon(InkomaIcons.Error, "Error", tint = MaterialTheme.colorScheme.error)
                    download != null -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    downloaded -> Icon(InkomaIcons.CheckCircle, "Downloaded", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Icon(InkomaIcons.Download, "Download", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ChapterSettingsSheet(manga: Manga, onDismiss: () -> Unit) {
    var flags by remember { mutableStateOf(manga.chapterFlags) }
    fun set(newFlags: Long) {
        flags = newFlags
        Inkoma.mangaRepository.setChapterFlags(manga.id, newFlags)
    }
    fun cycle(mask: Long, show: Long, hide: Long) {
        val next = when (flags and mask) { 0L -> show; show -> hide; else -> 0L }
        set((flags and mask.inv()) or next)
    }
    fun state(mask: Long, show: Long, hide: Long) = when (flags and mask) { show -> TriState.ENABLED_IS; hide -> TriState.ENABLED_NOT; else -> TriState.DISABLED }
    TabbedSheet(onDismissRequest = onDismiss, tabTitles = listOf("Filter", "Sort", "Display")) { tab ->
        when (tab) {
            0 -> {
                if (manga.source != LocalSource.ID) {
                    TriStateItem("Downloaded", state(Manga.CHAPTER_DOWNLOADED_MASK, Manga.CHAPTER_SHOW_DOWNLOADED, Manga.CHAPTER_SHOW_NOT_DOWNLOADED)) {
                        cycle(Manga.CHAPTER_DOWNLOADED_MASK, Manga.CHAPTER_SHOW_DOWNLOADED, Manga.CHAPTER_SHOW_NOT_DOWNLOADED)
                    }
                }
                TriStateItem("Unread", state(Manga.CHAPTER_UNREAD_MASK, Manga.CHAPTER_SHOW_UNREAD, Manga.CHAPTER_SHOW_READ)) {
                    cycle(Manga.CHAPTER_UNREAD_MASK, Manga.CHAPTER_SHOW_UNREAD, Manga.CHAPTER_SHOW_READ)
                }
                TriStateItem("Bookmarked", state(Manga.CHAPTER_BOOKMARKED_MASK, Manga.CHAPTER_SHOW_BOOKMARKED, Manga.CHAPTER_SHOW_NOT_BOOKMARKED)) {
                    cycle(Manga.CHAPTER_BOOKMARKED_MASK, Manga.CHAPTER_SHOW_BOOKMARKED, Manga.CHAPTER_SHOW_NOT_BOOKMARKED)
                }
            }
            1 -> {
                val asc = flags and Manga.CHAPTER_SORT_DIR_MASK == Manga.CHAPTER_SORT_ASC
                listOf(
                    Manga.CHAPTER_SORTING_SOURCE to "By source",
                    Manga.CHAPTER_SORTING_NUMBER to "By chapter number",
                    Manga.CHAPTER_SORTING_UPLOAD_DATE to "By upload date",
                    Manga.CHAPTER_SORTING_ALPHABET to "By alphabet",
                ).forEach { (mode, label) ->
                    val active = flags and Manga.CHAPTER_SORTING_MASK == mode
                    SortItem(label, if (active) !asc else null) {
                        if (active) {
                            set((flags and Manga.CHAPTER_SORT_DIR_MASK.inv()) or if (asc) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC)
                        } else {
                            set((flags and Manga.CHAPTER_SORTING_MASK.inv()) or mode)
                        }
                    }
                }
            }
            else -> {
                HeadingItem("Display")
                val byNumber = flags and Manga.CHAPTER_DISPLAY_MASK == Manga.CHAPTER_DISPLAY_NUMBER
                RadioItem("Source title", !byNumber) { set(flags and Manga.CHAPTER_DISPLAY_MASK.inv()) }
                RadioItem("Chapter number", byNumber) { set((flags and Manga.CHAPTER_DISPLAY_MASK.inv()) or Manga.CHAPTER_DISPLAY_NUMBER) }
            }
        }
    }
}
