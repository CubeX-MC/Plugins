package org.cubexmc.metro.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.config.MigrationContext;
import org.cubexmc.metro.Metro;
import org.junit.jupiter.api.Test;

class RailwayModernizationResourceTest {

    @Test
    void bundledConfigUsesV2OnlyForDisplayWhitelist() {
        YamlConfiguration config = load("config.yml");

        assertEquals(MetroMigrations.CONFIG_VERSION, config.getInt("config-version"));
        assertEquals("<aqua><stop_name>", config.getString("titles.stop_continuous.title"));
        assertEquals("<gold>☛ <bold>", config.getString("scoreboard.styles.current_stop"));
        assertEquals("VANILLA_MOMENTUM", config.getString("speed_control.mode"));
        assertEquals("NOTE,18,1.0,PLING,0", config.getStringList("sounds.departure.notes").get(0));
        assertFalse(config.contains("lines.name"), "data resources must not be folded into config migration");
    }

    @Test
    void bundledLanguageUsesMiniMessageAndEscapesLiteralUsage() {
        YamlConfiguration language = load("lang/en_US.yml");

        assertEquals(MetroMigrations.LANG_VERSION, language.getInt("lang-version"));
        assertEquals("<green>Successfully created line: <line_id>", language.getString("line.create_success"));
        assertEquals("<green>Successfully set line <line_id> color to: <color_code>Example text",
                language.getString("line.setcolor_success"));
        assertTrue(language.getString("line.usage_create").contains("\\<line_id>"));
        assertFalse(language.getString("line.usage_create").contains("&c"));
    }

    @Test
    void entityDefaultsRemainDataOnly() {
        YamlConfiguration entity = load("entity.yml");

        assertEquals(1.6, entity.getDouble("defaults.spacing"));
        assertEquals(0.0, entity.getDouble("defaults.height"));
        assertTrue(entity.getBoolean("entities.happy_ghast.harness"));
        assertFalse(entity.contains("lang-version"));
        assertFalse(entity.contains("config-version"));
    }

    @Test
    void configMigrationConvertsOnlyDisplayWhitelistAndMergesV2Defaults() throws Exception {
        Metro plugin = pluginWithResource("config.yml", """
                config-version: 2
                titles:
                  departure:
                    subtitle: '<green>Default <line_id>'
                speed_control:
                  mode: VANILLA_MOMENTUM
                """);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(new ByteArrayInputStream("""
                titles:
                  departure:
                    title: '&6{line_name}'
                speed_control:
                  mode: '&6NOT_DISPLAY_TEXT'
                """.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));

        new MetroConfigModernizationStep(plugin).migrate(new SimpleMigrationContext("config.yml", yaml));

        assertEquals("<gold><line_name>", yaml.getString("titles.departure.title"));
        assertEquals("<green>Default <line_id>", yaml.getString("titles.departure.subtitle"));
        assertEquals("&6NOT_DISPLAY_TEXT", yaml.getString("speed_control.mode"));
    }

    @Test
    void languageMigrationConvertsExistingKeysBeforeMergingV2Defaults() throws Exception {
        Metro plugin = pluginWithResource("lang/en_US.yml", """
                lang-version: 2
                new_default: '<green>Default <line_id>'
                """);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(new ByteArrayInputStream("""
                line:
                  create_success: '&aCreated {line_id}'
                """.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));

        new MetroLanguageModernizationStep(plugin).migrate(new SimpleMigrationContext("lang/en_US.yml", yaml));

        assertEquals("<green>Created <line_id>", yaml.getString("line.create_success"));
        assertEquals("<green>Default <line_id>", yaml.getString("new_default"));
    }

    private YamlConfiguration load(String resourcePath) {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
        assertTrue(inputStream != null, () -> "Missing resource: " + resourcePath);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    private Metro pluginWithResource(String resourcePath, String content) {
        Metro plugin = mock(Metro.class);
        when(plugin.getResource(resourcePath)).thenAnswer(invocation ->
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return plugin;
    }

    private record SimpleMigrationContext(String resourcePath, YamlConfiguration yaml) implements MigrationContext {
        @Override
        public File file() {
            return new File(resourcePath);
        }

        @Override
        public void warning(String path, String message) {
        }

        @Override
        public void fail(String path, String message) {
            throw new AssertionError(path + ": " + message);
        }
    }
}
