package inkoma.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import inkoma.Inkoma
import inkoma.backup.BackupOptions
import inkoma.backup.BackupState
import inkoma.core.AppDirs
import inkoma.core.AppPreferences
import inkoma.core.BuildInfo
import inkoma.core.ChapterSort
import inkoma.core.DualPageSpread
import inkoma.core.ImageScale
import inkoma.core.ReaderBackground
import inkoma.core.ReadingMode
import inkoma.core.TappingLayout
import inkoma.core.ThemeMode
import inkoma.ui.LocalSnackbar
import inkoma.ui.browse.ExtensionStoresScreen
import inkoma.ui.browse.pickDirectory
import inkoma.ui.browse.pickFile
import inkoma.ui.browse.pickSaveFile
import inkoma.ui.category.CategoryScreen
import inkoma.ui.components.AppBar
import inkoma.ui.components.ConfirmDialog
import inkoma.ui.components.EditTextPreferenceWidget
import inkoma.ui.components.ImageLoader
import inkoma.ui.components.LabeledCheckbox
import inkoma.ui.components.ListPreference
import inkoma.ui.components.ListPreferenceWidget
import inkoma.ui.components.MultiSelectListPreferenceWidget
import inkoma.ui.components.PreferenceGroupHeader
import inkoma.ui.components.SliderPreferenceWidget
import inkoma.ui.components.SwitchPreference
import inkoma.ui.components.TextPreferenceWidget
import inkoma.ui.components.TriStateListDialogPreference
import inkoma.ui.navigation.LocalNavigator
import inkoma.ui.navigation.Screen
import inkoma.ui.theme.InkomaIcons
import java.io.File

/** Base for a scrolling settings page with a back-navigating app bar. */
abstract class SettingsPage(private val title: String) : Screen() {
    @Composable
    abstract fun Items()

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        Scaffold(topBar = { AppBar(title = title, navigateUp = { navigator.pop() }) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) { Items() }
        }
    }
}

class SettingsMainScreen : SettingsPage("Settings") {
    @Composable
    override fun Items() {
        val navigator = LocalNavigator.current
        TextPreferenceWidget("Appearance", "Theme, date & time format", InkomaIcons.Palette) { navigator.push(SettingsAppearanceScreen()) }
        TextPreferenceWidget("Library", "Categories, global update, chapter swipe", InkomaIcons.Library) { navigator.push(SettingsLibraryScreen()) }
        TextPreferenceWidget("Reader", "Reading mode, display, navigation", InkomaIcons.Reader) { navigator.push(SettingsReaderScreen()) }
        TextPreferenceWidget("Downloads", "Automatic download, download ahead", InkomaIcons.GetApp) { navigator.push(SettingsDownloadScreen()) }
        TextPreferenceWidget("Browse", "Sources, extensions, global search", InkomaIcons.Browse) { navigator.push(SettingsBrowseScreen()) }
        TextPreferenceWidget("Data and storage", "Manual & automatic backups, storage space", InkomaIcons.Storage) { navigator.push(SettingsDataScreen()) }
        TextPreferenceWidget("Advanced", "Dump crash logs, battery optimizations", InkomaIcons.Code) { navigator.push(SettingsAdvancedScreen()) }
        TextPreferenceWidget("About", "Inkoma ${BuildInfo.versionName}", InkomaIcons.Info) { navigator.push(AboutScreen()) }
    }
}

class SettingsAppearanceScreen : SettingsPage("Appearance") {
    @Composable
    override fun Items() {
        val p = Inkoma.preferences
        PreferenceGroupHeader("Theme")
        ListPreference(p.themeMode, "Theme mode", listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"))
        ListPreference(p.appTheme, "App theme", inkoma.ui.theme.AppTheme.entries.map { it to it.label })
        val appTheme by p.appTheme.changes().collectAsState(p.appTheme.get())
        if (appTheme == inkoma.ui.theme.AppTheme.CUSTOM) CustomColorPicker()
        SwitchPreference(p.coverColors, "Cover-based colours", "Entry pages and the reader take their colours from the cover")
        SwitchPreference(p.themeDarkAmoled, "Pure black dark mode")
        PreferenceGroupHeader("Display")
        ListPreference(
            p.dateFormat, "Date format",
            listOf("" to "Default", "MM/dd/yy" to "MM/dd/yy", "dd/MM/yy" to "dd/MM/yy", "yyyy-MM-dd" to "yyyy-MM-dd", "dd MMM yyyy" to "dd MMM yyyy", "MMM dd, yyyy" to "MMM dd, yyyy"),
        )
        SwitchPreference(p.relativeTime, "Relative timestamps", "\"Today\" instead of the date")
        PreferenceGroupHeader("Layout")
        SwitchPreference(p.twoPaneManga, "Two-pane entry page", "On wide windows show details and chapters side by side")
        SwitchPreference(p.panoramaCovers, "Panorama covers", "Show wide covers in full instead of cropping them")
        ListPreference(p.resumeButtonPosition, "Read/Resume button position", inkoma.core.FabPosition.entries.map { it to it.label })
        SwitchPreference(p.groupUpdates, "Group updates by entry", "Collapse several new chapters of one entry in Updates")
    }
}

class SettingsLibraryScreen : SettingsPage("Library") {
    @Composable
    override fun Items() {
        val navigator = LocalNavigator.current
        val p = Inkoma.preferences
        val categories by remember { Inkoma.categoryRepository.observeAll() }.collectAsState(emptyList())
        val userCats = categories.filterNot { it.isSystemCategory }
        PreferenceGroupHeader("Categories")
        TextPreferenceWidget("Edit categories", "${userCats.size} categories") { navigator.push(CategoryScreen()) }
        ListPreference(p.defaultCategory, "Default category", listOf(-1 to "Always ask", 0 to "Default") + userCats.map { it.id.toInt() to it.name })
        PreferenceGroupHeader("Global update")
        ListPreference(
            p.libraryUpdateInterval, "Automatic updates",
            listOf(0 to "Off", 12 to "Every 12 hours", 24 to "Daily", 48 to "Every 2 days", 72 to "Every 3 days", 168 to "Weekly"),
        )
        val restrictions by p.libraryUpdateMangaRestriction.changes().collectAsState(p.libraryUpdateMangaRestriction.get())
        val restrictionEntries = listOf(
            AppPreferences.MANGA_HAS_UNREAD to "With unread chapter(s)",
            AppPreferences.MANGA_NON_READ to "With \"Not started\" status",
            AppPreferences.MANGA_NON_COMPLETED to "With \"Completed\" status",
        )
        MultiSelectListPreferenceWidget(
            "Skip updating entries",
            restrictionEntries.filter { it.first in restrictions }.joinToString { it.second }.ifEmpty { "None" },
            restrictions, restrictionEntries,
        ) { p.libraryUpdateMangaRestriction.set(it) }
        val inc by p.libraryUpdateCategories.changes().collectAsState(p.libraryUpdateCategories.get())
        val exc by p.libraryUpdateCategoriesExclude.changes().collectAsState(p.libraryUpdateCategoriesExclude.get())
        val catEntries = categories.map { it.id.toString() to (if (it.isSystemCategory) "Default" else it.name) }
        TriStateListDialogPreference(
            "Categories",
            "Include: ${catEntries.filter { it.first in inc }.joinToString { it.second }.ifEmpty { "All" }}\nExclude: ${catEntries.filter { it.first in exc }.joinToString { it.second }.ifEmpty { "None" }}",
            catEntries, inc, exc,
        ) { i, e ->
            p.libraryUpdateCategories.set(i)
            p.libraryUpdateCategoriesExclude.set(e)
        }
        SwitchPreference(p.autoUpdateMetadata, "Automatically refresh metadata", "Check for new cover and details when updating library")
        SwitchPreference(p.newShowUpdatesCount, "Show unread count on Updates icon")
        PreferenceGroupHeader("Chapters")
        ListPreference(p.chapterSort, "Default chapter sort", listOf(ChapterSort.SOURCE to "By source", ChapterSort.NUMBER to "By chapter number", ChapterSort.UPLOAD_DATE to "By upload date", ChapterSort.ALPHABET to "By alphabet"))
        SwitchPreference(p.chapterSortDescending, "Sort descending")
        val dup by p.markDuplicateReadChapterAsRead.changes().collectAsState(p.markDuplicateReadChapterAsRead.get())
        MultiSelectListPreferenceWidget(
            "Mark duplicate read chapter as read",
            if (dup.isEmpty()) "Never" else "After fetching new chapter",
            dup, listOf("new" to "After fetching new chapter"),
        ) { p.markDuplicateReadChapterAsRead.set(it) }
    }
}

class SettingsReaderScreen : SettingsPage("Reader") {
    @Composable
    override fun Items() {
        val p = Inkoma.preferences
        ListPreference(p.defaultReadingMode, "Default reading mode", ReadingMode.entries.map { it to it.label })
        SwitchPreference(p.autoWebtoon, "Detect webtoons automatically", "Use webtoon mode for long vertical strips when a series has no reading mode set")
        SwitchPreference(p.pageTransitions, "Animate page transitions")
        PreferenceGroupHeader("Display")
        ListPreference(p.readerBackground, "Background color", ReaderBackground.entries.map { it to it.label })
        SwitchPreference(p.fullscreen, "Fullscreen")
        SwitchPreference(p.showPageNumber, "Show page number")
        PreferenceGroupHeader("Reading")
        SwitchPreference(p.skipRead, "Skip chapters marked read")
        SwitchPreference(p.skipDupe, "Skip duplicate chapters")
        SwitchPreference(p.alwaysShowChapterTransition, "Always show chapter transition")
        PreferenceGroupHeader("Paged")
        ListPreference(p.pagedTappingLayout, "Tap zones", TappingLayout.entries.map { it to it.label })
        SwitchPreference(p.invertTapping, "Invert tap zones")
        ListPreference(p.imageScale, "Scale type", ImageScale.entries.map { it to it.label })
        ListPreference(p.dualPageSpread, "Two-page spread", DualPageSpread.entries.map { it to it.label })
        SwitchPreference(p.dualPageShift, "Shift spread by one page")
        PreferenceGroupHeader("Webtoon")
        ListPreference(p.webtoonTappingLayout, "Tap zones", TappingLayout.entries.map { it to it.label })
        val pad by p.webtoonSidePadding.changes().collectAsState(p.webtoonSidePadding.get())
        SliderPreferenceWidget("Side padding", pad, 0..25, "$pad%") { p.webtoonSidePadding.set(it) }
        PreferenceGroupHeader("Image")
        inkoma.ui.reader.ReaderFiltersTab()
        PreferenceGroupHeader("Navigation")
        val navigator = LocalNavigator.current
        TextPreferenceWidget("Keyboard shortcuts", "Customise reader keys • Ctrl+wheel zoom • Esc back") { navigator.push(KeyBindingsScreen()) }
    }
}

class SettingsDownloadScreen : SettingsPage("Downloads") {
    @Composable
    override fun Items() {
        val p = Inkoma.preferences
        val snackbar = LocalSnackbar.current
        val scope = rememberCoroutineScope()
        val dir by p.downloadsDirectory.changes().collectAsState(p.downloadsDirectory.get())
        TextPreferenceWidget("Download location", dir) {
            pickDirectory("Download location")?.let { p.downloadsDirectory.set(it.absolutePath) }
        }
        SwitchPreference(p.saveChaptersAsCbz, "Save as CBZ archive")
        SliderPreferenceWidget("Concurrent source downloads", p.parallelSourceLimit.changes().collectAsState(p.parallelSourceLimit.get()).value, 1..10, "") { p.parallelSourceLimit.set(it) }
        SliderPreferenceWidget("Concurrent page downloads", p.parallelPageLimit.changes().collectAsState(p.parallelPageLimit.get()).value, 1..10, "") { p.parallelPageLimit.set(it) }
        PreferenceGroupHeader("Delete chapters")
        SwitchPreference(p.removeAfterMarkedAsRead, "After manually marked as read")
        ListPreference(
            p.removeAfterReadSlots, "After reading automatically delete",
            listOf(-1 to "Disabled", 0 to "Last read chapter", 1 to "Second to last chapter", 2 to "Third to last chapter", 3 to "Fourth to last chapter", 4 to "Fifth to last chapter"),
        )
        SwitchPreference(p.removeBookmarkedChapters, "Allow deleting bookmarked chapters")
        PreferenceGroupHeader("Auto-download")
        SwitchPreference(p.downloadNewChapters, "Download new chapters")
        SwitchPreference(p.downloadNewUnreadChaptersOnly, "Only download unread chapters")
        val categories by remember { Inkoma.categoryRepository.observeAll() }.collectAsState(emptyList())
        val catEntries = categories.map { it.id.toString() to (if (it.isSystemCategory) "Default" else it.name) }
        val inc by p.downloadNewChapterCategories.changes().collectAsState(p.downloadNewChapterCategories.get())
        val exc by p.downloadNewChapterCategoriesExclude.changes().collectAsState(p.downloadNewChapterCategoriesExclude.get())
        TriStateListDialogPreference(
            "Categories",
            "Include: ${catEntries.filter { it.first in inc }.joinToString { it.second }.ifEmpty { "All" }}\nExclude: ${catEntries.filter { it.first in exc }.joinToString { it.second }.ifEmpty { "None" }}",
            catEntries, inc, exc,
        ) { i, e ->
            p.downloadNewChapterCategories.set(i)
            p.downloadNewChapterCategoriesExclude.set(e)
        }
        PreferenceGroupHeader("Download ahead")
        ListPreference(p.autoDownloadWhileReading, "Auto download while reading", listOf(0 to "Disabled", 2 to "Next 2 chapters", 3 to "Next 3 chapters", 5 to "Next 5 chapters", 10 to "Next 10 chapters"))
        TextPreferenceWidget("Only works on entries in library and if the current chapter plus the next one are already downloaded")
        LaunchedEffect(Unit) { if (!File(dir).exists() && !File(dir).mkdirs()) scope.launch { snackbar.showSnackbar("Download location is not writable") } }
    }
}

class SettingsBrowseScreen : SettingsPage("Browse") {
    @Composable
    override fun Items() {
        val navigator = LocalNavigator.current
        val p = Inkoma.preferences
        val stores by remember { Inkoma.extensionStoreRepository.observeAll() }.collectAsState(emptyList())
        PreferenceGroupHeader("Extensions")
        TextPreferenceWidget("Extension stores", "${stores.size} stores") { navigator.push(ExtensionStoresScreen()) }
        var revoke by remember { mutableStateOf(false) }
        TextPreferenceWidget("Revoke trusted unknown extensions") { revoke = true }
        if (revoke) ConfirmDialog("Revoke trusted extensions", "This will require re-trusting any unknown extensions.", onConfirm = { Inkoma.extensionManager.revokeAllTrust() }, onDismiss = { revoke = false })
        PreferenceGroupHeader("Global search")
        SwitchPreference(p.hideInLibraryItems, "Hide entries already in library")
        PreferenceGroupHeader("NSFW (18+) sources")
        SwitchPreference(p.showNsfwSource, "Show in sources and extensions lists", "This does not prevent unofficial or potentially incorrectly flagged extensions from surfacing NSFW (18+) content within the app.")
    }
}

class SettingsDataScreen : SettingsPage("Data and storage") {
    @Composable
    override fun Items() {
        val p = Inkoma.preferences
        val snackbar = LocalSnackbar.current
        val scope = rememberCoroutineScope()
        val state by Inkoma.backupManager.state.collectAsState()
        var createDialog by remember { mutableStateOf(false) }
        var restoreFile by remember { mutableStateOf<Pair<File, Pair<List<String>, Int>>?>(null) }

        PreferenceGroupHeader("Backup and restore")
        TextPreferenceWidget("Create backup", "Can be used to restore current library") { createDialog = true }
        TextPreferenceWidget("Restore backup", "Restore library from backup file (.tachibk)") {
            val f = pickFile("Restore backup", null) ?: return@TextPreferenceWidget
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { Inkoma.backupManager.validate(f) } }
                    .onSuccess { restoreFile = f to it }
                    .onFailure { snackbar.showSnackbar(it.message ?: "Invalid backup file") }
            }
        }
        when (val s = state) {
            is BackupState.Running -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(s.label, style = MaterialTheme.typography.bodySmall)
                if (s.total > 0) LinearProgressIndicator(progress = { s.progress.toFloat() / s.total }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is BackupState.Done -> {
                LaunchedEffect(s) { snackbar.showSnackbar(s.message + if (s.errors.isNotEmpty()) " — ${s.errors.size} errors" else "") }
                if (s.errors.isNotEmpty()) TextPreferenceWidget("Restore errors", s.errors.take(10).joinToString("\n"))
            }
            is BackupState.Failed -> LaunchedEffect(s) { snackbar.showSnackbar("Error: ${s.message}") }
            BackupState.Idle -> {}
        }
        PreferenceGroupHeader("Automatic backups")
        ListPreference(p.backupInterval, "Backup frequency", listOf(0 to "Off", 6 to "Every 6 hours", 12 to "Every 12 hours", 24 to "Daily", 48 to "Every 2 days", 168 to "Weekly"))
        ListPreference(p.numberOfBackups, "Maximum backups", (1..5).map { it to it.toString() })
        TextPreferenceWidget("Backup location", AppDirs.autoBackups.absolutePath)
        val last by p.lastAutoBackup.changes().collectAsState(p.lastAutoBackup.get())
        if (last > 0) TextPreferenceWidget("Last automatically backed up", inkoma.ui.components.relativeDateText(last) + " " + inkoma.ui.components.timeText(last))

        PreferenceGroupHeader("Storage usage")
        var cacheSize by remember { mutableStateOf<Long?>(null) }
        var downloadsSize by remember { mutableStateOf<Long?>(null) }
        LaunchedEffect(state) {
            withContext(Dispatchers.IO) {
                cacheSize = ImageLoader.diskCacheSize() + File(AppDirs.cache, "network_cache").walkTopDown().filter { it.isFile }.sumOf { it.length() }
                downloadsSize = Inkoma.downloadManager.totalDownloadSize()
            }
        }
        TextPreferenceWidget("Downloads", downloadsSize?.let { formatSize(it) } ?: "…")
        TextPreferenceWidget("Clear chapter cache", "Used: ${cacheSize?.let { formatSize(it) } ?: "…"}") {
            scope.launch(Dispatchers.IO) {
                ImageLoader.clearDisk()
                File(AppDirs.cache, "network_cache").deleteRecursively()
                cacheSize = 0
                snackbar.showSnackbar("Cache cleared")
            }
        }

        if (createDialog) {
            var opts by remember { mutableStateOf(BackupOptions()) }
            AlertDialog(
                onDismissRequest = { createDialog = false },
                title = { Text("Create backup") },
                text = {
                    Column {
                        LabeledCheckbox("Library entries", opts.libraryEntries) { opts = opts.copy(libraryEntries = it) }
                        LabeledCheckbox("Categories", opts.categories) { opts = opts.copy(categories = it) }
                        LabeledCheckbox("Chapters", opts.chapters) { opts = opts.copy(chapters = it) }
                        LabeledCheckbox("History", opts.history) { opts = opts.copy(history = it) }
                        LabeledCheckbox("Read entries", opts.readEntries) { opts = opts.copy(readEntries = it) }
                        LabeledCheckbox("App settings", opts.appSettings) { opts = opts.copy(appSettings = it) }
                        LabeledCheckbox("Extension stores", opts.extensionStores) { opts = opts.copy(extensionStores = it) }
                        LabeledCheckbox("Source settings", opts.sourceSettings) { opts = opts.copy(sourceSettings = it) }
                        LabeledCheckbox("Include sensitive settings", opts.privateSettings) { opts = opts.copy(privateSettings = it) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        createDialog = false
                        pickSaveFile("Create backup", Inkoma.backupManager.filename())?.let { Inkoma.backupManager.createBackup(it, opts) }
                    }) { Text("Create") }
                },
                dismissButton = { TextButton(onClick = { createDialog = false }) { Text("Cancel") } },
            )
        }
        restoreFile?.let { (file, info) ->
            val (missing, count) = info
            AlertDialog(
                onDismissRequest = { restoreFile = null },
                title = { Text("Restore backup") },
                text = {
                    Text(
                        "$count entries will be restored." +
                            if (missing.isNotEmpty()) "\n\nMissing sources (install the extensions to see these entries):\n" + missing.joinToString("\n") { "• $it" } else "",
                    )
                },
                confirmButton = { TextButton(onClick = { Inkoma.backupManager.restoreBackup(file); restoreFile = null }) { Text("Restore") } },
                dismissButton = { TextButton(onClick = { restoreFile = null }) { Text("Cancel") } },
            )
        }
    }
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.2f %s".format(v, units[i])
}

class SettingsAdvancedScreen : SettingsPage("Advanced") {
    @Composable
    override fun Items() {
        val p = Inkoma.preferences
        val snackbar = LocalSnackbar.current
        val scope = rememberCoroutineScope()
        PreferenceGroupHeader("Network")
        TextPreferenceWidget("Clear cookies") {
            Inkoma.network.cookieJar.removeAll()
            scope.launch { snackbar.showSnackbar("Cookies cleared") }
        }
        val doh by p.dohProvider.changes().collectAsState(p.dohProvider.get())
        ListPreferenceWidget(
            "DNS over HTTPS (DoH)", doh, NetworkHelper.DOH_PROVIDERS,
            subtitle = (NetworkHelper.DOH_PROVIDERS.firstOrNull { it.first == doh }?.second ?: "") +
                ". Gets around sites being blocked by your network's DNS; applies to sources and the built-in browser.",
        ) {
            p.dohProvider.set(it)
            inkoma.core.net.DnsSettings.provider = it
        }
        val ua by p.defaultUserAgent.changes().collectAsState(p.defaultUserAgent.get())
        EditTextPreferenceWidget("Default user agent string", ua, ua) { v ->
            if (v.isBlank()) false else { p.defaultUserAgent.set(v.trim()); true }
        }
        TextPreferenceWidget("Reset default user agent string") { p.defaultUserAgent.delete() }
        SwitchPreference(p.verboseLogging, "Verbose logging", "Print verbose logs to the system log (requires restart)")
        PreferenceGroupHeader("Desktop")
        SwitchPreference(p.gpuRendering, "GPU rendering", "Smoother scrolling using OpenGL; uses more memory. Requires restart.")
        SwitchPreference(p.desktopNotifications, "Desktop notifications", "Notify when library updates and downloads finish")
        var menuInstalled by remember { mutableStateOf(inkoma.core.DesktopIntegration.isMenuEntryInstalled()) }
        if (inkoma.core.DesktopIntegration.appImagePath != null || menuInstalled) {
            TextPreferenceWidget(
                if (menuInstalled) "Remove from applications menu" else "Add to applications menu",
                "Show Inkoma in the desktop's application menu",
            ) {
                if (menuInstalled) {
                    inkoma.core.DesktopIntegration.removeMenuEntry()
                    menuInstalled = false
                } else {
                    inkoma.core.DesktopIntegration.installMenuEntry()
                        .onSuccess { menuInstalled = true }
                        .onFailure { e -> scope.launch { snackbar.showSnackbar(e.message ?: "Failed") } }
                }
            }
        }
        TextPreferenceWidget("Show setup again", "Re-run the first-launch setup on next start") { p.onboardingComplete.set(false) }
        PreferenceGroupHeader("Data")
        var clearDb by remember { mutableStateOf(false) }
        TextPreferenceWidget("Clear database", "Delete history for entries that are not saved in your library") { clearDb = true }
        if (clearDb) {
            ConfirmDialog("Clear database", "Are you sure? Read chapters and progress of non-library entries will be lost.", onConfirm = {
                scope.launch(Dispatchers.IO) {
                    val n = Inkoma.mangaRepository.deleteNonLibrary(null)
                    snackbar.showSnackbar("Entries deleted: $n")
                }
            }, onDismiss = { clearDb = false })
        }
        TextPreferenceWidget("Refresh library covers") {
            scope.launch(Dispatchers.IO) {
                Inkoma.mangaRepository.getFavorites().forEach { m -> m.thumbnailUrl?.let { ImageLoader.invalidate(it) } }
                snackbar.showSnackbar("Covers will be refreshed")
            }
        }
        PreferenceGroupHeader("Extensions")
        TextPreferenceWidget("Re-translate installed extensions", "Re-run the Dalvik → JVM conversion for all installed extensions") {
            scope.launch(Dispatchers.IO) {
                AppDirs.extensions.listFiles { f -> f.name.endsWith(".jar") }?.forEach { it.delete() }
                Inkoma.extensionManager.loadInstalled()
                snackbar.showSnackbar("Extensions reloaded")
            }
        }
    }
}

class AboutScreen : SettingsPage("About") {
    @Composable
    override fun Items() {
        val navigator = LocalNavigator.current
        Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("Inkoma", style = MaterialTheme.typography.headlineLarge)
        }
        TextPreferenceWidget("Based on Mihon", "Built on Mihon's open source code and compatible with its extensions and backups. Not affiliated with or endorsed by the Mihon project.")
        TextPreferenceWidget("Version", "${BuildInfo.versionName} (Linux desktop)")
        TextPreferenceWidget("Extension API", "extensions-lib 1.4 and 1.6")
        TextPreferenceWidget("Data directory", AppDirs.data.absolutePath)
        TextPreferenceWidget("Website", "https://github.com/jrypzm/inkoma") { navigator.push(inkoma.ui.browser.BrowserScreen("https://github.com/jrypzm/inkoma", "Inkoma")) }
        TextPreferenceWidget("Open source licenses", "Apache License 2.0 (Mihon, Tachiyomi, dex2jar), SIL OFL 1.1 (Roboto)")
    }
}

/** Colour picker for the custom app theme: preset swatches plus hue/saturation/lightness sliders. */
@Composable
private fun CustomColorPicker() {
    val p = Inkoma.preferences
    val seedArgb by p.customThemeSeed.changes().collectAsState(p.customThemeSeed.get())
    val seed = androidx.compose.ui.graphics.Color(seedArgb)
    val hsl = inkoma.ui.theme.SeedColors.toHsl(seed)
    fun set(h: Float, s: Float, l: Float) = p.customThemeSeed.set(inkoma.ui.theme.SeedColors.argb(inkoma.ui.theme.SeedColors.fromHsl(h, s, l)))
    val presets = listOf(0xFF0058CA, 0xFF6750A4, 0xFFB3261E, 0xFF006E1B, 0xFFE8740C, 0xFF00897B, 0xFFD81B60, 0xFF5D4037, 0xFF455A64)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        presets.forEach { c ->
            Box(
                Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(c))
                    .clickable { p.customThemeSeed.set(c.toInt()) },
            )
        }
    }
    SliderPreferenceWidget("Hue", hsl.h.toInt() / 10, 0..35, "${hsl.h.toInt()}°") { set(it * 10f, hsl.s, hsl.l) }
    SliderPreferenceWidget("Saturation", (hsl.s * 10).toInt(), 1..10, "${(hsl.s * 100).toInt()}%") { set(hsl.h, it / 10f, hsl.l) }
    SliderPreferenceWidget("Lightness", (hsl.l * 10).toInt(), 2..8, "${(hsl.l * 100).toInt()}%") { set(hsl.h, hsl.s, it / 10f) }
}

/** Settings › Reader › Keyboard shortcuts: click an action, then press the key to assign. */
class KeyBindingsScreen : SettingsPage("Keyboard shortcuts") {
    @Composable
    override fun Items() {
        val p = Inkoma.preferences
        val raw by p.keyBindings.changes().collectAsState(p.keyBindings.get())
        val bindings = inkoma.ui.reader.KeyBindings.parse(raw)
        var capturing by remember { mutableStateOf<inkoma.ui.reader.ReaderAction?>(null) }
        inkoma.ui.reader.ReaderAction.entries.forEach { action ->
            TextPreferenceWidget(action.label, bindings.getValue(action).joinToString(", ") { inkoma.ui.reader.KeyBindings.keyName(it) }.ifEmpty { "None" }) {
                capturing = action
            }
        }
        TextPreferenceWidget("Reset to defaults") { p.keyBindings.set("") }
        capturing?.let { action ->
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            AlertDialog(
                onDismissRequest = { capturing = null },
                title = { Text(action.label) },
                text = {
                    Box(
                        Modifier.fillMaxWidth().focusRequester(focus).focusable().onPreviewKeyEvent { e ->
                            if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                                // Escape cancels; any other key replaces the binding for this action.
                                if (e.key != androidx.compose.ui.input.key.Key.Escape) {
                                    val updated = bindings.toMutableMap()
                                    updated.keys.forEach { a -> if (a != action) updated[a] = updated.getValue(a) - e.key }
                                    updated[action] = listOf(e.key)
                                    p.keyBindings.set(inkoma.ui.reader.KeyBindings.serialize(updated))
                                }
                                capturing = null
                                true
                            } else {
                                false
                            }
                        },
                    ) { Text("Press a key… (Esc to cancel)") }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = {
                        val updated = bindings.toMutableMap()
                        updated[action] = action.defaults
                        p.keyBindings.set(inkoma.ui.reader.KeyBindings.serialize(updated))
                        capturing = null
                    }) { Text("Default") }
                },
            )
        }
    }
}
