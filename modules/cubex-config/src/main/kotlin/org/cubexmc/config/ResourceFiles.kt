package org.cubexmc.config

import org.cubexmc.core.CubexPlugin
import java.io.File

public class ResourceFiles(private val plugin: CubexPlugin) {
    public fun saveIfMissing(resourcePath: String?): Boolean {
        if (resourcePath.isNullOrBlank()) return false
        if (dataFile(resourcePath).exists()) return false
        if (resourcePath == "config.yml") plugin.saveDefaultConfig() else plugin.saveResource(resourcePath, false)
        return true
    }

    public fun saveIfMissing(resourcePaths: Collection<String>?): List<String> =
        resourcePaths?.filter { saveIfMissing(it) } ?: emptyList()

    public fun dataFile(resourcePath: String): File = File(plugin.dataFolder, resourcePath)

    public fun exists(resourcePath: String): Boolean = dataFile(resourcePath).exists()
}
