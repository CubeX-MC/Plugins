package org.cubexmc.booklite.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

import org.cubexmc.booklite.BookLitePlugin;
import org.cubexmc.booklite.config.ConfigManager;
import org.cubexmc.booklite.model.BookRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BookRepositoryTest {

    @TempDir
    File dataFolder;

    private BookRepository repository;

    @BeforeEach
    void setUp() {
        BookLitePlugin plugin = mock(BookLitePlugin.class);
        lenient().when(plugin.getDataFolder()).thenReturn(dataFolder);
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("BookLiteTest"));

        ConfigManager config = mock(ConfigManager.class);
        lenient().when(config.getSqliteFile()).thenReturn("test.db");
        lenient().when(config.isWal()).thenReturn(true);

        repository = new BookRepository(plugin, config);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        if (repository != null) repository.close();
    }

    private BookRecord draft(String id, String hash) {
        return draftAt(id, hash, System.currentTimeMillis());
    }

    private BookRecord draftAt(String id, String hash, long now) {
        return new BookRecord(id, hash, "Title", "Author", List.of("page-a", "page-b"),
                now, now);
    }

    @Test
    void saveOrGetDeduplicatesByHash() throws SQLException {
        BookRecord first = repository.saveOrGet(draft("id-1", "shared-hash"));
        BookRecord second = repository.saveOrGet(draft("id-2", "shared-hash"));

        assertEquals(first.id(), second.id());
        assertEquals(1, repository.stats().total());
    }

    @Test
    void saveOrGetInsertsNewBookForDifferentHash() throws SQLException {
        repository.saveOrGet(draft("id-1", "hash-a"));
        repository.saveOrGet(draft("id-2", "hash-b"));
        assertEquals(2, repository.stats().total());
    }

    @Test
    void findReturnsNullForBlankOrUnknownId() throws SQLException {
        assertNull(repository.find(null));
        assertNull(repository.find(""));
        assertNull(repository.find("missing"));
    }

    @Test
    void deleteRemovesRecord() throws SQLException {
        repository.saveOrGet(draft("id-1", "hash"));
        assertTrue(repository.delete("id-1"));
        assertNull(repository.find("id-1"));
        assertFalse(repository.delete("id-1"), "second delete is a no-op");
    }

    @Test
    void findByPrefixMatchesAndRespectsLimit() throws SQLException {
        repository.saveOrGet(draft("aaa-1", "hash-1"));
        repository.saveOrGet(draft("aaa-2", "hash-2"));
        repository.saveOrGet(draft("bbb-1", "hash-3"));

        assertEquals(2, repository.findByPrefix("aaa", 10).size());
        assertEquals(1, repository.findByPrefix("aaa", 1).size());
        assertEquals(1, repository.findByPrefix("bbb", 10).size());
        assertTrue(repository.findByPrefix("zzz", 10).isEmpty());
        assertTrue(repository.findByPrefix(" ", 10).isEmpty());
    }

    @Test
    void completeIdsByPrefixReturnsRecentIdsFirst() throws SQLException {
        repository.saveOrGet(draftAt("id-1", "hash-1", 1_000L));
        repository.saveOrGet(draftAt("id-2", "hash-2", 2_000L));

        assertEquals(List.of("id-2"), repository.completeIdsByPrefix("", 1));
        assertEquals(List.of("id-2", "id-1"), repository.completeIdsByPrefix("id", 10));
    }

    @Test
    void statsTracksTotalCount() throws SQLException {
        repository.saveOrGet(draft("id-1", "hash-1"));
        repository.saveOrGet(draft("id-2", "hash-2"));
        repository.saveOrGet(draft("id-3", "hash-3"));

        assertEquals(3, repository.stats().total());
    }

    @Test
    void markAccessedUpdatesRecordAndStats() throws SQLException {
        repository.saveOrGet(draft("id-1", "hash-1"));

        assertNull(repository.find("id-1").lastAccessedAt());
        assertNull(repository.stats().lastAccessedAt());

        assertTrue(repository.markAccessed("id-1", 5_000L));
        assertEquals(5_000L, repository.find("id-1").lastAccessedAt());
        assertEquals(5_000L, repository.stats().lastAccessedAt());
    }

    @Test
    void purgeStaleUsesLastAccessedWhenPresent() throws SQLException {
        repository.saveOrGet(draftAt("fresh", "hash-fresh", 1_000L));
        repository.saveOrGet(draftAt("stale", "hash-stale", 1_000L));
        repository.markAccessed("fresh", 10_000L);
        repository.markAccessed("stale", 2_000L);

        assertEquals(1, repository.purgeStale(5_000L));
        assertNull(repository.find("stale"));
        assertNotNull(repository.find("fresh"));
    }

    @Test
    void purgeStaleFallsBackToCreatedAtForNeverRead() throws SQLException {
        repository.saveOrGet(draftAt("old-unread", "hash-old", 1_000L));
        repository.saveOrGet(draftAt("recent-unread", "hash-recent", 9_000L));

        // Cutoff 5_000: old-unread (created 1_000) is stale; recent-unread is not.
        assertEquals(1, repository.purgeStale(5_000L));
        assertNull(repository.find("old-unread"));
        assertNotNull(repository.find("recent-unread"));
    }

    @Test
    void initMigratesLegacyV1SchemaToCurrent() throws SQLException {
        repository.close();
        File legacy = new File(dataFolder, "legacy.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + legacy.getAbsolutePath());
             Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE books (
                      id TEXT PRIMARY KEY,
                      content_hash TEXT NOT NULL,
                      title_raw TEXT NOT NULL,
                      author TEXT NOT NULL,
                      pages_json TEXT NOT NULL,
                      total_pages INTEGER NOT NULL,
                      created_at INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL,
                      deleted_at INTEGER NULL
                    )
                    """);
        }

        ConfigManager config = mock(ConfigManager.class);
        lenient().when(config.getSqliteFile()).thenReturn("legacy.db");
        lenient().when(config.isWal()).thenReturn(true);
        BookLitePlugin plugin = mock(BookLitePlugin.class);
        lenient().when(plugin.getDataFolder()).thenReturn(dataFolder);
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("BookLiteTest"));

        repository = new BookRepository(plugin, config);
        repository.init();

        // last_accessed_at column was added, deleted_at dropped.
        repository.saveOrGet(draft("id-1", "hash-1"));
        assertTrue(repository.markAccessed("id-1", 5_000L));
        assertEquals(5_000L, repository.find("id-1").lastAccessedAt());
    }

    @Test
    void initMigrationPurgesPreviouslySoftDeletedRecords() throws SQLException {
        repository.close();
        File legacy = new File(dataFolder, "legacy-with-deleted.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + legacy.getAbsolutePath());
             Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE books (
                      id TEXT PRIMARY KEY,
                      content_hash TEXT NOT NULL,
                      title_raw TEXT NOT NULL,
                      author TEXT NOT NULL,
                      pages_json TEXT NOT NULL,
                      total_pages INTEGER NOT NULL,
                      created_at INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL,
                      deleted_at INTEGER NULL
                    )
                    """);
            st.execute("INSERT INTO books VALUES ('keep', 'h-keep', 't', 'a', '[\"p\"]', 1, 0, 0, NULL)");
            st.execute("INSERT INTO books VALUES ('drop', 'h-drop', 't', 'a', '[\"p\"]', 1, 0, 0, 1234)");
        }

        ConfigManager config = mock(ConfigManager.class);
        lenient().when(config.getSqliteFile()).thenReturn("legacy-with-deleted.db");
        lenient().when(config.isWal()).thenReturn(true);
        BookLitePlugin plugin = mock(BookLitePlugin.class);
        lenient().when(plugin.getDataFolder()).thenReturn(dataFolder);
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("BookLiteTest"));

        repository = new BookRepository(plugin, config);
        repository.init();

        assertNotNull(repository.find("keep"));
        assertNull(repository.find("drop"));
        assertEquals(1, repository.stats().total());
    }

    @Test
    void readPreservesPagesAcrossPersistence() throws SQLException {
        repository.saveOrGet(draft("id-1", "hash"));
        BookRecord loaded = repository.find("id-1");
        assertNotNull(loaded);
        assertEquals(List.of("page-a", "page-b"), loaded.pages());
        assertEquals(2, loaded.totalPages());
    }
}
