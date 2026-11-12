package inkoma.source

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import inkoma.data.ChapterRecognition
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Port of Mihon's LocalSource. Layout: `<local dir>/<Manga title>/<chapter folder | .cbz | .zip | .epub>`.
 * Optional per-manga `cover.(jpg|png|webp)`, `details.json` and per-chapter `ComicInfo.xml`.
 */
class LocalSource(private val baseDir: () -> File) : CatalogueSource, UnmeteredSource {
    override val id: Long = ID
    override val name: String = "Local source"
    override val lang: String = "other"
    override val supportsLatest: Boolean = true
    override fun toString(): String = name

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Details(
        val title: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val description: String? = null,
        val genre: List<String>? = null,
        val status: Int? = null,
    )

    private class OrderBy : Filter.Sort("Order by", arrayOf("Title", "Date"), Selection(0, true))

    override fun getFilterList(): FilterList = FilterList(OrderBy())

    private fun mangaDirs(): List<File> =
        baseDir().apply { mkdirs() }.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchManga(page, "", getFilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        getSearchManga(page, "", FilterList(OrderBy().apply { state = Filter.Sort.Selection(1, false) }))

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withContext(Dispatchers.IO) {
        var dirs = mangaDirs().filter { it.name.contains(query, ignoreCase = true) }
        val sort = filters.filterIsInstance<OrderBy>().firstOrNull()?.state
        dirs = when (sort?.index) {
            1 -> if (sort.ascending) dirs.sortedBy { it.lastModified() } else dirs.sortedByDescending { it.lastModified() }
            else -> if (sort?.ascending == false) dirs.sortedByDescending { it.name.lowercase() } else dirs.sortedBy { it.name.lowercase() }
        }
        MangasPage(dirs.map { dir -> SManga.create().apply { title = dir.name; url = dir.name; thumbnail_url = coverFile(dir)?.toURI()?.toString() } }, false)
    }

    fun coverFile(mangaDir: File): File? =
        mangaDir.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension.equals("cover", true) && it.extension.lowercase() in IMAGE_EXT }

    override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate =
        withContext(Dispatchers.IO) {
            val dir = File(baseDir(), manga.url)
            val details = if (fetchDetails) readDetails(dir, manga) else manga
            val chs = if (fetchChapters) readChapters(dir, manga) else chapters
            SMangaUpdate(details, chs)
        }

    private fun readDetails(dir: File, manga: SManga): SManga {
        val out = manga.copy()
        out.thumbnail_url = coverFile(dir)?.toURI()?.toString() ?: manga.thumbnail_url
        val detailsFile = dir.listFiles()?.firstOrNull { it.name.equals("details.json", true) }
        if (detailsFile != null) {
            runCatching { json.decodeFromString<Details>(detailsFile.readText()) }.getOrNull()?.let { d ->
                d.title?.let { out.title = it }
                out.author = d.author ?: out.author
                out.artist = d.artist ?: out.artist
                out.description = d.description ?: out.description
                out.genre = d.genre?.joinToString(", ") ?: out.genre
                out.status = d.status ?: out.status
            }
        } else {
            chapterFiles(dir).firstOrNull()?.let { first -> comicInfo(first)?.let { applyComicInfo(out, it) } }
        }
        out.initialized = true
        return out
    }

    private fun applyComicInfo(manga: SManga, info: Map<String, String>) {
        info["Series"]?.let { manga.title = it }
        info["Summary"]?.let { manga.description = it }
        info["Writer"]?.let { manga.author = it }
        info["Penciller"]?.let { manga.artist = it }
        listOfNotNull(info["Genre"], info["Tags"]).joinToString(", ").takeIf { it.isNotBlank() }?.let { manga.genre = it }
        manga.status = when (info["PublishingStatusTachiyomi"]?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "licensed" -> SManga.LICENSED
            "publishing finished" -> SManga.PUBLISHING_FINISHED
            "cancelled" -> SManga.CANCELLED
            "on hiatus" -> SManga.ON_HIATUS
            else -> manga.status
        }
    }

    private fun comicInfo(chapter: File): Map<String, String>? {
        val stream: InputStream? = when {
            chapter.isDirectory -> chapter.listFiles()?.firstOrNull { it.name.equals("ComicInfo.xml", true) }?.inputStream()
            chapter.extension.lowercase() in ARCHIVE_EXT -> runCatching {
                val zip = ZipFile(chapter)
                val entry = zip.entries().asSequence().firstOrNull { it.name.equals("ComicInfo.xml", true) }
                if (entry == null) {
                    zip.close()
                    null
                } else {
                    zip.getInputStream(entry).readBytes().also { zip.close() }.inputStream()
                }
            }.getOrNull()
            else -> null
        }
        return stream?.use { input ->
            runCatching {
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
                val root = doc.documentElement
                val map = HashMap<String, String>()
                val children = root.childNodes
                for (i in 0 until children.length) {
                    val node = children.item(i)
                    if (node is Element && node.textContent.isNotBlank()) map[node.tagName] = node.textContent.trim()
                }
                map
            }.getOrNull()
        }
    }

    private fun chapterFiles(dir: File): List<File> = dir.listFiles()
        ?.filter { f -> !f.name.startsWith(".") && (f.isDirectory || f.extension.lowercase() in ARCHIVE_EXT || f.extension.lowercase() == "epub") }
        .orEmpty()

    private fun readChapters(dir: File, manga: SManga): List<SChapter> =
        chapterFiles(dir).map { file ->
            SChapter.create().apply {
                url = "${manga.url}/${file.name}"
                name = if (file.isDirectory) file.name else file.nameWithoutExtension
                date_upload = file.lastModified()
                val info = comicInfo(file)
                info?.get("Title")?.let { name = it }
                scanlator = info?.get("Translator")
                chapter_number = (info?.get("Number")?.toFloatOrNull()
                    ?: ChapterRecognition.parse(manga.title, name).toFloat())
            }
        }.sortedWith(compareByDescending<SChapter> { it.chapter_number }.thenByDescending { it.name })

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val file = File(baseDir(), chapter.url)
        val names = when {
            file.isDirectory -> file.listFiles()?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXT && !it.nameWithoutExtension.equals("cover", true) }
                ?.sortedWith { a, b -> naturalCompare(a.name, b.name) }
                ?.map { it.toURI().toString() }
            else -> ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in IMAGE_EXT }
                    .map { it.name }
                    .sortedWith { a, b -> naturalCompare(a, b) }
                    .map { "$ARCHIVE_SCHEME${file.absolutePath}!/$it" }
                    .toList()
            }
        }.orEmpty()
        names.mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    companion object {
        const val ID = 0L
        const val ARCHIVE_SCHEME = "archive://"
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "jxl")
        val ARCHIVE_EXT = setOf("zip", "cbz")

        /** Opens a page produced by [getPageList] or by a CBZ download (file:// or archive:// URL). */
        fun openLocal(url: String): ByteArray? = when {
            url.startsWith("file:") -> runCatching { File(java.net.URI(url)).readBytes() }.getOrNull()
            url.startsWith(ARCHIVE_SCHEME) -> {
                val rest = url.removePrefix(ARCHIVE_SCHEME)
                val archive = rest.substringBefore("!/")
                val entry = rest.substringAfter("!/")
                runCatching { ZipFile(archive).use { zip -> zip.getEntry(entry)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } } }.getOrNull()
            }
            else -> null
        }

        fun naturalCompare(a: String, b: String): Int {
            val re = Regex("(\\d+)|(\\D+)")
            val pa = re.findAll(a.lowercase()).map { it.value }.toList()
            val pb = re.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(pa.size, pb.size)) {
                val x = pa[i]
                val y = pb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) {
                    x.toBigInteger().compareTo(y.toBigInteger())
                } else {
                    x.compareTo(y)
                }
                if (c != 0) return c
            }
            return pa.size - pb.size
        }
    }
}
