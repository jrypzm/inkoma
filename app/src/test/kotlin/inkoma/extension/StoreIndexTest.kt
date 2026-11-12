package inkoma.extension

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/** Fetches and parses a real store index. Runs only when STORE_URL is set (network access required). */
class StoreIndexTest {
    @Test
    fun parsesRealStore() {
        val url = System.getenv("STORE_URL") ?: return
        val work = Files.createTempDirectory("store").toFile()
        val network = NetworkHelper(File(work, "c"), File(work, "d"), { "Mozilla/5.0" }, NetworkHelper.PREF_DOH_DISABLED, false)
        val service = ExtensionStoreService(network)
        val store = runBlocking { service.fetch(url) }.getOrThrow()
        println("STORE ${store.name} badge=${store.badgeLabel} key=${store.signingKey} legacy=${store.isLegacy} list=${store.extensionListUrl}")
        val exts = runBlocking { service.getExtensions(store) }.getOrThrow()
        println("EXTENSIONS ${exts.size}; first=${exts.first()}")
        assertTrue(exts.size > 100)
        assertTrue(exts.all { it.apkUrl.startsWith("https://") })
        work.deleteRecursively()
    }
}
