package inkoma.backup

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.PreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.core.common.extensions.toByteArray
import mihon.core.common.extensions.toJsonObject
import inkoma.core.AppDirs
import inkoma.core.AppPreferences
import inkoma.core.PreferenceStore
import inkoma.data.CategoryRepository
import inkoma.data.ChapterRepository
import inkoma.data.ExtensionStoreRepository
import inkoma.data.HistoryRepository
import inkoma.data.MangaRepository
import inkoma.domain.Chapter
import inkoma.domain.ExtensionStore
import inkoma.domain.Manga
import inkoma.source.SourceManager
import kotlinx.serialization.json.JsonObject
import okio.buffer
import okio.gzip
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class BackupOptions(
    val libraryEntries: Boolean = true,
    val categories: Boolean = true,
    val chapters: Boolean = true,
    val history: Boolean = true,
    val appSettings: Boolean = true,
    val extensionStores: Boolean = true,
    val sourceSettings: Boolean = true,
    val privateSettings: Boolean = false,
    val readEntries: Boolean = true,
)

sealed interface BackupState {
    data object Idle : BackupState
    data class Running(val label: String, val progress: Int, val total: Int) : BackupState
    data class Done(val message: String, val errors: List<String> = emptyList()) : BackupState
    data class Failed(val message: String) : BackupState
}

/** Creates and restores Mihon `.tachibk` backups (gzip-compressed protobuf), byte-compatible with Android. */
@OptIn(ExperimentalSerializationApi::class)
class BackupManager(
    private val preferences: AppPreferences,
    private val preferenceStore: PreferenceStore,
    private val application: Application,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val categoryRepository: CategoryRepository,
    private val historyRepository: HistoryRepository,
    private val storeRepository: ExtensionStoreRepository,
    private val sourceManager: SourceManager,
) {
    private val parser = ProtoBuf
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    fun filename(): String = "inkoma_" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ENGLISH).format(Date()) + ".tachibk"

    // ---- Create ---------------------------------------------------------------------------------

    fun createBackup(file: File, options: BackupOptions = BackupOptions()) {
        scope.launch {
            _state.value = BackupState.Running("Creating backup", 0, 0)
            try {
                writeBackup(file, options)
                _state.value = BackupState.Done("Backup created: ${file.absolutePath}")
            } catch (e: Throwable) {
                _state.value = BackupState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    suspend fun writeBackup(file: File, options: BackupOptions) = withContext(Dispatchers.IO) {
        val favorites = if (options.libraryEntries) mangaRepository.getFavorites() else emptyList()
        val mangas = favorites.map { backupManga(it, options) }
            .filter { options.readEntries || it.chapters.none { c -> c.read } || it.favorite }
        val categories = if (options.categories) {
            categoryRepository.getAll().filterNot { it.isSystemCategory }.map { BackupCategory(name = it.name, order = it.order, id = it.id, flags = it.flags) }
        } else {
            emptyList()
        }
        val sources = mangas.map { it.source }.distinct().map { id -> BackupSource(name = sourceManager.getOrStub(id).name, sourceId = id) }
        val prefs = if (options.appSettings) backupPreferences(preferenceStore.prefs.getAll(), options.privateSettings) else emptyList()
        val sourcePrefs = if (options.sourceSettings) backupSourcePreferences(options.privateSettings) else emptyList()
        val stores = if (options.extensionStores) {
            storeRepository.getAll().map {
                BackupExtensionStore(it.indexUrl, it.name, it.badgeLabel, it.signingKey, it.contactWebsite, it.contactDiscord, it.isLegacy, it.extensionListUrl)
            }
        } else {
            emptyList()
        }
        val backup = Backup(
            backupManga = mangas,
            backupCategories = categories,
            backupSources = sources,
            backupPreferences = prefs,
            backupSourcePreferences = sourcePrefs,
            backupExtensionStores = stores,
        )
        val bytes = parser.encodeToByteArray(Backup.serializer(), backup)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.sink().gzip().buffer().use { it.write(bytes) }
        // Validate by decoding before replacing the destination.
        decode(tmp)
        tmp.renameTo(file).also { if (!it) { file.delete(); tmp.renameTo(file) } }
    }

    private fun backupManga(manga: Manga, options: BackupOptions): BackupManga {
        val chapters = if (options.chapters) chapterRepository.getByMangaId(manga.id) else emptyList()
        val categoryOrders = if (options.categories) categoryRepository.getForManga(manga.id).map { it.order } else emptyList()
        val history = if (options.history) {
            val byChapter = chapters.associateBy { it.id }
            historyRepository.getByMangaId(manga.id).mapNotNull { h ->
                val ch = byChapter[h.chapterId] ?: return@mapNotNull null
                if (h.readAt <= 0) null else BackupHistory(ch.url, h.readAt, h.readDuration)
            }
        } else {
            emptyList()
        }
        return BackupManga(
            source = manga.source,
            url = manga.url,
            title = manga.title,
            artist = manga.artist,
            author = manga.author,
            description = manga.description,
            genre = manga.genre.orEmpty(),
            status = manga.status.toInt(),
            thumbnailUrl = manga.thumbnailUrl,
            dateAdded = manga.dateAdded,
            viewer = (manga.viewerFlags and Manga.READING_MODE_MASK).toInt(),
            chapters = chapters.map {
                BackupChapter(
                    url = it.url, name = it.name, scanlator = it.scanlator, read = it.read, bookmark = it.bookmark,
                    lastPageRead = it.lastPageRead, dateFetch = it.dateFetch, dateUpload = it.dateUpload,
                    chapterNumber = it.chapterNumber.toFloat(), sourceOrder = it.sourceOrder, memo = it.memo.toByteArray(),
                )
            },
            categories = categoryOrders,
            favorite = manga.favorite,
            chapterFlags = manga.chapterFlags.toInt(),
            viewer_flags = manga.viewerFlags.toInt(),
            history = history,
            updateStrategy = manga.updateStrategy,
            excludedScanlators = mangaRepository.getExcludedScanlators(manga.id).toList(),
            notes = manga.notes,
            initialized = manga.initialized,
            memo = manga.memo.toByteArray(),
            nextUpdate = manga.nextUpdate,
        )
    }

    private fun isPrivate(key: String) = key.startsWith("__PRIVATE_")
    private fun isAppState(key: String) = key.startsWith("__APP_STATE_")

    private fun toValue(v: Any?): PreferenceValue? = when (v) {
        is Int -> IntPreferenceValue(v)
        is Long -> LongPreferenceValue(v)
        is Float -> FloatPreferenceValue(v)
        is String -> StringPreferenceValue(v)
        is Boolean -> BooleanPreferenceValue(v)
        is Set<*> -> StringSetPreferenceValue(v.map { it.toString() }.toSet())
        else -> null
    }

    private fun backupPreferences(all: Map<String, *>, includePrivate: Boolean): List<BackupPreference> =
        all.filterKeys { !isAppState(it) && (includePrivate || !isPrivate(it)) }
            .mapNotNull { (k, v) -> toValue(v)?.let { BackupPreference(k, it) } }

    private fun backupSourcePreferences(includePrivate: Boolean): List<BackupSourcePreferences> =
        sourceManager.getOnlineSources().mapNotNull { source ->
            val key = "source_${source.id}"
            val prefs = application.getSharedPreferences(key, Context.MODE_PRIVATE).getAll()
            if (prefs.isEmpty()) null else BackupSourcePreferences(key, backupPreferences(prefs, includePrivate))
        }

    // ---- Restore --------------------------------------------------------------------------------

    fun decode(file: File): Backup {
        val raw = file.source().buffer().use { it.readByteArray() }
        if (raw.size >= 2) {
            val magic = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
            if (magic == 0x7b7d || magic == 0x7b22 || magic == 0x7b0a) {
                throw IOException("JSON backups are not supported. Use a .tachibk (protobuf) backup, as on Android.")
            }
        }
        val bytes = if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            raw.inputStream().source().gzip().buffer().use { it.readByteArray() }
        } else {
            raw
        }
        return try {
            parser.decodeFromByteArray(Backup.serializer(), bytes)
        } catch (e: Exception) {
            throw IOException("Invalid backup file", e)
        }
    }

    /** Returns (missing sources, total manga) for the restore confirmation dialog, as Mihon's BackupFileValidator. */
    fun validate(file: File): Pair<List<String>, Int> {
        val backup = decode(file)
        val sourceNames = backup.backupSources.associate { it.sourceId to it.name }
        val missing = backup.backupManga.map { it.source }.distinct()
            .filter { sourceManager.get(it) == null }
            .map { sourceNames[it] ?: it.toString() }
            .sorted()
        return missing to backup.backupManga.size
    }

    fun restoreBackup(file: File) {
        scope.launch {
            val errors = ArrayList<String>()
            try {
                val backup = decode(file)
                val total = backup.backupManga.size
                _state.value = BackupState.Running("Restoring backup", 0, total)
                restoreCategories(backup.backupCategories)
                if (backup.backupPreferences.isNotEmpty()) restorePreferences(backup.backupPreferences, preferenceStore.prefs)
                backup.backupSourcePreferences.forEach { sp ->
                    restorePreferences(sp.prefs, application.getSharedPreferences(sp.sourceKey, Context.MODE_PRIVATE))
                }
                backup.backupExtensionStores.forEach {
                    storeRepository.upsert(
                        ExtensionStore(
                            indexUrl = it.indexUrl, name = it.name, badgeLabel = it.badgeLabel ?: it.name, signingKey = it.signingKey,
                            contactWebsite = it.contactWebsite, contactDiscord = it.contactDiscord, isLegacy = it.isLegacy ?: false,
                            extensionListUrl = it.extensionListUrl,
                        ),
                    )
                }
                val categoriesByOrder = backup.backupCategories.associateBy { it.order }
                backup.backupManga.forEachIndexed { index, bm ->
                    try {
                        restoreManga(bm, categoriesByOrder)
                    } catch (e: Throwable) {
                        errors += "${bm.title}: ${e.message}"
                    }
                    _state.value = BackupState.Running("Restoring ${bm.title}", index + 1, total)
                }
                _state.value = BackupState.Done("Restore completed (${total - errors.size}/$total)", errors)
            } catch (e: Throwable) {
                _state.value = BackupState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun restoreCategories(backupCategories: List<BackupCategory>) {
        val existing = categoryRepository.getAll()
        val names = existing.map { it.name.lowercase() }.toSet()
        var nextOrder = (existing.maxOfOrNull { it.order } ?: 0) + 1
        backupCategories.sortedBy { it.order }.forEach { bc ->
            if (bc.name.lowercase() !in names) {
                categoryRepository.insertWithOrder(bc.name, nextOrder++, bc.flags)
            }
        }
    }

    private fun restorePreferences(prefs: List<BackupPreference>, target: android.content.SharedPreferences) {
        val editor = target.edit()
        prefs.forEach { p ->
            when (val v = p.value) {
                is IntPreferenceValue -> editor.putInt(p.key, v.value)
                is LongPreferenceValue -> editor.putLong(p.key, v.value)
                is FloatPreferenceValue -> editor.putFloat(p.key, v.value)
                is StringPreferenceValue -> editor.putString(p.key, v.value)
                is BooleanPreferenceValue -> editor.putBoolean(p.key, v.value)
                is StringSetPreferenceValue -> editor.putStringSet(p.key, v.value)
            }
        }
        editor.apply()
    }

    private fun memo(bytes: ByteArray): JsonObject = runCatching { bytes.toJsonObject() }.getOrDefault(JsonObject(emptyMap()))

    /** Mirrors Mihon's MangaRestorer: merges with an existing entry or inserts a new one. */
    private fun restoreManga(bm: BackupManga, categoriesByOrder: Map<Long, BackupCategory>) {
        val existing = mangaRepository.getByUrlAndSource(bm.url, bm.source)
        val viewerFlags = (bm.viewer_flags ?: bm.viewer).toLong()
        val mangaId = if (existing == null) {
            mangaRepository.insertFull(
                Manga(
                    id = -1, source = bm.source, url = bm.url, title = bm.title, artist = bm.artist, author = bm.author,
                    description = bm.description, genre = bm.genre, status = bm.status.toLong(), thumbnailUrl = bm.thumbnailUrl,
                    favorite = bm.favorite, lastUpdate = 0, nextUpdate = bm.nextUpdate, initialized = bm.initialized,
                    viewerFlags = viewerFlags, chapterFlags = bm.chapterFlags.toLong(), coverLastModified = 0,
                    dateAdded = if (bm.favorite) bm.dateAdded.takeIf { it > 0 } ?: bm.favoriteModifiedAt?.times(1000) ?: System.currentTimeMillis() else 0,
                    updateStrategy = bm.updateStrategy, notes = bm.notes, memo = memo(bm.memo),
                ),
            )
        } else {
            mangaRepository.updateFull(
                existing.copy(
                    title = if (existing.favorite) existing.title else bm.title,
                    artist = bm.artist ?: existing.artist,
                    author = bm.author ?: existing.author,
                    description = bm.description ?: existing.description,
                    genre = bm.genre.ifEmpty { existing.genre },
                    thumbnailUrl = bm.thumbnailUrl ?: existing.thumbnailUrl,
                    favorite = existing.favorite || bm.favorite,
                    viewerFlags = viewerFlags,
                    chapterFlags = bm.chapterFlags.toLong(),
                    dateAdded = if (existing.favorite) existing.dateAdded else bm.dateAdded,
                    updateStrategy = bm.updateStrategy,
                    notes = bm.notes.ifEmpty { existing.notes },
                    initialized = existing.initialized || bm.initialized,
                ),
            )
            existing.id
        }

        // Chapters: merge read/bookmark/progress, keep the "most read" state (Mihon's restoreChapters).
        val dbChapters = chapterRepository.getByMangaId(mangaId).associateBy { it.url }
        bm.chapters.forEach { bc ->
            val local = dbChapters[bc.url]
            if (local == null) {
                chapterRepository.insert(
                    Chapter(
                        id = -1, mangaId = mangaId, url = bc.url, name = bc.name, scanlator = bc.scanlator, read = bc.read,
                        bookmark = bc.bookmark, lastPageRead = bc.lastPageRead.coerceIn(0L, Int.MAX_VALUE.toLong()),
                        chapterNumber = bc.chapterNumber.toDouble(), sourceOrder = bc.sourceOrder, dateFetch = bc.dateFetch,
                        dateUpload = bc.dateUpload, lastModifiedAt = 0, memo = memo(bc.memo),
                    ),
                )
            } else {
                chapterRepository.update(
                    local.copy(
                        read = local.read || bc.read,
                        bookmark = local.bookmark || bc.bookmark,
                        lastPageRead = if (local.read || bc.read) 0 else maxOf(local.lastPageRead, bc.lastPageRead),
                    ),
                )
            }
        }

        // Categories, by name through the backup's category order list.
        val dbCategories = categoryRepository.getAll()
        val ids = bm.categories.mapNotNull { order ->
            val name = categoriesByOrder[order]?.name ?: return@mapNotNull null
            dbCategories.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
        }
        if (ids.isNotEmpty()) {
            val current = categoryRepository.getForManga(mangaId).map { it.id }
            categoryRepository.setMangaCategories(mangaId, (current + ids).distinct())
        }

        // History
        val chaptersByUrl = chapterRepository.getByMangaId(mangaId).associateBy { it.url }
        bm.history.forEach { h ->
            val ch = chaptersByUrl[h.url] ?: return@forEach
            historyRepository.restore(ch.id, h.lastRead, h.readDuration)
        }

        if (bm.excludedScanlators.isNotEmpty()) {
            mangaRepository.setExcludedScanlators(mangaId, mangaRepository.getExcludedScanlators(mangaId) + bm.excludedScanlators)
        }
    }

    // ---- Automatic backups ----------------------------------------------------------------------

    fun startAutoBackupScheduler() {
        scope.launch {
            while (isActive) {
                val hours = preferences.backupInterval.get()
                if (hours > 0) {
                    val due = preferences.lastAutoBackup.get() + TimeUnit.HOURS.toMillis(hours.toLong())
                    if (System.currentTimeMillis() >= due) {
                        runCatching {
                            val dir = AppDirs.autoBackups
                            writeBackup(File(dir, filename()), BackupOptions())
                            preferences.lastAutoBackup.set(System.currentTimeMillis())
                            dir.listFiles { f -> f.name.endsWith(".tachibk") }?.sortedByDescending { it.lastModified() }
                                ?.drop(preferences.numberOfBackups.get().coerceAtLeast(1))?.forEach { it.delete() }
                        }
                    }
                }
                delay(TimeUnit.MINUTES.toMillis(30))
            }
        }
    }

    fun resetState() {
        _state.value = BackupState.Idle
    }
}
