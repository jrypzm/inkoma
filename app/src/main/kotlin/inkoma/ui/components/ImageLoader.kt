package inkoma.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import inkoma.Inkoma
import inkoma.core.AppDirs
import inkoma.source.LocalSource
import okhttp3.CacheControl
import org.jetbrains.skia.Image as SkImage
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * Minimal image pipeline (memory LRU + disk cache + Skia decode with downsampling) replacing Coil.
 * Memory use is bounded by [MEMORY_BUDGET] bytes of decoded pixels.
 */
object ImageLoader {
    private const val MEMORY_BUDGET = 32L * 1024 * 1024
    private const val DISK_BUDGET = 200L * 1024 * 1024

    private val memory = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {}
    private var memoryBytes = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(4)

    private val diskDir: File get() = File(AppDirs.cache, "coil_disk_cache").apply { mkdirs() }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun ImageBitmap.bytes() = width.toLong() * height * 4

    @Synchronized
    fun getMemory(key: String): ImageBitmap? = memory[key]

    @Synchronized
    private fun putMemory(key: String, bmp: ImageBitmap) {
        memory.put(key, bmp)?.let { memoryBytes -= it.bytes() }
        memoryBytes += bmp.bytes()
        val it = memory.entries.iterator()
        while (memoryBytes > MEMORY_BUDGET && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            memoryBytes -= e.value.bytes()
            it.remove()
        }
    }

    @Synchronized
    fun clearMemory() {
        memory.clear()
        memoryBytes = 0
    }

    fun clearDisk() {
        diskDir.deleteRecursively()
    }

    fun diskCacheSize(): Long = diskDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Library covers are kept outside the evictable cache, like Mihon's CoverCache. */
    fun coverFile(url: String): File = File(AppDirs.covers, md5(url))

    private fun trimDisk() {
        val files = diskDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= DISK_BUDGET) break
            total -= f.length()
            f.delete()
        }
    }

    /** Fetches raw bytes for [url], using the source's client and headers for HTTP. */
    suspend fun fetchBytes(url: String, sourceId: Long?, persistent: Boolean): ByteArray? = withContext(dispatcher) {
        LocalSource.openLocal(url)?.let { return@withContext it }
        val cacheFile = if (persistent) coverFile(url) else File(diskDir, md5(url))
        if (cacheFile.exists() && cacheFile.length() > 0) {
            cacheFile.setLastModified(System.currentTimeMillis())
            return@withContext cacheFile.readBytes()
        }
        if (!url.startsWith("http")) return@withContext null
        val source = sourceId?.let { Inkoma.sourceManager.get(it) } as? HttpSource
        val client = source?.client ?: Inkoma.network.client
        val request = if (source != null) GET(url, source.headers, CacheControl.FORCE_NETWORK) else GET(url, cache = CacheControl.FORCE_NETWORK)
        val bytes = runCatching {
            client.newCall(request).execute().use { resp -> if (resp.isSuccessful) resp.body.bytes() else null }
        }.getOrNull() ?: return@withContext null
        cacheFile.parentFile.mkdirs()
        cacheFile.writeBytes(bytes)
        if (!persistent) trimDisk()
        bytes
    }

    /** Decodes [bytes], downscaling so the longest side is at most [maxSide] (0 = no limit). */
    fun decode(bytes: ByteArray, maxSide: Int): ImageBitmap? = runCatching {
        val image = SkImage.makeFromEncoded(bytes)
        val longest = maxOf(image.width, image.height)
        if (maxSide <= 0 || longest <= maxSide) {
            image.toComposeImageBitmap()
        } else {
            val scale = maxSide.toFloat() / longest
            val w = (image.width * scale).toInt().coerceAtLeast(1)
            val h = (image.height * scale).toInt().coerceAtLeast(1)
            val surface = Surface.makeRasterN32Premul(w, h)
            surface.canvas.drawImageRect(
                image,
                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                Rect.makeWH(w.toFloat(), h.toFloat()),
                SamplingMode.LINEAR,
                null,
                true,
            )
            val snapshot = surface.makeImageSnapshot()
            image.close()
            surface.close()
            snapshot.toComposeImageBitmap()
        }
    }.getOrNull()

    suspend fun load(url: String, sourceId: Long?, maxSide: Int, persistent: Boolean, cacheKeySuffix: String = ""): ImageBitmap? {
        val key = "$url#$maxSide$cacheKeySuffix"
        getMemory(key)?.let { return it }
        val bytes = fetchBytes(url, sourceId, persistent) ?: return null
        val bmp = withContext(dispatcher) { decode(bytes, maxSide) } ?: return null
        putMemory(key, bmp)
        return bmp
    }

    /** Drops cached copies of a cover (used after "refresh"/edit). */
    fun invalidate(url: String) {
        coverFile(url).delete()
        File(diskDir, md5(url)).delete()
        synchronized(this) {
            val it = memory.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key.startsWith("$url#")) {
                    memoryBytes -= e.value.bytes()
                    it.remove()
                }
            }
        }
    }
}

@Composable
fun RemoteImage(
    url: String?,
    sourceId: Long?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxSide: Int = 480,
    persistent: Boolean = false,
    cacheKeySuffix: String = "",
    contentDescription: String? = null,
) {
    var bitmap by remember(url, cacheKeySuffix) { mutableStateOf(url?.let { ImageLoader.getMemory("$it#$maxSide$cacheKeySuffix") }) }
    LaunchedEffect(url, sourceId, cacheKeySuffix) {
        if (bitmap == null && !url.isNullOrBlank()) {
            bitmap = ImageLoader.load(url, sourceId, maxSide, persistent, cacheKeySuffix)
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(painter = BitmapPainter(bmp), contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
