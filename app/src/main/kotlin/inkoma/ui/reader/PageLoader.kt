package inkoma.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import inkoma.Inkoma
import inkoma.domain.Chapter
import inkoma.domain.Manga
import inkoma.source.LocalSource
import inkoma.ui.components.ImageLoader
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap

/** Loads page lists (downloaded → local → online, like Mihon's ChapterLoader) and decoded page bitmaps. */
object PageLoader {
    /** Decoded pages kept in memory; bounded so reading stays within the RAM budget. */
    private const val CACHE_BUDGET = 48L * 1024 * 1024
    private val cache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {}
    private var cacheBytes = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(3)

    suspend fun loadPages(manga: Manga, chapter: Chapter): List<Page> = withContext(Dispatchers.IO) {
        val downloaded = Inkoma.downloadManager.findChapter(manga, chapter)
        when {
            downloaded != null -> Inkoma.downloadManager.buildPageList(downloaded)
            else -> Inkoma.sourceManager.getOrStub(manga.source).getPageList(chapter.toSChapter())
        }.ifEmpty { throw IllegalStateException("No pages found") }
    }

    private fun key(chapterId: Long, page: Page) = "$chapterId:${page.index}:${Inkoma.preferences.sharpening.get()}"

    @Synchronized
    fun cached(chapterId: Long, page: Page): ImageBitmap? = cache[key(chapterId, page)]

    @Synchronized
    private fun put(k: String, bmp: ImageBitmap) {
        cache.put(k, bmp)?.let { cacheBytes -= it.width.toLong() * it.height * 4 }
        cacheBytes += bmp.width.toLong() * bmp.height * 4
        val it = cache.entries.iterator()
        while (cacheBytes > CACHE_BUDGET && cache.size > 2 && it.hasNext()) {
            val e = it.next()
            if (e.key == k) continue
            cacheBytes -= e.value.width.toLong() * e.value.height * 4
            it.remove()
        }
    }

    @Synchronized
    fun clear() {
        cache.clear()
        cacheBytes = 0
    }

    /** Fetches and decodes a page (see [decodePage] for the size caps). */
    suspend fun loadImage(manga: Manga, chapterId: Long, page: Page): ImageBitmap = withContext(io) {
        cached(chapterId, page)?.let { return@withContext it }
        val source = Inkoma.sourceManager.getOrStub(manga.source)
        val bytes = page.imageUrl?.let { LocalSource.openLocal(it) } ?: Inkoma.downloadManager.fetchPageBytes(source, page)
        val bmp = decodePage(bytes) ?: throw IllegalStateException("Unsupported image format")
        put(key(chapterId, page), bmp)
        bmp
    }

    private fun decodePage(bytes: ByteArray): ImageBitmap? {
        val img = runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
        val w = img.width.toDouble()
        val h = img.height.toDouble()
        img.close()
        // Cap width at 2048 px and total size at 8 MP (32 MB decoded) to bound memory on huge webtoon strips.
        val scale = minOf(1.0, 2048.0 / w, kotlin.math.sqrt(8_000_000.0 / (w * h)))
        val maxSide = if (scale >= 1.0) 0 else (maxOf(w, h) * scale).toInt()
        val decoded = ImageLoader.decode(bytes, maxSide) ?: return null
        val level = Inkoma.preferences.sharpening.get()
        return if (level > 0) sharpen(decoded, level) else decoded
    }

    /** Unsharp-style 3×3 convolution; [level] 1..10 maps to strength 0.1..1.0. */
    private fun sharpen(bitmap: ImageBitmap, level: Int): ImageBitmap = runCatching {
        val a = level / 10f
        val kernel = floatArrayOf(0f, -a, 0f, -a, 1f + 4f * a, -a, 0f, -a, 0f)
        val source = org.jetbrains.skia.Image.makeFromBitmap(bitmap.asSkiaBitmap())
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(bitmap.width, bitmap.height)
        val paint = org.jetbrains.skia.Paint().apply {
            imageFilter = org.jetbrains.skia.ImageFilter.makeMatrixConvolution(
                3, 3, kernel, 1f, 0f, 1, 1, org.jetbrains.skia.FilterTileMode.CLAMP, false, null, null,
            )
        }
        surface.canvas.drawImage(source, 0f, 0f, paint)
        surface.makeImageSnapshot().toComposeImageBitmap().also { surface.close(); source.close() }
    }.getOrDefault(bitmap)
}
