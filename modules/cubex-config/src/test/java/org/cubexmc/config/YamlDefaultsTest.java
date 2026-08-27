package org.cubexmc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.core.CubexPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlDefaultsTest {

    @TempDir
    Path tempDir;

    @Test
    void corruptYamlIsNeverOverwritten() throws Exception {
        CubexPlugin plugin = mock(CubexPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("YamlDefaultsTest"));
        when(plugin.getResource("config.yml")).thenReturn(
                new ByteArrayInputStream("missing: 42\n".getBytes(StandardCharsets.UTF_8)));
        Path target = tempDir.resolve("config.yml");
        String corrupt = "custom: [broken\n";
        Files.writeString(target, corrupt);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                new YamlDefaults(plugin).mergeResourceIntoDataFile("config.yml", target.toFile(),
                        DefaultMergeOptions.copyMissingKeys().failOnError(true)));
        assertEquals(corrupt, Files.readString(target));
    }

    @Test
    void failedBackupStopsTheMergeBeforeWriting() throws Exception {
        CubexPlugin plugin = mock(CubexPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("YamlDefaultsTest"));
        when(plugin.getResource("config.yml")).thenReturn(
                new ByteArrayInputStream("missing: 42\n".getBytes(StandardCharsets.UTF_8)));
        Path target = tempDir.resolve("config.yml");
        Files.writeString(target, "custom: retained\n");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                new YamlDefaults(plugin).mergeResourceIntoDataFile("config.yml", target.toFile(),
                        DefaultMergeOptions.copyMissingKeys().backupWith(file -> null).failOnError(true)));
        assertEquals("custom: retained\n", Files.readString(target));
    }

    @Test
    void mergesMissingKeysAndSavesYaml() throws Exception {
        // Arrange
        CubexPlugin plugin = mock(CubexPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("YamlDefaultsTest"));
        when(plugin.getResource("config.yml")).thenReturn(new ByteArrayInputStream(("""
                present: default
                missing: 42
                section:
                  child: value
                """).getBytes(StandardCharsets.UTF_8)));
        Path target = tempDir.resolve("config.yml");
        Files.writeString(target, "present: custom\n");
        YamlDefaults defaults = new YamlDefaults(plugin);

        // Act
        DefaultMergeResult result = defaults.mergeResourceIntoDataFile(
                "config.yml",
                target.toFile(),
                DefaultMergeOptions.copyMissingKeys().warnAboutCommentLoss(false));

        // Assert
        assertTrue(result.changed());
        assertTrue(result.addedKeys().contains("missing"));
        YamlConfiguration merged = YamlConfiguration.loadConfiguration(target.toFile());
        assertEquals("custom", merged.getString("present"));
        assertEquals(42, merged.getInt("missing"));
        assertEquals("value", merged.getString("section.child"));
    }
}
