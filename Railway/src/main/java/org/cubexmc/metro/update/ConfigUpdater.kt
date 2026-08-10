package org.cubexmc.metro.update

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import org.cubexmc.config.MigrationException
import org.cubexmc.metro.Metro
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * 配置文件更新工具类
 * 用于在插件升级后自动合并新的配置项到现有配置文件中
 */
object ConfigUpdater {

    /**
     * 将默认配置值合并到现有配置中
     * 只添加缺失的键，不覆盖用户已有的设置
     *
     * @param plugin 插件实例
     * @param resourcePath 资源文件路径（如 "config.yml"）
     */
    @JvmStatic
    fun applyDefaults(plugin: JavaPlugin, resourcePath: String) {
        if (plugin is Metro && "config.yml" == resourcePath) {
            try {
                MetroMigrations.migrateConfig(plugin)
            } catch (ex: MigrationException) {
                throw IllegalStateException("Failed to migrate Railway config", ex)
            }
            return
        }
        plugin.logger.warning("Unsupported config migration resource: $resourcePath")
    }

    /**
     * Merge default keys into a secondary YAML file in the plugin data folder.
     */
    @JvmStatic
    fun applyDefaultsToFile(plugin: JavaPlugin, resourcePath: String) {
        val targetFile = File(plugin.dataFolder, resourcePath)
        if (!targetFile.exists()) {
            plugin.saveResource(resourcePath, false)
            return
        }
        try {
            plugin.getResource(resourcePath).use { input ->
                if (input == null) {
                    plugin.logger.warning("Default resource not found: $resourcePath")
                    return
                }
                val target = YamlConfiguration.loadConfiguration(targetFile)
                val defaults = YamlConfiguration.loadConfiguration(
                    InputStreamReader(input, StandardCharsets.UTF_8),
                )
                target.addDefaults(defaults)
                target.options().copyDefaults(true)
                target.save(targetFile)
            }
        } catch (ex: Exception) {
            plugin.logger.warning(
                "Failed to apply default config values from " + resourcePath + ": " + ex.message,
            )
        }
    }

    @JvmStatic
    fun migrateLegacyEnterStop(config: FileConfiguration): Boolean {
        if (!config.isConfigurationSection("titles.enter_stop") || config.contains("titles.stop_continuous")) {
            return false
        }

        val legacySection = config.getConfigurationSection("titles.enter_stop") ?: return false
        val targetSection = config.createSection("titles.stop_continuous")
        copySection(legacySection, targetSection)
        return true
    }

    private fun copySection(source: ConfigurationSection, target: ConfigurationSection) {
        for (key in source.getKeys(false)) {
            val value = source.get(key)
            if (value is ConfigurationSection) {
                val childTarget = target.createSection(key)
                copySection(value, childTarget)
            } else {
                target.set(key, value)
            }
        }
    }
}
