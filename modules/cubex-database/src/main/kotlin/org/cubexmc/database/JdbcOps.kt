package org.cubexmc.database

import java.sql.Connection
import java.sql.SQLException

/** A connection-scoped action that is allowed to fail with [SQLException]. */
fun interface SQLFunction<T> {
    @Throws(SQLException::class)
    fun apply(connection: Connection): T
}

/**
 * Small try-with-resources helpers for the per-operation connection style used by
 * EcoBalancer and RuleGems. Plugins holding one long-lived connection (BookLite) do not need these.
 */
object JdbcOps {
    @JvmStatic
    @Throws(SQLException::class)
    fun <T> withConnection(database: SQLiteDatabase, action: SQLFunction<T>): T =
        database.openConnection().use { connection -> action.apply(connection) }

    /**
     * Runs [action] inside an explicit transaction, committing on success and rolling back on any
     * failure. The connection's previous auto-commit setting is restored before it is closed.
     */
    @JvmStatic
    @Throws(SQLException::class)
    fun <T> inTransaction(database: SQLiteDatabase, action: SQLFunction<T>): T =
        database.openConnection().use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val result = action.apply(connection)
                connection.commit()
                result
            } catch (throwable: Throwable) {
                runCatching { connection.rollback() }
                throw throwable
            } finally {
                runCatching { connection.autoCommit = previousAutoCommit }
            }
        }
}
