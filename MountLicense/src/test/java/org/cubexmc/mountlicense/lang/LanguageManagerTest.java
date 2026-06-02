package org.cubexmc.mountlicense.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;
import org.cubexmc.mountlicense.MountLicensePlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LanguageManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void rendersMiniMessageWithCanonicalPlaceholdersAndLiteralUsage() throws Exception {
        // Arrange
        Path langDir = tempDir.resolve("lang");
        Files.createDirectories(langDir);
        Files.writeString(langDir.resolve("zh_CN.yml"), """
                lang-version: 2
                prefix: "<dark_gray>[<gold>牌照<dark_gray>] <reset>"
                message: "<prefix><green>已注册 <white><profile> <gray>#<short_id>"
                usage: "<yellow>/ml info \\\\<牌照>"
                list:
                  - "<gray>Line <name>"
                """);
        MountLicensePlugin plugin = mockPlugin();
        LanguageManager language = new LanguageManager(plugin, "zh_CN");

        // Act
        language.load();

        // Assert
        assertEquals("missing.key", language.raw("missing.key"));
        assertTrue(language.msg("message", Map.of("profile", "horse", "short_id", "abc123"))
                .contains("§a已注册"));
        assertTrue(language.msg("usage").contains("<牌照>"));
        assertEquals("§7Line Ada", language.msgList("list", Map.of("name", "Ada")).get(0));
    }

    private MountLicensePlugin mockPlugin() {
        MountLicensePlugin plugin = mock(MountLicensePlugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MountLicenseLanguageManagerTest"));
        return plugin;
    }
}
