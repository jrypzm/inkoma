package inkoma.download

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import inkoma.core.AppDirs
import inkoma.core.AppPreferences
import inkoma.data.ChapterRepository
import inkoma.data.MangaRepository
import inkoma.domain.Chapter
import inkoma.domain.Manga
import inkoma.source.LocalSource
import inkoma.source.SourceManager
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException

data class Download(
    val manga: Manga,
    val chapter: Chapter,
    val sourceId: Long,
    val status: Status = Status.QUEUE,
    val downloadedPages: Int = 0,
    val totalPages: Int = 0,
    val error: String? = null,
) {
    enum class Status { NOT_DOWNLOADED, QUEUE, DOWNLOADING, DOWNLOADED, ERROR }

    val progress: Int get() = if (totalPages > 0) downloadedPages * 100 / totalPages else 0
}

/** Mihon's DownloadManager + Downloader + DownloadProvider + DownloadCache rolled into one desktop class. */
class DownloadManager(
    private val preferences: AppPreferences,
    private val sourceManager: SourceManager,
    private val chapterRepository: ChapterRepository,
    private val mangaRepository: MangaRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueFile = File(AppDirs.data, "download_queue.json")

    private val _queue = MutableStateFlow<List<Download>>(emptyList())
    val queue: StateFlow<List<Download>> = _queue.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** Incremented whenever files on disk change, so UI can recompute "downloaded" badges. */
    private val _cacheVersion = MutableStateFlow(0)
    val cacheVersion: StateFlow<Int> = _cacheVersion.asStateFlow()

    private var job: Job? = null

    /** Invoked when the queue empties after downloading [count] chapters. */
    var onQueueFinished: (count: Int) -> Unit = {}

    // ---- Paths ----------------------------------------------------------------------------------

    val root: File get() = File(preferences.downloadsDirectory.get()).apply { mkdirs() }

    private fun sourceDir(source: Source) = File(root, DiskUtil.buildValidFilename(source.toString()))
    fun mangaDir(manga: Manga): File = File(sourceDir(sourceManager.getOrStub(manga.source)), DiskUtil.buildValidFilename(manga.title))

    /** Returns the downloaded folder or .cbz for [chapter], or null. */
    fun findChapter(manga: Manga, chapter: Chapter): File? {
        val dir = mangaDir(manga)
        if (!dir.exists()) return null
        val names = buildList {
            val base = DiskUtil.chapterDirName(chapter.name, chapter.scanlator, chapter.url)
            add(base)
            add("$base.cbz")
            DiskUtil.legacyChapterDirNames(chapter.name, chapter.scanlator).forEach {
                add(it)
                add("$it.cbz")
            }
        }
        return names.asSequence().map { File(dir, it) }.firstOrNull { it.exists() }
    }

    fun isChapterDownloaded(manga: Manga, chapter: Chapter): Boolean =
        manga.source == LocalSource.ID || findChapter(manga, chapter) != null

    fun downloadedCount(manga: Manga): Int {
        val dir = mangaDir(manga)
        return dir.listFiles()?.count { (it.isDirectory && !it.name.endsWith("_tmp")) || it.name.endsWith(".cbz") } ?: 0
    }

    /** Builds the page list of a downloaded chapter (folder of images or CBZ). */
    fun buildPageList(file: File): List<Page> {
        val urls = if (file.isDirectory) {
            file.listFiles()?.filter { it.isFile && it.extension.lowercase() in LocalSource.IMAGE_EXT }
                ?.sortedWith { a, b -> LocalSource.naturalCompare(a.name, b.name) }
                ?.map { it.toURI().toString() }
                .orEmpty()
        } else {
            ZipFile(file).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in LocalSource.IMAGE_EXT }
                    .map { it.name }.sortedWith { a, b -> LocalSource.naturalCompare(a, b) }
                    .map { "${LocalSource.ARCHIVE_SCHEME}${file.absolutePath}!/$it" }.toList()
            }
        }
        return urls.mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    // ---- Queue ----------------------------------------------------------------------------------

    fun restoreQueue() {
        val ids = runCatching { Json.decodeFromString<List<Long>>(queueFile.readText()) }.getOrDefault(emptyList())
        val restored = ids.mapNotNull { id ->
            val chapter = chapterRepository.getById(id) ?: return@mapNotNull null
            val manga = mangaRepository.getById(chapter.mangaId) ?: return@mapNotNull null
            Download(manga, chapter, manga.source)
        }
        _queue.value = restored
    }

    private fun persistQueue() {
        runCatching {
            queueFile.writeText(Json.encodeToString(_queue.value.filter { it.status != Download.Status.DOWNLOADED }.map { it.chapter.id }))
        }
    }

    fun downloadChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean = true) {
        if (manga.source == LocalSource.ID) return
        val existing = _queue.value.map { it.chapter.id }.toSet()
        val toAdd = chapters.filter { it.id !in existing && !isChapterDownloaded(manga, it) }
            .sortedByDescending { it.sourceOrder }
            .map { Download(manga, it, manga.source) }
        if (toAdd.isEmpty()) return
        _queue.update { it + toAdd }
        persistQueue()
        if (autoStart) startDownloads()
    }

    fun startDownloads() {
        if (job?.isActive == true) return
        _queue.update { q -> q.map { if (it.status == Download.Status.ERROR) it.copy(status = Download.Status.QUEUE, error = null) else it } }
        job = scope.launch { runQueue() }
    }

    fun pauseDownloads() {
        val j = job ?: return
        scope.launch {
            j.cancelAndJoin()
            _queue.update { q -> q.map { if (it.status == Download.Status.DOWNLOADING) it.copy(status = Download.Status.QUEUE) else it } }
            _isRunning.value = false
        }
    }

    fun clearQueue() {
        scope.launch {
            job?.cancelAndJoin()
            _queue.value.filter { it.status != Download.Status.DOWNLOADED }.forEach { tmpDir(it).deleteRecursively() }
            _queue.value = emptyList()
            _isRunning.value = false
            persistQueue()
        }
    }

    fun cancel(downloads: List<Download>) {
        val ids = downloads.map { it.chapter.id }.toSet()
        val wasRunning = job?.isActive == true
        scope.launch {
            if (downloads.any { it.status == Download.Status.DOWNLOADING }) job?.cancelAndJoin()
            downloads.forEach { tmpDir(it).deleteRecursively() }
            _queue.update { q -> q.filterNot { it.chapter.id in ids } }
            persistQueue()
            if (wasRunning && _queue.value.isNotEmpty()) startDownloads()
        }
    }

    fun reorder(newOrder: List<Download>) {
        _queue.value = newOrder
        persistQueue()
    }

    fun moveToTop(download: Download) = reorder(listOf(download) + _queue.value.filterNot { it.chapter.id == download.chapter.id })
    fun moveToBottom(download: Download) = reorder(_queue.value.filterNot { it.chapter.id == download.chapter.id } + download)

    private fun tmpDir(d: Download) = File(mangaDir(d.manga), DiskUtil.chapterDirName(d.chapter.name, d.chapter.scanlator, d.chapter.url) + "_tmp")

    private fun updateDownload(chapterId: Long, transform: (Download) -> Download) {
        _queue.update { q -> q.map { if (it.chapter.id == chapterId) transform(it) else it } }
    }

    private suspend fun runQueue() {
        _isRunning.value = true
        try {
            val sourceLimit = Semaphore(preferences.parallelSourceLimit.get().coerceIn(1, 10))
            while (true) {
                val pending = _queue.value.filter { it.status == Download.Status.QUEUE }
                if (pending.isEmpty()) break
                // One chapter at a time per source, up to N sources in parallel (Mihon's behaviour).
                coroutineScope {
                    pending.groupBy { it.sourceId }.map { (_, list) ->
                        async { sourceLimit.withPermit { downloadChapter(list.first()) } }
                    }.awaitAll()
                }
            }
            val finished = _queue.value.count { it.status == Download.Status.DOWNLOADED }
            _queue.update { q -> q.filterNot { it.status == Download.Status.DOWNLOADED } }
            persistQueue()
            if (finished > 0 && _queue.value.isEmpty()) onQueueFinished(finished)
        } finally {
            _isRunning.value = false
        }
    }

    private suspend fun downloadChapter(download: Download) {
        val source = sourceManager.get(download.sourceId)
        if (source == null) {
            updateDownload(download.chapter.id) { it.copy(status = Download.Status.ERROR, error = "Source not installed") }
            return
        }
        updateDownload(download.chapter.id) { it.copy(status = Download.Status.DOWNLOADING, error = null) }
        val tmp = tmpDir(download).apply { mkdirs() }
        try {
            val pages = source.getPageList(download.chapter.toSChapter())
            if (pages.isEmpty()) throw IllegalStateException("Page list is empty")
            updateDownload(download.chapter.id) { it.copy(totalPages = pages.size, downloadedPages = 0) }
            val pageLimit = Semaphore(preferences.parallelPageLimit.get().coerceIn(1, 10))
            coroutineScope {
                pages.map { page ->
                    async {
                        pageLimit.withPermit {
                            downloadPage(source, page, tmp)
                            updateDownload(download.chapter.id) { it.copy(downloadedPages = it.downloadedPages + 1) }
                        }
                    }
                }.awaitAll()
            }
            val images = tmp.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }.orEmpty()
            check(images.size == pages.size) { "Some pages failed to download" }
            val finalName = DiskUtil.chapterDirName(download.chapter.name, download.chapter.scanlator, download.chapter.url)
            val dir = tmp.parentFile
            if (preferences.saveChaptersAsCbz.get()) {
                val cbz = File(dir, "$finalName.cbz")
                val tmpCbz = File(dir, "$finalName.cbz.tmp")
                ZipOutputStream(tmpCbz.outputStream().buffered()).use { zos ->
                    zos.setMethod(ZipOutputStream.STORED)
                    images.sortedBy { it.name }.forEach { img ->
                        val bytes = img.readBytes()
                        val crc = CRC32().apply { update(bytes) }
                        val entry = ZipEntry(img.name).apply {
                            size = bytes.size.toLong()
                            compressedSize = bytes.size.toLong()
                            this.crc = crc.value
                        }
                        zos.putNextEntry(entry)
                        zos.write(bytes)
                        zos.closeEntry()
                    }
                }
                tmpCbz.renameTo(cbz)
                tmp.deleteRecursively()
            } else {
                tmp.renameTo(File(dir, finalName))
            }
            updateDownload(download.chapter.id) { it.copy(status = Download.Status.DOWNLOADED) }
            _cacheVersion.update { it + 1 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            updateDownload(download.chapter.id) { it.copy(status = Download.Status.ERROR, error = e.message ?: e.javaClass.simpleName) }
        }
    }

    private suspend fun downloadPage(source: Source, page: Page, dir: File) {
        val baseName = "%03d".format(page.number)
        if (dir.listFiles()?.any { it.nameWithoutExtension == baseName && !it.name.endsWith(".tmp") } == true) return
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            try {
                val bytes = fetchPageBytes(source, page)
                val ext = imageExtension(bytes)
                val tmpFile = File(dir, "$baseName.tmp")
                tmpFile.writeBytes(bytes)
                tmpFile.renameTo(File(dir, "$baseName.$ext"))
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (++attempt >= 3) throw e
                delay(2000L * attempt)
            }
        }
    }

    /** Resolves the image URL if needed and returns the image bytes (shared with the reader). */
    suspend fun fetchPageBytes(source: Source, page: Page): ByteArray {
        page.imageUrl?.let { url -> LocalSource.openLocal(url)?.let { return it } }
        val http = source as? HttpSource ?: throw IllegalStateException("Source can't serve images")
        if (page.imageUrl.isNullOrEmpty()) {
            page.status = Page.State.LoadPage
            page.imageUrl = http.getImageUrl(page)
        }
        page.status = Page.State.DownloadImage
        return http.getImage(page).use { it.body.bytes() }.also { page.status = Page.State.Ready }
    }

    // ---- Deletion -------------------------------------------------------------------------------

    fun deleteChapters(manga: Manga, chapters: List<Chapter>) {
        if (manga.source == LocalSource.ID) return
        val queued = _queue.value.filter { d -> chapters.any { it.id == d.chapter.id } }
        if (queued.isNotEmpty()) cancel(queued)
        chapters.forEach { findChapter(manga, it)?.deleteRecursively() }
        val dir = mangaDir(manga)
        if (dir.exists() && dir.listFiles().isNullOrEmpty()) dir.delete()
        _cacheVersion.update { it + 1 }
    }

    fun deleteManga(manga: Manga) {
        cancel(_queue.value.filter { it.manga.id == manga.id })
        mangaDir(manga).deleteRecursively()
        _cacheVersion.update { it + 1 }
    }

    fun totalDownloadSize(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        fun imageExtension(bytes: ByteArray): String = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
            bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "gif"
            bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            bytes.size >= 12 && String(bytes, 4, 8, Charsets.US_ASCII).let { it == "ftypavif" || it == "ftypavis" } -> "avif"
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0x0A.toByte() -> "jxl"
            bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "bmp"
            else -> "jpg"
        }
    }
}
