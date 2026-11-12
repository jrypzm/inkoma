package inkoma

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import inkoma.backup.BackupManager
import inkoma.core.AppDirs
import inkoma.core.AppPreferences
import inkoma.core.BuildInfo
import inkoma.core.DesktopIntegration
import inkoma.core.PreferenceStore
import inkoma.data.CategoryRepository
import inkoma.data.ChapterRepository
import inkoma.data.Database
import inkoma.data.ExtensionStoreRepository
import inkoma.data.HistoryRepository
import inkoma.data.LibraryRepository
import inkoma.data.MangaRepository
import inkoma.data.UpdatesRepository
import inkoma.download.DownloadManager
import inkoma.extension.ExtensionManager
import inkoma.extension.ExtensionStoreService
import inkoma.library.LibraryUpdater
import inkoma.library.MangaInteractor
import inkoma.source.LocalSource
import inkoma.source.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import java.io.File

/** Application container: owns every singleton and registers what extensions expect in Injekt. */
object Inkoma {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var application: Application private set
    lateinit var preferenceStore: PreferenceStore private set
    lateinit var preferences: AppPreferences private set
    lateinit var network: NetworkHelper private set
    lateinit var database: Database private set
    lateinit var mangaRepository: MangaRepository private set
    lateinit var chapterRepository: ChapterRepository private set
    lateinit var categoryRepository: CategoryRepository private set
    lateinit var libraryRepository: LibraryRepository private set
    lateinit var historyRepository: HistoryRepository private set
    lateinit var updatesRepository: UpdatesRepository private set
    lateinit var extensionStoreRepository: ExtensionStoreRepository private set
    lateinit var sourceManager: SourceManager private set
    lateinit var extensionManager: ExtensionManager private set
    lateinit var downloadManager: DownloadManager private set
    lateinit var libraryUpdater: LibraryUpdater private set
    lateinit var mangaInteractor: MangaInteractor private set
    lateinit var backupManager: BackupManager private set

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun init() {
        AppInfo.configure(BuildInfo.versionCode, BuildInfo.versionName)
        application = Application(AppDirs.data)
        val appPrefs: SharedPreferences = application.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        preferenceStore = PreferenceStore(appPrefs)
        preferences = AppPreferences(preferenceStore)

        network = NetworkHelper(
            cacheDir = application.getCacheDir(),
            dataDir = AppDirs.data,
            userAgent = { preferences.defaultUserAgent.get() },
            dohProvider = preferences.dohProvider.get(),
            verboseLogging = preferences.verboseLogging.get(),
        )

        // Objects extensions obtain through Injekt (see Mihon's AppModule).
        Injekt.addSingleton<Application>(application)
        Injekt.addSingleton<Context>(application)
        Injekt.addSingleton(network)
        Injekt.addSingleton(json)
        Injekt.addSingleton(ProtoBuf)
        Injekt.addSingletonFactory { JavaScriptEngine(application) }

        database = Database(AppDirs.database)
        mangaRepository = MangaRepository(database)
        chapterRepository = ChapterRepository(database)
        categoryRepository = CategoryRepository(database)
        libraryRepository = LibraryRepository(database)
        historyRepository = HistoryRepository(database)
        updatesRepository = UpdatesRepository(database)
        extensionStoreRepository = ExtensionStoreRepository(database)

        val localSource = LocalSource { File(AppDirs.data, "local") }
        sourceManager = SourceManager(localSource)
        extensionManager = ExtensionManager(network, preferences, extensionStoreRepository, ExtensionStoreService(network))
        extensionManager.onSourcesChanged = { sourceManager.onExtensionsChanged(it) }

        downloadManager = DownloadManager(preferences, sourceManager, chapterRepository, mangaRepository)
        mangaInteractor = MangaInteractor(mangaRepository, chapterRepository, categoryRepository, historyRepository, sourceManager, downloadManager, preferences)
        libraryUpdater = LibraryUpdater(preferences, libraryRepository, categoryRepository, mangaInteractor, downloadManager, sourceManager)
        libraryUpdater.onFinished = { newChapters, errors, updated ->
            if (preferences.desktopNotifications.get()) {
                if (updated.isNotEmpty() && updated.size <= 5) {
                    // One notification per entry, with its cover as the icon (Komikku-style).
                    updated.forEach { (manga, count) ->
                        val cover = manga.thumbnailUrl?.let { inkoma.ui.components.ImageLoader.coverFile(it).absolutePath }
                        DesktopIntegration.notify(manga.title, if (count == 1) "1 new chapter" else "$count new chapters", cover)
                    }
                } else if (newChapters > 0 || errors > 0) {
                    DesktopIntegration.notify(
                        "Library update finished",
                        buildString {
                            append(if (newChapters > 0) "$newChapters new chapters in ${updated.size} entries" else "No new chapters")
                            if (errors > 0) append(" • $errors failed")
                        },
                    )
                }
            }
        }
        downloadManager.onQueueFinished = { count ->
            if (preferences.desktopNotifications.get()) DesktopIntegration.notify("Downloads finished", "$count chapters downloaded")
        }
        backupManager = BackupManager(
            preferences, preferenceStore, application, mangaRepository, chapterRepository, categoryRepository,
            historyRepository, extensionStoreRepository, sourceManager,
        )
    }
}
