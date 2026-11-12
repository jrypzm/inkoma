package inkoma.extension

import eu.kanade.tachiyomi.network.NetworkHelper
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Keiyoushi extensions inspect the default client by interceptor class name and throw
 * ("... must be present in default client") if it doesn't look like Mihon's.
 */
class ClientContractTest {
    @Test
    fun defaultClientMatchesWhatExtensionsAssert() {
        val work = Files.createTempDirectory("client").toFile()
        val client = NetworkHelper(File(work, "c"), File(work, "d"), { "UA" }, NetworkHelper.PREF_DOH_DISABLED, false).client
        val names = client.interceptors.map { it.javaClass.simpleName }
        val network = client.networkInterceptors.map { it.javaClass.simpleName }
        listOf("UncaughtExceptionInterceptor", "UserAgentInterceptor", "CloudflareInterceptor").forEach {
            assertTrue(it in names, "$it must be present in default client (has $names)")
        }
        listOf("BrotliInterceptor", "IgnoreGzipInterceptor").forEach {
            assertFalse(it in network, "$it must not be present in default client")
        }
        work.deleteRecursively()
    }
}
