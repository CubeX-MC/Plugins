package org.cubexmc.ecobalancer.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.logging.Logger;

class EcoBalancerMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void productionConfig4AndLang3MigrateToModernVersionsWithoutDoubleConvertingDefaults() throws Exception {
        // Arrange
        EcoBalancer plugin = mockPlugin();
        Files.writeString(tempDir.resolve("config.yml"), """
                config-version: 4
                language: en_US
                debt-commands:
                  - 'broadcast &e%player% &cdoes not have enough money to pay taxes.'
                """, StandardCharsets.UTF_8);
        Path langDir = Files.createDirectories(tempDir.resolve("lang"));
        Files.writeString(langDir.resolve("en_US.yml"), """
                lang-version: 3
                prefix: '&7[&6EcoBalancer&7]&r'
                messages:
                  reload_success: '%prefix% &aReloaded'
                  stats_hist_header: '§6Header§r'
                """, StandardCharsets.UTF_8);

        ConfigMigrator migrator = new ConfigMigrator(plugin);

        // Act
        assertTrue(migrator.migrateConfig());
        assertTrue(migrator.migrateLanguageFile("en_US"));

        // Assert
        YamlConfiguration config = YamlConfiguration.loadConfiguration(tempDir.resolve("config.yml").toFile());
        assertEquals(5, config.getInt("config-version"));
        assertEquals("broadcast &e%player% &cdoes not have enough money to pay taxes.",
                config.getStringList("debt-commands").get(0));

        YamlConfiguration lang = YamlConfiguration.loadConfiguration(langDir.resolve("en_US.yml").toFile());
        assertEquals(4, lang.getInt("lang-version"));
        assertEquals("<gray>[<gold>EcoBalancer<gray>]<reset>", lang.getString("prefix"));
        assertEquals("<prefix> <green>Reloaded", lang.getString("messages.reload_success"));
        assertEquals("<gold>Header<reset>", lang.getString("messages.stats_hist_header"));
        assertEquals("<white>/ecobal help <gold>- Help", lang.getString("messages.commands.help"));
        assertFalse(lang.getString("messages.commands.help", "").contains("\\<white>"),
                "MiniMessage defaults must be merged after conversion, not converted again.");
        assertTrue(Files.exists(tempDir.resolve("backups").resolve("migrations")));

        long backupCount = Files.walk(tempDir.resolve("backups").resolve("migrations"))
                .filter(Files::isRegularFile)
                .count();
        assertFalse(migrator.migrateConfig());
        assertFalse(migrator.migrateLanguageFile("en_US"));
        long backupCountAfterSecondRun = Files.walk(tempDir.resolve("backups").resolve("migrations"))
                .filter(Files::isRegularFile)
                .count();
        assertEquals(backupCount, backupCountAfterSecondRun);
    }

    @Test
    void legacyConfigMigrationStillExtractsPolicyAndRemovesLegacyKeys() throws Exception {
        // Arrange
        EcoBalancer plugin = mockPlugin();
        Files.writeString(tempDir.resolve("config.yml"), """
                config-version: 2
                language: en_US
                check-time: "12:30"
                schedule-type: weekly
                schedule-days-of-week: [2, 4]
                schedule-dates-of-month: [1]
                inactive-days-to-deduct: 12
                inactive-days-to-clear: 40
                max-deduction-per-player: 123.45
                min-balance-protection: 20.0
                only-offline-players: true
                percentile-thresholds: true
                tax-brackets:
                  - threshold: 80
                    rate: 0.01
                  - threshold: null
                    rate: 0.05
                """, StandardCharsets.UTF_8);

        // Act
        assertTrue(new ConfigMigrator(plugin).migrateConfig());

        // Assert
        YamlConfiguration config = YamlConfiguration.loadConfiguration(tempDir.resolve("config.yml").toFile());
        assertEquals(5, config.getInt("config-version"));
        assertEquals("12:30", config.getString("check-time"));

        Path policyFile = tempDir.resolve("policies").resolve("default.yml");
        assertTrue(Files.exists(policyFile));
        YamlConfiguration policy = YamlConfiguration.loadConfiguration(policyFile.toFile());
        assertEquals("weekly", policy.getString("policies.default.schedule.type"));
        assertEquals("12:30", policy.getString("policies.default.schedule.time"));
        assertEquals(123.45, policy.getDouble("policies.default.settings.max-deduction"), 0.001);
        assertTrue(policy.getBoolean("policies.default.settings.only-offline"));
        assertEquals(2, policy.getMapList("policies.default.brackets").size());
    }

    @Test
    void miniMessageAdapterPreservesTrustedLegacyColorPlaceholders() {
        // Arrange
        YamlConfiguration lang = new YamlConfiguration();
        lang.set("messages.impact", "<delta_color><delta> <bar> <player>");
        Map<String, String> placeholders = Map.of(
                "delta_color", "&c",
                "delta", "+12.5",
                "bar", "§a▏▏§r",
                "player", "Alice");
        String legacy = "&c+12.5 §a▏▏§r Alice";

        // Act
        String rendered = MessageUtils.formatMessage(lang, "messages.impact", placeholders, "");

        // Assert
        assertEquals(ChatColor.translateAlternateColorCodes('&', legacy), rendered);
    }

    private EcoBalancer mockPlugin() throws Exception {
        EcoBalancer plugin = mock(EcoBalancer.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("EcoBalancerMigrationTest"));
        when(plugin.getResource("config.yml")).thenAnswer(invocation -> resource("src/main/resources/config.yml"));
        when(plugin.getResource("lang/en_US.yml")).thenAnswer(invocation -> resource("src/main/resources/lang/en_US.yml"));
        when(plugin.getResource("lang/zh_CN.yml")).thenAnswer(invocation -> resource("src/main/resources/lang/zh_CN.yml"));
        return plugin;
    }

    private InputStream resource(String path) throws Exception {
        Path resourcePath = Paths.get(path);
        if (!Files.exists(resourcePath)) {
            resourcePath = Paths.get("EcoBalancer").resolve(path);
        }
        InputStream input = new FileInputStream(resourcePath.toFile());
        assertNotNull(input);
        return input;
    }
}
