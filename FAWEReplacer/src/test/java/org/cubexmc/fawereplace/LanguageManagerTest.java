package org.cubexmc.fawereplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.cubexmc.core.CubexPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LanguageManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void rendersMiniMessageWithVarargsPlaceholdersAndLiteralUsage() throws Exception {
        // Arrange
        writeLang("""
                lang-version: 2
                prefix: "<reset><gray>[<reset><gold>FAWEReplace<reset><gray>]"
                help:
                  start: "<reset><yellow>/<label> start <reset><gray>- Start cleaning task"
                  addrule: "<reset><yellow>/<label> addrule \\\\<origin> \\\\<target> <reset><gray>- Add replacement rule"
                log:
                  language_loaded: "Loaded language: <language> (<count> messages)"
                """);
        LanguageManager languageManager = new LanguageManager(mockPlugin(), "zh_CN");

        // Act
        String start = languageManager.getMessage("help.start", "label", "fawereplace");
        String usage = languageManager.getMessage("help.addrule", "label", "fawereplace");
        String missing = languageManager.getMessage("missing.key");

        // Assert
        assertTrue(start.contains("/fawereplace start"));
        assertTrue(usage.contains("<origin> <target>"));
        assertEquals("missing.key", missing);
    }

    private void writeLang(String content) throws Exception {
        Path lang = tempDir.resolve("lang").resolve("zh_CN.yml");
        Files.createDirectories(lang.getParent());
        Files.writeString(lang, content);
    }

    private CubexPlugin mockPlugin() {
        CubexPlugin plugin = mock(CubexPlugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FAWEReplacerLanguageManagerTest"));
        return plugin;
    }
}
