package org.cubexmc.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.cubexmc.core.CubexPlugin;
import org.cubexmc.core.CubexText;
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
    void rendersMiniMessageToLegacySectionString() throws Exception {
        // Arrange
        writeLang("zh_CN", """
                prefix: "<dark_gray>[<aqua>Test<dark_gray>] <reset>"
                hello: "<prefix><green>Hello <name>"
                ignored: "<red><InvalidName>"
                """);
        I18nService service = service(I18nOptions.create()
                .currentLocale("zh_CN")
                .prefixToken("<prefix>")
                .placeholderStyles(List.of(PlaceholderStyle.MINIMESSAGE_TAG))
                .colorMode(ColorMode.MINIMESSAGE));

        // Act
        String message = service.message("hello", Map.of("name", "Ada<admin>"));

        // Assert
        assertEquals("§8[§bTest§8] §aHello Ada<admin>", message);
    }

    @Test
    void minimessageRenderingMatchesLegacyVisualOutput() {
        // Arrange
        String legacyTemplate = "{prefix}&cRed &lBold &rReset &f%name%";
        String miniTemplate = "<prefix><red>Red <bold>Bold <reset>Reset <white><name>";
        String legacyPrefix = "&8[&bBookLite&8] &r";
        String miniPrefix = "<dark_gray>[<aqua>BookLite<dark_gray>] <reset>";
        CubexText text = new CubexText();
        String legacy = text.color(legacyTemplate
                .replace("{prefix}", text.color(legacyPrefix))
                .replace("%name%", "Ada"));

        // Act
        String modern = LegacyComponentSerializer.legacySection().serialize(MiniMessage.miniMessage().deserialize(
                miniTemplate.replace("<prefix>", miniPrefix),
                Placeholder.unparsed("name", "Ada")));

        // Assert
        assertEquals(removeRedundantResetBeforeColor(legacy), modern);
    }

    private String removeRedundantResetBeforeColor(String input) {
        return input.replace("§r§a", "§a")
                .replace("§r§b", "§b")
                .replace("§r§c", "§c")
                .replace("§r§f", "§f");
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
