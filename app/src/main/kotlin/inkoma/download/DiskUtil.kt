package inkoma.download

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Port of Mihon's DiskUtil / ChapterDirNameParts naming so download folders are interchangeable with Android. */
object DiskUtil {
    const val MAX_FILE_NAME_BYTES = 240

    fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun buildValidFilename(origName: String, maxBytes: Int = MAX_FILE_NAME_BYTES): String {
        val name = origName.trim('.', ' ')
        if (name.isEmpty()) return "(invalid)"
        val sb = StringBuilder(name.length)
        name.forEach { c -> sb.append(if (isValidFatFilenameChar(c)) c else '_') }
        return truncateToLength(sb.toString(), maxBytes)
    }

    private fun truncateToLength(s: String, maxBytes: Int): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return s
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE)
        val bb = ByteBuffer.wrap(bytes, 0, maxBytes)
        val cb = CharBuffer.allocate(maxBytes)
        decoder.decode(bb, cb, true)
        decoder.flush(cb)
        return String(cb.array(), 0, cb.position())
    }

    private fun isValidFatFilenameChar(c: Char): Boolean {
        if (c in '\u0000'..'\u001f') return false
        return when (c) {
            '"', '*', '/', ':', '<', '>', '?', '\\', '|', '\u007f' -> false
            else -> true
        }
    }

    fun chapterDirName(chapterName: String, scanlator: String?, chapterUrl: String): String {
        val name = chapterName.ifBlank { "Chapter" }
        val base = buildValidFilename(if (scanlator.isNullOrBlank()) name else "${scanlator}_$name", MAX_FILE_NAME_BYTES - 11)
        return base + "_" + md5(chapterUrl).take(6)
    }

    /** Names used before the hash suffix was introduced; still recognised as downloaded. */
    fun legacyChapterDirNames(chapterName: String, scanlator: String?): List<String> {
        val name = chapterName.ifBlank { "Chapter" }
        return listOf(buildValidFilename(if (scanlator.isNullOrBlank()) name else "${scanlator}_$name"))
    }
}
