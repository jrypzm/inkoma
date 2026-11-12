package inkoma.ui.more

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import inkoma.Inkoma
import inkoma.download.Download
import inkoma.ui.components.AppBar
import inkoma.ui.components.EmptyScreen
import inkoma.ui.components.ListGroupHeader
import inkoma.ui.components.TextPreferenceWidget
import inkoma.ui.navigation.LocalNavigator
import inkoma.ui.navigation.Screen
import inkoma.ui.settings.SettingsMainScreen
import inkoma.ui.settings.SettingsDataScreen
import inkoma.ui.settings.AboutScreen
import inkoma.ui.category.CategoryScreen
import inkoma.ui.theme.InkomaIcons

class MoreTab {
    @Composable
    fun Content() {
        val navigator = LocalNavigator.current
        val queue by Inkoma.downloadManager.queue.collectAsState()
        val running by Inkoma.downloadManager.isRunning.collectAsState()
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                Text("Inkoma", style = MaterialTheme.typography.headlineMedium)
            }
            HorizontalDivider()
            TextPreferenceWidget(
                title = "Download queue",
                subtitle = when {
                    queue.isEmpty() -> null
                    running -> "Downloading (${queue.size} remaining)"
                    else -> "Paused (${queue.size} remaining)"
                },
                icon = InkomaIcons.GetApp,
                onClick = { navigator.push(DownloadQueueScreen()) },
            )
            TextPreferenceWidget("Categories", icon = InkomaIcons.Label, onClick = { navigator.push(CategoryScreen()) })
            TextPreferenceWidget("Data and storage", icon = InkomaIcons.Storage, onClick = { navigator.push(SettingsDataScreen()) })
            HorizontalDivider()
            TextPreferenceWidget("Settings", icon = InkomaIcons.Settings, onClick = { navigator.push(SettingsMainScreen()) })
            TextPreferenceWidget("About", icon = InkomaIcons.Info, onClick = { navigator.push(AboutScreen()) })
        }
    }
}

class DownloadQueueScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val queue by Inkoma.downloadManager.queue.collectAsState()
        val running by Inkoma.downloadManager.isRunning.collectAsState()
        var menu by remember { mutableStateOf(false) }
        Scaffold(
            topBar = {
                AppBar(title = "Download queue", subtitle = if (queue.isNotEmpty()) "${queue.size}" else null, navigateUp = { navigator.pop() }, actions = {
                    if (queue.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(InkomaIcons.MoreVert, "More") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Sort by upload date (newest)") }, onClick = {
                                    menu = false
                                    Inkoma.downloadManager.reorder(queue.sortedByDescending { it.chapter.dateUpload })
                                })
                                DropdownMenuItem(text = { Text("Sort by upload date (oldest)") }, onClick = {
                                    menu = false
                                    Inkoma.downloadManager.reorder(queue.sortedBy { it.chapter.dateUpload })
                                })
                                DropdownMenuItem(text = { Text("Sort by chapter number (asc)") }, onClick = {
                                    menu = false
                                    Inkoma.downloadManager.reorder(queue.sortedBy { it.chapter.chapterNumber })
                                })
                                DropdownMenuItem(text = { Text("Sort by chapter number (desc)") }, onClick = {
                                    menu = false
                                    Inkoma.downloadManager.reorder(queue.sortedByDescending { it.chapter.chapterNumber })
                                })
                                DropdownMenuItem(text = { Text("Cancel all") }, onClick = { menu = false; Inkoma.downloadManager.clearQueue() })
                            }
                        }
                    }
                })
            },
            floatingActionButton = {
                if (queue.isNotEmpty()) {
                    ExtendedFloatingActionButton(
                        text = { Text(if (running) "Pause" else "Resume") },
                        icon = { Icon(if (running) InkomaIcons.Pause else InkomaIcons.PlayArrow, null) },
                        onClick = { if (running) Inkoma.downloadManager.pauseDownloads() else Inkoma.downloadManager.startDownloads() },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (queue.isEmpty()) {
                    EmptyScreen("No downloads")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        queue.groupBy { it.sourceId }.forEach { (sid, list) ->
                            item(key = "h$sid") { ListGroupHeader("${Inkoma.sourceManager.getOrStub(sid)} (${list.size})") }
                            items(list, key = { it.chapter.id }) { d -> DownloadRow(d) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(d: Download) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d.manga.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(d.chapter.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (d.status) {
                Download.Status.DOWNLOADING -> {
                    LinearProgressIndicator(progress = { d.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    Text("${d.downloadedPages}/${d.totalPages}", style = MaterialTheme.typography.labelSmall)
                }
                Download.Status.ERROR -> Text(d.error ?: "Error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                else -> {}
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(InkomaIcons.MoreVert, "More") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Move to top") }, onClick = { menu = false; Inkoma.downloadManager.moveToTop(d) })
                DropdownMenuItem(text = { Text("Move to bottom") }, onClick = { menu = false; Inkoma.downloadManager.moveToBottom(d) })
                DropdownMenuItem(text = { Text("Cancel") }, onClick = { menu = false; Inkoma.downloadManager.cancel(listOf(d)) })
                DropdownMenuItem(text = { Text("Cancel all for this series") }, onClick = {
                    menu = false
                    Inkoma.downloadManager.cancel(Inkoma.downloadManager.queue.value.filter { it.manga.id == d.manga.id })
                })
            }
        }
    }
}
