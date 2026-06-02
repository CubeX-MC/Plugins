package org.cubexmc.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import org.cubexmc.core.CubexPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceFilesTest {

    @TempDir
    Path tempDir;

    @Test
    void doesNotOverwriteExistingFiles() throws Exception {
        // Arrange
        CubexPlugin plugin = mock(CubexPlugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        Files.writeString(tempDir.resolve("lang.yml"), "custom: true\n");
        ResourceFiles resources = new ResourceFiles(plugin);

        // Act
        boolean saved = resources.saveIfMissing("lang.yml");

        // Assert
        org.junit.jupiter.api.Assertions.assertFalse(saved);
        verify(plugin, never()).saveResource("lang.yml", false);
    }

    @Test
    void savesMissingFilesWithoutOverwrite() {
        // Arrange
        CubexPlugin plugin = mock(CubexPlugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        ResourceFiles resources = new ResourceFiles(plugin);

        // Act
        boolean saved = resources.saveIfMissing("lang/zh_CN.yml");

        // Assert
        org.junit.jupiter.api.Assertions.assertTrue(saved);
        verify(plugin).saveResource("lang/zh_CN.yml", false);
    }
}
