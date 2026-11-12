package inkoma.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import android.widget.ToastBridge
import kotlinx.coroutines.launch
import inkoma.Inkoma
import inkoma.ui.browse.BrowseTab
import inkoma.ui.history.HistoryTab
import inkoma.ui.library.LibraryTab
import inkoma.ui.more.MoreTab
import inkoma.ui.navigation.LocalNavigator
import inkoma.ui.navigation.Navigator
import inkoma.ui.navigation.Screen
import inkoma.ui.theme.InkomaIcons
import inkoma.ui.theme.InkomaTheme
import inkoma.ui.updates.UpdatesTab

val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("No snackbar host") }

/** Fullscreen toggle exposed to the reader. */
class WindowControls(
    val isFullscreen: () -> Boolean,
    val setFullscreen: (Boolean) -> Unit,
    val minimize: () -> Unit = {},
    val close: () -> Unit = {},
)

val LocalWindowControls = staticCompositionLocalOf<WindowControls> { error("No window controls") }

/** Global key handling: the top-most handler wins (reader registers its own). */
object AppKeyEvents {
    private val handlers = ArrayList<(KeyEvent) -> Boolean>()

    fun register(handler: (KeyEvent) -> Boolean) {
        handlers.add(handler)
    }

    fun unregister(handler: (KeyEvent) -> Boolean) {
        handlers.remove(handler)
    }

    fun dispatch(event: KeyEvent): Boolean = handlers.asReversed().any { it(event) }
}

enum class HomeTab { Library, Updates, History, Browse, More }

/** Root screen: navigation rail + the selected tab (Mihon's HomeScreen in its large-screen layout). */
class HomeScreen : Screen() {
    var tab by mutableStateOf(HomeTab.Library)

    private val library = LibraryTab()
    private val updates = UpdatesTab()
    private val history = HistoryTab()
    private val browse = BrowseTab()
    private val more = MoreTab()

    @Composable
    override fun Content() {
        val updatesCount by Inkoma.preferences.extensionUpdatesCount.changes().collectAsState(0)
        val queue by Inkoma.downloadManager.queue.collectAsState()
        Row(Modifier.fillMaxSize()) {
            NavigationRail(modifier = Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                HomeTab.entries.forEach { t ->
                    NavigationRailItem(
                        selected = tab == t,
                        onClick = {
                            if (tab == t && t == HomeTab.Library) library.onReselect()
                            tab = t
                        },
                        icon = {
                            val icon = when (t) {
                                HomeTab.Library -> InkomaIcons.Library
                                HomeTab.Updates -> InkomaIcons.Updates
                                HomeTab.History -> InkomaIcons.History
                                HomeTab.Browse -> InkomaIcons.Browse
                                HomeTab.More -> InkomaIcons.More
                            }
                            val badge = when (t) {
                                HomeTab.Browse -> updatesCount
                                HomeTab.More -> queue.size
                                else -> 0
                            }
                            if (badge > 0) {
                                BadgedBox(badge = { Badge { Text(badge.toString()) } }) { Icon(icon, contentDescription = t.name) }
                            } else {
                                Icon(icon, contentDescription = t.name)
                            }
                        },
                        label = { Text(t.name) },
                        alwaysShowLabel = true,
                    )
                }
                Spacer(Modifier.weight(1f))
                val controls = LocalWindowControls.current
                IconButton(onClick = { controls.setFullscreen(!controls.isFullscreen()) }, modifier = Modifier.padding(bottom = 12.dp)) {
                    Icon(if (controls.isFullscreen()) InkomaIcons.FullscreenExit else InkomaIcons.Fullscreen, "Fullscreen (F11)")
                }
            }
            val tabStates = rememberSaveableStateHolder()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                tabStates.SaveableStateProvider(tab.name) {
                when (tab) {
                    HomeTab.Library -> library.Content()
                    HomeTab.Updates -> updates.Content()
                    HomeTab.History -> history.Content()
                    HomeTab.Browse -> browse.Content()
                    HomeTab.More -> more.Content()
                }
                }
            }
        }
    }
}

@Composable
fun InkomaApp(isFullscreen: Boolean, setFullscreen: (Boolean) -> Unit, minimize: () -> Unit = {}, close: () -> Unit = {}) {
    val themeMode by Inkoma.preferences.themeMode.changes().collectAsState(Inkoma.preferences.themeMode.get())
    val amoled by Inkoma.preferences.themeDarkAmoled.changes().collectAsState(Inkoma.preferences.themeDarkAmoled.get())
    val navigator = remember { Navigator(HomeScreen()) }
    val stateHolder = rememberSaveableStateHolder()
    navigator.onRemoved = { stateHolder.removeState(it.stateKey) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val controls = remember(isFullscreen) { WindowControls({ isFullscreen }, setFullscreen, minimize, close) }

    LaunchedEffect(Unit) {
        if (System.getenv("INKOMA_DEBUG") != null) {
            val start = ProcessHandle.current().info().startInstant().orElse(null)
            if (start != null) println("[inkoma] first frame after ${java.time.Duration.between(start, java.time.Instant.now()).toMillis()} ms")
        }
    }
    DisposableEffect(Unit) {
        ToastBridge.handler = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
        val backHandler: (KeyEvent) -> Boolean = { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.F11) {
                controls.setFullscreen(!controls.isFullscreen())
                true
            } else if (e.type == KeyEventType.KeyUp && (e.key == Key.Escape || (e.isAltPressed && e.key == Key.DirectionLeft))) {
                navigator.pop()
            } else {
                false
            }
        }
        AppKeyEvents.register(backHandler)
        onDispose { AppKeyEvents.unregister(backHandler) }
    }

    val appTheme by Inkoma.preferences.appTheme.changes().collectAsState(Inkoma.preferences.appTheme.get())
    val customSeed by Inkoma.preferences.customThemeSeed.changes().collectAsState(Inkoma.preferences.customThemeSeed.get())
    InkomaTheme(mode = themeMode, amoled = amoled, appTheme = appTheme, customSeed = customSeed) {
        CompositionLocalProvider(
            LocalNavigator provides navigator,
            LocalSnackbar provides snackbar,
            LocalWindowControls provides controls,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) { ev ->
                    if (ev.button == PointerButton.Back) navigator.pop()
                },
                color = MaterialTheme.colorScheme.background,
            ) {
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { _ ->
                    var onboarded by remember { mutableStateOf(Inkoma.preferences.onboardingComplete.get()) }
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            if (onboarded) ProgressBanner()
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                if (!onboarded) {
                                    inkoma.ui.onboarding.OnboardingScreen(onFinished = { onboarded = true })
                                } else {
                                    val current = navigator.current
                                    stateHolder.SaveableStateProvider(current.stateKey) { current.Content() }
                                }
                            }
                        }
                        if (isFullscreen) FullscreenBar(controls)
                    }
                }
            }
        }
    }
}

/**
 * In fullscreen there is no title bar: moving the pointer to the top edge reveals a bar with
 * minimize / exit fullscreen / close.
 */
@Composable
private fun BoxScope.FullscreenBar(controls: WindowControls) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            kotlinx.coroutines.delay(3500)
            visible = false
        }
    }
    Box(
        Modifier.align(Alignment.TopCenter).fillMaxWidth().height(if (visible) 48.dp else 6.dp)
            .onPointerEvent(PointerEventType.Enter) { visible = true }
            .onPointerEvent(PointerEventType.Move) { visible = true },
    ) {
        androidx.compose.animation.AnimatedVisibility(visible, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f), tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Inkoma", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = controls.minimize) { Icon(InkomaIcons.Minimize, "Minimize") }
                    IconButton(onClick = { controls.setFullscreen(false) }) { Icon(InkomaIcons.FullscreenExit, "Exit fullscreen") }
                    IconButton(onClick = controls.close) { Icon(InkomaIcons.Close, "Close") }
                }
            }
        }
    }
}

/** Komikku-style in-app banner for long-running work: library update, backup/restore, downloads. */
@Composable
private fun ProgressBanner() {
    val update by Inkoma.libraryUpdater.progress.collectAsState()
    val backup by Inkoma.backupManager.state.collectAsState()
    val downloading by Inkoma.downloadManager.isRunning.collectAsState()
    val queue by Inkoma.downloadManager.queue.collectAsState()
    val (text, fraction) = when {
        update.running -> "Updating library (${update.current}/${update.total})" + (update.title?.let { " • $it" } ?: "") to
            (if (update.total > 0) update.current.toFloat() / update.total else null)
        backup is inkoma.backup.BackupState.Running -> (backup as inkoma.backup.BackupState.Running).let { b ->
            b.label + if (b.total > 0) " (${b.progress}/${b.total})" else ""
        } to (backup as inkoma.backup.BackupState.Running).let { b -> if (b.total > 0) b.progress.toFloat() / b.total else null }
        downloading && queue.isNotEmpty() -> {
            val d = queue.firstOrNull { it.status == inkoma.download.Download.Status.DOWNLOADING }
            "Downloading ${queue.size} chapter(s)" + (d?.let { " • ${it.manga.title} – ${it.chapter.name}" } ?: "") to d?.let { it.progress / 100f }
        }
        else -> null to null
    }
    androidx.compose.animation.AnimatedVisibility(text != null) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                if (fraction != null) {
                    androidx.compose.material3.LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}
