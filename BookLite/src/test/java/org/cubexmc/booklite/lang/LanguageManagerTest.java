package org.cubexmc.booklite.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;
import org.cubexmc.booklite.BookLitePlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LanguageManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void preservesBookLitePrefixPlaceholderAndMissingKeyBehavior() throws Exception {
        // Arrange
        Path langDir = tempDir.resolve("lang");
        Files.createDirectories(langDir);
        Files.writeString(langDir.resolve("zh_CN.yml"), """
                prefix: "&7[&6BookLite&7]&r "
                message: "{prefix}&aHello %name%"
                hex: "&#FF0000Red"
                """);
        BookLitePlugin plugin = mock(BookLitePlugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LanguageManagerTest"));
        LanguageManager languageManager = new LanguageManager(plugin, "zh_CN");

        // Act
        languageManager.load();

        // Assert
        assertEquals("missing.key", languageManager.raw("missing.key"));
        assertTrue(languageManager.msg("message", Map.of("name", "Ada")).contains("§aHello Ada"));
        assertTrue(languageManager.msg("hex").contains("§x§F§F§0§0§0§0Red"));
    }
}
