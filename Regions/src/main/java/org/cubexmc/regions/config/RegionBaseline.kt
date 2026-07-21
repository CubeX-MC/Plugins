package org.cubexmc.regions.config

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

data class BaselineFile(
    val path: String,
    val versionKey: String,
    val version: Int,
)

object RegionBaseline {
    val files: List<BaselineFile> = listOf(
        BaselineFile("config.yml", "config-version", 4),
        BaselineFile("regions.yml", "regions-version", 4),
        BaselineFile("templates.yml", "templates-version", 1),
        BaselineFile("lang/zh_CN.yml", "lang-version", 4),
        BaselineFile("lang/en_US.yml", "lang-version", 4),
    )

    fun validate(dataFolder: File): List<String> =
        files.mapNotNull { baseline ->
            val file = File(dataFolder, baseline.path)
            val yaml = YamlConfiguration.loadConfiguration(file)
            val actual = if (yaml.contains(baseline.versionKey)) yaml.getInt(baseline.versionKey) else null
            if (actual == baseline.version) {
                null
            } else {
                "${baseline.path} uses ${baseline.versionKey}=${actual ?: "missing"}, " +
                    "but this first public release requires ${baseline.version}"
            }
        }
}
