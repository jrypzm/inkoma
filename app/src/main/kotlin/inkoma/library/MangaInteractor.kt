package inkoma.library

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import inkoma.core.AppPreferences
import inkoma.data.CategoryRepository
import inkoma.data.ChapterRepository
import inkoma.data.HistoryRepository
import inkoma.data.MangaRepository
import inkoma.domain.Chapter
import inkoma.domain.Manga
import inkoma.download.DownloadManager
import inkoma.source.SourceManager
import inkoma.ui.components.ImageLoader

/** Business rules shared by Manga/Updates/History/Reader screens (Mihon's interactors). */
class MangaInteractor(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val categoryRepository: CategoryRepository,
    private val historyRepository: HistoryRepository,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val preferences: AppPreferences,
) {
    /** Fetches details and/or chapters from the source and syncs them into the database. Returns new chapters. */
    suspend fun refresh(manga: Manga, fetchDetails: Boolean, fetchChapters: Boolean, manual: Boolean): List<Chapter> = withContext(Dispatchers.IO) {
        val source = sourceManager.getOrStub(manga.source)
        val chapters = chapterRepository.getByMangaId(manga.id).map { it.toSChapter() }
        val update = source.getMangaUpdate(manga.toSManga(), chapters, fetchDetails, fetchChapters)
        if (fetchDetails) {
            val oldThumb = manga.thumbnailUrl
            mangaRepository.updateFromSource(manga, update.manga, manual)
            val newThumb = update.manga.thumbnail_url
            if (manual || (newThumb != null && newThumb != oldThumb)) oldThumb?.let { ImageLoader.invalidate(it) }
        }
        if (!fetchChapters) return@withContext emptyList()
        val current = mangaRepository.getById(manga.id) ?: manga
        val sManga = current.toSManga()
        val http = source as? HttpSource
        val added = chapterRepository.syncWithSource(
            mangaId = manga.id,
            remote = update.chapters,
            manga = sManga,
            prepare = { sc -> http?.prepareNewChapter(sc, sManga) },
            markDuplicateRead = preferences.markDuplicateReadChapterAsRead.get().isNotEmpty(),
        )
        mangaRepository.setLastUpdate(manga.id, System.currentTimeMillis())
        added
    }

    /** Adds to / removes from the library (Mihon: favorite toggle + category assignment). */
    fun setFavorite(manga: Manga, favorite: Boolean, categoryIds: List<Long>?) {
        mangaRepository.setFavorite(manga.id, favorite)
        if (favorite) {
            val ids = categoryIds ?: preferences.defaultCategory.get().takeIf { it > 0 }?.let { listOf(it.toLong()) } ?: emptyList()
            categoryRepository.setMangaCategories(manga.id, ids)
            manga.thumbnailUrl?.let { url -> ImageLoader.coverFile(url) }
        } else {
            categoryRepository.setMangaCategories(manga.id, emptyList())
        }
    }

    fun networkToLocal(sourceId: Long, sManga: SManga): Manga = mangaRepository.networkToLocal(sourceId, sManga)

    fun setRead(manga: Manga, chapters: List<Chapter>, read: Boolean) {
        chapterRepository.setRead(chapters.map { it.id }, read)
        if (read && preferences.removeAfterMarkedAsRead.get()) {
            val deletable = if (preferences.removeBookmarkedChapters.get()) chapters else chapters.filterNot { it.bookmark }
            downloadManager.deleteChapters(manga, deletable)
        }
    }

    fun setBookmark(chapters: List<Chapter>, bookmark: Boolean) {
        chapterRepository.setBookmark(chapters.map { it.id }, bookmark)
    }

    /** Marks every chapter before [chapter] (in reading order) as read. */
    fun markPreviousRead(manga: Manga, all: List<Chapter>, chapter: Chapter) {
        val previous = all.filter { it.sourceOrder > chapter.sourceOrder && !it.read }
        setRead(manga, previous, true)
    }

    /** Saves reader progress; mirrors Mihon's ReaderViewModel.updateChapterProgress/updateHistory. */
    fun saveProgress(manga: Manga, chapter: Chapter, pageIndex: Int, pageCount: Int, sessionMillis: Long) {
        val finished = pageCount > 0 && pageIndex >= pageCount - 1
        val read = chapter.read || finished
        chapterRepository.setProgress(chapter.id, if (finished) 0 else pageIndex.toLong(), read)
        historyRepository.upsert(chapter.id, System.currentTimeMillis(), sessionMillis)
        if (finished && !chapter.read) onChapterFinished(manga, chapter)
    }

    private fun onChapterFinished(manga: Manga, chapter: Chapter) {
        val slots = preferences.removeAfterReadSlots.get()
        if (slots < 0) return
        val chapters = chapterRepository.getByMangaId(manga.id).sortedByDescending { it.sourceOrder }
        val idx = chapters.indexOfFirst { it.id == chapter.id }
        val target = chapters.getOrNull(idx - slots) ?: return
        if (target.bookmark && !preferences.removeBookmarkedChapters.get()) return
        downloadManager.deleteChapters(manga, listOf(target))
    }

    fun nextUnread(mangaId: Long): Chapter? =
        chapterRepository.getByMangaId(mangaId).filter { !it.read }.maxByOrNull { it.sourceOrder }
}
