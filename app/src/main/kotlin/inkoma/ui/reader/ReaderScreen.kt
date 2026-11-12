package inkoma.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import inkoma.Inkoma
import inkoma.core.DualPageSpread
import inkoma.core.ImageScale
import inkoma.core.ReaderBackground
import inkoma.core.ReadingMode
import inkoma.core.TappingLayout
import inkoma.domain.Chapter
import inkoma.domain.Manga
import inkoma.ui.AppKeyEvents
import inkoma.ui.LocalWindowControls
import inkoma.ui.components.CheckboxItem
import inkoma.ui.components.HeadingItem
import inkoma.ui.components.RadioItem
import inkoma.ui.components.SliderPreferenceWidget
import inkoma.ui.components.TabbedSheet
import inkoma.ui.navigation.LocalNavigator
import inkoma.ui.navigation.Screen
import inkoma.ui.theme.InkomaIcons

/** One item of the reader strip: a page, or a chapter transition marker (Mihon's ChapterTransition). */
sealed interface ReaderItem {
    data class PageItem(val chapter: Chapter, val page: Page, val total: Int) : ReaderItem
    data class Transition(val from: Chapter, val to: Chapter?, val isNext: Boolean) : ReaderItem
}

private enum class TapAction { PREV, NEXT, MENU }

class ReaderScreen(private val mangaId: Long, private val initialChapterId: Long) : Screen() {
    private var manga by mutableStateOf<Manga?>(null)
    private var chapters by mutableStateOf<List<Chapter>>(emptyList()) // reading order
    private var current by mutableStateOf<Chapter?>(null)
    private var items by mutableStateOf<List<ReaderItem>>(emptyList())
    private var error by mutableStateOf<String?>(null)
    private var loading by mutableStateOf(true)
    private var menuVisible by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var startIndex = 0
    private var sessionStart = System.currentTimeMillis()
    private var pendingJump: Int? = null

    /** The current chapter's page on the source's website (for the built-in browser), or null for local sources. */
    private fun chapterWebUrl(): String? {
        val m = manga ?: return null
        val ch = current ?: return null
        val source = Inkoma.sourceManager.getOrStub(m.source) as? eu.kanade.tachiyomi.source.online.HttpSource ?: return null
        return runCatching { source.getChapterUrl(ch.toSChapter()) }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    /** Set when the series has no explicit reading mode and its pages are long vertical strips. */
    private var detectedWebtoon by mutableStateOf(false)

    private fun neighbour(chapter: Chapter, next: Boolean): Chapter? {
        val idx = chapters.indexOfFirst { it.id == chapter.id }
        if (idx < 0) return null
        val p = Inkoma.preferences
        var i = if (next) idx + 1 else idx - 1
        while (i in chapters.indices) {
            val c = chapters[i]
            val skip = (p.skipRead.get() && c.read && next) || (p.skipDupe.get() && c.chapterNumber == chapter.chapterNumber && c.isRecognizedNumber)
            if (!skip) return c
            i += if (next) 1 else -1
        }
        return null
    }

    fun load(chapterId: Long, fromEnd: Boolean = false) {
        screenScope.launch {
            loading = true
            error = null
            try {
                val m = withContext(Dispatchers.IO) { Inkoma.mangaRepository.getById(mangaId) } ?: throw IllegalStateException("Manga not found")
                manga = m
                chapters = withContext(Dispatchers.IO) { Inkoma.chapterRepository.getByMangaId(mangaId) }.sortedByDescending { it.sourceOrder }
                val ch = chapters.firstOrNull { it.id == chapterId } ?: throw IllegalStateException("Chapter not found")
                saveCurrent()
                current = ch
                sessionStart = System.currentTimeMillis()
                val pages = PageLoader.loadPages(m, ch)
                // Automatic webtoon detection (TachiyomiSY): only when the series has no explicit reading mode.
                if (m.readingModeOrdinal !in ReadingMode.entries.indices && Inkoma.preferences.autoWebtoon.get()) {
                    val probe = pages.getOrNull(minOf(1, pages.lastIndex))
                    detectedWebtoon = probe != null && runCatching { PageLoader.loadImage(m, ch.id, probe) }
                        .getOrNull()?.let { ReaderVisuals.looksLikeWebtoon(it.width, it.height) } == true
                }
                val list = ArrayList<ReaderItem>()
                val prev = neighbour(ch, false)
                if (prev != null || Inkoma.preferences.alwaysShowChapterTransition.get()) list.add(ReaderItem.Transition(ch, prev, false))
                pages.forEach { list.add(ReaderItem.PageItem(ch, it, pages.size)) }
                list.add(ReaderItem.Transition(ch, neighbour(ch, true), true))
                val firstPage = list.indexOfFirst { it is ReaderItem.PageItem }
                startIndex = when {
                    fromEnd -> list.indexOfLast { it is ReaderItem.PageItem }
                    !ch.read && ch.lastPageRead > 0 -> firstPage + ch.lastPageRead.toInt().coerceIn(0, pages.size - 1)
                    else -> firstPage
                }
                items = list
                pendingJump = startIndex
                withContext(Dispatchers.IO) { Inkoma.historyRepository.upsert(ch.id, System.currentTimeMillis(), 0) }
            } catch (e: Throwable) {
                error = inkoma.core.ErrorMessages.describe(e)
            } finally {
                loading = false
            }
        }
    }

    private var lastPageIndex = 0

    fun onItemVisible(index: Int) {
        val item = items.getOrNull(index) as? ReaderItem.PageItem ?: return
        lastPageIndex = item.page.index
        val m = manga ?: return
        val ch = current ?: return
        val now = System.currentTimeMillis()
        val elapsed = now - sessionStart
        sessionStart = now
        Inkoma.scope.launch(Dispatchers.IO) {
            Inkoma.mangaInteractor.saveProgress(m, ch, item.page.index, item.total, elapsed)
            if (item.page.index >= item.total - 1) current = Inkoma.chapterRepository.getById(ch.id) ?: ch
        }
        // Download-ahead while reading (Mihon's "auto download while reading").
        val ahead = Inkoma.preferences.autoDownloadWhileReading.get()
        if (ahead > 0 && item.page.index >= item.total - 1 && Inkoma.downloadManager.findChapter(m, ch) != null) {
            val nexts = chapters.dropWhile { it.id != ch.id }.drop(1).filter { !it.read }.take(ahead)
            Inkoma.downloadManager.downloadChapters(m, nexts)
        }
    }

    private fun saveCurrent() {
        val m = manga ?: return
        val ch = current ?: return
        val elapsed = System.currentTimeMillis() - sessionStart
        val total = (items.lastOrNull { it is ReaderItem.PageItem } as? ReaderItem.PageItem)?.total ?: return
        Inkoma.scope.launch(Dispatchers.IO) { Inkoma.mangaInteractor.saveProgress(m, ch, lastPageIndex, total, elapsed) }
    }

    override fun onDispose() {
        saveCurrent()
        PageLoader.clear()
        super.onDispose()
    }

    private fun readingMode(m: Manga): ReadingMode {
        val ord = m.readingModeOrdinal
        return when {
            ord in ReadingMode.entries.indices -> ReadingMode.entries[ord]
            detectedWebtoon -> ReadingMode.WEBTOON
            else -> Inkoma.preferences.defaultReadingMode.get()
        }
    }

    private fun tapAction(x: Float, y: Float, layout: TappingLayout, invert: Boolean): TapAction {
        var fx = x
        var fy = y
        if (invert) {
            fx = 1f - x
            fy = 1f - y
        }
        val col = when { fx < 1f / 3 -> 0; fx > 2f / 3 -> 2; else -> 1 }
        val row = when { fy < 1f / 3 -> 0; fy > 2f / 3 -> 2; else -> 1 }
        return when (layout) {
            TappingLayout.DISABLED -> TapAction.MENU
            TappingLayout.DEFAULT, TappingLayout.L_SHAPED -> when {
                row == 1 && col == 1 -> TapAction.MENU
                row == 0 || (row == 1 && col == 0) -> TapAction.PREV
                else -> TapAction.NEXT
            }
            TappingLayout.KINDLISH -> when {
                row == 0 -> TapAction.MENU
                col == 0 -> TapAction.PREV
                else -> TapAction.NEXT
            }
            TappingLayout.EDGE -> when {
                col != 1 -> TapAction.NEXT
                row == 2 -> TapAction.PREV
                else -> TapAction.MENU
            }
            TappingLayout.RIGHT_AND_LEFT -> when (col) {
                0 -> TapAction.PREV
                2 -> TapAction.NEXT
                else -> TapAction.MENU
            }
        }
    }

    @Composable
    override fun Content() {
        // Cover-based colours for the reader menus (Komikku); pages themselves are never tinted.
        val seed = inkoma.ui.theme.rememberCoverSeed(manga?.thumbnailUrl, manga?.source ?: 0L)
        inkoma.ui.theme.SeedTheme(seed) { Body() }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Body() {
        val navigator = LocalNavigator.current
        val window = LocalWindowControls.current
        val scope = rememberCoroutineScope()
        val p = Inkoma.preferences
        LaunchedEffect(Unit) { if (items.isEmpty()) load(initialChapterId) }
        DisposableEffect(Unit) {
            val wasFullscreen = window.isFullscreen()
            if (p.fullscreen.get() && !wasFullscreen) window.setFullscreen(true)
            onDispose { if (p.fullscreen.get() && !wasFullscreen) window.setFullscreen(false) }
        }

        val bgPref by p.readerBackground.changes().collectAsState(p.readerBackground.get())
        val scalePref by p.imageScale.changes().collectAsState(p.imageScale.get())
        val showPageNumber by p.showPageNumber.changes().collectAsState(p.showPageNumber.get())
        val transitions by p.pageTransitions.changes().collectAsState(p.pageTransitions.get())
        val pagedLayout by p.pagedTappingLayout.changes().collectAsState(p.pagedTappingLayout.get())
        val webtoonLayout by p.webtoonTappingLayout.changes().collectAsState(p.webtoonTappingLayout.get())
        val invert by p.invertTapping.changes().collectAsState(p.invertTapping.get())
        val sidePadding by p.webtoonSidePadding.changes().collectAsState(p.webtoonSidePadding.get())
        val defaultMode by p.defaultReadingMode.changes().collectAsState(p.defaultReadingMode.get())
        val spreadPref by p.dualPageSpread.changes().collectAsState(p.dualPageSpread.get())
        val spreadShift by p.dualPageShift.changes().collectAsState(p.dualPageShift.get())
        var windowIsWide by remember { mutableStateOf(true) }

        val m = manga
        val mode = m?.let { readingMode(it) } ?: defaultMode
        var smartBackground by remember { mutableStateOf<Color?>(null) }
        val maxPageWidth by p.readerMaxWidth.changes().collectAsState(p.readerMaxWidth.get())
        val bindingsRaw by p.keyBindings.changes().collectAsState(p.keyBindings.get())
        val bindings = remember(bindingsRaw) { KeyBindings.parse(bindingsRaw) }
        var perMangaTick by remember { mutableStateOf(0) }
        val effectiveSpread = remember(m?.id, perMangaTick, spreadPref) { m?.let { PerMangaSettings.spread(it.id) } ?: spreadPref }
        val effectiveScale = remember(m?.id, perMangaTick, scalePref) { m?.let { PerMangaSettings.scale(it.id) } ?: scalePref }
        val dark = isSystemInDarkTheme()
        val background = when (bgPref) {
            ReaderBackground.BLACK -> Color.Black
            ReaderBackground.GRAY -> Color(0xFF202125)
            ReaderBackground.WHITE -> Color.White
            ReaderBackground.AUTOMATIC -> if (dark) Color.Black else Color.White
            ReaderBackground.SMART -> smartBackground ?: if (dark) Color.Black else Color.White
        }

        // Pager "slots": one item each, or page pairs when the two-page spread is active.
        val spread = (mode == ReadingMode.LEFT_TO_RIGHT || mode == ReadingMode.RIGHT_TO_LEFT) &&
            (effectiveSpread == DualPageSpread.ON || (effectiveSpread == DualPageSpread.AUTO && windowIsWide))
        val slots = remember(items, spread, spreadShift) { buildSlots(items, spread, spreadShift) }
        fun slotOf(itemIndex: Int): Int = slots.indexOfFirst { itemIndex in it }.coerceAtLeast(0)

        val pagerState = rememberPagerState(initialPage = 0) { slots.size }
        val listState = rememberLazyListState()
        var currentIndex by remember { mutableStateOf(0) }

        // Jump to the start page after (re)loading a chapter or changing the layout.
        LaunchedEffect(items, mode, slots) {
            val target = pendingJump ?: currentIndex
            pendingJump = null
            if (items.isNotEmpty()) {
                val idx = target.coerceIn(0, items.lastIndex)
                if (mode.isPaged) pagerState.scrollToPage(slotOf(idx)) else listState.scrollToItem(idx)
                currentIndex = idx
            }
        }
        LaunchedEffect(items, mode, slots) {
            if (mode.isPaged) {
                snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { slot ->
                    val slotItems = slots.getOrNull(slot) ?: return@collect
                    currentIndex = slotItems.last()
                    slotItems.forEach { onItemVisible(it) }
                }
            } else {
                snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { currentIndex = it; onItemVisible(it) }
            }
        }

        fun go(delta: Int) {
            if (items.isEmpty()) return
            if (mode.isPaged) {
                val targetSlot = slotOf(currentIndex) + delta
                if (targetSlot > slots.lastIndex) {
                    (items.last() as? ReaderItem.Transition)?.to?.let { load(it.id) }
                    return
                }
                if (targetSlot < 0) {
                    (items.first() as? ReaderItem.Transition)?.to?.let { load(it.id, fromEnd = true) }
                    return
                }
                scope.launch { if (transitions) pagerState.animateScrollToPage(targetSlot) else pagerState.scrollToPage(targetSlot) }
                return
            }
            val target = currentIndex + delta
            if (target > items.lastIndex) {
                (items.last() as? ReaderItem.Transition)?.to?.let { load(it.id) }
                return
            }
            if (target < 0) {
                (items.first() as? ReaderItem.Transition)?.to?.let { load(it.id, fromEnd = true) }
                return
            }
            scope.launch {
                val height = listState.layoutInfo.viewportSize.height.toFloat()
                if (transitions) listState.animateScrollBy(height * 0.75f * delta) else listState.scrollBy(height * 0.75f * delta)
            }
        }

        fun jumpTo(index: Int) {
            scope.launch { if (mode.isPaged) pagerState.scrollToPage(slotOf(index)) else listState.scrollToItem(index) }
        }

        // Smart background: match the current page's border colour once its bitmap is decoded.
        LaunchedEffect(currentIndex, items, bgPref) {
            if (bgPref != ReaderBackground.SMART) return@LaunchedEffect
            val item = items.getOrNull(currentIndex) as? ReaderItem.PageItem ?: return@LaunchedEffect
            val bmp = m?.let { runCatching { PageLoader.loadImage(it, item.chapter.id, item.page) }.getOrNull() }
            smartBackground = bmp?.let { withContext(Dispatchers.Default) { ReaderVisuals.edgeColor(it) } }
        }

        DisposableEffect(mode, items, bindings) {
            val handler: (KeyEvent) -> Boolean = handler@{ e ->
                if (e.type != KeyEventType.KeyDown) return@handler e.key == Key.Escape && e.type == KeyEventType.KeyUp && (showSettings || menuVisible).also {
                    if (showSettings) showSettings = false else if (menuVisible) menuVisible = false
                }
                val rtl = mode == ReadingMode.RIGHT_TO_LEFT
                if (e.isAltPressed) return@handler false
                when (KeyBindings.actionFor(e.key, bindings)) {
                    ReaderAction.PAGE_RIGHT -> { go(if (rtl) -1 else 1); true }
                    ReaderAction.PAGE_LEFT -> { go(if (rtl) 1 else -1); true }
                    ReaderAction.NEXT_PAGE -> { go(1); true }
                    ReaderAction.PREVIOUS_PAGE -> { go(-1); true }
                    ReaderAction.FIRST_PAGE -> { jumpTo(items.indexOfFirst { it is ReaderItem.PageItem }.coerceAtLeast(0)); true }
                    ReaderAction.LAST_PAGE -> { jumpTo(items.indexOfLast { it is ReaderItem.PageItem }.coerceAtLeast(0)); true }
                    ReaderAction.NEXT_CHAPTER -> { current?.let { c -> neighbour(c, true)?.let { load(it.id) } }; true }
                    ReaderAction.PREVIOUS_CHAPTER -> { current?.let { c -> neighbour(c, false)?.let { load(it.id) } }; true }
                    ReaderAction.TOGGLE_MENU -> { menuVisible = !menuVisible; true }
                    ReaderAction.FULLSCREEN -> { window.setFullscreen(!window.isFullscreen()); true }
                    ReaderAction.BOOKMARK -> {
                        current?.let { c -> Inkoma.mangaInteractor.setBookmark(listOf(c), !c.bookmark); current = c.copy(bookmark = !c.bookmark) }
                        true
                    }
                    null -> false
                }
            }
            AppKeyEvents.register(handler)
            onDispose { AppKeyEvents.unregister(handler) }
        }

        Box(Modifier.fillMaxSize().background(background)) {
            when {
                error != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { load(current?.id ?: initialChapterId) }) { Text("Retry") }
                        chapterWebUrl()?.let { url ->
                            OutlinedButton(onClick = { navigator.push(inkoma.ui.browser.BrowserScreen(url, current?.name)) }) { Text("Read on website") }
                        }
                    }
                }
                loading && items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                m != null -> {
                    BoxWithConstraints(
                        Modifier.fillMaxHeight().fillMaxWidth((maxPageWidth.coerceIn(30, 100)) / 100f).align(Alignment.Center),
                    ) {
                        val w = constraints.maxWidth.toFloat()
                        val h = constraints.maxHeight.toFloat()
                        SideEffect { windowIsWide = w > h * 1.15f }
                        val layout = if (mode.isPaged) pagedLayout else webtoonLayout
                        val onTap: (Offset) -> Unit = { pos ->
                            when (tapAction(pos.x / w, pos.y / h, layout, invert)) {
                                TapAction.MENU -> menuVisible = !menuVisible
                                TapAction.NEXT -> if (mode == ReadingMode.RIGHT_TO_LEFT) go(-1) else go(1)
                                TapAction.PREV -> if (mode == ReadingMode.RIGHT_TO_LEFT) go(1) else go(-1)
                            }
                        }
                        // Mouse wheel turns pages in paged modes (debounced so one trackpad swipe = one page).
                        val lastWheel = remember { longArrayOf(0L) }
                        val onWheel: (Float) -> Unit = { dy ->
                            val now = System.currentTimeMillis()
                            if (dy != 0f && now - lastWheel[0] > 220) {
                                lastWheel[0] = now
                                go(if (dy > 0) 1 else -1)
                            }
                        }
                        val tapModifier = Modifier.pointerInput(mode, layout, invert, items) { detectTapGestures(onTap = onTap) }
                        when (mode) {
                            ReadingMode.LEFT_TO_RIGHT, ReadingMode.RIGHT_TO_LEFT -> HorizontalPager(
                                state = pagerState,
                                reverseLayout = mode == ReadingMode.RIGHT_TO_LEFT,
                                beyondViewportPageCount = 1,
                                modifier = Modifier.fillMaxSize(),
                                key = { slot -> slots.getOrNull(slot)?.let { itemKey(items.getOrNull(it.first()), it.first()) } ?: "s-$slot" },
                            ) { slot ->
                                val slotItems = slots[slot]
                                if (slotItems.size == 2) {
                                    SpreadItem(m, items[slotItems[0]], items[slotItems[1]], mode == ReadingMode.RIGHT_TO_LEFT, onTap, onWheel)
                                } else {
                                    PagedItem(m, items[slotItems[0]], effectiveScale, onTap, onWheel)
                                }
                            }
                            ReadingMode.VERTICAL -> VerticalPager(
                                state = pagerState,
                                beyondViewportPageCount = 1,
                                modifier = Modifier.fillMaxSize(),
                                key = { slot -> slots.getOrNull(slot)?.let { itemKey(items.getOrNull(it.first()), it.first()) } ?: "s-$slot" },
                            ) { slot -> PagedItem(m, items[slots[slot][0]], effectiveScale, onTap, onWheel) }
                            ReadingMode.WEBTOON, ReadingMode.CONTINUOUS_VERTICAL -> {
                                LazyColumn(
                                    state = listState,
                                    verticalArrangement = Arrangement.spacedBy(if (mode == ReadingMode.CONTINUOUS_VERTICAL) 16.dp else 0.dp),
                                    modifier = Modifier.fillMaxSize().then(tapModifier)
                                        .padding(horizontal = (maxWidth * (sidePadding / 100f))),
                                ) {
                                    itemsIndexed(items, key = { idx, it -> itemKey(it, idx) }) { _, item ->
                                        when (item) {
                                            is ReaderItem.PageItem -> PageImage(m, item, ContentScale.FillWidth, Modifier.fillMaxWidth(), webtoon = true)
                                            is ReaderItem.Transition -> TransitionView(item, Modifier.fillMaxWidth().height(maxHeight * 0.6f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val page = items.getOrNull(currentIndex) as? ReaderItem.PageItem
            if (showPageNumber && page != null && !menuVisible) {
                Text(
                    "${page.page.number} / ${page.total}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                        .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            AnimatedVisibility(menuVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { navigator.pop() }) { Icon(InkomaIcons.ArrowBack, "Back") }
                        Column(Modifier.weight(1f)) {
                            Text(m?.title ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(current?.name ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = {
                            current?.let { c -> Inkoma.mangaInteractor.setBookmark(listOf(c), !c.bookmark); current = c.copy(bookmark = !c.bookmark) }
                        }) { Icon(if (current?.bookmark == true) InkomaIcons.Bookmark else InkomaIcons.BookmarkBorder, "Bookmark") }
                        chapterWebUrl()?.let { url ->
                            IconButton(onClick = { navigator.push(inkoma.ui.browser.BrowserScreen(url, current?.name)) }) { Icon(InkomaIcons.Public, "Open chapter in browser") }
                        }
                        IconButton(onClick = { window.setFullscreen(!window.isFullscreen()) }) { Icon(InkomaIcons.Fullscreen, "Fullscreen") }
                    }
                }
            }
            AnimatedVisibility(menuVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        val pageItems = items.withIndex().filter { it.value is ReaderItem.PageItem }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val rtl = mode == ReadingMode.RIGHT_TO_LEFT
                            IconButton(onClick = { current?.let { c -> neighbour(c, !rtl)?.let { load(it.id) } } }) {
                                Icon(InkomaIcons.SkipPrevious, if (rtl) "Next chapter" else "Previous chapter")
                            }
                            Text(page?.page?.number?.toString() ?: "", modifier = Modifier.padding(horizontal = 8.dp))
                            if (pageItems.size > 1) {
                                val pos = pageItems.indexOfFirst { it.index == currentIndex }.coerceAtLeast(0)
                                Slider(
                                    value = pos.toFloat(),
                                    onValueChange = { v -> pageItems.getOrNull(v.toInt())?.let { jumpTo(it.index) } },
                                    valueRange = 0f..(pageItems.size - 1).toFloat(),
                                    steps = (pageItems.size - 2).coerceAtLeast(0),
                                    modifier = Modifier.weight(1f).graphicsLayer { if (rtl) scaleX = -1f },
                                )
                            } else {
                                Box(Modifier.weight(1f))
                            }
                            Text(page?.total?.toString() ?: "", modifier = Modifier.padding(horizontal = 8.dp))
                            IconButton(onClick = { current?.let { c -> neighbour(c, rtl)?.let { load(it.id) } } }) {
                                Icon(InkomaIcons.SkipNext, if (rtl) "Previous chapter" else "Next chapter")
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            IconButton(onClick = { showSettings = true }) { Icon(InkomaIcons.Reader, "Reading mode") }
                            IconButton(onClick = { showSettings = true }) { Icon(InkomaIcons.Settings, "Settings") }
                        }
                    }
                }
            }
        }

        if (showSettings && m != null) {
            ReaderSettingsSheet(m, onDismiss = { showSettings = false }, onPerMangaChanged = { perMangaTick++ }) { newFlags ->
                Inkoma.mangaRepository.setViewerFlags(m.id, newFlags)
                manga = m.copy(viewerFlags = newFlags)
            }
        }
    }

    private fun itemKey(item: ReaderItem?, idx: Int): Any = when (item) {
        is ReaderItem.PageItem -> "p-${item.chapter.id}-${item.page.index}"
        is ReaderItem.Transition -> "t-${item.from.id}-${item.isNext}"
        null -> "n-$idx"
    }

    @Composable
    private fun PagedItem(manga: Manga, item: ReaderItem, scale: ImageScale, onTap: (Offset) -> Unit, onWheel: (Float) -> Unit) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (item) {
                is ReaderItem.PageItem -> ZoomablePage(onTap, onWheel) {
                    val cs = when (scale) {
                        ImageScale.FIT_SCREEN, ImageScale.SMART_FIT -> ContentScale.Fit
                        ImageScale.STRETCH -> ContentScale.FillBounds
                        ImageScale.FIT_WIDTH -> ContentScale.FillWidth
                        ImageScale.FIT_HEIGHT -> ContentScale.FillHeight
                        ImageScale.ORIGINAL -> ContentScale.None
                    }
                    PageImage(manga, item, cs, Modifier.fillMaxSize(), webtoon = false)
                }
                is ReaderItem.Transition -> {
                    val tap by rememberUpdatedState(onTap)
                    val wheel by rememberUpdatedState(onWheel)
                    TransitionView(
                        item,
                        Modifier.fillMaxSize()
                            .onPointerEvent(PointerEventType.Scroll) { ev -> wheel(ev.changes.first().scrollDelta.y) }
                            .pointerInput(Unit) { detectTapGestures(onTap = { tap(it) }) },
                    )
                }
            }
        }
    }

    /** Two pages side by side meeting at the spine; for right-to-left the first page sits on the right. */
    @Composable
    private fun SpreadItem(manga: Manga, first: ReaderItem, second: ReaderItem, rtl: Boolean, onTap: (Offset) -> Unit, onWheel: (Float) -> Unit) {
        val (left, right) = if (rtl) second to first else first to second
        ZoomablePage(onTap, onWheel) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                    (left as? ReaderItem.PageItem)?.let { PageImage(manga, it, ContentScale.Fit, Modifier.fillMaxSize(), webtoon = false, alignment = Alignment.CenterEnd) }
                }
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    (right as? ReaderItem.PageItem)?.let { PageImage(manga, it, ContentScale.Fit, Modifier.fillMaxSize(), webtoon = false, alignment = Alignment.CenterStart) }
                }
            }
        }
    }

    /** One gesture detector handles tap zones and double-tap zoom, so single taps are never swallowed. */
    @Composable
    private fun ZoomablePage(onTapArg: (Offset) -> Unit, onWheelArg: (Float) -> Unit, content: @Composable () -> Unit) {
        val onTap by rememberUpdatedState(onTapArg)
        val onWheel by rememberUpdatedState(onWheelArg)
        var zoom by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier.fillMaxSize()
                .onPointerEvent(PointerEventType.Scroll) { ev ->
                    val dy = ev.changes.first().scrollDelta.y
                    if (ev.keyboardModifiers.isCtrlPressed) {
                        zoom = (zoom * if (dy < 0) 1.1f else 1 / 1.1f).coerceIn(1f, 5f)
                        if (zoom == 1f) offset = Offset.Zero
                    } else if (zoom > 1f) {
                        offset += Offset(0f, -dy * 60f)
                    } else {
                        onWheel(dy)
                    }
                }
                .pointerInput(zoom) {
                    if (zoom > 1f) detectDragGestures { change, drag -> change.consume(); offset += drag }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { pos -> if (zoom == 1f) onTap(pos) },
                        onDoubleTap = {
                            zoom = if (zoom > 1f) 1f else 2f
                            if (zoom == 1f) offset = Offset.Zero
                        },
                    )
                }
                .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y },
        ) { content() }
    }

    @Composable
    private fun PageImage(
        manga: Manga,
        item: ReaderItem.PageItem,
        scale: ContentScale,
        modifier: Modifier,
        webtoon: Boolean,
        alignment: Alignment = Alignment.Center,
    ) {
        var retry by remember { mutableStateOf(0) }
        val state by produceState<Result<ImageBitmap>?>(PageLoader.cached(item.chapter.id, item.page)?.let { Result.success(it) }, item, retry) {
            if (value?.isSuccess != true) value = runCatching { PageLoader.loadImage(manga, item.chapter.id, item.page) }
        }
        val progress by item.page.progressFlow.collectAsState()
        val result = state
        when {
            result == null -> Box(modifier.then(if (webtoon) Modifier.height(600.dp) else Modifier), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (progress in 1..99) CircularProgressIndicator(progress = { progress / 100f }) else CircularProgressIndicator()
                    Text("${item.page.number}", color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                }
            }
            result.isFailure -> Box(modifier.then(if (webtoon) Modifier.height(400.dp) else Modifier), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(result.exceptionOrNull()?.let { inkoma.core.ErrorMessages.describe(it) } ?: "Error", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Button(onClick = { retry++ }, modifier = Modifier.padding(top = 8.dp)) { Text("Retry") }
                }
            }
            else -> {
                val bmp = result.getOrThrow()
                Image(
                    painter = BitmapPainter(bmp, filterQuality = ReaderVisuals.rememberFilterQuality()),
                    contentDescription = "Page ${item.page.number}",
                    contentScale = scale,
                    alignment = alignment,
                    colorFilter = ReaderVisuals.rememberColorFilter(),
                    modifier = modifier,
                )
            }
        }
    }

    @Composable
    private fun TransitionView(item: ReaderItem.Transition, modifier: Modifier) {
        Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val color = Color(0xFFE3E2E6)
                if (item.isNext) {
                    Text("Finished:", color = color, style = MaterialTheme.typography.labelLarge)
                    Text(item.from.name, color = color, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    if (item.to != null) {
                        Text("Next:", color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
                        Text(item.to.name, color = color, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    } else {
                        Text("There's no next chapter", color = color, modifier = Modifier.padding(top = 16.dp))
                    }
                } else {
                    Text("Current:", color = color, style = MaterialTheme.typography.labelLarge)
                    Text(item.from.name, color = color, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    if (item.to != null) {
                        Text("Previous:", color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
                        Text(item.to.name, color = color, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    } else {
                        Text("There's no previous chapter", color = color, modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
    }
}

/**
 * Groups reader items into pager slots. Without a spread every item is its own slot. With a spread, transitions
 * stay alone, each chapter's first page (the cover) stays alone unless [shift] is set, and the rest are paired.
 */
internal fun buildSlots(items: List<ReaderItem>, spread: Boolean, shift: Boolean): List<List<Int>> {
    if (!spread) return items.indices.map { listOf(it) }
    val slots = ArrayList<List<Int>>()
    var pending: Int? = null
    var pagesInChapter = 0
    items.forEachIndexed { i, item ->
        when (item) {
            is ReaderItem.Transition -> {
                pending?.let { slots.add(listOf(it)) }
                pending = null
                pagesInChapter = 0
                slots.add(listOf(i))
            }
            is ReaderItem.PageItem -> {
                val isCover = pagesInChapter == 0 && !shift
                pagesInChapter++
                when {
                    isCover -> slots.add(listOf(i))
                    pending == null -> pending = i
                    else -> {
                        slots.add(listOf(pending!!, i))
                        pending = null
                    }
                }
            }
        }
    }
    pending?.let { slots.add(listOf(it)) }
    return slots
}

@Composable
private fun ReaderSettingsSheet(manga: Manga, onDismiss: () -> Unit, onPerMangaChanged: () -> Unit, onSetViewerFlags: (Long) -> Unit) {
    val p = Inkoma.preferences
    TabbedSheet(onDismissRequest = onDismiss, tabTitles = listOf("Reading mode", "General", "Filters", "Paged", "Webtoon")) { tab0 ->
        // Tab 2 is Filters; shift the original tab indices after it.
        val tab = if (tab0 >= 3) tab0 - 1 else tab0
        if (tab0 == 2) {
            ReaderFiltersTab()
            return@TabbedSheet
        }
        when (tab) {
            0 -> {
                HeadingItem("Reading mode (this series)")
                RadioItem("Default (${p.defaultReadingMode.get().label})", manga.readingModeOrdinal < 0) {
                    onSetViewerFlags(manga.viewerFlags and Manga.READING_MODE_MASK.inv())
                }
                ReadingMode.entries.forEach { rm ->
                    RadioItem(rm.label, manga.readingModeOrdinal == rm.ordinal) {
                        onSetViewerFlags((manga.viewerFlags and Manga.READING_MODE_MASK.inv()) or (rm.ordinal + 1L))
                    }
                }
                var spread by remember { mutableStateOf(PerMangaSettings.spread(manga.id)) }
                HeadingItem("Two-page spread (this series)")
                RadioItem("Default (${p.dualPageSpread.get().label})", spread == null) {
                    spread = null; PerMangaSettings.setSpread(manga.id, null); onPerMangaChanged()
                }
                DualPageSpread.entries.forEach { d ->
                    RadioItem(d.label, spread == d) { spread = d; PerMangaSettings.setSpread(manga.id, d); onPerMangaChanged() }
                }
                var scale by remember { mutableStateOf(PerMangaSettings.scale(manga.id)) }
                HeadingItem("Scale type (this series)")
                RadioItem("Default (${p.imageScale.get().label})", scale == null) {
                    scale = null; PerMangaSettings.setScale(manga.id, null); onPerMangaChanged()
                }
                ImageScale.entries.forEach { sc ->
                    RadioItem(sc.label, scale == sc) { scale = sc; PerMangaSettings.setScale(manga.id, sc); onPerMangaChanged() }
                }
            }
            1 -> {
                val bg by p.readerBackground.changes().collectAsState(p.readerBackground.get())
                HeadingItem("Background color")
                ReaderBackground.entries.forEach { b -> RadioItem(b.label, bg == b) { p.readerBackground.set(b) } }
                listOf(
                    "Show page number" to p.showPageNumber,
                    "Animate page transitions" to p.pageTransitions,
                    "Always show chapter transition" to p.alwaysShowChapterTransition,
                    "Skip chapters marked read" to p.skipRead,
                    "Skip duplicate chapters" to p.skipDupe,
                ).forEach { (label, pref) ->
                    val v by pref.changes().collectAsState(pref.get())
                    CheckboxItem(label, v) { pref.set(!v) }
                }
            }
            2 -> {
                val scale by p.imageScale.changes().collectAsState(p.imageScale.get())
                HeadingItem("Scale type")
                ImageScale.entries.forEach { s -> RadioItem(s.label, scale == s) { p.imageScale.set(s) } }
                val nav by p.pagedTappingLayout.changes().collectAsState(p.pagedTappingLayout.get())
                HeadingItem("Tap zones")
                TappingLayout.entries.forEach { t -> RadioItem(t.label, nav == t) { p.pagedTappingLayout.set(t) } }
                val inv by p.invertTapping.changes().collectAsState(p.invertTapping.get())
                CheckboxItem("Invert tap zones", inv) { p.invertTapping.set(!inv) }
                val dual by p.dualPageSpread.changes().collectAsState(p.dualPageSpread.get())
                HeadingItem("Two-page spread (left to right / right to left)")
                DualPageSpread.entries.forEach { d -> RadioItem(d.label, dual == d) { p.dualPageSpread.set(d) } }
                val shift by p.dualPageShift.changes().collectAsState(p.dualPageShift.get())
                CheckboxItem("Shift spread by one page", shift) { p.dualPageShift.set(!shift) }
            }
            else -> {
                val pad by p.webtoonSidePadding.changes().collectAsState(p.webtoonSidePadding.get())
                SliderPreferenceWidget("Side padding", pad, 0..25, "$pad%") { p.webtoonSidePadding.set(it) }
                val nav by p.webtoonTappingLayout.changes().collectAsState(p.webtoonTappingLayout.get())
                HeadingItem("Tap zones")
                TappingLayout.entries.forEach { t -> RadioItem(t.label, nav == t) { p.webtoonTappingLayout.set(t) } }
            }
        }
    }
}

/** Image filters, scaling quality, sharpening and page width (shared by the reader sheet and settings). */
@Composable
fun ReaderFiltersTab() {
    val p = Inkoma.preferences
    val q by p.scalingQuality.changes().collectAsState(p.scalingQuality.get())
    HeadingItem("Image quality")
    inkoma.core.ScalingQuality.entries.forEach { RadioItem(it.label, q == it) { p.scalingQuality.set(it) } }
    val sharp by p.sharpening.changes().collectAsState(p.sharpening.get())
    SliderPreferenceWidget("Sharpening", sharp, 0..10, if (sharp == 0) "Off" else "$sharp") {
        p.sharpening.set(it)
        PageLoader.clear()
    }
    val width by p.readerMaxWidth.changes().collectAsState(p.readerMaxWidth.get())
    SliderPreferenceWidget("Maximum page width", width / 10, 3..10, "${width}%") { p.readerMaxWidth.set(it * 10) }
    HeadingItem("Filters")
    val bright by p.filterBrightness.changes().collectAsState(p.filterBrightness.get())
    SliderPreferenceWidget("Brightness", bright / 10 + 10, 0..20, if (bright > 0) "+$bright" else "$bright") { p.filterBrightness.set((it - 10) * 10) }
    val contrast by p.filterContrast.changes().collectAsState(p.filterContrast.get())
    SliderPreferenceWidget("Contrast", contrast / 10 + 10, 0..20, if (contrast > 0) "+$contrast" else "$contrast") { p.filterContrast.set((it - 10) * 10) }
    val warm by p.filterWarmth.changes().collectAsState(p.filterWarmth.get())
    SliderPreferenceWidget("Night tint", warm / 10, 0..10, if (warm == 0) "Off" else "$warm%") { p.filterWarmth.set(it * 10) }
    val gray by p.filterGrayscale.changes().collectAsState(p.filterGrayscale.get())
    CheckboxItem("Grayscale", gray) { p.filterGrayscale.set(!gray) }
    val inv by p.filterInvert.changes().collectAsState(p.filterInvert.get())
    CheckboxItem("Invert colours", inv) { p.filterInvert.set(!inv) }
    androidx.compose.material3.TextButton(
        onClick = {
            p.filterBrightness.set(0); p.filterContrast.set(0); p.filterWarmth.set(0)
            p.filterGrayscale.set(false); p.filterInvert.set(false)
        },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) { Text("Reset filters") }
}
