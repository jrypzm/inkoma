package inkoma.data

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.coroutines.flow.Flow
import inkoma.data.Database.Companion.CATEGORIES
import inkoma.data.Database.Companion.CHAPTERS
import inkoma.data.Database.Companion.EXCLUDED_SCANLATORS
import inkoma.data.Database.Companion.EXTENSION_STORE
import inkoma.data.Database.Companion.HISTORY
import inkoma.data.Database.Companion.MANGAS
import inkoma.data.Database.Companion.MANGAS_CATEGORIES
import inkoma.domain.Category
import inkoma.domain.Chapter
import inkoma.domain.ExtensionStore
import mihon.core.common.extensions.toByteArray
import mihon.core.common.extensions.toJsonObject
import kotlinx.serialization.json.JsonObject
import inkoma.domain.History
import inkoma.domain.HistoryWithRelations
import inkoma.domain.LibraryManga
import inkoma.domain.Manga
import inkoma.domain.UpdatesWithRelations
import java.sql.ResultSet

private fun ResultSet.toManga(): Manga = Manga(
    id = getLong("_id"),
    source = getLong("source"),
    url = getString("url"),
    title = getString("title"),
    artist = getString("artist"),
    author = getString("author"),
    description = getString("description"),
    genre = getString("genre")?.split(", ")?.filter { it.isNotBlank() },
    status = getLong("status"),
    thumbnailUrl = getString("thumbnail_url"),
    favorite = getBool("favorite"),
    lastUpdate = getLong("last_update"),
    nextUpdate = getLong("next_update"),
    initialized = getBool("initialized"),
    viewerFlags = getLong("viewer"),
    chapterFlags = getLong("chapter_flags"),
    coverLastModified = getLong("cover_last_modified"),
    dateAdded = getLong("date_added"),
    updateStrategy = UpdateStrategy.entries.getOrElse(getInt("update_strategy")) { UpdateStrategy.ALWAYS_UPDATE },
    notes = getString("notes") ?: "",
    memo = memoOf(getBytes("memo")),
)

private fun ResultSet.toChapter(): Chapter = Chapter(
    id = getLong("_id"),
    mangaId = getLong("manga_id"),
    url = getString("url"),
    name = getString("name"),
    scanlator = getString("scanlator"),
    read = getBool("read"),
    bookmark = getBool("bookmark"),
    lastPageRead = getLong("last_page_read"),
    chapterNumber = getDouble("chapter_number"),
    sourceOrder = getLong("source_order"),
    dateFetch = getLong("date_fetch"),
    dateUpload = getLong("date_upload"),
    lastModifiedAt = getLong("last_modified_at"),
    memo = memoOf(getBytes("memo")),
)

private fun memoOf(bytes: ByteArray?): JsonObject =
    bytes?.let { runCatching { it.toJsonObject() }.getOrNull() } ?: JsonObject(emptyMap())

private fun now() = System.currentTimeMillis()

class MangaRepository(private val db: Database) {
    fun getById(id: Long): Manga? = db.queryOne("SELECT * FROM mangas WHERE _id = ?", id) { it.toManga() }

    fun observeById(id: Long): Flow<Manga?> = db.observe(setOf(MANGAS)) { getById(id) }

    fun getByUrlAndSource(url: String, source: Long): Manga? =
        db.queryOne("SELECT * FROM mangas WHERE url = ? AND source = ?", url, source) { it.toManga() }

    fun getFavorites(): List<Manga> = db.query("SELECT * FROM mangas WHERE favorite = 1") { it.toManga() }

    fun observeFavoriteKeys(): Flow<Set<Pair<Long, String>>> =
        db.observe(setOf(MANGAS)) { db.query("SELECT source, url FROM mangas WHERE favorite = 1") { it.getLong(1) to it.getString(2) }.toSet() }

    fun getDuplicateFavorites(title: String, excludeId: Long): List<Manga> =
        db.query("SELECT * FROM mangas WHERE favorite = 1 AND LOWER(title) = LOWER(?) AND _id != ?", title, excludeId) { it.toManga() }

    /** Inserts a network manga if it is not already in the database and returns the local copy. */
    fun networkToLocal(source: Long, sManga: SManga): Manga {
        getByUrlAndSource(sManga.url, source)?.let { existing ->
            if (!existing.favorite && sManga.thumbnail_url != null && existing.thumbnailUrl != sManga.thumbnail_url) {
                db.execute(setOf(MANGAS), "UPDATE mangas SET thumbnail_url = ?, title = ? WHERE _id = ?", sManga.thumbnail_url, sManga.title, existing.id)
                return getById(existing.id)!!
            }
            return existing
        }
        val id = db.execute(
            setOf(MANGAS),
            """INSERT INTO mangas(source, url, artist, author, description, genre, title, status, thumbnail_url, favorite,
               last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy,
               calculate_interval, last_modified_at, notes, memo)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?, 0, 0, 0, 0, ?, 0, ?, '', ?)""",
            source, sManga.url, sManga.artist, sManga.author, sManga.description, sManga.genre, sManga.title,
            sManga.status, sManga.thumbnail_url, sManga.initialized, sManga.update_strategy.ordinal, now(),
            sManga.memo.toByteArray(),
        )
        return getById(id)!!
    }

    fun insertFull(m: Manga): Long = db.execute(
        setOf(MANGAS),
        """INSERT INTO mangas(source, url, artist, author, description, genre, title, status, thumbnail_url, favorite,
           last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy,
           calculate_interval, last_modified_at, favorite_modified_at, notes, memo)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)""",
        m.source, m.url, m.artist, m.author, m.description, m.genre?.joinToString(", "), m.title, m.status, m.thumbnailUrl,
        m.favorite, m.lastUpdate, m.nextUpdate, m.initialized, m.viewerFlags, m.chapterFlags, m.coverLastModified, m.dateAdded,
        m.updateStrategy.ordinal, now(), if (m.favorite) now() else null, m.notes, m.memo.toByteArray(),
    )

    fun updateFull(m: Manga) {
        db.execute(
            setOf(MANGAS),
            """UPDATE mangas SET artist = ?, author = ?, description = ?, genre = ?, title = ?, status = ?, thumbnail_url = ?,
               favorite = ?, last_update = ?, next_update = ?, initialized = ?, viewer = ?, chapter_flags = ?, cover_last_modified = ?,
               date_added = ?, update_strategy = ?, notes = ?, memo = ?, last_modified_at = ? WHERE _id = ?""",
            m.artist, m.author, m.description, m.genre?.joinToString(", "), m.title, m.status, m.thumbnailUrl, m.favorite,
            m.lastUpdate, m.nextUpdate, m.initialized, m.viewerFlags, m.chapterFlags, m.coverLastModified, m.dateAdded,
            m.updateStrategy.ordinal, m.notes, m.memo.toByteArray(), now(), m.id,
        )
    }

    /** Applies details fetched from the source (Mihon's UpdateManga.awaitUpdateFromSource). */
    fun updateFromSource(local: Manga, remote: SManga, manualFetch: Boolean) {
        val title = if (remote.title.isNotBlank() && !local.favorite) remote.title else local.title
        val coverChanged = remote.thumbnail_url != null && remote.thumbnail_url != local.thumbnailUrl
        db.execute(
            setOf(MANGAS),
            """UPDATE mangas SET title = ?, artist = ?, author = ?, description = ?, genre = ?, status = ?, thumbnail_url = ?,
               update_strategy = ?, initialized = 1, cover_last_modified = ?, memo = ?, last_modified_at = ? WHERE _id = ?""",
            title,
            remote.artist ?: local.artist,
            remote.author ?: local.author,
            remote.description ?: local.description,
            remote.genre ?: local.genre?.joinToString(", "),
            remote.status,
            remote.thumbnail_url?.takeIf { it.isNotBlank() } ?: local.thumbnailUrl,
            remote.update_strategy.ordinal,
            if (coverChanged || manualFetch) now() else local.coverLastModified,
            remote.memo.toByteArray(),
            now(),
            local.id,
        )
    }

    fun setFavorite(id: Long, favorite: Boolean) {
        db.execute(
            setOf(MANGAS),
            "UPDATE mangas SET favorite = ?, date_added = ?, favorite_modified_at = ?, last_modified_at = ? WHERE _id = ?",
            favorite, if (favorite) now() else 0L, now(), now(), id,
        )
    }

    fun setViewerFlags(id: Long, flags: Long) {
        db.execute(setOf(MANGAS), "UPDATE mangas SET viewer = ? WHERE _id = ?", flags, id)
    }

    fun setChapterFlags(id: Long, flags: Long) {
        db.execute(setOf(MANGAS), "UPDATE mangas SET chapter_flags = ? WHERE _id = ?", flags, id)
    }

    fun setLastUpdate(id: Long, time: Long) {
        db.execute(setOf(MANGAS), "UPDATE mangas SET last_update = ? WHERE _id = ?", time, id)
    }

    fun setNotes(id: Long, notes: String) {
        db.execute(setOf(MANGAS), "UPDATE mangas SET notes = ? WHERE _id = ?", notes, id)
    }

    /** Removes non-library manga that have no read history (Advanced → Clear database). */
    fun deleteNonLibrary(sourceIds: List<Long>?): Long {
        val sourceClause = sourceIds?.takeIf { it.isNotEmpty() }?.joinToString(",", prefix = " AND source IN (", postfix = ")") ?: ""
        return db.execute(
            setOf(MANGAS, CHAPTERS, HISTORY),
            """DELETE FROM mangas WHERE favorite = 0$sourceClause AND _id NOT IN (
                 SELECT chapters.manga_id FROM chapters JOIN history ON chapters._id = history.chapter_id)""",
        )
    }

    fun getExcludedScanlators(mangaId: Long): Set<String> =
        db.query("SELECT scanlator FROM excluded_scanlators WHERE manga_id = ?", mangaId) { it.getString(1) }.toSet()

    fun observeExcludedScanlators(mangaId: Long): Flow<Set<String>> = db.observe(setOf(EXCLUDED_SCANLATORS)) { getExcludedScanlators(mangaId) }

    fun setExcludedScanlators(mangaId: Long, scanlators: Set<String>) = db.transaction {
        db.execute(setOf(EXCLUDED_SCANLATORS), "DELETE FROM excluded_scanlators WHERE manga_id = ?", mangaId)
        scanlators.forEach { db.execute(setOf(EXCLUDED_SCANLATORS), "INSERT OR IGNORE INTO excluded_scanlators(manga_id, scanlator) VALUES (?, ?)", mangaId, it) }
    }
}

class ChapterRepository(private val db: Database) {
    fun getById(id: Long): Chapter? = db.queryOne("SELECT * FROM chapters WHERE _id = ?", id) { it.toChapter() }

    fun getByMangaId(mangaId: Long): List<Chapter> =
        db.query("SELECT * FROM chapters WHERE manga_id = ? ORDER BY source_order ASC", mangaId) { it.toChapter() }

    fun observeByMangaId(mangaId: Long): Flow<List<Chapter>> = db.observe(setOf(CHAPTERS)) { getByMangaId(mangaId) }

    fun getByUrlAndMangaId(url: String, mangaId: Long): Chapter? =
        db.queryOne("SELECT * FROM chapters WHERE url = ? AND manga_id = ?", url, mangaId) { it.toChapter() }

    fun getBookmarked(mangaId: Long): List<Chapter> =
        db.query("SELECT * FROM chapters WHERE manga_id = ? AND bookmark = 1", mangaId) { it.toChapter() }

    fun insert(c: Chapter): Long = db.execute(
        setOf(CHAPTERS),
        """INSERT INTO chapters(manga_id, url, name, scanlator, read, bookmark, last_page_read, chapter_number, source_order,
           date_fetch, date_upload, last_modified_at, memo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        c.mangaId, c.url, c.name, c.scanlator, c.read, c.bookmark, c.lastPageRead, c.chapterNumber, c.sourceOrder,
        c.dateFetch, c.dateUpload, now(), c.memo.toByteArray(),
    )

    fun update(c: Chapter) {
        db.execute(
            setOf(CHAPTERS),
            """UPDATE chapters SET url = ?, name = ?, scanlator = ?, read = ?, bookmark = ?, last_page_read = ?, chapter_number = ?,
               source_order = ?, date_fetch = ?, date_upload = ?, memo = ?, last_modified_at = ? WHERE _id = ?""",
            c.url, c.name, c.scanlator, c.read, c.bookmark, c.lastPageRead, c.chapterNumber, c.sourceOrder, c.dateFetch,
            c.dateUpload, c.memo.toByteArray(), now(), c.id,
        )
    }

    fun setRead(ids: Collection<Long>, read: Boolean) = db.transaction {
        ids.forEach {
            db.execute(
                setOf(CHAPTERS),
                "UPDATE chapters SET read = ?, last_page_read = CASE WHEN ? THEN last_page_read ELSE 0 END, last_modified_at = ? WHERE _id = ?",
                read, read, now(), it,
            )
        }
    }

    fun setBookmark(ids: Collection<Long>, bookmark: Boolean) = db.transaction {
        ids.forEach { db.execute(setOf(CHAPTERS), "UPDATE chapters SET bookmark = ?, last_modified_at = ? WHERE _id = ?", bookmark, now(), it) }
    }

    fun setProgress(id: Long, lastPageRead: Long, read: Boolean) {
        db.execute(setOf(CHAPTERS), "UPDATE chapters SET last_page_read = ?, read = ?, last_modified_at = ? WHERE _id = ?", lastPageRead, read, now(), id)
    }

    fun delete(ids: Collection<Long>) = db.transaction {
        ids.forEach { db.execute(setOf(CHAPTERS, HISTORY), "DELETE FROM chapters WHERE _id = ?", it) }
    }

    /**
     * Mihon's SyncChaptersWithSource: inserts new chapters, updates changed ones, removes ones no longer
     * provided by the source (keeping read state for re-added chapters). Returns newly inserted chapters.
     */
    fun syncWithSource(mangaId: Long, remote: List<SChapter>, manga: SManga, prepare: (SChapter) -> Unit, markDuplicateRead: Boolean): List<Chapter> {
        val sourceChapters = remote.distinctBy { it.url }.mapIndexed { index, sc ->
            prepare(sc)
            val number = if (sc.chapter_number >= 0f) sc.chapter_number.toDouble() else ChapterRecognition.parse(manga.title, sc.name, sc.chapter_number.toDouble())
            Triple(sc, index.toLong(), number)
        }
        val added = ArrayList<Chapter>()
        db.transaction {
            val existing = getByMangaId(mangaId)
            val existingByUrl = existing.associateBy { it.url }
            val remoteUrls = sourceChapters.map { it.first.url }.toSet()
            val removed = existing.filter { it.url !in remoteUrls }
            val removedReadNumbers = removed.filter { it.read }.map { it.chapterNumber }.toSet()
            val readNumbers = existing.filter { it.read }.map { it.chapterNumber }.toSet()
            val fetchTime = now()
            var maxUpload = existing.maxOfOrNull { it.dateUpload } ?: 0L
            for ((sc, order, number) in sourceChapters) {
                val local = existingByUrl[sc.url]
                val dateUpload = if (sc.date_upload > 0) sc.date_upload else maxUpload.coerceAtLeast(0)
                maxUpload = maxOf(maxUpload, dateUpload)
                if (local == null) {
                    val wasRead = number >= 0 && (number in removedReadNumbers || (markDuplicateRead && number in readNumbers))
                    val chapter = Chapter(
                        id = -1, mangaId = mangaId, url = sc.url, name = sc.name, scanlator = sc.scanlator?.ifBlank { null },
                        read = wasRead, bookmark = false, lastPageRead = 0, chapterNumber = number, sourceOrder = order,
                        dateFetch = fetchTime + (sourceChapters.size - order), dateUpload = dateUpload, lastModifiedAt = fetchTime,
                        memo = sc.memo,
                    )
                    val id = insert(chapter)
                    added.add(chapter.copy(id = id))
                } else if (local.name != sc.name || local.scanlator != sc.scanlator?.ifBlank { null } || local.chapterNumber != number ||
                    local.sourceOrder != order || (sc.date_upload > 0 && local.dateUpload != sc.date_upload) || local.memo != sc.memo
                ) {
                    update(
                        local.copy(
                            name = sc.name, scanlator = sc.scanlator?.ifBlank { null }, chapterNumber = number, sourceOrder = order,
                            dateUpload = if (sc.date_upload > 0) sc.date_upload else local.dateUpload,
                            memo = sc.memo,
                        ),
                    )
                }
            }
            if (removed.isNotEmpty()) delete(removed.map { it.id })
        }
        return added
    }
}

class CategoryRepository(private val db: Database) {
    private val map: (ResultSet) -> Category = { Category(it.getLong("_id"), it.getString("name"), it.getLong("sort"), it.getLong("flags")) }

    fun getAll(): List<Category> = db.query("SELECT * FROM categories ORDER BY sort", mapper = map)

    fun observeAll(): Flow<List<Category>> = db.observe(setOf(CATEGORIES)) { getAll() }

    fun getForManga(mangaId: Long): List<Category> = db.query(
        "SELECT categories.* FROM categories JOIN mangas_categories ON categories._id = mangas_categories.category_id WHERE manga_id = ?",
        mangaId, mapper = map,
    )

    fun insert(name: String): Long {
        val nextSort = (db.queryOne("SELECT MAX(sort) FROM categories") { it.getLong(1) } ?: 0L) + 1
        return db.execute(setOf(CATEGORIES), "INSERT INTO categories(name, sort, flags) VALUES (?, ?, 0)", name, nextSort)
    }

    fun insertWithOrder(name: String, order: Long, flags: Long): Long =
        db.execute(setOf(CATEGORIES), "INSERT INTO categories(name, sort, flags) VALUES (?, ?, ?)", name, order, flags)

    fun rename(id: Long, name: String) {
        db.execute(setOf(CATEGORIES), "UPDATE categories SET name = ? WHERE _id = ?", name, id)
    }

    fun delete(id: Long) = db.transaction {
        db.execute(setOf(CATEGORIES, MANGAS_CATEGORIES), "DELETE FROM categories WHERE _id = ?", id)
        normalizeOrder()
    }

    fun reorder(orderedIds: List<Long>) = db.transaction {
        orderedIds.forEachIndexed { index, id -> db.execute(setOf(CATEGORIES), "UPDATE categories SET sort = ? WHERE _id = ?", index + 1L, id) }
    }

    private fun normalizeOrder() {
        reorder(getAll().filterNot { it.isSystemCategory }.map { it.id })
    }

    fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) = db.transaction {
        db.execute(setOf(MANGAS_CATEGORIES), "DELETE FROM mangas_categories WHERE manga_id = ?", mangaId)
        categoryIds.filter { it != Category.UNCATEGORIZED_ID }.forEach {
            db.execute(setOf(MANGAS_CATEGORIES), "INSERT OR IGNORE INTO mangas_categories(manga_id, category_id) VALUES (?, ?)", mangaId, it)
        }
    }
}

class LibraryRepository(private val db: Database) {
    private val sql = """
        SELECT M.*, COALESCE(MC.category_id, 0) AS category,
               COALESCE(C.total, 0) AS total, COALESCE(C.read_count, 0) AS read_count,
               COALESCE(C.bookmark_count, 0) AS bookmark_count, COALESCE(C.latest_upload, 0) AS latest_upload,
               COALESCE(C.fetched_at, 0) AS fetched_at, COALESCE(H.last_read, 0) AS last_read
        FROM mangas M
        LEFT JOIN (
            SELECT manga_id, COUNT(*) AS total, SUM(read) AS read_count, SUM(bookmark) AS bookmark_count,
                   MAX(date_upload) AS latest_upload, MAX(date_fetch) AS fetched_at
            FROM chapters GROUP BY manga_id
        ) C ON M._id = C.manga_id
        LEFT JOIN (
            SELECT chapters.manga_id, MAX(history.last_read) AS last_read
            FROM history JOIN chapters ON chapters._id = history.chapter_id GROUP BY chapters.manga_id
        ) H ON M._id = H.manga_id
        LEFT JOIN mangas_categories MC ON MC.manga_id = M._id
        WHERE M.favorite = 1
    """.trimIndent()

    fun getLibrary(): List<LibraryManga> = db.query(sql) {
        LibraryManga(
            manga = it.toManga(),
            categoryId = it.getLong("category"),
            totalChapters = it.getLong("total"),
            readCount = it.getLong("read_count"),
            bookmarkCount = it.getLong("bookmark_count"),
            latestUpload = it.getLong("latest_upload"),
            chapterFetchedAt = it.getLong("fetched_at"),
            lastRead = it.getLong("last_read"),
        )
    }

    fun observeLibrary(): Flow<List<LibraryManga>> =
        db.observe(setOf(MANGAS, CHAPTERS, HISTORY, MANGAS_CATEGORIES, CATEGORIES)) { getLibrary() }
}

class HistoryRepository(private val db: Database) {
    fun observeHistory(query: String): Flow<List<HistoryWithRelations>> = db.observe(setOf(HISTORY, CHAPTERS, MANGAS)) {
        db.query(
            """SELECT H._id, H.chapter_id, M._id AS manga_id, M.title, M.thumbnail_url, M.source, M.favorite, M.cover_last_modified,
                      C.chapter_number, C.name, H.last_read, H.time_read
               FROM history H JOIN chapters C ON H.chapter_id = C._id JOIN mangas M ON C.manga_id = M._id
               WHERE H.last_read > 0 AND H._id IN (
                   SELECT H2._id FROM history H2 JOIN chapters C2 ON H2.chapter_id = C2._id
                   WHERE H2.last_read = (SELECT MAX(H3.last_read) FROM history H3 JOIN chapters C3 ON H3.chapter_id = C3._id WHERE C3.manga_id = C2.manga_id)
               ) AND M.title LIKE ?
               ORDER BY H.last_read DESC""",
            "%$query%",
        ) {
            HistoryWithRelations(
                id = it.getLong(1), chapterId = it.getLong(2), mangaId = it.getLong(3), title = it.getString(4),
                thumbnailUrl = it.getString(5), source = it.getLong(6), favorite = it.getBool("favorite"),
                coverLastModified = it.getLong(8), chapterNumber = it.getDouble(9), chapterName = it.getString(10),
                readAt = it.getLong(11), readDuration = it.getLong(12),
            )
        }
    }

    fun getByChapterId(chapterId: Long): History? =
        db.queryOne("SELECT * FROM history WHERE chapter_id = ?", chapterId) { History(it.getLong("_id"), it.getLong("chapter_id"), it.getLong("last_read"), it.getLong("time_read")) }

    fun getByMangaId(mangaId: Long): List<History> = db.query(
        "SELECT history.* FROM history JOIN chapters ON chapters._id = history.chapter_id WHERE chapters.manga_id = ?",
        mangaId,
    ) { History(it.getLong("_id"), it.getLong("chapter_id"), it.getLong("last_read"), it.getLong("time_read")) }

    fun upsert(chapterId: Long, readAt: Long, sessionReadDuration: Long) {
        db.execute(
            setOf(HISTORY),
            """INSERT INTO history(chapter_id, last_read, time_read) VALUES (?, ?, ?)
               ON CONFLICT(chapter_id) DO UPDATE SET last_read = excluded.last_read, time_read = time_read + excluded.time_read""",
            chapterId, readAt, sessionReadDuration,
        )
    }

    fun restore(chapterId: Long, readAt: Long, duration: Long) {
        db.execute(
            setOf(HISTORY),
            """INSERT INTO history(chapter_id, last_read, time_read) VALUES (?, ?, ?)
               ON CONFLICT(chapter_id) DO UPDATE SET last_read = MAX(last_read, excluded.last_read), time_read = MAX(time_read, excluded.time_read)""",
            chapterId, readAt, duration,
        )
    }

    fun resetLastRead(historyId: Long) {
        db.execute(setOf(HISTORY), "UPDATE history SET last_read = 0 WHERE _id = ?", historyId)
    }

    fun resetForManga(mangaId: Long) {
        db.execute(setOf(HISTORY), "UPDATE history SET last_read = 0 WHERE chapter_id IN (SELECT _id FROM chapters WHERE manga_id = ?)", mangaId)
    }

    fun deleteAll() {
        db.execute(setOf(HISTORY), "DELETE FROM history")
    }

    fun getTotalReadDuration(): Long = db.queryOne("SELECT COALESCE(SUM(time_read), 0) FROM history") { it.getLong(1) } ?: 0L

    /** Next unread chapter (by source order) for "Resume"/"Continue reading". */
    fun getNextChapterId(mangaId: Long): Long? = db.queryOne(
        "SELECT _id FROM chapters WHERE manga_id = ? AND read = 0 ORDER BY source_order DESC LIMIT 1",
        mangaId,
    ) { it.getLong(1) }
}

class UpdatesRepository(private val db: Database) {
    fun observeRecent(after: Long, limit: Long = 500): Flow<List<UpdatesWithRelations>> = db.observe(setOf(MANGAS, CHAPTERS)) {
        db.query(
            """SELECT M._id AS manga_id, M.title, C._id AS chapter_id, C.name, C.scanlator, C.read, C.bookmark, C.last_page_read,
                      M.source, M.thumbnail_url, M.cover_last_modified, C.date_upload, C.date_fetch
               FROM mangas M JOIN chapters C ON M._id = C.manga_id
               WHERE M.favorite = 1 AND C.date_fetch > M.date_added AND C.date_fetch > ?
               ORDER BY C.date_fetch DESC LIMIT ?""",
            after, limit,
        ) {
            UpdatesWithRelations(
                mangaId = it.getLong("manga_id"), mangaTitle = it.getString("title"), chapterId = it.getLong("chapter_id"),
                chapterName = it.getString("name"), scanlator = it.getString("scanlator"), read = it.getBool("read"),
                bookmark = it.getBool("bookmark"), lastPageRead = it.getLong("last_page_read"), source = it.getLong("source"),
                thumbnailUrl = it.getString("thumbnail_url"), coverLastModified = it.getLong("cover_last_modified"),
                dateUpload = it.getLong("date_upload"), dateFetch = it.getLong("date_fetch"),
            )
        }
    }
}

class ExtensionStoreRepository(private val db: Database) {
    private val map: (ResultSet) -> ExtensionStore = {
        ExtensionStore(
            indexUrl = it.getString("index_url"),
            name = it.getString("name"),
            badgeLabel = it.getString("badge_label"),
            signingKey = it.getString("signing_key"),
            contactWebsite = it.getString("contact_website"),
            contactDiscord = it.getString("contact_discord"),
            isLegacy = it.getBool("is_legacy"),
            extensionListUrl = it.getString("extension_list_url"),
        )
    }

    fun getAll(): List<ExtensionStore> = db.query("SELECT * FROM extension_store ORDER BY name", mapper = map)
    fun observeAll(): Flow<List<ExtensionStore>> = db.observe(setOf(EXTENSION_STORE)) { getAll() }
    fun contains(indexUrl: String): Boolean = db.queryOne("SELECT 1 FROM extension_store WHERE index_url = ?", indexUrl) { true } ?: false

    fun upsert(store: ExtensionStore) {
        db.execute(
            setOf(EXTENSION_STORE),
            """INSERT INTO extension_store(index_url, name, badge_label, signing_key, contact_website, contact_discord, is_legacy, extension_list_url)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(index_url) DO UPDATE SET name = excluded.name, badge_label = excluded.badge_label,
               signing_key = excluded.signing_key, contact_website = excluded.contact_website, contact_discord = excluded.contact_discord,
               is_legacy = excluded.is_legacy, extension_list_url = excluded.extension_list_url""",
            store.indexUrl, store.name, store.badgeLabel, store.signingKey, store.contactWebsite, store.contactDiscord,
            store.isLegacy, store.extensionListUrl,
        )
    }

    fun delete(indexUrl: String) {
        db.execute(setOf(EXTENSION_STORE), "DELETE FROM extension_store WHERE index_url = ?", indexUrl)
    }
}
