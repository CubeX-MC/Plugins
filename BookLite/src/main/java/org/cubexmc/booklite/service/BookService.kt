package org.cubexmc.booklite.service

import java.sql.SQLException
import java.util.Locale
import org.cubexmc.booklite.BookLitePlugin
import org.cubexmc.booklite.model.BookRecord
import org.cubexmc.booklite.storage.BookRepository

class BookService(
    private val plugin: BookLitePlugin,
    private val repository: BookRepository,
    private val cache: BookCache,
) {
    @Throws(SQLException::class)
    fun saveOrGet(draft: BookRecord): BookRecord {
        val record = repository.saveOrGet(draft)
        cache.put(record)
        return record
    }

    @Throws(SQLException::class)
    fun find(id: String?): BookRecord? {
        val cached = cache.get(id)
        if (cached != null) {
            return cached
        }
        val record = repository.find(id)
        if (record != null) {
            cache.put(record)
        }
        return record
    }

    @Throws(SQLException::class)
    fun resolve(input: String?): Resolution {
        if (input.isNullOrBlank()) {
            return Resolution.notFound()
        }
        val exact = find(input)
        if (exact != null) {
            return Resolution.found(exact)
        }
        val matches = repository.findByPrefix(input.lowercase(Locale.getDefault()), 2)
        if (matches.isEmpty()) {
            return Resolution.notFound()
        }
        if (matches.size > 1) {
            return Resolution.ambiguousMatch()
        }
        val record = matches[0]
        cache.put(record)
        return Resolution.found(record)
    }

    @Throws(SQLException::class)
    fun resolveAndMarkAccessed(input: String?): Resolution {
        val resolution = resolve(input)
        if (resolution.found()) {
            markAccessed(resolution.record())
        }
        return resolution
    }

    /** Outcome of resolving a (possibly short) book id: a hit, a miss, or an ambiguous prefix. */
    class Resolution private constructor(
        private val record: BookRecord?,
        private val ambiguous: Boolean,
    ) {
        fun record(): BookRecord? = record

        fun ambiguous(): Boolean = ambiguous

        fun found(): Boolean = record != null

        companion object {
            @JvmStatic
            fun found(record: BookRecord): Resolution = Resolution(record, false)

            @JvmStatic
            fun notFound(): Resolution = Resolution(null, false)

            @JvmStatic
            fun ambiguousMatch(): Resolution = Resolution(null, true)
        }
    }

    @Throws(SQLException::class)
    fun list(page: Int, perPage: Int): List<BookRecord> {
        val safePage = maxOf(1, page)
        return repository.list((safePage - 1) * perPage, perPage)
    }

    @Throws(SQLException::class)
    fun completeIds(prefix: String?, limit: Int): List<String> =
        repository.completeIdsByPrefix(prefix, limit)

    @Throws(SQLException::class)
    fun delete(id: String): Boolean {
        val changed = repository.delete(id)
        if (changed) {
            cache.invalidate(id)
        }
        return changed
    }

    @Throws(SQLException::class)
    fun markAccessed(record: BookRecord?): Boolean {
        val currentRecord = record ?: return false
        val now = System.currentTimeMillis()
        val changed = repository.markAccessed(currentRecord.id(), now)
        if (changed) {
            currentRecord.setLastAccessedAt(now)
            currentRecord.setUpdatedAt(now)
            cache.put(currentRecord)
        }
        return changed
    }

    @Throws(SQLException::class)
    fun purgeStale(staleBefore: Long): Int {
        val purged = repository.purgeStale(staleBefore)
        if (purged > 0) {
            cache.clear()
        }
        return purged
    }

    @Throws(SQLException::class)
    fun stats(): BookRepository.Stats = repository.stats()

    fun cacheSize(): Int = cache.size()

    fun logStorageFailure(action: String, exception: Exception) {
        plugin.logger.warning("BookLite storage action failed during $action: ${exception.message}")
    }
}
