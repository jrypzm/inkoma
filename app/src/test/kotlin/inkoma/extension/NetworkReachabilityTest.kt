package inkoma.extension

import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Talks to a real source API (MangaDex) with the app's default client, directly and through DNS over HTTPS.
 * Needs the internet, so it only runs when INKOMA_NETWORK_TEST=1 (CI runs it on Linux, Windows and macOS).
 */
class NetworkReachabilityTest {
    private val enabled = System.getenv("INKOMA_NETWORK_TEST") == "1"

    private fun client(doh: Int) = Files.createTempDirectory("net").toFile().let { work ->
        NetworkHelper(File(work, "c"), File(work, "d"), { "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Inkoma" }, doh, false).client
    }

    private fun check(doh: Int) {
        // DNS over HTTPS is applied process-wide by the app's resolver (DohResolver), like in the app.
        inkoma.core.net.DnsSettings.provider = doh
        val c = client(doh)
        c.newCall(Request.Builder().url("https://api.mangadex.org/manga?limit=1").build()).execute().use { r ->
            val body = r.body.string()
            val info = "status ${r.code}, content-type ${r.header("Content-Type")}, body: ${body.take(300)}"
            println("MangaDex /manga: $info")
            assertEquals(200, r.code, info)
            assertTrue(body.contains("\"result\":\"ok\""), info)
        }
    }

    @Test
    fun mangaDexDirect() {
        if (enabled) check(NetworkHelper.PREF_DOH_DISABLED)
    }

    @Test
    fun mangaDexThroughDnsOverHttps() {
        if (enabled) check(NetworkHelper.PREF_DOH_CLOUDFLARE)
    }

    @Test
    fun mangaDexAutomaticDns() {
        if (enabled) check(NetworkHelper.PREF_DOH_AUTO)
    }
}
