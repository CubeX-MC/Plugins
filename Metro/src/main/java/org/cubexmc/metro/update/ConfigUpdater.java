package org.cubexmc.metro.update;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.cubexmc.config.MigrationException;
import org.cubexmc.metro.Metro;

/**
 * 配置文件更新工具类
 * 用于在插件升级后自动合并新的配置项到现有配置文件中
 */
public final class ConfigUpdater {

    private ConfigUpdater() {
    }

    /**
     * 将默认配置值合并到现有配置中
     * 只添加缺失的键，不覆盖用户已有的设置
     *
     * @param plugin 插件实例
     * @param resourcePath 资源文件路径（如 "config.yml"）
     */
    public static void applyDefaults(JavaPlugin plugin, String resourcePath) {
        if (!(plugin instanceof Metro metro) || !"config.yml".equals(resourcePath)) {
            plugin.getLogger().warning("Unsupported Metro config migration request: " + resourcePath);
            return;
        }
        try {
            MetroMigrations.migrateConfig(metro);
        } catch (MigrationException ex) {
            throw new IllegalStateException("Failed to migrate Metro config.", ex);
        }
    }

    static boolean migrateLegacyEnterStop(FileConfiguration config) {
        if (!config.isConfigurationSection("titles.enter_stop") || config.contains("titles.stop_continuous")) {
            return false;
        }

        ConfigurationSection legacySection = config.getConfigurationSection("titles.enter_stop");
        ConfigurationSection targetSection = config.createSection("titles.stop_continuous");
        copySection(legacySection, targetSection);
        return true;
    }

    private static void copySection(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            Object value = source.get(key);
            if (value instanceof ConfigurationSection childSource) {
                ConfigurationSection childTarget = target.createSection(key);
                copySection(childSource, childTarget);
            } else {
                target.set(key, value);
            }
        }
    }
}
