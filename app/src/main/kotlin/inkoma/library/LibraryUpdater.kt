package inkoma.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import inkoma.core.AppPreferences
import inkoma.data.CategoryRepository
import inkoma.data.LibraryRepository
import inkoma.domain.Category
import inkoma.domain.LibraryManga
import inkoma.download.DownloadManager
import inkoma.source.SourceManager
import java.util.concurrent.TimeUnit

data class LibraryUpdateProgress(val running: Boolean, val current: Int, val total: Int, val title: String?, val errors: List<Pair<String, String>>, val newChapters: Int)

/** Port of Mihon's LibraryUpdateJob, scheduled in-process while the app runs. */
class LibraryUpdater(
    private val preferences: AppPreferences,
    private val libraryRepository: LibraryRepository,
    private val categoryRepository: CategoryRepository,
    private val interactor: MangaInteractor,
    private val downloadManager: DownloadManager,
    private val sourceManager: SourceManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Invoked after each completed update with the number of new chapters. */
    var onFinished: (newChapters: Int, errors: Int, updated: List<Pair<inkoma.domain.Manga, Int>>) -> Unit = { _, _, _ -> }

    private val _progress = MutableStateFlow(LibraryUpdateProgress(false, 0, 0, null, emptyList(), 0))
    val progress: StateFlow<LibraryUpdateProgress> = _progress.asStateFlow()

    fun startScheduler() {
        scope.launch {
            while (isActive) {
                val hours = preferences.libraryUpdateInterval.get()
                if (hours > 0) {
                    val due = preferences.lastLibraryUpdate.get() + TimeUnit.HOURS.toMillis(hours.toLong())
                    if (System.currentTimeMillis() >= due && job?.isActive != true) start(null)
                }
                delay(TimeUnit.MINUTES.toMillis(15))
            }
        }
    }

    fun isRunning(): Boolean = job?.isActive == true

    fun cancel() {
        job?.cancel()
        _progress.value = _progress.value.copy(running = false)
    }

    /** Starts an update for [category] (null = categories selected in settings). Returns false if already running. */
    fun start(category: Category?): Boolean {
        if (job?.isActive == true) return false
        job = scope.launch { run(category) }
        return true
    }

    private fun selectManga(category: Category?): List<LibraryManga> {
        val library = libraryRepository.getLibrary()
        val byManga = library.groupBy { it.manga.id }
        val candidates = if (category != null) {
            library.filter { it.categoryId == category.id }
        } else {
            val include = preferences.libraryUpdateCategories.get().mapNotNull { it.toLongOrNull() }.toSet()
            val exclude = preferences.libraryUpdateCategoriesExclude.get().mapNotNull { it.toLongOrNull() }.toSet()
            val included = if (include.isEmpty()) library else library.filter { it.categoryId in include }
            val excludedIds = library.filter { it.categoryId in exclude }.map { it.manga.id }.toSet()
            included.filterNot { it.manga.id in excludedIds }
        }.distinctBy { it.manga.id }

        val restrictions = preferences.libraryUpdateMangaRestriction.get()
        return candidates.filter { item ->
            val m = item.manga
            val agg = byManga[m.id]?.first() ?: item
            when {
                m.updateStrategy != eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE -> false
                AppPreferences.MANGA_NON_COMPLETED in restrictions && m.status.toInt() == eu.kanade.tachiyomi.source.model.SManga.COMPLETED -> false
                AppPreferences.MANGA_HAS_UNREAD in restrictions && agg.unreadCount != 0L -> false
                AppPreferences.MANGA_NON_READ in restrictions && agg.totalChapters > 0L && !agg.hasStarted -> false
                else -> true
            }
        }.sortedBy { it.manga.title.lowercase() }
    }

    private suspend fun run(category: Category?) {
        val targets = selectManga(category)
        val errors = ArrayList<Pair<String, String>>()
        var done = 0
        var newCount = 0
        val updated = ArrayList<Pair<inkoma.domain.Manga, Int>>()
        _progress.value = LibraryUpdateProgress(true, 0, targets.size, null, emptyList(), 0)
        val downloadCats = preferences.downloadNewChapterCategories.get().mapNotNull { it.toLongOrNull() }.toSet()
        val downloadExclude = preferences.downloadNewChapterCategoriesExclude.get().mapNotNull { it.toLongOrNull() }.toSet()
        val perSource = Semaphore(5)
        try {
            targets.groupBy { it.manga.source }.map { (_, items) ->
                scope.async {
                    perSource.withPermit {
                        for (item in items) {
                            val manga = item.manga
                            _progress.value = _progress.value.copy(title = manga.title)
                            try {
                                val added = interactor.refresh(manga, fetchDetails = preferences.autoUpdateMetadata.get(), fetchChapters = true, manual = false)
                                if (added.isNotEmpty()) {
                                    newCount += added.size
                                    synchronized(updated) { updated.add(manga to added.size) }
                                    val shouldDownload = preferences.downloadNewChapters.get() &&
                                        (downloadCats.isEmpty() || item.categoryId in downloadCats) && item.categoryId !in downloadExclude
                                    if (shouldDownload) {
                                        val toDownload = if (preferences.downloadNewUnreadChaptersOnly.get()) added.filterNot { it.read } else added
                                        downloadManager.downloadChapters(manga, toDownload)
                                    }
                                }
                            } catch (e: Throwable) {
                                synchronized(errors) { errors.add(manga.title to (e.message ?: e.javaClass.simpleName)) }
                            }
                            synchronized(this@LibraryUpdater) { done++ }
                            _progress.value = _progress.value.copy(current = done, errors = errors.toList(), newChapters = newCount)
                        }
                    }
                }
            }.awaitAll()
            preferences.lastLibraryUpdate.set(System.currentTimeMillis())
            onFinished(newCount, errors.size, updated.toList())
        } finally {
            _progress.value = _progress.value.copy(running = false, title = null)
        }
    }
}
