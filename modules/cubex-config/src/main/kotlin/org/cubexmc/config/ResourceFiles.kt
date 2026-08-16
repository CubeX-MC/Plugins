package org.cubexmc.config

import org.cubexmc.core.CubexPlugin
import java.io.File

class ResourceFiles(private val plugin: CubexPlugin) {
    fun saveIfMissing(resourcePath: String?): Boolean {
        if (resourcePath.isNullOrBlank()) return false
        if (dataFile(resourcePath).exists()) return false
        if (resourcePath == "config.yml") plugin.saveDefaultConfig() else plugin.saveResource(resourcePath, false)
        return true
    }

    fun saveIfMissing(resourcePaths: Collection<String>?): List<String> =
        resourcePaths?.filter { saveIfMissing(it) } ?: emptyList()

    fun dataFile(resourcePath: String): File = File(plugin.dataFolder, resourcePath)

    fun exists(resourcePath: String): Boolean = dataFile(resourcePath).exists()
}
