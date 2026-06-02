package org.cubexmc.config;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.core.CubexPlugin;

/**
 * Merges missing default YAML keys with Bukkit's YamlConfiguration.
 * Saving with this API rewrites YAML and does not preserve comments or formatting.
 */
public final class YamlDefaults {

    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final CubexPlugin plugin;

    public YamlDefaults(CubexPlugin plugin) {
        this.plugin = plugin;
    }

    public DefaultMergeResult mergeResourceIntoDataFile(String resourcePath, DefaultMergeOptions options) {
        return mergeResourceIntoDataFile(resourcePath, new File(plugin.getDataFolder(), resourcePath), options);
    }

    public DefaultMergeResult mergeResourceIntoDataFile(
            String resourcePath,
            File targetFile,
            DefaultMergeOptions options) {
        DefaultMergeOptions effectiveOptions = options == null ? DefaultMergeOptions.copyMissingKeys() : options;
        if (!targetFile.exists()) {
            plugin.saveResource(resourcePath, false);
            return new DefaultMergeResult(false, List.of(), null);
        }

        try (InputStream inputStream = plugin.getResource(resourcePath)) {
            if (inputStream == null) {
                plugin.getLogger().warning("Default resource missing from jar: " + resourcePath);
                return new DefaultMergeResult(false, List.of(), null);
            }

            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            YamlConfiguration existing = YamlConfiguration.loadConfiguration(targetFile);
            List<String> addedKeys = new ArrayList<>();

            for (String key : defaults.getKeys(true)) {
                if (!effectiveOptions.isIncludeSections() && defaults.isConfigurationSection(key)) {
                    continue;
                }
                if (!existing.contains(key)) {
                    existing.set(key, defaults.get(key));
                    addedKeys.add(key);
                }
            }

            File backupFile = null;
            if (!addedKeys.isEmpty() && effectiveOptions.isSaveWhenChanged()) {
                if (effectiveOptions.isWarnAboutCommentLoss()) {
                    plugin.getLogger().warning("Merging YAML defaults rewrites " + targetFile.getName()
                            + " and may drop comments/formatting.");
                }
                if (effectiveOptions.isBackupBeforeSave()) {
                    backupFile = backup(targetFile);
                }
                existing.save(targetFile);
            }

            return new DefaultMergeResult(!addedKeys.isEmpty(), addedKeys, backupFile);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to merge defaults into " + resourcePath + ": " + ex.getMessage());
            return new DefaultMergeResult(false, List.of(), null);
        }
    }

    private File backup(File targetFile) throws Exception {
        File backupFile = new File(targetFile.getParentFile(),
                targetFile.getName() + ".bak-" + LocalDateTime.now().format(BACKUP_TIMESTAMP));
        Files.copy(targetFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return backupFile;
    }
}
