package org.cubexmc.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.cubexmc.core.CubexPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class I18nServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void appliesLegacyAndHexColors() throws Exception {
        // Arrange
        writeLang("zh_CN", """
                prefix: "&7[&6Test&7]"
                message: "{prefix}&aGreen &#FF0000Red"
                """);
        I18nService service = service(I18nOptions.create()
                .currentLocale("zh_CN")
                .prefixToken("{prefix}")
                .colorMode(ColorMode.LEGACY_AND_HEX));

        // Act
        String message = service.message("message");

        // Assert
        assertTrue(message.contains("§aGreen"));
        assertTrue(message.contains("§x§F§F§0§0§0§0Red"));
    }

    @Test
    void supportsPercentBraceAndPositionalPlaceholders() throws Exception {
        // Arrange
        writeLang("zh_CN", """
                percent: "Hello %name%"
                brace: "Hello {name}"
                positional: "Hello %1"
                """);
        I18nService service = service(I18nOptions.create()
                .currentLocale("zh_CN")
                .placeholderStyles(List.of(
                        PlaceholderStyle.PERCENT_NAME,
                        PlaceholderStyle.BRACE_NAME,
                        PlaceholderStyle.POSITIONAL_PERCENT_INDEX)));

        // Act / Assert
        assertEquals("Hello Ada", service.message("percent", Map.of("name", "Ada")));
        assertEquals("Hello Ada", service.message("brace", Map.of("name", "Ada")));
        assertEquals("Hello Ada", service.message("positional", "Ada"));
    }

    @Test
    void appliesMissingKeyModes() throws Exception {
        // Arrange
        writeLang("zh_CN", "prefix: ''\n");

        // Act / Assert
        assertEquals("missing", service(I18nOptions.create()
                .currentLocale("zh_CN")
                .missingKeyMode(MissingKeyMode.RETURN_KEY)).message("missing"));
        assertEquals("", service(I18nOptions.create()
                .currentLocale("zh_CN")
                .missingKeyMode(MissingKeyMode.RETURN_EMPTY)).message("missing"));
        assertEquals("Missing message: missing", service(I18nOptions.create()
                .currentLocale("zh_CN")
                .missingKeyMode(MissingKeyMode.RETURN_MISSING_MESSAGE_PREFIX)).message("missing"));
    }

    @Test
    void usesFallbackChain() throws Exception {
        // Arrange
        writeLang("zh_CN", "hello: fallback\n");
        I18nService service = service(I18nOptions.create()
                .currentLocale("missing_locale")
                .defaultLocale("missing_locale")
                .fallbackLocales(List.of("zh_CN")));

        // Act / Assert
        assertEquals("fallback", service.message("hello"));
    }

    @Test
    void minimessageModeIsReservedForPhaseB() throws Exception {
        // Arrange
        writeLang("zh_CN", "hello: '<green>Hello'\n");
        CubexPlugin plugin = mock(CubexPlugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("I18nServiceTest"));
        I18nService service = I18nServices.create(plugin, I18nOptions.create()
                .currentLocale("zh_CN")
                .colorMode(ColorMode.MINIMESSAGE));

        // Act / Assert
        assertThrows(UnsupportedOperationException.class, service::reload);
    }

    private I18nService service(I18nOptions options) {
        CubexPlugin plugin = mock(CubexPlugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("I18nServiceTest"));
        I18nService service = I18nServices.create(plugin, options);
        service.reload();
        return service;
    }

    private void writeLang(String locale, String content) throws Exception {
        Path langDir = tempDir.resolve("lang");
        Files.createDirectories(langDir);
        Files.writeString(langDir.resolve(locale + ".yml"), content);
    }
}
