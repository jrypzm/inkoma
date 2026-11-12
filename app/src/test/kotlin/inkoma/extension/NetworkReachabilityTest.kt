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
        val c = client(doh)
        c.newCall(Request.Builder().url("https://api.mangadex.org/ping").build()).execute().use { r ->
            assertEquals(200, r.code, "ping status")
            assertEquals("pong", r.body.string().trim())
        }
        c.newCall(Request.Builder().url("https://api.mangadex.org/manga?limit=1").build()).execute().use { r ->
            assertEquals(200, r.code, "manga list status")
            assertTrue(r.body.string().contains("\"result\":\"ok\""))
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
}
