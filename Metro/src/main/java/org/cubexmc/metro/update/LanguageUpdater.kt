package org.cubexmc.metro.update

import java.io.File
import org.bukkit.plugin.java.JavaPlugin
import org.cubexmc.config.MigrationException
import org.cubexmc.metro.Metro

/**
 * 语言文件更新工具类
 * 用于在插件升级后自动合并新的语言键到现有语言文件中
 */
object LanguageUpdater {
    /**
     * 将默认语言文件中的新键合并到用户的语言文件中
     * 只添加缺失的键，不覆盖用户已有的翻译
     *
     * @param plugin 插件实例
     * @param targetFile 目标语言文件（用户的文件）
     * @param resourcePath 资源文件路径（如 "lang/zh_CN.yml"）
     */
    @JvmStatic
    fun merge(plugin: JavaPlugin, targetFile: File, resourcePath: String) {
        if (plugin !is Metro) {
            plugin.logger.warning("Unsupported Metro language migration request: $resourcePath")
            return
        }
        val name = targetFile.name
        val language = if (name.endsWith(".yml")) name.substring(0, name.length - 4) else name
        try {
            MetroMigrations.migrateLanguage(plugin, language)
        } catch (ex: MigrationException) {
            throw IllegalStateException("Failed to migrate Metro language file: $resourcePath", ex)
        }
    }
}
