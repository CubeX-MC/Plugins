package org.cubexmc.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteDatabaseTest {

    @Test
    void shouldEmitOnlyTheConfiguredPragmasInAStableOrder() {
        SQLitePragmas pragmas = SQLitePragmas.builder()
                .busyTimeoutMillis(10000)
                .wal(true)
                .synchronous("normal")
                .foreignKeys(true)
                .tempStoreMemory(true)
                .cacheSizeKb(8192)
                .build();

        assertEquals(
                List.of(
                        "PRAGMA busy_timeout = 10000",
                        "PRAGMA journal_mode = WAL",
                        "PRAGMA synchronous = NORMAL",
                        "PRAGMA foreign_keys = ON",
                        "PRAGMA temp_store = MEMORY",
                        "PRAGMA cache_size = -8192"),
                pragmas.statements());
    }

    @Test
    void shouldEmitNothingForUnsetPragmas() {
        assertTrue(SQLitePragmas.none().statements().isEmpty());
        // Only the pragma that was actually set shows up; the rest keep SQLite's own defaults.
        assertEquals(List.of("PRAGMA busy_timeout = 5000"),
                SQLitePragmas.builder().busyTimeoutMillis(5000).build().statements());
    }

    @Test
    void shouldRejectNonsensePragmaValues() {
        assertThrows(IllegalArgumentException.class, () -> SQLitePragmas.builder().busyTimeoutMillis(-1));
        assertThrows(IllegalArgumentException.class, () -> SQLitePragmas.builder().cacheSizeKb(0));
    }

    @Test
    void shouldResolveRelativePathsAgainstTheDataFolderAndKeepAbsoluteOnes(@TempDir Path tempDir) {
        Plugin plugin = mock(Plugin.class);
        File dataFolder = tempDir.resolve("PluginData").toFile();
        when(plugin.getDataFolder()).thenReturn(dataFolder);

        SQLiteDatabase relative = new SQLiteDatabase(plugin, "records.db", SQLitePragmas.none());
        assertEquals(new File(dataFolder, "records.db"), relative.file());

        File absolute = tempDir.resolve("elsewhere.db").toFile();
        SQLiteDatabase pinned = new SQLiteDatabase(plugin, absolute.getAbsolutePath(), SQLitePragmas.none());
        assertEquals(absolute, pinned.file());
    }

    @Test
    void shouldMirrorBusyTimeoutIntoTheJdbcUrlOnlyWhenAskedTo(@TempDir Path tempDir) {
        File file = tempDir.resolve("books.db").toFile();
        SQLitePragmas timeout = SQLitePragmas.builder().busyTimeoutMillis(10000).build();

        // Default: the URL stays exactly what these plugins already opened.
        assertEquals("jdbc:sqlite:" + file.getAbsolutePath(), new SQLiteDatabase(file, timeout).jdbcUrl());
        assertEquals(
                "jdbc:sqlite:" + file.getAbsolutePath(),
                new SQLiteDatabase(file, SQLitePragmas.none(), true).jdbcUrl());

        assertEquals(
                "jdbc:sqlite:" + file.getAbsolutePath() + "?busy_timeout=10000",
                new SQLiteDatabase(file, timeout, true).jdbcUrl());
    }

    @Test
    void shouldCreateTheParentDirectoryOnDemand(@TempDir Path tempDir) {
        File nested = tempDir.resolve("data").resolve("gems.db").toFile();
        SQLiteDatabase database = new SQLiteDatabase(nested, SQLitePragmas.none());

        assertFalse(nested.getParentFile().exists());
        assertTrue(database.ensureParentDirectory());
        assertTrue(nested.getParentFile().isDirectory());
        // Idempotent: a second call on an existing directory still reports success.
        assertTrue(database.ensureParentDirectory());
    }

    @Test
    void shouldOpenARealConnectionWithPragmasApplied(@TempDir Path tempDir) throws SQLException {
        File file = tempDir.resolve("real.db").toFile();
        SQLiteDatabase database = new SQLiteDatabase(
                file,
                SQLitePragmas.builder().busyTimeoutMillis(5000).foreignKeys(true).build());

        try (Connection connection = database.openConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
        assertTrue(file.exists());
    }

    @Test
    void shouldCommitOnSuccessAndRollBackOnFailure(@TempDir Path tempDir) throws SQLException {
        File file = tempDir.resolve("tx.db").toFile();
        SQLiteDatabase database = new SQLiteDatabase(file, SQLitePragmas.none());

        JdbcOps.withConnection(database, connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
            }
            return null;
        });

        JdbcOps.inTransaction(database, connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO t (id) VALUES (1)");
            }
            return null;
        });
        assertEquals(1, rowCount(database));

        assertThrows(SQLException.class, () -> JdbcOps.inTransaction(database, connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO t (id) VALUES (2)");
                // Duplicate primary key: the whole transaction must roll back, including row 2.
                statement.executeUpdate("INSERT INTO t (id) VALUES (1)");
            }
            return null;
        }));
        assertEquals(1, rowCount(database));
    }

    private int rowCount(SQLiteDatabase database) throws SQLException {
        return JdbcOps.withConnection(database, connection -> {
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM t")) {
                result.next();
                return result.getInt(1);
            }
        });
    }
}
