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
