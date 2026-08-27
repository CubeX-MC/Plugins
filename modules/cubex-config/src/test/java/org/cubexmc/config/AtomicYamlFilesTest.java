package org.cubexmc.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AtomicYamlFilesTest {
    @TempDir Path directory;

    @Test void missingIsEmptyButMalformedIsAnError() throws Exception {
        Path file = directory.resolve("data.yml");
        assertTrue(AtomicYamlFiles.read(file.toFile()).getKeys(false).isEmpty());
        Files.writeString(file, "data: [broken\n");
        assertThrows(InvalidConfigurationException.class, () -> AtomicYamlFiles.read(file.toFile()));
        assertEquals("data: [broken\n", Files.readString(file));
    }

    @Test void savesUnicodeAndNestedDataWithoutTemporaryFiles() throws Exception {
        Path file = directory.resolve("nested/data.yml");
        YamlConfiguration data = new YamlConfiguration();
        data.set("owner.name", "测试玩家");
        AtomicYamlFiles.write(file.toFile(), data);
        assertEquals("测试玩家", AtomicYamlFiles.read(file.toFile()).getString("owner.name"));
        try (var files = Files.list(file.getParent())) { assertEquals(1, files.count()); }
    }

    @Test void failedSerializationLeavesOriginalUntouchedAndCleansTemporaryFile() throws Exception {
        Path file = directory.resolve("data.yml");
        Files.writeString(file, "old: retained\n");
        YamlConfiguration broken = mock(YamlConfiguration.class);
        doThrow(new IOException("disk failure")).when(broken).save(any(java.io.File.class));
        assertThrows(IOException.class, () -> AtomicYamlFiles.write(file.toFile(), broken));
        assertEquals("old: retained\n", Files.readString(file));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void backupsNeverOverwriteEarlierCopies() throws Exception {
        Path file = directory.resolve("config.yml");
        Path backups = directory.resolve("backups");
        Files.writeString(file, "first");
        var first = FileBackups.copyUnique(file.toFile(), backups.toFile());
        Files.writeString(file, "second");
        var second = FileBackups.copyUnique(file.toFile(), backups.toFile());
        assertNotEquals(first, second);
        assertEquals("first", Files.readString(first.toPath()));
        assertEquals("second", Files.readString(second.toPath()));
    }
    @org.junit.jupiter.api.Test
    void accessFailureIsNotTreatedAsAMissingFile() {
        java.nio.file.Path path = java.nio.file.Path.of("unreadable.yml");
        try (var files = org.mockito.Mockito.mockStatic(java.nio.file.Files.class)) {
            files.when(() -> java.nio.file.Files.newBufferedReader(path, java.nio.charset.StandardCharsets.UTF_8))
                    .thenThrow(new java.nio.file.AccessDeniedException(path.toString()));
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> AtomicYamlFiles.read(path.toFile()));
        }
    }
}
