package org.cubexmc.config

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.core.CubexPlugin
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class YamlFiles(private val plugin: CubexPlugin) {
    fun loadDataFile(resourcePath: String): YamlConfiguration = loadDataFile(File(plugin.dataFolder, resourcePath))

    fun loadDataFile(file: File): YamlConfiguration = YamlConfiguration.loadConfiguration(file)

    fun loadResource(resourcePath: String, charset: Charset): YamlConfiguration {
        val inputStream = plugin.getResource(resourcePath) ?: return YamlConfiguration()
        return try {
            InputStreamReader(inputStream, charset).use(YamlConfiguration::loadConfiguration)
        } catch (exception: Exception) {
            plugin.logger.warning("Failed to load YAML resource $resourcePath: ${exception.message}")
            YamlConfiguration()
        }
    }

    fun loadResourceUtf8(resourcePath: String): YamlConfiguration = loadResource(resourcePath, StandardCharsets.UTF_8)
}
