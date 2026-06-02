package org.cubexmc.mountlicense.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.config.LegacyTextToMiniMessageStep;
import org.cubexmc.config.MigrationPlan;
import org.cubexmc.config.MigrationReport;
import org.cubexmc.config.MigrationRunner;
import org.cubexmc.config.NoOpMigrationStep;
import org.cubexmc.mountlicense.MountLicensePlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MountLicenseMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void migratesLegacyConfigAndLangWithBackupThenSkipsSecondRun() throws Exception {
        // Arrange
        Files.writeString(tempDir.resolve("config.yml"), """
                language: zh_CN
                registration:
                  display_name_format: "%plate%"
                """);
        Path langFile = tempDir.resolve("lang").resolve("zh_CN.yml");
        Files.createDirectories(langFile.getParent());
        Files.writeString(langFile, """
                prefix: "&8[&6牌照&8] &r"
                commands:
                  help:
                    line_info: "&e/ml info <牌照> &7- 查看载具详情"
                registration:
                  success: "{prefix}&a已注册为 &f%profile%&a。牌照: &7%vehicle_id%"
                """);
        MountLicensePlugin plugin = mockPlugin();
        MigrationRunner runner = new MigrationRunner(plugin);

        // Act
        MigrationReport configFirst = runner.run(MigrationPlan.yaml("MountLicense config", "config.yml")
                .versionKey("config-version")
                .targetVersion(2)
                .addStep(new NoOpMigrationStep(1, 2, "Add MountLicense config-version.")));
        MigrationReport langFirst = runner.run(MigrationPlan.yaml("MountLicense lang zh_CN", "lang/zh_CN.yml")
                .versionKey("lang-version")
                .targetVersion(2)
                .addStep(new LegacyTextToMiniMessageStep(1, 2)));
        MigrationReport langSecond = runner.run(MigrationPlan.yaml("MountLicense lang zh_CN", "lang/zh_CN.yml")
                .versionKey("lang-version")
                .targetVersion(2)
                .addStep(new LegacyTextToMiniMessageStep(1, 2)));

        // Assert
        YamlConfiguration config = YamlConfiguration.loadConfiguration(tempDir.resolve("config.yml").toFile());
        YamlConfiguration lang = YamlConfiguration.loadConfiguration(langFile.toFile());
        assertTrue(configFirst.migrated());
        assertTrue(langFirst.migrated());
        assertFalse(langSecond.migrated());
        assertTrue(langSecond.skipped());
        assertTrue(configFirst.backupFile().exists());
        assertTrue(langFirst.backupFile().exists());
        assertEquals(2, config.getInt("config-version"));
        assertEquals(2, lang.getInt("lang-version"));
        assertEquals("<dark_gray>[<gold>牌照<dark_gray>] <reset>", lang.getString("prefix"));
        assertEquals("<yellow>/ml info \\<牌照> <gray>- 查看载具详情",
                lang.getString("commands.help.line_info"));
        assertEquals("<prefix><green>已注册为 <white><profile><green>。牌照: <gray><vehicle_id>",
                lang.getString("registration.success"));
    }

    private MountLicensePlugin mockPlugin() {
        MountLicensePlugin plugin = mock(MountLicensePlugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MountLicenseMigrationTest"));
        return plugin;
    }
}
