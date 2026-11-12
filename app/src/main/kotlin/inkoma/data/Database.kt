package inkoma.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * Thin JDBC layer over SQLite with table-level change notifications, playing the role of
 * SQLDelight's query flows in Mihon.
 */
class Database(file: File) {
    private val connection: Connection
    private val changes = MutableSharedFlow<Set<String>>(extraBufferCapacity = 64)
    private var transactionDepth = 0
    private val pendingTables = HashSet<String>()

    init {
        Class.forName("org.sqlite.JDBC")
        file.parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        connection.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA foreign_keys=ON")
            it.execute("PRAGMA synchronous=NORMAL")
            it.execute("PRAGMA cache_size=-2000")
        }
        migrate()
    }

    private fun migrate() {
        val version = queryOne("PRAGMA user_version") { it.getInt(1) } ?: 0
        if (version < 1) {
            transaction {
                SCHEMA_V1.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { stmt ->
                    connection.createStatement().use { it.execute(stmt) }
                }
                connection.createStatement().use { it.execute("PRAGMA user_version = 1") }
            }
        }
    }

    private fun bind(ps: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, arg ->
            val idx = i + 1
            when (arg) {
                null -> ps.setNull(idx, Types.NULL)
                is Long -> ps.setLong(idx, arg)
                is Int -> ps.setLong(idx, arg.toLong())
                is Boolean -> ps.setLong(idx, if (arg) 1 else 0)
                is Double -> ps.setDouble(idx, arg)
                is Float -> ps.setDouble(idx, arg.toDouble())
                is String -> ps.setString(idx, arg)
                is ByteArray -> ps.setBytes(idx, arg)
                else -> ps.setString(idx, arg.toString())
            }
        }
    }

    fun <T> query(sql: String, vararg args: Any?, mapper: (ResultSet) -> T): List<T> = synchronized(this) {
        connection.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs ->
                val out = ArrayList<T>()
                while (rs.next()) out.add(mapper(rs))
                out
            }
        }
    }

    fun <T> queryOne(sql: String, vararg args: Any?, mapper: (ResultSet) -> T): T? = query(sql, *args, mapper = mapper).firstOrNull()

    /** Executes a write and notifies observers of [tables]. Returns the last inserted rowid for INSERTs, else the update count. */
    fun execute(tables: Set<String>, sql: String, vararg args: Any?): Long = synchronized(this) {
        val result = connection.prepareStatement(sql).use { ps ->
            bind(ps, args)
            val count = ps.executeUpdate()
            if (sql.trimStart().startsWith("INSERT", ignoreCase = true)) {
                connection.createStatement().use { st ->
                    st.executeQuery("SELECT last_insert_rowid()").use { rs -> if (rs.next()) rs.getLong(1) else -1L }
                }
            } else {
                count.toLong()
            }
        }
        notify(tables)
        result
    }

    private fun notify(tables: Set<String>) {
        if (transactionDepth > 0) {
            pendingTables.addAll(tables)
        } else if (tables.isNotEmpty()) {
            changes.tryEmit(tables)
        }
    }

    fun <T> transaction(block: () -> T): T = synchronized(this) {
        val outer = transactionDepth == 0
        if (outer) connection.autoCommit = false
        transactionDepth++
        try {
            val result = block()
            transactionDepth--
            if (outer) {
                connection.commit()
                connection.autoCommit = true
                val tables = pendingTables.toSet()
                pendingTables.clear()
                if (tables.isNotEmpty()) changes.tryEmit(tables)
            }
            result
        } catch (e: Throwable) {
            transactionDepth--
            if (outer) {
                connection.rollback()
                connection.autoCommit = true
                pendingTables.clear()
            }
            throw e
        }
    }

    /** Re-runs [block] whenever one of [tables] changes. */
    fun <T> observe(tables: Set<String>, block: () -> T): Flow<T> =
        changes.filter { changed -> changed.any { it in tables } }
            .onStart { emit(tables) }
            .map { block() }
            .flowOn(Dispatchers.IO)

    companion object {
        const val MANGAS = "mangas"
        const val CHAPTERS = "chapters"
        const val CATEGORIES = "categories"
        const val MANGAS_CATEGORIES = "mangas_categories"
        const val HISTORY = "history"
        const val EXCLUDED_SCANLATORS = "excluded_scanlators"
        const val EXTENSION_STORE = "extension_store"

        private val SCHEMA_V1 = """
            CREATE TABLE IF NOT EXISTS mangas(
                _id INTEGER NOT NULL PRIMARY KEY,
                source INTEGER NOT NULL,
                url TEXT NOT NULL,
                artist TEXT,
                author TEXT,
                description TEXT,
                genre TEXT,
                title TEXT NOT NULL,
                status INTEGER NOT NULL,
                thumbnail_url TEXT,
                favorite INTEGER NOT NULL,
                last_update INTEGER,
                next_update INTEGER,
                initialized INTEGER NOT NULL,
                viewer INTEGER NOT NULL,
                chapter_flags INTEGER NOT NULL,
                cover_last_modified INTEGER NOT NULL,
                date_added INTEGER NOT NULL,
                update_strategy INTEGER NOT NULL DEFAULT 0,
                calculate_interval INTEGER NOT NULL DEFAULT 0,
                last_modified_at INTEGER NOT NULL DEFAULT 0,
                favorite_modified_at INTEGER,
                version INTEGER NOT NULL DEFAULT 0,
                notes TEXT NOT NULL DEFAULT '',
                memo BLOB NOT NULL DEFAULT X'7B7D'
            );
            CREATE INDEX IF NOT EXISTS library_favorite_index ON mangas(favorite) WHERE favorite = 1;
            CREATE INDEX IF NOT EXISTS mangas_url_index ON mangas(url);
            CREATE INDEX IF NOT EXISTS mangas_source_url_index ON mangas(source, url);
            CREATE TABLE IF NOT EXISTS chapters(
                _id INTEGER NOT NULL PRIMARY KEY,
                manga_id INTEGER NOT NULL,
                url TEXT NOT NULL,
                name TEXT NOT NULL,
                scanlator TEXT,
                read INTEGER NOT NULL,
                bookmark INTEGER NOT NULL,
                last_page_read INTEGER NOT NULL,
                chapter_number REAL NOT NULL,
                source_order INTEGER NOT NULL,
                date_fetch INTEGER NOT NULL,
                date_upload INTEGER NOT NULL,
                last_modified_at INTEGER NOT NULL DEFAULT 0,
                version INTEGER NOT NULL DEFAULT 0,
                memo BLOB NOT NULL DEFAULT X'7B7D',
                FOREIGN KEY(manga_id) REFERENCES mangas (_id) ON DELETE CASCADE
            );
            CREATE INDEX IF NOT EXISTS chapters_manga_id_index ON chapters(manga_id);
            CREATE INDEX IF NOT EXISTS chapters_unread_by_manga_index ON chapters(manga_id, read) WHERE read = 0;
            CREATE TABLE IF NOT EXISTS categories(
                _id INTEGER NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                sort INTEGER NOT NULL,
                flags INTEGER NOT NULL
            );
            INSERT OR IGNORE INTO categories(_id, name, sort, flags) VALUES (0, '', -1, 0);
            CREATE TABLE IF NOT EXISTS mangas_categories(
                _id INTEGER NOT NULL PRIMARY KEY,
                manga_id INTEGER NOT NULL,
                category_id INTEGER NOT NULL,
                FOREIGN KEY(category_id) REFERENCES categories (_id) ON DELETE CASCADE,
                FOREIGN KEY(manga_id) REFERENCES mangas (_id) ON DELETE CASCADE
            );
            CREATE UNIQUE INDEX IF NOT EXISTS mangas_categories_unique ON mangas_categories(manga_id, category_id);
            CREATE TABLE IF NOT EXISTS history(
                _id INTEGER NOT NULL PRIMARY KEY,
                chapter_id INTEGER NOT NULL UNIQUE,
                last_read INTEGER,
                time_read INTEGER NOT NULL,
                FOREIGN KEY(chapter_id) REFERENCES chapters (_id) ON DELETE CASCADE
            );
            CREATE INDEX IF NOT EXISTS history_history_chapter_id_index ON history(chapter_id);
            CREATE TABLE IF NOT EXISTS excluded_scanlators(
                manga_id INTEGER NOT NULL,
                scanlator TEXT NOT NULL,
                FOREIGN KEY(manga_id) REFERENCES mangas (_id) ON DELETE CASCADE
            );
            CREATE UNIQUE INDEX IF NOT EXISTS excluded_scanlators_unique ON excluded_scanlators(manga_id, scanlator);
            CREATE TABLE IF NOT EXISTS extension_store(
                index_url TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                badge_label TEXT NOT NULL,
                signing_key TEXT NOT NULL,
                contact_website TEXT NOT NULL,
                contact_discord TEXT,
                is_legacy INTEGER NOT NULL,
                extension_list_url TEXT
            )
        """.trimIndent()
    }
}

fun ResultSet.getLongOrNull(column: String): Long? = getLong(column).let { if (wasNull()) null else it }
fun ResultSet.getBool(column: String): Boolean = getLong(column) != 0L
