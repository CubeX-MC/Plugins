package org.cubexmc.contract.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.contract.ContractPlugin;
import org.cubexmc.contract.model.ContractStatus;
import org.cubexmc.contract.util.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LanguageManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void messageUsesMessagesKeyPrefixAndMiniMessagePlaceholders() throws Exception {
        // Arrange
        writeV2Lang("""
                lang-version: 2
                prefix: "<#F4D03F>[Contract]<#F1F5F9> "
                status:
                  open: "公开中"
                messages:
                  create-success: "<prefix><#69DB7C>合同已发布: <#FFE066>#<id> <#F1F5F9>已托管 <#69DB7C><amount><#F1F5F9>。"
                  invalid-usage: "<prefix><#FFE066>用法: <usage>"
                  list-footer: "<#CFD8DC>使用 <#FFE066>/contract info \\\\<id> <#CFD8DC>查看详情。"
                """);
        LanguageManager manager = new LanguageManager(mockPlugin());

        // Act
        manager.load();
        String created = manager.message("create-success", Map.of("id", "abc123", "amount", "$50"));
        String usage = manager.message("invalid-usage", Map.of("usage", "/contract info <id>"));
        String footer = manager.message("list-footer");

        // Assert
        assertEquals(Text.color("&#F4D03F[Contract]&#F1F5F9 &#69DB7C合同已发布: &#FFE066#abc123 &#F1F5F9已托管 &#69DB7C$50&#F1F5F9。")
                        .toLowerCase(Locale.ROOT),
                created.toLowerCase(Locale.ROOT));
        assertTrue(usage.contains("/contract info <id>"));
        assertTrue(footer.contains("/contract info <id>"));
    }

    @Test
    void enumLabelsStayOnContractsSideAdapter() throws Exception {
        // Arrange
        writeV2Lang("""
                lang-version: 2
                prefix: "<#F4D03F>[Contract]<#F1F5F9> "
                status:
                  open: "公开中"
                messages:
                  reloaded: "<prefix><#69DB7C>配置已重新加载。"
                """);
        LanguageManager manager = new LanguageManager(mockPlugin());

        // Act
        manager.load();

        // Assert
        assertEquals("公开中", manager.status(ContractStatus.OPEN));
        assertEquals("missing-key", manager.message("missing-key"));
    }

    private void writeV2Lang(String content) throws Exception {
        Path lang = tempDir.resolve("lang").resolve("zh_CN.yml");
        Files.createDirectories(lang.getParent());
        Files.writeString(lang, content);
    }

    private ContractPlugin mockPlugin() {
        ContractPlugin plugin = mock(ContractPlugin.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("language", "zh_CN");
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LanguageManagerTest"));
        return plugin;
    }
}
