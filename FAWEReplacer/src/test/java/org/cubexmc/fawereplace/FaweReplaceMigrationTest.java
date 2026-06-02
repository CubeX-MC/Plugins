package org.cubexmc.fawereplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.config.MigrationPlan;
import org.cubexmc.config.MigrationReport;
import org.cubexmc.config.MigrationRunner;
import org.cubexmc.config.NoOpMigrationStep;
import org.cubexmc.core.CubexPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FaweReplaceMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void migratesLegacyConfigAndLangThenSkipsSecondRun() throws Exception {
        // Arrange
        Files.writeString(tempDir.resolve("config.yml"), """
                language: zh_CN
                world: world
                """);
        Path langFile = tempDir.resolve("lang").resolve("zh_CN.yml");
        Files.createDirectories(langFile.getParent());
        Files.writeString(langFile, """
                prefix: "§7[§6FAWEReplace§7]"
                help:
                  title: "§6§lFAWE Replace §7- Command Help"
                  addrule: "§e/{label} addrule <origin> <target> §7- Add replacement rule"
                """);
        MigrationRunner runner = new MigrationRunner(mockPlugin());

        // Act
        MigrationReport configFirst = runner.run(MigrationPlan.yaml("FAWEReplace config", "config.yml")
                .versionKey("config-version")
                .targetVersion(2)
                .addStep(new NoOpMigrationStep(1, 2, "Add FAWEReplace config-version.")));
        MigrationReport langFirst = runner.run(MigrationPlan.yaml("FAWEReplace lang zh_CN", "lang/zh_CN.yml")
                .versionKey("lang-version")
                .targetVersion(2)
                .addStep(new FaweReplaceTextToMiniMessageStep(1, 2)));
        MigrationReport langSecond = runner.run(MigrationPlan.yaml("FAWEReplace lang zh_CN", "lang/zh_CN.yml")
                .versionKey("lang-version")
                .targetVersion(2)
                .addStep(new FaweReplaceTextToMiniMessageStep(1, 2)));

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
        assertEquals("<reset><gray>[<reset><gold>FAWEReplace<reset><gray>]", lang.getString("prefix"));
        assertEquals("<reset><gold><bold>FAWE Replace <reset><gray>- Command Help",
                lang.getString("help.title"));
        assertEquals("<reset><yellow>/<label> addrule \\<origin> \\<target> <reset><gray>- Add replacement rule",
                lang.getString("help.addrule"));
    }

    private CubexPlugin mockPlugin() {
        CubexPlugin plugin = mock(CubexPlugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FAWEReplacerMigrationTest"));
        return plugin;
    }
}
