package org.cubexmc.metro.update

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.java.JavaPlugin
import org.cubexmc.config.MigrationException
import org.cubexmc.metro.Metro

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
        if (plugin !is Metro || resourcePath != "config.yml") {
            plugin.logger.warning("Unsupported Metro config migration request: $resourcePath")
            return
        }
        try {
            MetroMigrations.migrateConfig(plugin)
        } catch (ex: MigrationException) {
            throw IllegalStateException("Failed to migrate Metro config.", ex)
        }
    }

    @JvmStatic
    fun migrateLegacyEnterStop(config: FileConfiguration): Boolean {
        if (!config.isConfigurationSection("titles.enter_stop") || config.contains("titles.stop_continuous")) {
            return false
        }

        val legacySection = config.getConfigurationSection("titles.enter_stop")
        val targetSection = config.createSection("titles.stop_continuous")
        copySection(legacySection, targetSection)
        return true
    }

    private fun copySection(source: ConfigurationSection?, target: ConfigurationSection) {
        if (source == null) {
            return
        }
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
