package org.cubexmc.contract.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.cubexmc.core.CubexLogger;
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

    @Test
    void migratesVersionFiveToTemplateAndSchedulingDefaultsWithoutOverwritingOperators() throws Exception {
        Files.writeString(tempDir.resolve("config.yml"), """
                config-version: 5
                limits:
                  max-templates-per-player: 7
                """);
        MigrationRunner runner = new MigrationRunner(mockPlugin());

        MigrationReport report = runner.run(MigrationPlan.yaml("Contracts config", "config.yml")
                .versionKey("config-version")
                .targetVersion(6)
                .addStep(ContractsConfigMigrations.schedulingAndTemplatesStep()));

        YamlConfiguration config = YamlConfiguration.loadConfiguration(tempDir.resolve("config.yml").toFile());
        assertTrue(report.migrated());
        assertEquals(6, config.getInt("config-version"));
        assertEquals(7, config.getInt("limits.max-templates-per-player"));
        assertEquals(64, config.getInt("limits.max-scheduled-contracts"));
        assertEquals(30, config.getInt("scheduling.max-days-ahead"));
        assertEquals(30, config.getInt("scheduling.scan-interval-seconds"));
    }

    @Test
    void langVersionTwoConvertsUiSectionsToMiniMessageAndAddsTheNewKeys() throws Exception {
        // Arrange: a v2 language file from before the GUI/service strings were externalized and
        // before ui.* moved onto the i18n service. Its ui values still use legacy &#RRGGBB, and
        // `ui.batch-card-title` shows the <name> placeholders v2 already substituted by hand.
        Path langFile = tempDir.resolve("lang").resolve("zh_CN.yml");
        Files.createDirectories(langFile.getParent());
        Files.writeString(langFile, """
                lang-version: 2
                status:
                  open: "&a公开中"
                ui:
                  batch-accept: "&#69DB7C我改过的领取按钮"
                  batch-card-title: "&#F4D03F<title> &#FFFFFF×<total>"
                messages:
                  reloaded: "<prefix><#69DB7C>配置已重新加载。"
                """);
        ContractPlugin plugin = mockPlugin();
        when(plugin.getResource("lang/zh_CN.yml"))
                .thenAnswer(invocation -> Files.newInputStream(bundledLang()));
        MigrationRunner runner = new MigrationRunner(plugin);

        // Act
        MigrationReport report = runner.run(MigrationPlan.yaml("Contracts lang zh_CN", "lang/zh_CN.yml")
                .versionKey("lang-version")
                .targetVersion(3)
                .addStep(new LangV2ToV3Step(plugin)));

        // Assert
        YamlConfiguration lang = YamlConfiguration.loadConfiguration(langFile.toFile());
        assertTrue(report.migrated());
        assertEquals(3, lang.getInt("lang-version"));
        // The operator's own wording survives; only its colour syntax is modernised.
        assertEquals("<#69DB7C>我改过的领取按钮", lang.getString("ui.batch-accept"));
        assertEquals("<green>公开中", lang.getString("status.open"));
        // Placeholders that v2 already used must not be escaped into literal text.
        assertEquals("<#F4D03F><title> <#FFFFFF>×<total>", lang.getString("ui.batch-card-title"));
        // messages.* was already MiniMessage in v2 and must be left exactly as-is.
        assertEquals("<prefix><#69DB7C>配置已重新加载。", lang.getString("messages.reloaded"));
        // Keys that only exist in the new bundle are filled in.
        assertNotNull(lang.getString("ui.hall-title-open"));
        assertNotNull(lang.getString("ui.err-insufficient-funds"));
        assertNotNull(lang.getString("objectives.kill_entity"));
    }

    @Test
    void langVersionThreeAddsSaleKeysWithoutOverwritingOperators() throws Exception {
        Path langFile = tempDir.resolve("lang").resolve("zh_CN.yml");
        Files.createDirectories(langFile.getParent());
        Files.writeString(langFile, """
                lang-version: 3
                ui:
                  wizard-type-title: "我自定义的类型标题"
                """);
        ContractPlugin plugin = mockPlugin();
        when(plugin.getResource("lang/zh_CN.yml"))
                .thenAnswer(invocation -> Files.newInputStream(bundledLang()));
        MigrationRunner runner = new MigrationRunner(plugin);

        MigrationReport report = runner.run(MigrationPlan.yaml("Contracts lang zh_CN", "lang/zh_CN.yml")
                .versionKey("lang-version")
                .targetVersion(4)
                .addStep(new LangV3ToV4Step(plugin)));

        YamlConfiguration lang = YamlConfiguration.loadConfiguration(langFile.toFile());
        assertTrue(report.migrated());
        assertEquals(4, lang.getInt("lang-version"));
        assertEquals("我自定义的类型标题", lang.getString("ui.wizard-type-title"));
        assertNotNull(lang.getString("ui.wizard-type-sale"));
        assertNotNull(lang.getString("ui.err-sale-hand-changed"));
        assertNotNull(lang.getString("messages.help"));
    }

    @Test
    void langVersionFourAddsAllianceLabelsInBothLocalesAndIsIdempotent() throws Exception {
        for (String locale : java.util.List.of("zh_CN", "en_US")) {
            Path langFile = tempDir.resolve("lang").resolve(locale + ".yml");
            Files.createDirectories(langFile.getParent());
            Files.writeString(langFile, """
                    lang-version: 4
                    status:
                      pending_accept: "custom pending label"
                    ui:
                      progress-pending-accept: "custom progress"
                    """);
            ContractPlugin plugin = mockPlugin();
            when(plugin.getResource("lang/" + locale + ".yml")).thenAnswer(invocation ->
                Files.newInputStream(Path.of("src/main/resources/lang/" + locale + ".yml")));
            MigrationRunner runner = new MigrationRunner(plugin);
            MigrationPlan plan = MigrationPlan.yaml("Contract lang " + locale, "lang/" + locale + ".yml")
                .versionKey("lang-version").targetVersion(5).addStep(new LangV4ToV5Step(plugin));
            assertTrue(runner.run(plan).migrated());
            assertTrue(runner.run(plan).skipped());
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(langFile.toFile());
            assertEquals(5, lang.getInt("lang-version"));
            assertEquals("custom pending label", lang.getString("status.pending_accept"));
            assertEquals("custom progress", lang.getString("ui.progress-pending-accept"));
            assertNotNull(lang.getString("status.pending_accept_multi"));
            assertNotNull(lang.getString("ui.progress-pending-accept-multi"));
        }
    }

    @Test
    void langVersionFiveAddsFundingKeysWithoutReplacingOperatorWording() throws Exception {
        for (String locale : java.util.List.of("zh_CN", "en_US")) {
            Path langFile = tempDir.resolve("lang").resolve(locale + ".yml");
            Files.createDirectories(langFile.getParent());
            Files.writeString(langFile, "lang-version: 5\nui:\n  err-alliance-invalid: Custom text\n");
            ContractPlugin plugin = mockPlugin();
            when(plugin.getResource("lang/" + locale + ".yml")).thenAnswer(i ->
                Files.newInputStream(Path.of("src/main/resources/lang/" + locale + ".yml")));
            MigrationRunner runner = new MigrationRunner(plugin);
            MigrationPlan plan = MigrationPlan.yaml("Funding lang", "lang/" + locale + ".yml")
                .versionKey("lang-version").targetVersion(6).addStep(new LangV5ToV6Step(plugin));
            assertTrue(runner.run(plan).migrated());
            assertTrue(runner.run(plan).skipped());
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(langFile.toFile());
            assertEquals(6, lang.getInt("lang-version"));
            assertEquals("Custom text", lang.getString("ui.err-alliance-invalid"));
            assertNotNull(lang.getString("ui.err-alliance-funding-review"));
            assertNotNull(lang.getString("ui.err-alliance-already-signed"));
        }
    }

    private Path bundledLang() {
        return Path.of("src", "main", "resources", "lang", "zh_CN.yml");
    }

    private ContractPlugin mockPlugin() {
        ContractPlugin plugin = mock(ContractPlugin.class);
        // Mockito's inline mock maker intercepts final methods too, so log() would otherwise
        // return null for anything that logs through the framework logger.
        when(plugin.log()).thenReturn(new CubexLogger(Logger.getLogger("ContractsMigrationTest")));
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn((Logger.getLogger("ContractsMigrationTest")));
        return plugin;
    }
}
