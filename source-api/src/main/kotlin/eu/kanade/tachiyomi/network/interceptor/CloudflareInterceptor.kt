package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Desktop counterpart of Mihon's CloudflareInterceptor. Extensions require an interceptor with this exact simple
 * name in the default client (they check for it and re-order it). Mihon solves challenges in an Android WebView;
 * there is no WebView on desktop, so a challenge is detected the same way and reported instead of returning the
 * challenge page as if it were content. Cookies obtained elsewhere (e.g. cf_clearance) are honoured via the jar.
 */
class CloudflareInterceptor : Interceptor {
    private val serverCheck = arrayOf("cloudflare-nginx", "cloudflare")

    fun shouldIntercept(response: Response): Boolean =
        response.header("cf-mitigated") == "challenge" && response.header("Server") in serverCheck

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!shouldIntercept(response)) return response
        response.close()
        throw IOException("Failed to bypass Cloudflare (${chain.request().url.host}). Open the site in WebView/browser and try again later.")
    }
}
