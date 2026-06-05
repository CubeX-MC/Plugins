package org.cubexmc.booklite.storage

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.util.Locale
import java.util.logging.Level
import org.cubexmc.booklite.BookLitePlugin
import org.cubexmc.booklite.config.ConfigManager
import org.cubexmc.booklite.model.BookRecord

class BookRepository(
    private val plugin: BookLitePlugin,
    private val config: ConfigManager,
) {
    private var connection: Connection? = null

    @Synchronized
    fun init() {
        try {
            Class.forName("org.sqlite.JDBC")
            if (!plugin.dataFolder.exists() && !plugin.dataFolder.mkdirs()) {
                plugin.logger.warning("Cannot create BookLite data folder.")
            }
            val db = File(plugin.dataFolder, config.getSqliteFile())
            connection = DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}")
            requireConnection().createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout = 5000")
                if (config.isWal()) {
                    statement.execute("PRAGMA journal_mode = WAL")
                    statement.execute("PRAGMA synchronous = NORMAL")
                }
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS books (
                      id TEXT PRIMARY KEY,
                      content_hash TEXT NOT NULL,
                      title_raw TEXT NOT NULL,
                      author TEXT NOT NULL,
                      pages_json TEXT NOT NULL,
                      total_pages INTEGER NOT NULL,
                      created_at INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL,
                      last_accessed_at INTEGER NULL
                    )
                    """.trimIndent(),
                )
                ensureColumn(statement, "last_accessed_at", "ALTER TABLE books ADD COLUMN last_accessed_at INTEGER NULL")
                dropDeletedAtColumn(statement)
                statement.execute("CREATE INDEX IF NOT EXISTS idx_books_hash ON books(content_hash)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_books_created ON books(created_at)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_books_last_accessed ON books(last_accessed_at)")
                statement.execute("PRAGMA user_version = 3")
            }
        } catch (exception: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to initialize BookLite SQLite storage.", exception)
            throw IllegalStateException(exception)
        }
    }

    @Synchronized
    fun close() {
        val currentConnection = connection ?: return
        try {
            currentConnection.close()
        } catch (exception: SQLException) {
            plugin.logger.log(Level.WARNING, "Failed to close BookLite database.", exception)
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun saveOrGet(draft: BookRecord): BookRecord {
        val existing = findByHash(draft.contentHash())
        if (existing != null) {
            return existing
        }

        requireConnection().prepareStatement(
            """
            INSERT INTO books
              (id, content_hash, title_raw, author, pages_json, total_pages, created_at, updated_at, last_accessed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, draft.id())
            statement.setString(2, draft.contentHash())
            statement.setString(3, draft.title())
            statement.setString(4, draft.author())
            statement.setString(5, GSON.toJson(draft.pages()))
            statement.setInt(6, draft.totalPages())
            statement.setLong(7, draft.createdAt())
            statement.setLong(8, draft.updatedAt())
            val lastAccessedAt = draft.lastAccessedAt()
            if (lastAccessedAt == null) {
                statement.setNull(9, Types.BIGINT)
            } else {
                statement.setLong(9, lastAccessedAt)
            }
            statement.executeUpdate()
        }
        return draft
    }

    @Synchronized
    @Throws(SQLException::class)
    fun find(id: String?): BookRecord? {
        if (id.isNullOrBlank()) {
            return null
        }
        requireConnection().prepareStatement("SELECT * FROM books WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) read(resultSet) else null
            }
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun findByPrefix(prefix: String?, limit: Int): List<BookRecord> {
        val out = ArrayList<BookRecord>()
        if (prefix.isNullOrBlank()) {
            return out
        }
        requireConnection().prepareStatement(
            "SELECT * FROM books WHERE id LIKE ? ORDER BY created_at DESC LIMIT ?",
        ).use { statement ->
            statement.setString(1, "$prefix%")
            statement.setInt(2, maxOf(1, limit))
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    out.add(read(resultSet))
                }
            }
        }
        return out
    }

    @Synchronized
    @Throws(SQLException::class)
    fun completeIdsByPrefix(prefix: String?, limit: Int): List<String> {
        val out = ArrayList<String>()
        requireConnection().prepareStatement(
            "SELECT id FROM books WHERE id LIKE ? ORDER BY created_at DESC LIMIT ?",
        ).use { statement ->
            statement.setString(1, (prefix ?: "").lowercase(Locale.getDefault()) + "%")
            statement.setInt(2, maxOf(1, limit))
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    out.add(resultSet.getString("id"))
                }
            }
        }
        return out
    }

    @Synchronized
    @Throws(SQLException::class)
    fun list(offset: Int, limit: Int): List<BookRecord> {
        val out = ArrayList<BookRecord>()
        requireConnection().prepareStatement(
            "SELECT * FROM books ORDER BY created_at DESC LIMIT ? OFFSET ?",
        ).use { statement ->
            statement.setInt(1, maxOf(1, limit))
            statement.setInt(2, maxOf(0, offset))
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    out.add(read(resultSet))
                }
            }
        }
        return out
    }

    @Synchronized
    @Throws(SQLException::class)
    fun delete(id: String): Boolean {
        requireConnection().prepareStatement("DELETE FROM books WHERE id = ?").use { statement ->
            statement.setString(1, id)
            return statement.executeUpdate() > 0
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun markAccessed(id: String, now: Long): Boolean {
        requireConnection().prepareStatement(
            "UPDATE books SET last_accessed_at = ?, updated_at = ? WHERE id = ?",
        ).use { statement ->
            statement.setLong(1, now)
            statement.setLong(2, now)
            statement.setString(3, id)
            return statement.executeUpdate() > 0
        }
    }

    /**
     * Permanently deletes records whose last player access (or, if never accessed,
     * creation time) is older than [staleBefore]. Admin operations like
     * /booklite read do not touch last_accessed_at, so they don't keep records alive.
     */
    @Synchronized
    @Throws(SQLException::class)
    fun purgeStale(staleBefore: Long): Int {
        requireConnection().prepareStatement(
            "DELETE FROM books WHERE COALESCE(last_accessed_at, created_at) < ?",
        ).use { statement ->
            statement.setLong(1, staleBefore)
            return statement.executeUpdate()
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun stats(): Stats {
        val total = count("SELECT COUNT(*) FROM books")
        val lastAccessedAt = maxNullable("SELECT MAX(last_accessed_at) FROM books")
        return Stats(total, lastAccessedAt)
    }

    @Throws(SQLException::class)
    private fun findByHash(hash: String): BookRecord? {
        requireConnection().prepareStatement(
            "SELECT * FROM books WHERE content_hash = ? ORDER BY created_at ASC LIMIT 1",
        ).use { statement ->
            statement.setString(1, hash)
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) read(resultSet) else null
            }
        }
    }

    @Throws(SQLException::class)
    private fun count(sql: String): Int {
        requireConnection().createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                return if (resultSet.next()) resultSet.getInt(1) else 0
            }
        }
    }

    @Throws(SQLException::class)
    private fun maxNullable(sql: String): Long? {
        requireConnection().createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                if (!resultSet.next()) {
                    return null
                }
                val value = resultSet.getLong(1)
                return if (resultSet.wasNull()) null else value
            }
        }
    }

    @Throws(SQLException::class)
    private fun read(resultSet: ResultSet): BookRecord {
        val pages: List<String>? = GSON.fromJson(
            resultSet.getString("pages_json"),
            object : TypeToken<List<String>>() {}.type,
        )
        val lastAccessed = resultSet.getLong("last_accessed_at")
        val lastAccessedAt = if (resultSet.wasNull()) null else lastAccessed
        return BookRecord(
            resultSet.getString("id"),
            resultSet.getString("content_hash"),
            resultSet.getString("title_raw"),
            resultSet.getString("author"),
            pages ?: listOf(""),
            resultSet.getLong("created_at"),
            resultSet.getLong("updated_at"),
            lastAccessedAt,
        )
    }

    @Throws(SQLException::class)
    private fun ensureColumn(statement: Statement, column: String, alterSql: String) {
        if (hasColumn(statement, column)) {
            return
        }
        statement.execute(alterSql)
    }

    /**
     * Migrates v2 schemas (which had a soft-delete column) to the current model:
     * previously soft-deleted records are physically removed and the column dropped.
     * SQLite 3.35+ supports `ALTER TABLE ... DROP COLUMN`; sqlite-jdbc 3.45 ships it.
     */
    @Throws(SQLException::class)
    private fun dropDeletedAtColumn(statement: Statement) {
        if (!hasColumn(statement, "deleted_at")) {
            return
        }
        statement.execute("DELETE FROM books WHERE deleted_at IS NOT NULL")
        statement.execute("DROP INDEX IF EXISTS idx_books_deleted")
        statement.execute("ALTER TABLE books DROP COLUMN deleted_at")
    }

    @Throws(SQLException::class)
    private fun hasColumn(statement: Statement, column: String): Boolean {
        statement.executeQuery("PRAGMA table_info(books)").use { resultSet ->
            while (resultSet.next()) {
                if (column.equals(resultSet.getString("name"), ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    private fun requireConnection(): Connection =
        requireNotNull(connection) { "BookLite database connection is not initialized" }

    class Stats(
        private val total: Int,
        private val lastAccessedAt: Long?,
    ) {
        fun total(): Int = total

        fun lastAccessedAt(): Long? = lastAccessedAt
    }

    private companion object {
        val GSON: Gson = Gson()
    }
}
