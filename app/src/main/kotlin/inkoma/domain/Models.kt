package inkoma.domain

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonObject

data class Manga(
    val id: Long,
    val source: Long,
    val url: String,
    val title: String,
    val artist: String?,
    val author: String?,
    val description: String?,
    val genre: List<String>?,
    val status: Long,
    val thumbnailUrl: String?,
    val favorite: Boolean,
    val lastUpdate: Long,
    val nextUpdate: Long,
    val initialized: Boolean,
    val viewerFlags: Long,
    val chapterFlags: Long,
    val coverLastModified: Long,
    val dateAdded: Long,
    val updateStrategy: UpdateStrategy,
    val notes: String,
    val memo: JsonObject = JsonObject(emptyMap()),
) {
    /** Reading mode stored in the low 3 bits of viewer flags (0 = default), like Mihon. */
    val readingModeOrdinal: Int get() = (viewerFlags and READING_MODE_MASK).toInt() - 1

    fun toSManga(): SManga = SManga.create().also {
        it.url = url
        it.title = title
        it.artist = artist
        it.author = author
        it.description = description
        it.genre = genre?.joinToString(", ")
        it.status = status.toInt()
        it.thumbnail_url = thumbnailUrl
        it.update_strategy = updateStrategy
        it.initialized = initialized
        it.memo = memo
    }

    companion object {
        const val READING_MODE_MASK = 0x00000007L

        // Chapter flags (same bit layout as Mihon)
        const val CHAPTER_SORT_DESC = 0x00000000L
        const val CHAPTER_SORT_ASC = 0x00000001L
        const val CHAPTER_SORT_DIR_MASK = 0x00000001L
        const val CHAPTER_SHOW_UNREAD = 0x00000002L
        const val CHAPTER_SHOW_READ = 0x00000004L
        const val CHAPTER_UNREAD_MASK = 0x00000006L
        const val CHAPTER_SHOW_DOWNLOADED = 0x00000008L
        const val CHAPTER_SHOW_NOT_DOWNLOADED = 0x00000010L
        const val CHAPTER_DOWNLOADED_MASK = 0x00000018L
        const val CHAPTER_SHOW_BOOKMARKED = 0x00000020L
        const val CHAPTER_SHOW_NOT_BOOKMARKED = 0x00000040L
        const val CHAPTER_BOOKMARKED_MASK = 0x00000060L
        const val CHAPTER_SORTING_SOURCE = 0x00000000L
        const val CHAPTER_SORTING_NUMBER = 0x00000100L
        const val CHAPTER_SORTING_UPLOAD_DATE = 0x00000200L
        const val CHAPTER_SORTING_ALPHABET = 0x00000300L
        const val CHAPTER_SORTING_MASK = 0x00000300L
        const val CHAPTER_DISPLAY_NAME = 0x00000000L
        const val CHAPTER_DISPLAY_NUMBER = 0x00100000L
        const val CHAPTER_DISPLAY_MASK = 0x00100000L
    }
}

data class Chapter(
    val id: Long,
    val mangaId: Long,
    val url: String,
    val name: String,
    val scanlator: String?,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Long,
    val chapterNumber: Double,
    val sourceOrder: Long,
    val dateFetch: Long,
    val dateUpload: Long,
    val lastModifiedAt: Long,
    val memo: JsonObject = JsonObject(emptyMap()),
) {
    val isRecognizedNumber: Boolean get() = chapterNumber >= 0.0

    fun toSChapter(): SChapter = SChapter.create().also {
        it.url = url
        it.name = name
        it.date_upload = dateUpload
        it.chapter_number = chapterNumber.toFloat()
        it.scanlator = scanlator
        it.memo = memo
    }
}

data class Category(val id: Long, val name: String, val order: Long, val flags: Long) {
    val isSystemCategory: Boolean get() = id == UNCATEGORIZED_ID

    companion object {
        const val UNCATEGORIZED_ID = 0L
    }
}

data class History(val id: Long, val chapterId: Long, val readAt: Long, val readDuration: Long)

/** Row shown on the History screen. */
data class HistoryWithRelations(
    val id: Long,
    val chapterId: Long,
    val mangaId: Long,
    val title: String,
    val thumbnailUrl: String?,
    val source: Long,
    val favorite: Boolean,
    val coverLastModified: Long,
    val chapterNumber: Double,
    val chapterName: String,
    val readAt: Long,
    val readDuration: Long,
)

/** Row shown on the Updates screen. */
data class UpdatesWithRelations(
    val mangaId: Long,
    val mangaTitle: String,
    val chapterId: Long,
    val chapterName: String,
    val scanlator: String?,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Long,
    val source: Long,
    val thumbnailUrl: String?,
    val coverLastModified: Long,
    val dateUpload: Long,
    val dateFetch: Long,
)

/** Library item with aggregated chapter information. */
data class LibraryManga(
    val manga: Manga,
    val categoryId: Long,
    val totalChapters: Long,
    val readCount: Long,
    val bookmarkCount: Long,
    val latestUpload: Long,
    val chapterFetchedAt: Long,
    val lastRead: Long,
) {
    val unreadCount: Long get() = totalChapters - readCount
    val hasBookmarks: Boolean get() = bookmarkCount > 0
    val hasStarted: Boolean get() = readCount > 0
}

/** An extension store (formerly "extension repo"), same fields as Mihon's ExtensionStore. */
data class ExtensionStore(
    val indexUrl: String,
    val name: String,
    val badgeLabel: String,
    val signingKey: String,
    val contactWebsite: String,
    val contactDiscord: String?,
    val isLegacy: Boolean,
    val extensionListUrl: String?,
)
