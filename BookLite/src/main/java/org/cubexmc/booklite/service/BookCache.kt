package org.cubexmc.booklite.service

import java.util.LinkedHashMap
import org.cubexmc.booklite.model.BookRecord

class BookCache(maximumSize: Int, expireAfterAccessMillis: Long) {
    private var maximumSize: Int = maxOf(1, maximumSize)
    private var expireAfterAccessMillis: Long = maxOf(1, expireAfterAccessMillis)
    private val cache: MutableMap<String, Entry> = LinkedHashMap(16, 0.75f, true)

    @Synchronized
    fun get(id: String?): BookRecord? {
        val entry = cache[id] ?: return null
        val now = System.currentTimeMillis()
        if (now - entry.lastAccess > expireAfterAccessMillis) {
            cache.remove(id)
            return null
        }
        entry.lastAccess = now
        return entry.record
    }

    @Synchronized
    fun put(record: BookRecord?) {
        if (record == null) {
            return
        }
        cache[record.id()] = Entry(record, System.currentTimeMillis())
        trim()
    }

    @Synchronized
    fun invalidate(id: String?) {
        cache.remove(id)
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    @Synchronized
    fun size(): Int = cache.size

    @Synchronized
    fun resize(maximumSize: Int, expireAfterAccessMillis: Long) {
        this.maximumSize = maxOf(1, maximumSize)
        this.expireAfterAccessMillis = maxOf(1, expireAfterAccessMillis)
        trim()
    }

    private fun trim() {
        while (cache.size > maximumSize) {
            val first = cache.keys.iterator().next()
            cache.remove(first)
        }
    }

    private class Entry(
        val record: BookRecord,
        var lastAccess: Long,
    )
}
