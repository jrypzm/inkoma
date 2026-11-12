package inkoma.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import inkoma.Inkoma
import inkoma.domain.HistoryWithRelations
import inkoma.ui.LocalSnackbar
import inkoma.ui.components.BOOK_RATIO
import inkoma.ui.components.EmptyScreen
import inkoma.ui.components.LabeledCheckbox
import inkoma.ui.components.ListGroupHeader
import inkoma.ui.components.MangaCover
import inkoma.ui.components.SearchToolbar
import inkoma.ui.components.formatChapterNumber
import inkoma.ui.components.relativeDateText
import inkoma.ui.components.timeText
import inkoma.ui.components.toDateKey
import inkoma.ui.manga.MangaScreen
import inkoma.ui.navigation.LocalNavigator
import inkoma.ui.reader.ReaderScreen
import inkoma.ui.theme.InkomaIcons

class HistoryTab {
    private val query = MutableStateFlow<String?>(null)

    @Composable
    fun Content() {
        val navigator = LocalNavigator.current
        val snackbar = LocalSnackbar.current
        val scope = rememberCoroutineScope()
        val search by query.collectAsState()
        val history by remember { query.flatMapLatest { Inkoma.historyRepository.observeHistory(it.orEmpty()) } }.collectAsState(null)
        var removeTarget by remember { mutableStateOf<HistoryWithRelations?>(null) }
        var clearAll by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                SearchToolbar(
                    title = "History",
                    searchQuery = search,
                    onChangeSearchQuery = { query.value = it },
                    actions = { IconButton(onClick = { clearAll = true }) { Icon(InkomaIcons.Delete, "Clear history") } },
                )
            },
        ) { padding ->
            val list = history
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    list == null -> {}
                    list.isEmpty() -> EmptyScreen(if (search.isNullOrEmpty()) "Nothing read recently" else "No results found")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        list.groupBy { it.readAt.toDateKey() }.forEach { (day, rows) ->
                            item(key = "h$day") { ListGroupHeader(relativeDateText(day, Inkoma.preferences.relativeTime.get(), Inkoma.preferences.dateFormat.get())) }
                            items(rows, key = { it.id }) { h ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { navigator.push(MangaScreen(h.mangaId)) }.height(96.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MangaCover(h.thumbnailUrl, h.source, Modifier.height(80.dp).width(80.dp * BOOK_RATIO), h.coverLastModified, persistent = h.favorite)
                                    Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
                                        Text(h.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        val ch = if (h.chapterNumber >= 0) "Ch. ${formatChapterNumber(h.chapterNumber)} - " else ""
                                        Text("$ch${timeText(h.readAt)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                                    }
                                    IconButton(onClick = { removeTarget = h }) { Icon(InkomaIcons.Delete, "Delete") }
                                    IconButton(onClick = {
                                        scope.launch {
                                            val next = withContext(Dispatchers.IO) {
                                                val chs = Inkoma.chapterRepository.getByMangaId(h.mangaId).sortedByDescending { it.sourceOrder }
                                                val cur = chs.firstOrNull { it.id == h.chapterId }
                                                if (cur != null && !cur.read) cur else chs.dropWhile { it.id != h.chapterId }.drop(1).firstOrNull()
                                            }
                                            if (next != null) navigator.push(ReaderScreen(h.mangaId, next.id)) else snackbar.showSnackbar("No next chapter")
                                        }
                                    }) { Icon(InkomaIcons.PlayArrow, "Resume") }
                                }
                            }
                        }
                    }
                }
            }
        }

        removeTarget?.let { target ->
            var all by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { removeTarget = null },
                title = { Text("Remove") },
                text = {
                    Column {
                        Text("This will remove the read date of this chapter. Are you sure?")
                        LabeledCheckbox("Reset all chapters for this entry", all) { all = it }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (all) Inkoma.historyRepository.resetForManga(target.mangaId) else Inkoma.historyRepository.resetLastRead(target.id)
                        removeTarget = null
                    }) { Text("Remove") }
                },
                dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } },
            )
        }
        if (clearAll) {
            AlertDialog(
                onDismissRequest = { clearAll = false },
                title = { Text("Remove everything") },
                text = { Text("Are you sure? All history will be lost.") },
                confirmButton = { TextButton(onClick = { Inkoma.historyRepository.deleteAll(); clearAll = false }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { clearAll = false }) { Text("Cancel") } },
            )
        }
    }
}
