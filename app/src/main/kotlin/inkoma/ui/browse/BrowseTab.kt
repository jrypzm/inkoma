package inkoma.ui.browse

import inkoma.ui.components.inkomaClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.CatalogueSource
import inkoma.Inkoma
import inkoma.source.LocalSource
import inkoma.ui.components.AppBar
import inkoma.ui.components.EmptyScreen
import inkoma.ui.components.LabeledCheckbox
import inkoma.ui.components.ListGroupHeader
import inkoma.ui.components.RemoteImage
import inkoma.ui.navigation.LocalNavigator
import inkoma.ui.theme.InkomaIcons
import java.io.File
import java.util.Locale

fun languageName(lang: String): String = when (lang) {
    "" -> "Other"
    "all" -> "Multi"
    "other" -> "Other"
    "last_used" -> "Last used"
    "pinned" -> "Pinned"
    else -> Locale.forLanguageTag(lang).let { loc -> loc.getDisplayName(loc).replaceFirstChar { it.titlecase(loc) } }.ifBlank { lang }
}

@Composable
fun SourceIcon(sourceId: Long, modifier: Modifier = Modifier.size(40.dp)) {
    val pkg = Inkoma.sourceManager.extensionPkgOf(sourceId)
    val icon = pkg?.let { File(inkoma.core.AppDirs.extensions, "$it.png") }?.takeIf { it.exists() }
    Box(modifier.clip(RoundedCornerShape(8.dp))) {
        if (icon != null) {
            RemoteImage(icon.toURI().toString(), null, Modifier.fillMaxSize().aspectRatio(1f), maxSide = 96)
        } else {
            Icon(if (sourceId == LocalSource.ID) InkomaIcons.Folder else InkomaIcons.Extension, null, Modifier.fillMaxSize().padding(6.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

class BrowseTab {
    private var selectedTab by mutableIntStateOf(0)

    @Composable
    fun Content() {
        val navigator = LocalNavigator.current
        val updates by Inkoma.preferences.extensionUpdatesCount.changes().collectAsState(0)
        var showLanguages by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        val extensionsTab = remember { ExtensionsTab() }
        Scaffold(
            topBar = {
                Column {
                    AppBar(
                        title = "Browse",
                        actions = {
                            if (selectedTab == 0) {
                                IconButton(onClick = { navigator.push(GlobalSearchScreen("")) }) { Icon(InkomaIcons.Search, "Global search") }
                                IconButton(onClick = { showLanguages = true }) { Icon(InkomaIcons.FilterList, "Filter") }
                            } else {
                                IconButton(onClick = { Inkoma.extensionManager.refreshAvailable() }) { Icon(InkomaIcons.Refresh, "Refresh") }
                                IconButton(onClick = { showLanguages = true }) { Icon(InkomaIcons.Translate, "Languages") }
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(InkomaIcons.MoreVert, "More") }
                                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text("Extension stores") }, onClick = { menu = false; navigator.push(ExtensionStoresScreen()) })
                                        DropdownMenuItem(text = { Text("Install from file") }, onClick = {
                                            menu = false
                                            pickFile("Select extension APK", "apk")?.let { Inkoma.extensionManager.installFromFile(it) }
                                        })
                                    }
                                }
                            }
                        },
                    )
                    PrimaryTabRow(selectedTabIndex = selectedTab) {
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Sources") })
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(if (updates > 0) "Extensions ($updates)" else "Extensions") })
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (selectedTab == 0) SourcesList() else extensionsTab.Content()
            }
        }
        if (showLanguages) LanguagesDialog { showLanguages = false }
    }

    @Composable
    private fun SourcesList() {
        val navigator = LocalNavigator.current
        val p = Inkoma.preferences
        val sources by Inkoma.sourceManager.catalogueSources.collectAsState()
        val langs by p.enabledLanguages.changes().collectAsState(p.enabledLanguages.get())
        val disabled by p.disabledSources.changes().collectAsState(p.disabledSources.get())
        val pinned by p.pinnedSources.changes().collectAsState(p.pinnedSources.get())
        val lastUsed by p.lastUsedSource.changes().collectAsState(p.lastUsedSource.get())
        val sourceCats by p.sourceCategories.changes().collectAsState(p.sourceCategories.get())
        val visible = sources.filter { (it.lang in langs || it.id == LocalSource.ID) && it.id.toString() !in disabled }
        if (visible.isEmpty()) {
            EmptyScreen("No sources found. Install extensions from the Extensions tab.")
            return
        }
        val groups = LinkedHashMap<String, List<CatalogueSource>>()
        visible.filter { it.id.toString() in pinned }.takeIf { it.isNotEmpty() }?.let { groups["pinned"] = it }
        visible.firstOrNull { it.id == lastUsed }?.let { groups["last_used"] = listOf(it) }
        // User source categories (folders), shown above the language groups.
        val catMap = SourceCategories.parse(sourceCats)
        catMap.entries.groupBy({ it.value }, { it.key }).toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, ids) ->
            visible.filter { it.id in ids }.sortedBy { it.name.lowercase() }.takeIf { it.isNotEmpty() }?.let { groups["cat:$name"] = it }
        }
        visible.groupBy { it.lang }.toSortedMap(compareBy<String> { if (it == "other") "￿" else languageName(it) })
            .forEach { (lang, list) -> groups[lang] = list.sortedBy { it.name.lowercase() } }
        LazyColumn(Modifier.fillMaxSize()) {
            groups.forEach { (lang, list) ->
                item(key = "h-$lang") { ListGroupHeader(if (lang.startsWith("cat:")) lang.removePrefix("cat:") else languageName(lang)) }
                items(list, key = { "$lang-${it.id}" }) { source ->
                    SourceRow(source, isPinned = source.id.toString() in pinned, onClick = {
                        p.lastUsedSource.set(source.id)
                        navigator.push(BrowseSourceScreen(source.id, listing = BrowseSourceScreen.Listing.Popular))
                    }, onLatest = {
                        p.lastUsedSource.set(source.id)
                        navigator.push(BrowseSourceScreen(source.id, listing = BrowseSourceScreen.Listing.Latest))
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceRow(source: CatalogueSource, isPinned: Boolean, onClick: () -> Unit, onLatest: () -> Unit) {
    val p = Inkoma.preferences
    var menu by remember { mutableStateOf(false) }
    var categoryDialog by remember { mutableStateOf(false) }
    if (categoryDialog) SourceCategoryDialog(source.id) { categoryDialog = false }
    Box {
        Row(
            Modifier.fillMaxWidth().inkomaClickable(onClick = onClick, onLongClick = { menu = true }).height(64.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceIcon(source.id)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(source.name, style = MaterialTheme.typography.bodyMedium)
                if (source.id != LocalSource.ID) Text(languageName(source.lang), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (source.supportsLatest) TextButton(onClick = onLatest) { Text("Latest") }
            IconButton(onClick = {
                val set = p.pinnedSources.get()
                p.pinnedSources.set(if (isPinned) set - source.id.toString() else set + source.id.toString())
            }) { Icon(InkomaIcons.PushPin, if (isPinned) "Unpin" else "Pin", tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (isPinned) "Unpin" else "Pin") }, onClick = {
                menu = false
                val set = p.pinnedSources.get()
                p.pinnedSources.set(if (isPinned) set - source.id.toString() else set + source.id.toString())
            })
            DropdownMenuItem(text = { Text("Set category") }, onClick = { menu = false; categoryDialog = true })
            if (source.id != LocalSource.ID) {
                DropdownMenuItem(text = { Text("Disable") }, onClick = {
                    menu = false
                    p.disabledSources.set(p.disabledSources.get() + source.id.toString())
                })
            }
        }
    }
}

@Composable
fun LanguagesDialog(onDismiss: () -> Unit) {
    val p = Inkoma.preferences
    val installed by Inkoma.extensionManager.installed.collectAsState()
    val available by Inkoma.extensionManager.available.collectAsState()
    val all = (installed.flatMap { e -> e.sources.map { it.lang } } + available.flatMap { e -> e.sources.map { it.lang } } + listOf("all", "en"))
        .distinct().sortedBy { languageName(it) }
    var enabled by remember { mutableStateOf(p.enabledLanguages.get()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Languages") },
        text = { LazyColumn { items(all) { lang -> LabeledCheckbox(languageName(lang), lang in enabled) { c -> enabled = if (c) enabled + lang else enabled - lang } } } },
        confirmButton = { TextButton(onClick = { p.enabledLanguages.set(enabled); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Native file chooser (AWT FileDialog uses the GTK dialog on Linux). */
fun pickFile(title: String, extension: String?): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    if (extension != null) dialog.setFilenameFilter { _, name -> name.endsWith(".$extension", ignoreCase = true) }
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return File(dialog.directory, file)
}

fun pickSaveFile(title: String, defaultName: String): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return File(dialog.directory, file)
}

fun pickDirectory(title: String): File? {
    val chooser = javax.swing.JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/** Source categories stored as "sourceId<TAB>category" entries in the source_categories preference. */
object SourceCategories {
    fun parse(raw: Set<String>): Map<Long, String> =
        raw.mapNotNull { e -> e.split('\t', limit = 2).takeIf { it.size == 2 }?.let { (id, name) -> id.toLongOrNull()?.let { it to name } } }.toMap()

    fun set(sourceId: Long, category: String?) {
        val pref = Inkoma.preferences.sourceCategories
        val kept = pref.get().filterNot { it.startsWith("$sourceId\t") }.toMutableSet()
        if (!category.isNullOrBlank()) kept += "$sourceId\t${category.trim()}"
        pref.set(kept)
    }
}

@Composable
private fun SourceCategoryDialog(sourceId: Long, onDismiss: () -> Unit) {
    val all = SourceCategories.parse(Inkoma.preferences.sourceCategories.get())
    val names = all.values.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    var selected by remember { mutableStateOf(all[sourceId]) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Source category") },
        text = {
            Column {
                inkoma.ui.components.RadioItem("None", selected == null) { selected = null }
                names.forEach { n -> inkoma.ui.components.RadioItem(n, selected == n) { selected = n } }
                androidx.compose.material3.OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it; if (it.isNotBlank()) selected = it.trim() },
                    label = { Text("New category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { SourceCategories.set(sourceId, selected); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
