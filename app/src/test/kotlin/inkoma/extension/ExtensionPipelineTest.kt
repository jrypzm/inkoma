package inkoma.extension

import android.app.Application
import android.content.Context
import com.android.dx.command.dexer.Main as Dx
import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File
import java.net.InetSocketAddress
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end check of the extension runtime: Kotlin extension → dex (as in an APK) → dex2jar →
 * URLClassLoader → Injekt-provided NetworkHelper → OkHttp + Jsoup against a local HTTP server.
 */
class ExtensionPipelineTest {
    private fun dex(jar: File, out: File) {
        val args = Dx.Arguments().apply {
            fileNames = arrayOf(jar.absolutePath)
            outName = out.absolutePath
            jarOutput = false
            minSdkVersion = 26
        }
        val code = Dx.run(args)
        check(code == 0) { "dx failed: $code" }
    }

    @Test
    fun dexTranslatedExtensionLoadsAndFetches() {
        val work = Files.createTempDirectory("ext-test").toFile()
        val extJar = File(System.getProperty("testExtensionJar"))
        val dexFile = File(work, "classes.dex")
        dex(extJar, dexFile)
        val apk = File(work, "test.apk")
        ZipOutputStream(apk.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(dexFile.readBytes())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("assets/info.txt"))
            zos.write("asset-ok".toByteArray())
            zos.closeEntry()
        }
        val jar = File(work, "test.jar")
        ExtensionLoader.convertApkToJar(apk, jar)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/popular") { ex ->
            val html = """<html><body>
                <div class="manga"><a href="/manga/1" data-cover="/c/1.jpg">First Manga</a></div>
                <div class="manga"><a href="/manga/2" data-cover="/c/2.jpg">Second Manga</a></div>
                <a class="next" href="/popular?page=2">next</a></body></html>"""
            val ua = ex.requestHeaders.getFirst("User-Agent") ?: ""
            val bytes = (if (ua.startsWith("Mozilla")) html else "missing UA").toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/manga/1") { ex ->
            val bytes = """<p class="desc">A description</p><ul><li class="chapter"><a href="/ch/2">Chapter 2</a></li>
                <li class="chapter"><a href="/ch/1">Chapter 1</a></li></ul>""".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            System.setProperty("testsource.baseUrl", "http://127.0.0.1:${server.address.port}")
            val app = Application(File(work, "data"))
            Injekt.addSingleton<Application>(app)
            Injekt.addSingleton<Context>(app)
            Injekt.addSingleton(Json { ignoreUnknownKeys = true })
            Injekt.addSingleton(
                NetworkHelper(File(work, "cache"), File(work, "data"), { "Mozilla/5.0 (test)" }, NetworkHelper.PREF_DOH_DISABLED, false),
            )

            val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), javaClass.classLoader)
            assertEquals("asset-ok", loader.getResourceAsStream("assets/info.txt")!!.readBytes().decodeToString())
            val source = Class.forName("eu.kanade.tachiyomi.extension.en.testsource.TestSource", true, loader)
                .getDeclaredConstructor().newInstance() as HttpSource

            assertEquals("Test Source", source.name)
            assertTrue(source.id > 0, "id is derived from name/lang/version")
            val page = runBlocking { source.getPopularManga(1) }
            assertEquals(listOf("First Manga", "Second Manga"), page.mangas.map { it.title })
            assertEquals("/manga/1", page.mangas.first().url)
            assertTrue(page.hasNextPage)

            val update = runBlocking { source.getMangaUpdate(page.mangas.first(), emptyList(), fetchDetails = true, fetchChapters = true) }
            assertEquals("A description", update.manga.description)
            assertEquals(listOf("Chapter 2", "Chapter 1"), update.chapters.map { it.name })
            assertEquals(2, runBlocking { source.getPageList(update.chapters.first()) }.size)
        } finally {
            server.stop(0)
            work.deleteRecursively()
        }
    }
}
