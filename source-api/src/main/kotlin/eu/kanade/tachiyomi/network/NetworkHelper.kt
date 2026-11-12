package eu.kanade.tachiyomi.network

import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Same public surface as Mihon's NetworkHelper: `client`, `cloudflareClient`, `cookieJar`,
 * `defaultUserAgentProvider()`.
 */
class NetworkHelper(
    cacheDir: File,
    dataDir: File,
    private val userAgent: () -> String,
    dohProvider: Int,
    verboseLogging: Boolean,
) {
    val cookieJar = AndroidCookieJar(File(dataDir, "cookies.json"))

    val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES)
            .cache(Cache(File(cacheDir, "network_cache"), 5L * 1024 * 1024))
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
        if (verboseLogging) {
            builder.addNetworkInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.HEADERS })
        }
        // DNS over HTTPS ([dohProvider]) is applied process-wide by the app's name resolver, so the system resolver used
        // here (and by the embedded browser) already honours it.
        // Same layout as Mihon's client: extensions assert that Uncaught/UserAgent/Cloudflare interceptors are present
        // and that no Brotli/IgnoreGzip network interceptor is (they add their own decompression).
        builder.addInterceptor(CloudflareInterceptor()).build()
    }

    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient get() = client

    fun defaultUserAgentProvider(): String = userAgent().trim()

    companion object {
        const val PREF_DOH_DISABLED = -1

        /** Cloudflare, then Google, then Quad9, then the system resolver: works on networks that block sites by DNS. */
        const val PREF_DOH_AUTO = 0
        const val PREF_DOH_CLOUDFLARE = 1
        const val PREF_DOH_GOOGLE = 2
        const val PREF_DOH_ADGUARD = 3
        const val PREF_DOH_QUAD9 = 4
        const val PREF_DOH_ALIDNS = 5
        const val PREF_DOH_DNSPOD = 6
        const val PREF_DOH_360 = 7
        const val PREF_DOH_QUAD101 = 8
        const val PREF_DOH_MULLVAD = 9
        const val PREF_DOH_CONTROLD = 10
        const val PREF_DOH_NJALLA = 11
        const val PREF_DOH_SHECAN = 12

        val DOH_PROVIDERS: List<Pair<Int, String>> = listOf(
            PREF_DOH_AUTO to "Automatic (recommended)",
            PREF_DOH_DISABLED to "Disabled",
            PREF_DOH_CLOUDFLARE to "Cloudflare",
            PREF_DOH_GOOGLE to "Google",
            PREF_DOH_ADGUARD to "AdGuard",
            PREF_DOH_QUAD9 to "Quad9",
            PREF_DOH_ALIDNS to "AliDNS",
            PREF_DOH_DNSPOD to "DNSPod",
            PREF_DOH_360 to "360",
            PREF_DOH_QUAD101 to "Quad 101",
            PREF_DOH_MULLVAD to "Mullvad",
            PREF_DOH_CONTROLD to "Control D",
            PREF_DOH_NJALLA to "Njalla",
            PREF_DOH_SHECAN to "Shecan",
        )

        /** DoH endpoint and its bootstrap IP addresses, or null for "disabled"/"automatic". */
        fun dohUrl(provider: Int): Pair<String, List<String>>? = when (provider) {
            PREF_DOH_CLOUDFLARE -> "https://cloudflare-dns.com/dns-query" to listOf("162.159.36.1", "162.159.46.1", "1.1.1.1", "1.0.0.1")
            PREF_DOH_GOOGLE -> "https://dns.google/dns-query" to listOf("8.8.4.4", "8.8.8.8")
            PREF_DOH_ADGUARD -> "https://dns-unfiltered.adguard.com/dns-query" to listOf("94.140.14.140", "94.140.14.141")
            PREF_DOH_QUAD9 -> "https://dns.quad9.net/dns-query" to listOf("9.9.9.9", "149.112.112.112")
            PREF_DOH_ALIDNS -> "https://dns.alidns.com/dns-query" to listOf("223.5.5.5", "223.6.6.6")
            PREF_DOH_DNSPOD -> "https://doh.pub/dns-query" to listOf("1.12.12.12", "120.53.53.53")
            PREF_DOH_360 -> "https://doh.360.cn/dns-query" to listOf("101.226.4.6", "218.30.118.6")
            PREF_DOH_QUAD101 -> "https://dns.twnic.tw/dns-query" to listOf("101.101.101.101")
            PREF_DOH_MULLVAD -> "https://dns.mullvad.net/dns-query" to listOf("194.242.2.2")
            PREF_DOH_CONTROLD -> "https://freedns.controld.com/p0" to listOf("76.76.2.0", "76.76.10.0")
            PREF_DOH_NJALLA -> "https://dns.njal.la/dns-query" to listOf("95.215.19.53")
            PREF_DOH_SHECAN -> "https://free.shecan.ir/dns-query" to listOf("178.22.122.100", "185.51.200.2")
            else -> null
        }
    }
}
