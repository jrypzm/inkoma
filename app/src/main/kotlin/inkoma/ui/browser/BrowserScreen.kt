package inkoma.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import inkoma.Inkoma
import inkoma.ui.components.AppBar
import inkoma.ui.navigation.LocalNavigator
import inkoma.ui.navigation.Screen
import inkoma.ui.theme.InkomaIcons
import java.util.logging.Level
import java.util.logging.Logger

/** Full-window in-app browser (Mihon's WebViewScreen): back / forward / reload, shares cookies with the sources. */
class BrowserScreen(private val url: String, private val initialTitle: String? = null) : Screen() {
    private var browser: EmbeddedBrowser? = null
    private var failure: Throwable? = null

    private fun browser(): EmbeddedBrowser? {
        if (browser == null && failure == null) {
            browser = runCatching { EmbeddedBrowser(Inkoma.preferences.defaultUserAgent.get(), Inkoma.network.cookieJar) }
                .onFailure {
                    failure = it
                    Logger.getLogger("BrowserScreen").log(Level.WARNING, "Embedded browser unavailable", it)
                }
                .getOrNull()
                ?.also { it.load(url) }
        }
        return browser
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val b = remember { browser() }
        Scaffold(
            topBar = {
                AppBar(
                    title = b?.title?.takeIf { it.isNotBlank() } ?: initialTitle ?: "Browser",
                    subtitle = b?.location?.takeIf { it.isNotBlank() } ?: url,
                    navigateUp = { navigator.pop() },
                    actions = {
                        if (b != null) {
                            IconButton(onClick = b::back, enabled = b.canGoBack) { Icon(InkomaIcons.ChevronLeft, "Back") }
                            IconButton(onClick = b::forward, enabled = b.canGoForward) { Icon(InkomaIcons.ChevronRight, "Forward") }
                            if (b.loading) {
                                IconButton(onClick = b::stop) { Icon(InkomaIcons.Close, "Stop") }
                            } else {
                                IconButton(onClick = b::reload) { Icon(InkomaIcons.Refresh, "Reload") }
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (b == null) {
                    Unavailable(failure)
                    return@Column
                }
                Box(Modifier.fillMaxWidth().height(3.dp)) {
                    if (b.loading) LinearProgressIndicator(progress = { b.progress }, modifier = Modifier.fillMaxWidth())
                }
                b.error?.let { msg ->
                    Text(
                        msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                SwingPanel(factory = { b.panel }, modifier = Modifier.fillMaxSize())
            }
        }
    }

    @Composable
    private fun Unavailable(error: Throwable?) {
        val uri = LocalUriHandler.current
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            val details = error?.let { inkoma.core.AppLog.details(it) }
            Text("The built-in browser could not start on this system.", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            details?.let {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                }
            }
            androidx.compose.foundation.layout.Row(Modifier.padding(top = 16.dp)) {
                if (details != null) {
                    TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString("$details\n\n${System.getProperty("os.name")} ${System.getProperty("os.version")}, Java ${System.getProperty("java.version")}"))
                    }) { Text("Copy error details") }
                }
                TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text("Open in system browser") }
            }
        }
    }

    override fun onDispose() {
        browser?.dispose()
        browser = null
        super.onDispose()
    }
}
