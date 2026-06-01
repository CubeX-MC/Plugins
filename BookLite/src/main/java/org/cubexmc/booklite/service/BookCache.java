package org.cubexmc.booklite.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.cubexmc.booklite.model.BookRecord;

public class BookCache {

    private int maximumSize;
    private long expireAfterAccessMillis;
    private final Map<String, Entry> cache = new LinkedHashMap<>(16, 0.75f, true);

    public BookCache(int maximumSize, long expireAfterAccessMillis) {
        this.maximumSize = Math.max(1, maximumSize);
        this.expireAfterAccessMillis = Math.max(1, expireAfterAccessMillis);
    }

    public synchronized BookRecord get(String id) {
        Entry entry = cache.get(id);
        if (entry == null) return null;
        long now = System.currentTimeMillis();
        if (now - entry.lastAccess > expireAfterAccessMillis) {
            cache.remove(id);
            return null;
        }
        entry.lastAccess = now;
        return entry.record;
    }

    public synchronized void put(BookRecord record) {
        if (record == null) return;
        cache.put(record.id(), new Entry(record, System.currentTimeMillis()));
        trim();
    }

    public synchronized void invalidate(String id) {
        cache.remove(id);
    }

    public synchronized void clear() {
        cache.clear();
    }

    public synchronized int size() {
        return cache.size();
    }

    public synchronized void resize(int maximumSize, long expireAfterAccessMillis) {
        this.maximumSize = Math.max(1, maximumSize);
        this.expireAfterAccessMillis = Math.max(1, expireAfterAccessMillis);
        trim();
    }

    private void trim() {
        while (cache.size() > maximumSize) {
            String first = cache.keySet().iterator().next();
            cache.remove(first);
        }
    }

    private static final class Entry {
        private final BookRecord record;
        private long lastAccess;

        private Entry(BookRecord record, long lastAccess) {
            this.record = record;
            this.lastAccess = lastAccess;
        }
    }
}
