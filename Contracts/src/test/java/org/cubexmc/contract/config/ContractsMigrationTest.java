package org.cubexmc.contract.config;

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
import org.cubexmc.contract.ContractPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContractsMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void migratesLegacyConfigAndMessagesThenSkipsSecondRun() throws Exception {
        // Arrange
        Files.writeString(tempDir.resolve("config.yml"), """
                language: zh_CN
                storage:
                  flush-interval-seconds: 30
                """);
        Path langFile = tempDir.resolve("lang").resolve("zh_CN.yml");
        Files.createDirectories(langFile.getParent());
        Files.writeString(langFile, """
                prefix: "&#F4D03F[Contract]&#F1F5F9 "
                status:
                  open: "公开中"
                messages:
                  reloaded: "%prefix%&#69DB7C配置已重新加载。"
                  list-footer: "&#CFD8DC使用 &#FFE066/contract info <id> &#CFD8DC查看详情。"
                  create-success: "%prefix%&#69DB7C合同已发布: &#FFE066#%id% &#F1F5F9已托管 &#69DB7C%amount%&#F1F5F9。"
                """);
        MigrationRunner runner = new MigrationRunner(mockPlugin());

        // Act
        MigrationReport configFirst = runner.run(MigrationPlan.yaml("Contracts config", "config.yml")
                .versionKey("config-version")
                .targetVersion(2)
                .addStep(new NoOpMigrationStep(1, 2, "Add Contracts config-version.")));
        MigrationReport langFirst = runner.run(MigrationPlan.yaml("Contracts lang zh_CN", "lang/zh_CN.yml")
                .versionKey("lang-version")
                .targetVersion(2)
                .addStep(new LegacyTextToMiniMessageStep(1, 2)));
        MigrationReport langSecond = runner.run(MigrationPlan.yaml("Contracts lang zh_CN", "lang/zh_CN.yml")
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
        assertEquals("<#F4D03F>[Contract]<#F1F5F9> ", lang.getString("prefix"));
        assertEquals("<prefix><#69DB7C>配置已重新加载。", lang.getString("messages.reloaded"));
        assertEquals("<#CFD8DC>使用 <#FFE066>/contract info \\<id> <#CFD8DC>查看详情。",
                lang.getString("messages.list-footer"));
        assertEquals("<prefix><#69DB7C>合同已发布: <#FFE066>#<id> <#F1F5F9>已托管 <#69DB7C><amount><#F1F5F9>。",
                lang.getString("messages.create-success"));
    }

    private ContractPlugin mockPlugin() {
        ContractPlugin plugin = mock(ContractPlugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ContractsMigrationTest"));
        return plugin;
    }
}
