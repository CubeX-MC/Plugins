package org.cubexmc.database

/**
 * The PRAGMA set applied to every connection opened by a [SQLiteDatabase].
 *
 * Each plugin keeps its own values — BookLite, EcoBalancer and RuleGems deliberately use different
 * busy timeouts and journal settings — so nothing here has a "one true" default beyond SQLite's own.
 * A pragma left unset is not emitted at all, which keeps SQLite's default rather than guessing one.
 */
public class SQLitePragmas private constructor(
    private val busyTimeoutMillis: Int?,
    private val wal: Boolean?,
    private val synchronous: String?,
    private val foreignKeys: Boolean?,
    private val tempStoreMemory: Boolean?,
    private val cacheSizeKb: Int?,
) {
    /** The PRAGMA statements to run, in a stable order, for a freshly opened connection. */
    public fun statements(): List<String> {
        val statements = ArrayList<String>(6)
        busyTimeoutMillis?.let { statements.add("PRAGMA busy_timeout = $it") }
        wal?.let { statements.add("PRAGMA journal_mode = ${if (it) "WAL" else "DELETE"}") }
        synchronous?.let { statements.add("PRAGMA synchronous = $it") }
        foreignKeys?.let { statements.add("PRAGMA foreign_keys = ${if (it) "ON" else "OFF"}") }
        tempStoreMemory?.let { statements.add("PRAGMA temp_store = ${if (it) "MEMORY" else "DEFAULT"}") }
        // SQLite reads a negative cache_size as kibibytes rather than pages.
        cacheSizeKb?.let { statements.add("PRAGMA cache_size = -$it") }
        return statements
    }

    /** The busy timeout, if set, so a caller can mirror it into the JDBC URL as EcoBalancer does. */
    public fun busyTimeoutMillis(): Int? = busyTimeoutMillis

    public class Builder internal constructor() {
        private var busyTimeoutMillis: Int? = null
        private var wal: Boolean? = null
        private var synchronous: String? = null
        private var foreignKeys: Boolean? = null
        private var tempStoreMemory: Boolean? = null
        private var cacheSizeKb: Int? = null

        public fun busyTimeoutMillis(millis: Int): Builder = apply {
            require(millis >= 0) { "busy_timeout must not be negative: $millis" }
            busyTimeoutMillis = millis
        }

        public fun wal(enabled: Boolean): Builder = apply { wal = enabled }

        public fun synchronous(value: String?): Builder = apply {
            synchronous = value?.takeUnless(String::isBlank)?.uppercase()
        }

        public fun foreignKeys(enabled: Boolean): Builder = apply { foreignKeys = enabled }

        public fun tempStoreMemory(enabled: Boolean): Builder = apply { tempStoreMemory = enabled }

        public fun cacheSizeKb(kilobytes: Int): Builder = apply {
            require(kilobytes > 0) { "cache_size must be positive: $kilobytes" }
            cacheSizeKb = kilobytes
        }

        public fun build(): SQLitePragmas =
            SQLitePragmas(busyTimeoutMillis, wal, synchronous, foreignKeys, tempStoreMemory, cacheSizeKb)
    }

    public companion object {
        @JvmStatic
        public fun builder(): Builder = Builder()

        /** No PRAGMA statements at all; SQLite defaults apply. */
        @JvmStatic
        public fun none(): SQLitePragmas = builder().build()
    }
}
