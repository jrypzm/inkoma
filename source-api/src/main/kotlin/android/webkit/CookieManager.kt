package android.webkit

import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uy.kohesive.injekt.Injekt

fun interface ValueCallback<T> {
    fun onReceiveValue(value: T)
}

/**
 * android.webkit.CookieManager backed by the app's OkHttp cookie jar, so (as on Android, where Mihon's
 * AndroidCookieJar wraps CookieManager) cookies set by extensions are sent with their requests and vice versa.
 */
class CookieManager private constructor() {
    private fun jar() = Injekt.getInstance<NetworkHelper>(NetworkHelper::class.java).cookieJar
    private var accept = true

    fun setAcceptCookie(accept: Boolean) { this.accept = accept }
    fun acceptCookie(): Boolean = accept
    fun setAcceptThirdPartyCookies(webview: WebView?, accept: Boolean) {}

    fun getCookie(url: String?): String? {
        val httpUrl = url?.toHttpUrlOrNull() ?: return null
        return jar().get(httpUrl).joinToString("; ") { "${it.name}=${it.value}" }.ifEmpty { null }
    }

    fun setCookie(url: String?, value: String?) {
        val httpUrl = url?.toHttpUrlOrNull() ?: return
        val cookie = value?.let { Cookie.parse(httpUrl, it) } ?: return
        if (accept) jar().addAll(httpUrl, listOf(cookie))
    }

    fun setCookie(url: String?, value: String?, callback: ValueCallback<Boolean>?) {
        setCookie(url, value)
        callback?.onReceiveValue(true)
    }

    fun hasCookies(): Boolean = true
    fun removeAllCookies(callback: ValueCallback<Boolean>?) {
        jar().removeAll()
        callback?.onReceiveValue(true)
    }
    fun removeSessionCookies(callback: ValueCallback<Boolean>?) { callback?.onReceiveValue(true) }
    @Deprecated("Android API") fun removeAllCookie() = jar().removeAll()
    fun flush() {}

    companion object {
        private val shared by lazy { CookieManager() }
        @JvmStatic fun getInstance(): CookieManager = shared
    }
}

