package inkoma.core

import eu.kanade.tachiyomi.network.NetworkHelper

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class LibraryDisplayMode { COMPACT_GRID, COMFORTABLE_GRID, COVER_ONLY_GRID, LIST }

enum class LibrarySort { ALPHABETICAL, LAST_READ, LAST_UPDATE, UNREAD_COUNT, TOTAL_CHAPTERS, LATEST_CHAPTER, CHAPTER_FETCH_DATE, DATE_ADDED, RANDOM }

/** Tri-state filter as in Mihon (ignore / include / exclude). */
enum class TriState { DISABLED, ENABLED_IS, ENABLED_NOT }

enum class ReadingMode(val label: String) {
    RIGHT_TO_LEFT("Paged (right to left)"),
    LEFT_TO_RIGHT("Paged (left to right)"),
    VERTICAL("Paged (vertical)"),
    WEBTOON("Webtoon"),
    CONTINUOUS_VERTICAL("Continuous vertical"),
    ;

    val isPaged: Boolean get() = this == RIGHT_TO_LEFT || this == LEFT_TO_RIGHT || this == VERTICAL
}

enum class ImageScale(val label: String) { FIT_SCREEN("Fit screen"), STRETCH("Stretch"), FIT_WIDTH("Fit width"), FIT_HEIGHT("Fit height"), ORIGINAL("Original size"), SMART_FIT("Smart fit") }

enum class ReaderBackground(val label: String) { BLACK("Black"), GRAY("Gray"), WHITE("White"), AUTOMATIC("Automatic"), SMART("Smart (match page)") }

enum class TappingLayout(val label: String) { DEFAULT("Default"), L_SHAPED("L shaped"), KINDLISH("Kindle-ish"), EDGE("Edge"), RIGHT_AND_LEFT("Right and Left"), DISABLED("Disabled") }

/** Two-page spread in paged LTR/RTL modes. AUTO = only when the window is wider than it is tall. */
enum class DualPageSpread(val label: String) { OFF("Off"), ON("On"), AUTO("Automatic (wide window)") }

enum class ScalingQuality(val label: String) { FAST("Fast"), SMOOTH("Smooth"), HIGH("High quality") }

enum class FabPosition(val label: String) { END("Right"), START("Left") }

enum class ChapterSort { SOURCE, NUMBER, UPLOAD_DATE, ALPHABET }

class AppPreferences(store: PreferenceStore) {
    // Appearance
    val themeMode = store.getEnum("pref_theme_mode_key", ThemeMode.SYSTEM)
    val themeDarkAmoled = store.getBoolean("pref_theme_dark_amoled_key", false)
    val relativeTime = store.getBoolean("relative_time_v2", true)
    val appTheme = store.getEnum("pref_app_theme", inkoma.ui.theme.AppTheme.TACHIYOMI)
    val customThemeSeed = store.getInt("pref_custom_theme_seed", 0xFF0058CA.toInt())
    val coverColors = store.getBoolean("pref_cover_colors", true)
    val panoramaCovers = store.getBoolean("pref_panorama_covers", true)
    val twoPaneManga = store.getBoolean("pref_two_pane_manga", true)
    val resumeButtonPosition = store.getEnum("pref_resume_button_position", FabPosition.END)
    val groupUpdates = store.getBoolean("pref_group_updates", true)
    val sourceIconBadge = store.getBoolean("display_source_icon_badge", false)
    val sourceCategories = store.getStringSet("source_categories", emptySet())
    val dateFormat = store.getString("app_date_format", "")

    // Library
    val libraryDisplayMode = store.getEnum("pref_display_mode_library", LibraryDisplayMode.COMPACT_GRID)
    val libraryColumns = store.getInt("pref_library_columns_portrait_key", 0)
    val librarySort = store.getEnum("library_sorting_mode_v2", LibrarySort.ALPHABETICAL)
    val librarySortAscending = store.getBoolean("library_sorting_ascending", true)
    val filterDownloaded = store.getEnum("pref_filter_library_downloaded_v2", TriState.DISABLED)
    val filterUnread = store.getEnum("pref_filter_library_unread_v2", TriState.DISABLED)
    val filterStarted = store.getEnum("pref_filter_library_started_v2", TriState.DISABLED)
    val filterBookmarked = store.getEnum("pref_filter_library_bookmarked_v2", TriState.DISABLED)
    val filterCompleted = store.getEnum("pref_filter_library_completed_v2", TriState.DISABLED)
    val downloadBadge = store.getBoolean("display_download_badge", false)
    val unreadBadge = store.getBoolean("display_unread_badge", true)
    val localBadge = store.getBoolean("display_local_badge", true)
    val languageBadge = store.getBoolean("display_language_badge", false)
    val showContinueReadingButton = store.getBoolean("display_continue_reading_button", false)
    val categoryTabs = store.getBoolean("display_category_tabs", true)
    val categoryNumberOfItems = store.getBoolean("display_number_of_items", false)
    val defaultCategory = store.getInt("default_category", -1)
    val lastUsedCategory = store.getInt("last_used_category", 0)
    val libraryUpdateInterval = store.getInt("pref_library_update_interval_key", 0)
    val libraryUpdateMangaRestriction = store.getStringSet("library_update_manga_restriction", setOf(MANGA_HAS_UNREAD, MANGA_NON_COMPLETED, MANGA_NON_READ))
    val libraryUpdateCategories = store.getStringSet("library_update_categories", emptySet())
    val libraryUpdateCategoriesExclude = store.getStringSet("library_update_categories_exclude", emptySet())
    val autoUpdateMetadata = store.getBoolean("auto_update_metadata", false)
    val newShowUpdatesCount = store.getBoolean("library_show_updates_count", true)
    val lastLibraryUpdate = store.getLong("library_update_last_timestamp", 0)
    val markDuplicateReadChapterAsRead = store.getStringSet("mark_duplicate_read_chapter_read", emptySet())

    // Chapter list defaults
    val chapterSort = store.getEnum("default_chapter_sort_by_source_or_number", ChapterSort.SOURCE)
    val chapterSortDescending = store.getBoolean("default_chapter_sort_descending", true)

    // Reader
    val defaultReadingMode = store.getEnum("pref_default_reading_mode_key", ReadingMode.RIGHT_TO_LEFT)
    val imageScale = store.getEnum("pref_image_scale_type_key", ImageScale.FIT_SCREEN)
    val readerBackground = store.getEnum("pref_reader_theme_key", ReaderBackground.BLACK)
    val showPageNumber = store.getBoolean("pref_show_page_number_key", true)
    val pageTransitions = store.getBoolean("pref_enable_transitions_key", true)
    val fullscreen = store.getBoolean("fullscreen", false)
    val pagedTappingLayout = store.getEnum("reader_navigation_mode_pager", TappingLayout.DEFAULT)
    val webtoonTappingLayout = store.getEnum("reader_navigation_mode_webtoon", TappingLayout.DEFAULT)
    val invertTapping = store.getBoolean("reader_tapping_inverted", false)
    val webtoonSidePadding = store.getInt("webtoon_side_padding", 0)
    // Two pages in landscape windows, one big page in portrait windows (the usual way people read on a PC).
    val dualPageSpread = store.getEnum("pref_dual_page_spread", DualPageSpread.AUTO)
    val scalingQuality = store.getEnum("reader_scaling_quality", ScalingQuality.HIGH)
    val sharpening = store.getInt("reader_sharpening", 0)
    val filterBrightness = store.getInt("reader_filter_brightness", 0)
    val filterContrast = store.getInt("reader_filter_contrast", 0)
    val filterWarmth = store.getInt("reader_filter_warmth", 0)
    val filterGrayscale = store.getBoolean("reader_filter_grayscale", false)
    val filterInvert = store.getBoolean("reader_filter_invert", false)
    val autoWebtoon = store.getBoolean("reader_auto_webtoon", true)
    val readerMaxWidth = store.getInt("reader_max_width", 100)
    val keyBindings = store.getString("reader_key_bindings", "")
    val dualPageShift = store.getBoolean("pref_dual_page_shift", false)
    val alwaysShowChapterTransition = store.getBoolean("always_show_chapter_transition", true)
    val skipRead = store.getBoolean("skip_read", false)
    val skipDupe = store.getBoolean("skip_dupe", false)

    // Downloads
    val downloadsDirectory = store.getString("download_directory", AppDirs.defaultDownloads.absolutePath)
    val saveChaptersAsCbz = store.getBoolean("save_chapter_as_cbz", true)
    val removeAfterMarkedAsRead = store.getBoolean("pref_remove_after_marked_as_read_key", false)
    val removeAfterReadSlots = store.getInt("remove_after_read_slots", -1)
    val removeBookmarkedChapters = store.getBoolean("pref_remove_bookmarked", false)
    val downloadNewChapters = store.getBoolean("download_new", false)
    val downloadNewChapterCategories = store.getStringSet("download_new_categories", emptySet())
    val downloadNewChapterCategoriesExclude = store.getStringSet("download_new_categories_exclude", emptySet())
    val downloadNewUnreadChaptersOnly = store.getBoolean("download_new_unread_chapters_only", false)
    val autoDownloadWhileReading = store.getInt("auto_download_while_reading", 0)
    val parallelSourceLimit = store.getInt("download_parallel_source_limit", 5)
    val parallelPageLimit = store.getInt("download_parallel_page_limit", 5)

    // Browse / extensions
    val enabledLanguages = store.getStringSet("source_languages", setOf("all", "en", java.util.Locale.getDefault().language))
    val disabledSources = store.getStringSet("hidden_catalogues", emptySet())
    val pinnedSources = store.getStringSet("pinned_catalogues", emptySet())
    val lastUsedSource = store.getLong("last_catalogue_source", -1)
    val showNsfwSource = store.getBoolean("show_nsfw_source", true)
    val hideInLibraryItems = store.getBoolean("browse_hide_in_library_items", false)
    val trustedExtensions = store.getStringSet("trusted_extensions", emptySet())
    val extensionUpdatesCount = store.getInt("ext_updates_count", 0)
    val sourceDisplayMode = store.getEnum("pref_display_mode_catalogue", LibraryDisplayMode.COMPACT_GRID)

    // Backup
    val backupInterval = store.getInt("backup_interval", 12)
    val numberOfBackups = store.getInt("backup_slots", 5)
    val lastAutoBackup = store.getLong("last_auto_backup_timestamp", 0)

    // Advanced / network
    val defaultUserAgent = store.getString("default_user_agent", DEFAULT_USER_AGENT)
    val dohProvider = store.getInt("doh_provider", NetworkHelper.PREF_DOH_DISABLED)
    val verboseLogging = store.getBoolean("verbose_logging", false)

    // Desktop
    val onboardingComplete = store.getBoolean("onboarding_complete", false)
    val desktopNotifications = store.getBoolean("desktop_notifications", true)
    /** GPU (OpenGL) rendering: smoother, but maps the graphics driver into the process (more RAM). */
    val gpuRendering = store.getBoolean("gpu_rendering", false)

    companion object {
        const val MANGA_HAS_UNREAD = "manga_fully_read"
        const val MANGA_NON_COMPLETED = "manga_ongoing"
        const val MANGA_NON_READ = "manga_started"
        const val MANGA_OUTSIDE_RELEASE_PERIOD = "manga_outside_release_period"

        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
    }
}
