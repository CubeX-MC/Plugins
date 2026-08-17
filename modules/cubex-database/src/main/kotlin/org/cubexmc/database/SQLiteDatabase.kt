package org.cubexmc.database

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.bukkit.plugin.Plugin

/**
 * Resolves one SQLite file and opens connections against it with a fixed [SQLitePragmas] set.
 *
 * Deliberately not included: schema, DAOs, migrations, transaction retry and connection pooling.
 * Those differ per plugin and stay in the plugin. This type only removes the duplicated
 * "resolve path, load driver, open connection, apply PRAGMAs" preamble.
 */
class SQLiteDatabase @JvmOverloads constructor(
    private val file: File,
    private val pragmas: SQLitePragmas = SQLitePragmas.none(),
    /**
     * Also append `?busy_timeout=` to the JDBC URL. Off by default because it changes the URL a
     * plugin has been opening; EcoBalancer opts in because that is what it already did.
     */
    private val mirrorBusyTimeoutInUrl: Boolean = false,
    /**
     * Keep the connection when a PRAGMA statement fails instead of closing it and rethrowing.
     * EcoBalancer opts in because it already swallowed these failures; a plugin that depends on
     * `foreign_keys` or WAL actually being on should leave this off.
     */
    private val ignorePragmaFailures: Boolean = false,
) {
    /**
     * @param relativeOrAbsolutePath an absolute path, or a path relative to the plugin data folder.
     */
    @JvmOverloads
    constructor(
        plugin: Plugin,
        relativeOrAbsolutePath: String,
        pragmas: SQLitePragmas = SQLitePragmas.none(),
        mirrorBusyTimeoutInUrl: Boolean = false,
        ignorePragmaFailures: Boolean = false,
    ) : this(
        resolve(plugin, relativeOrAbsolutePath),
        pragmas,
        mirrorBusyTimeoutInUrl,
        ignorePragmaFailures,
    )

    fun file(): File = file

    /** Creates the parent directory if missing. Returns false when it could not be created. */
    fun ensureParentDirectory(): Boolean {
        val parent = file.parentFile ?: return true
        return parent.exists() || parent.mkdirs()
    }

    @Throws(SQLException::class)
    fun openConnection(): Connection {
        loadDriver()
        val connection = DriverManager.getConnection(jdbcUrl())
        try {
            applyPragmas(connection)
        } catch (exception: SQLException) {
            if (!ignorePragmaFailures) {
                // A half-configured connection is worse than none: don't leak it to the caller.
                runCatching { connection.close() }
                throw exception
            }
        }
        return connection
    }

    fun jdbcUrl(): String {
        val base = "jdbc:sqlite:${file.absolutePath}"
        if (!mirrorBusyTimeoutInUrl) return base
        // As a URL parameter busy_timeout also covers the window before the PRAGMA statement itself
        // runs on a contended file.
        val busyTimeout = pragmas.busyTimeoutMillis() ?: return base
        return "$base?busy_timeout=$busyTimeout"
    }

    @Throws(SQLException::class)
    private fun applyPragmas(connection: Connection) {
        val statements = pragmas.statements()
        if (statements.isEmpty()) return
        connection.createStatement().use { statement ->
            for (pragma in statements) {
                statement.execute(pragma)
            }
        }
    }

    private companion object {
        fun resolve(plugin: Plugin, relativeOrAbsolutePath: String): File {
            val candidate = File(relativeOrAbsolutePath)
            return if (candidate.isAbsolute) candidate else File(plugin.dataFolder, relativeOrAbsolutePath)
        }

        fun loadDriver() {
            // Shaded sqlite-jdbc registers itself via the service loader on modern versions, but the
            // explicit load keeps the older plugins' behaviour when the plugin class loader is late.
            runCatching { Class.forName("org.sqlite.JDBC") }
        }
    }
}
