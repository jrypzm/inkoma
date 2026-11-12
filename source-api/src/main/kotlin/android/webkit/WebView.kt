package android.webkit

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup

/** Thrown when an extension needs a browser engine; shown to the user as the extension's error. */
class WebViewUnavailableException : UnsupportedOperationException(
    "This source needs a WebView (embedded browser), which is not available in Mihon Desktop.",
)

/**
 * WebView API surface so extension classes that mention it can be loaded. Creating one fails with a clear
 * message; there is no embedded browser engine on desktop.
 */
open class WebView(context: Context?) : ViewGroup(context) {
    init {
        throw WebViewUnavailableException()
    }

    open fun getSettings(): WebSettings = throw WebViewUnavailableException()
    open fun setWebViewClient(client: WebViewClient) {}
    open fun setWebChromeClient(client: WebChromeClient?) {}
    open fun addJavascriptInterface(obj: Any, name: String) {}
    open fun removeJavascriptInterface(name: String) {}
    open fun loadUrl(url: String) {}
    open fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) {}
    open fun loadData(data: String, mimeType: String?, encoding: String?) {}
    open fun loadDataWithBaseURL(baseUrl: String?, data: String, mimeType: String?, encoding: String?, historyUrl: String?) {}
    open fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {}
    open fun stopLoading() {}
    open fun destroy() {}
    open fun reload() {}
    open fun getUrl(): String? = null
    open fun getTitle(): String? = null
    open fun setLayoutParams(params: ViewGroup.LayoutParams) {}
    open fun setBackgroundColor(color: Int) {}
    open fun postVisualStateCallback(requestId: Long, callback: VisualStateCallback) {}

    abstract class VisualStateCallback {
        abstract fun onComplete(requestId: Long)
    }

    companion object {
        @JvmStatic fun setWebContentsDebuggingEnabled(enabled: Boolean) {}
        @JvmStatic fun getWebViewClassLoader(): ClassLoader = WebView::class.java.classLoader
        @JvmStatic fun getCurrentWebViewPackage(): Any? = null
    }
}

abstract class WebSettings {
    abstract fun setJavaScriptEnabled(flag: Boolean)
    abstract fun getJavaScriptEnabled(): Boolean
    abstract fun setDomStorageEnabled(flag: Boolean)
    abstract fun setDatabaseEnabled(flag: Boolean)
    abstract fun setUserAgentString(ua: String?)
    abstract fun getUserAgentString(): String
    abstract fun setBlockNetworkImage(flag: Boolean)
    abstract fun setLoadsImagesAutomatically(flag: Boolean)
    abstract fun setCacheMode(mode: Int)
    abstract fun setMixedContentMode(mode: Int)
    abstract fun setUseWideViewPort(use: Boolean)
    abstract fun setLoadWithOverviewMode(overview: Boolean)
    abstract fun getBlockNetworkImage(): Boolean
    abstract fun setSupportMultipleWindows(support: Boolean)

    companion object {
        const val LOAD_DEFAULT = -1
        const val LOAD_NO_CACHE = 2
        const val MIXED_CONTENT_ALWAYS_ALLOW = 0
        @JvmStatic fun getDefaultUserAgent(context: Context?): String =
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
    }
}

open class WebViewClient {
    open fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
    open fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = false
    open fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {}
    open fun onPageFinished(view: WebView, url: String) {}
    open fun onLoadResource(view: WebView, url: String) {}
    open fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = null
    open fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {}
    open fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {}
    open fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = false
}

open class WebChromeClient {
    open fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = false
    open fun onProgressChanged(view: WebView, newProgress: Int) {}
}

interface WebResourceRequest {
    fun getUrl(): Uri
    fun isForMainFrame(): Boolean
    fun getMethod(): String
    fun getRequestHeaders(): Map<String, String>
    fun hasGesture(): Boolean
    fun isRedirect(): Boolean
}

open class WebResourceResponse(
    private var mimeType: String?,
    private var encoding: String?,
    private var data: java.io.InputStream?,
) {
    constructor(
        mimeType: String?,
        encoding: String?,
        statusCode: Int,
        reasonPhrase: String,
        responseHeaders: Map<String, String>?,
        data: java.io.InputStream?,
    ) : this(mimeType, encoding, data)

    fun getMimeType(): String? = mimeType
    fun getEncoding(): String? = encoding
    fun getData(): java.io.InputStream? = data
}

abstract class WebResourceError {
    abstract fun getErrorCode(): Int
    abstract fun getDescription(): CharSequence
}

abstract class RenderProcessGoneDetail {
    abstract fun didCrash(): Boolean
    abstract fun rendererPriorityAtExit(): Int
}

class ConsoleMessage(private val message: String, private val sourceId: String, private val lineNumber: Int, private val level: MessageLevel) {
    enum class MessageLevel { TIP, LOG, WARNING, ERROR, DEBUG }

    fun message(): String = message
    fun sourceId(): String = sourceId
    fun lineNumber(): Int = lineNumber
    fun messageLevel(): MessageLevel = level
}

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class JavascriptInterface
