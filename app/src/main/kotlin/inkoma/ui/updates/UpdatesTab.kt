package inkoma.ui.updates

import inkoma.ui.components.inkomaClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import inkoma.Inkoma
import inkoma.domain.UpdatesWithRelations
import inkoma.download.Download
import inkoma.ui.LocalSnackbar
import inkoma.ui.components.AppBar
import inkoma.ui.components.EmptyScreen
import inkoma.ui.components.ListGroupHeader
import inkoma.ui.components.MangaCover
import inkoma.ui.components.relativeDateText
import inkoma.ui.components.selectedBackground
import inkoma.ui.components.toDateKey
import inkoma.ui.manga.MangaScreen
import inkoma.ui.navigation.LocalNavigator
import inkoma.ui.reader.ReaderScreen
import inkoma.ui.theme.InkomaIcons
import java.util.concurrent.TimeUnit

class UpdatesTab {
    private val selection = mutableStateListOf<Long>()
    private val expanded = mutableStateListOf<String>()

    @Composable
    fun Content() {
        val navigator = LocalNavigator.current
        val snackbar = LocalSnackbar.current
        val scope = rememberCoroutineScope()
        val after = remember { System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90) }
        val updates by remember { Inkoma.updatesRepository.observeRecent(after) }.collectAsState(null)
        val progress by Inkoma.libraryUpdater.progress.collectAsState()
        val queue by Inkoma.downloadManager.queue.collectAsState()
        val cacheVersion by Inkoma.downloadManager.cacheVersion.collectAsState()
        val grouped by Inkoma.preferences.groupUpdates.changes().collectAsState(Inkoma.preferences.groupUpdates.get())
        val lastUpdate by Inkoma.preferences.lastLibraryUpdate.changes().collectAsState(Inkoma.preferences.lastLibraryUpdate.get())
        val list = updates
        val selected = list.orEmpty().filter { it.chapterId in selection }

        fun withEntities(items: List<UpdatesWithRelations>, block: (inkoma.domain.Manga, List<inkoma.domain.Chapter>) -> Unit) {
            scope.launch(Dispatchers.IO) {
                items.groupBy { it.mangaId }.forEach { (mid, rows) ->
                    val manga = Inkoma.mangaRepository.getById(mid) ?: return@forEach
                    val chs = rows.mapNotNull { Inkoma.chapterRepository.getById(it.chapterId) }
                    block(manga, chs)
                }
            }
        }

        Scaffold(
            topBar = {
                Column {
                    AppBar(
                        title = "Updates",
                        subtitle = if (lastUpdate > 0) "Last updated: ${relativeDateText(lastUpdate)}" else null,
                        actionModeCounter = selection.size,
                        onCancelActionMode = { selection.clear() },
                        actionModeActions = {
                            IconButton(onClick = { selection.clear(); selection.addAll(list.orEmpty().map { it.chapterId }) }) { Icon(InkomaIcons.SelectAll, "Select all") }
                        },
                        actions = {
                            IconButton(onClick = {
                                val started = Inkoma.libraryUpdater.start(null)
                                scope.launch { snackbar.showSnackbar(if (started) "Updating library" else "Update already in progress") }
                            }) { Icon(InkomaIcons.Refresh, "Update library") }
                        },
                    )
                    if (progress.running) {
                        LinearProgressIndicator(progress = { if (progress.total > 0) progress.current.toFloat() / progress.total else 0f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            bottomBar = {
                if (selection.isNotEmpty()) {
                    BottomAppBar {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            val anyUnbookmarked = selected.any { !it.bookmark }
                            IconButton(onClick = {
                                withEntities(selected) { _, chs -> Inkoma.mangaInteractor.setBookmark(chs, anyUnbookmarked) }
                                selection.clear()
                            }) { Icon(if (anyUnbookmarked) InkomaIcons.BookmarkBorder else InkomaIcons.Bookmark, "Bookmark") }
                            val anyUnread = selected.any { !it.read }
                            IconButton(onClick = {
                                withEntities(selected) { m, chs -> Inkoma.mangaInteractor.setRead(m, chs, anyUnread) }
                                selection.clear()
                            }) { Icon(if (anyUnread) InkomaIcons.DoneAll else InkomaIcons.Done, "Mark read") }
                            IconButton(onClick = {
                                withEntities(selected) { m, chs -> Inkoma.downloadManager.downloadChapters(m, chs) }
                                selection.clear()
                            }) { Icon(InkomaIcons.Download, "Download") }
                            IconButton(onClick = {
                                withEntities(selected) { m, chs -> Inkoma.downloadManager.deleteChapters(m, chs) }
                                selection.clear()
                            }) { Icon(InkomaIcons.Delete, "Delete") }
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    list == null -> {}
                    list.isEmpty() -> EmptyScreen("No recent updates")
                    else -> {
                        val queueById = queue.associateBy { it.chapter.id }
                        LazyColumn(Modifier.fillMaxSize()) {
                            list.groupBy { it.dateFetch.toDateKey() }.forEach { (day, rows) ->
                                item(key = "h$day") { ListGroupHeader(relativeDateText(day, true)) }
                                // Grouped updates (Komikku): several new chapters of one entry collapse under a header.
                                val visibleRows = if (!grouped) rows else rows.groupBy { it.mangaId }.values.flatMap { g ->
                                    if (g.size > 1 && "$day-${g.first().mangaId}" !in expanded) listOf(g.first()) else g
                                }
                                items(visibleRows, key = { it.chapterId }) { row ->
                                    val groupSize = if (grouped) rows.count { it.mangaId == row.mangaId } else 1
                                    val groupKey = "$day-${row.mangaId}"
                                    val isGroupLead = groupSize > 1 && rows.first { it.mangaId == row.mangaId }.chapterId == row.chapterId
                                    if (isGroupLead) {
                                        GroupHeader(row, groupSize, groupKey in expanded) {
                                            if (groupKey in expanded) expanded.remove(groupKey) else expanded.add(groupKey)
                                        }
                                    }
                                    val downloaded = remember(row.chapterId, cacheVersion) {
                                        val m = Inkoma.mangaRepository.getById(row.mangaId)
                                        val c = Inkoma.chapterRepository.getById(row.chapterId)
                                        m != null && c != null && Inkoma.downloadManager.findChapter(m, c) != null
                                    }
                                    UpdateRow(
                                        row, row.chapterId in selection, downloaded, queueById[row.chapterId],
                                        onClick = {
                                            if (selection.isNotEmpty()) {
                                                if (row.chapterId in selection) selection.remove(row.chapterId) else selection.add(row.chapterId)
                                            } else {
                                                navigator.push(ReaderScreen(row.mangaId, row.chapterId))
                                            }
                                        },
                                        onLongClick = { if (row.chapterId !in selection) selection.add(row.chapterId) },
                                        onCoverClick = { navigator.push(MangaScreen(row.mangaId)) },
                                        onDownload = { withEntities(listOf(row)) { m, chs -> if (downloaded) Inkoma.downloadManager.deleteChapters(m, chs) else Inkoma.downloadManager.downloadChapters(m, chs) } },
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

@Composable
private fun UpdateRow(
    row: UpdatesWithRelations,
    selected: Boolean,
    downloaded: Boolean,
    download: Download?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCoverClick: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().selectedBackground(selected).inkomaClickable(onClick = onClick, onLongClick = onLongClick)
            .height(56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(vertical = 6.dp).aspectRatio(1f).combinedClickable(onClick = onCoverClick)) {
            MangaCover(row.thumbnailUrl, row.source, Modifier.fillMaxSize(), row.coverLastModified, persistent = true)
        }
        Column(Modifier.padding(horizontal = 16.dp).weight(1f).alpha(if (row.read) 0.38f else 1f)) {
            Text(row.mangaTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.bookmark) Icon(InkomaIcons.Bookmark, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp).padding(end = 2.dp))
                Text(
                    row.chapterName + if (!row.read && row.lastPageRead > 0) " • Page ${row.lastPageRead + 1}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDownload) {
            when {
                download != null && download.status == Download.Status.DOWNLOADING ->
                    CircularProgressIndicator(progress = { download.progress / 100f }, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                download != null -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                downloaded -> Icon(InkomaIcons.CheckCircle, "Downloaded", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Icon(InkomaIcons.Download, "Download", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GroupHeader(row: UpdatesWithRelations, count: Int, isExpanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${row.mangaTitle} • $count new chapters",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(if (isExpanded) InkomaIcons.ArrowDropUp else InkomaIcons.ArrowDropDown, if (isExpanded) "Collapse" else "Expand")
    }
}
