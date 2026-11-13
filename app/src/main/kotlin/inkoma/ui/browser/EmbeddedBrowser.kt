package inkoma.ui.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.network.AndroidCookieJar
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.CookieHandler
import java.net.URI
import javax.swing.SwingUtilities

/**
 * In-app browser (JavaFX WebKit) shown inside the window.
 *
 * It runs in the app's process and uses the app's networking, so it shares with the sources:
 * - DNS over HTTPS and the system proxy (both are JVM-wide, see DohResolver), so sites blocked by DNS open here too;
 * - cookies: WebKit stores them through [CookieHandler], which is bridged to the sources' cookie jar, so a login or a
 *   solved Cloudflare check here is used by the extension right away;
 * - the user agent, so Cloudflare clearance cookies stay valid for the source's requests.
 */
class EmbeddedBrowser(private val userAgent: String, cookieJar: AndroidCookieJar) {
    var title by mutableStateOf("")
        private set
    var location by mutableStateOf("")
        private set
    var progress by mutableStateOf(0f)
        private set
    var loading by mutableStateOf(false)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Swing component hosting the page; created on the event dispatch thread (starts the JavaFX runtime once). */
    val panel: JFXPanel

    @Volatile private var view: WebView? = null
    @Volatile private var pendingUrl: String? = null

    init {
        installCookieBridge(cookieJar)
        panel = JFXPanel()
        // Keep the JavaFX runtime alive after the last browser closes, so the browser can be opened again.
        Platform.setImplicitExit(false)
        Platform.runLater {
            runCatching { createView() }.onFailure { e ->
                java.util.logging.Logger.getLogger("EmbeddedBrowser").log(java.util.logging.Level.WARNING, "Page view could not be created", e)
                ui { error = "The page view could not be created: ${inkoma.core.AppLog.details(e)}" }
            }
        }
    }

    private fun createView() {
        val web = WebView()
        val engine = web.engine
        engine.userAgent = userAgent
        engine.isJavaScriptEnabled = true
        // Links that open a new window (target=_blank, window.open) open here instead.
        engine.setCreatePopupHandler { engine }
        engine.titleProperty().addListener { _, _, t -> ui { title = t ?: "" } }
        engine.locationProperty().addListener { _, _, l -> ui { location = l ?: "" } }
        engine.loadWorker.progressProperty().addListener { _, _, p -> ui { progress = p.toFloat().coerceIn(0f, 1f) } }
        engine.loadWorker.stateProperty().addListener { _, _, s ->
            val running = s == Worker.State.RUNNING || s == Worker.State.SCHEDULED
            val failure = if (s == Worker.State.FAILED) engine.loadWorker.exception?.message ?: "The page could not be loaded" else null
            val history = engine.history
            val back = history.currentIndex > 0
            val forward = history.currentIndex < history.entries.size - 1
            ui {
                loading = running
                if (running) error = null
                if (failure != null) error = failure
                canGoBack = back
                canGoForward = forward
            }
        }
        panel.scene = Scene(web)
        view = web
        pendingUrl?.let { engine.load(it) }
        pendingUrl = null
    }

    fun load(url: String) = fx {
        val v = view
        if (v == null) pendingUrl = url else v.engine.load(url)
    }

    fun back() = fx { view?.engine?.history?.takeIf { it.currentIndex > 0 }?.go(-1) }

    fun forward() = fx { view?.engine?.history?.takeIf { it.currentIndex < it.entries.size - 1 }?.go(1) }

    fun reload() = fx { view?.engine?.reload() }

    fun stop() = fx { view?.engine?.loadWorker?.cancel() }

    /** Stops scripts and media and frees the page. */
    fun dispose() = fx {
        view?.engine?.load("about:blank")
        panel.scene = null
        view = null
    }

    private fun fx(block: () -> Unit) = if (Platform.isFxApplicationThread()) block() else Platform.runLater(block)

    private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)

    companion object {
        @Volatile private var bridged = false

        /**
         * WebKit asks [CookieHandler.getDefault] for cookies. JavaFX marks requests made by page scripts
         * (document.cookie) with the scheme "javascript"/"javascripts": those must not see or set HttpOnly cookies.
         */
        @Synchronized
        private fun installCookieBridge(jar: AndroidCookieJar) {
            if (bridged) return
            CookieHandler.setDefault(object : CookieHandler() {
                override fun get(uri: URI, requestHeaders: Map<String, List<String>>): Map<String, List<String>> {
                    val (url, fromScript) = httpUrl(uri) ?: return emptyMap()
                    val cookies = jar.loadForRequest(url).filter { !fromScript || !it.httpOnly }
                    if (cookies.isEmpty()) return emptyMap()
                    return mapOf("Cookie" to listOf(cookies.joinToString("; ") { "${it.name}=${it.value}" }))
                }

                override fun put(uri: URI, responseHeaders: Map<String, List<String>>) {
                    val (url, fromScript) = httpUrl(uri) ?: return
                    val cookies = responseHeaders.entries
                        .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                        .flatMap { it.value }
                        .mapNotNull { Cookie.parse(url, it) }
                        .filter { !fromScript || !it.httpOnly }
                    if (cookies.isNotEmpty()) jar.saveFromResponse(url, cookies)
                }
            })
            bridged = true
        }

        private fun httpUrl(uri: URI): Pair<okhttp3.HttpUrl, Boolean>? {
            val scheme = uri.scheme?.lowercase() ?: return null
            val (real, fromScript) = when (scheme) {
                "javascript" -> "http" to true
                "javascripts" -> "https" to true
                "http", "https" -> scheme to false
                else -> return null
            }
            val text = if (fromScript) real + uri.toString().substring(scheme.length) else uri.toString()
            return text.toHttpUrlOrNull()?.let { it to fromScript }
        }
    }
}
