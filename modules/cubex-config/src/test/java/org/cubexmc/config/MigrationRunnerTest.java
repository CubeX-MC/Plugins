package org.cubexmc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.core.CubexPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void migratesLegacyLangCreatesBackupAndIsIdempotent() throws Exception {
        // Arrange
        Path lang = tempDir.resolve("lang").resolve("zh_CN.yml");
        Files.createDirectories(lang.getParent());
        Files.writeString(lang, """
                prefix: "&8[&bBookLite&8] &r"
                commands:
                  help_info: "&e/booklite info <id>"
                  unknown: "{prefix}&c未知子命令：&f%input%"
                """);
        MigrationRunner runner = new MigrationRunner(plugin());
        MigrationPlan plan = MigrationPlan.yaml("BookLite lang zh_CN", "lang/zh_CN.yml")
                .versionKey("lang-version")
                .targetVersion(2)
                .addStep(new LegacyTextToMiniMessageStep(1, 2));

        // Act
        MigrationReport first = runner.run(plan);
        MigrationReport second = runner.run(plan);

        // Assert
        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(lang.toFile());
        assertTrue(first.migrated());
        assertFalse(second.migrated());
        assertTrue(second.skipped());
        assertEquals(2, migrated.getInt("lang-version"));
        assertEquals("<dark_gray>[<aqua>BookLite<dark_gray>] <reset>", migrated.getString("prefix"));
        assertEquals("<yellow>/booklite info \\<id>", migrated.getString("commands.help_info"));
        assertEquals("<prefix><red>未知子命令：<white><input>", migrated.getString("commands.unknown"));
        assertTrue(first.backupFile().exists());
        assertTrue(first.backupFile().getPath().contains("backups"));
    }

    private CubexPlugin plugin() {
        CubexPlugin plugin = mock(CubexPlugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationRunnerTest"));
        return plugin;
    }
}
