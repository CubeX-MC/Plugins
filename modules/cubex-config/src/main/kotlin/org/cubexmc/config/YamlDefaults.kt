package org.cubexmc.config

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.core.CubexPlugin
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Merges missing default YAML keys. Saving rewrites YAML and may drop comments. */
class YamlDefaults(private val plugin: CubexPlugin) {
    fun mergeResourceIntoDataFile(resourcePath: String, options: DefaultMergeOptions?): DefaultMergeResult =
        mergeResourceIntoDataFile(resourcePath, File(plugin.dataFolder, resourcePath), options)

    fun mergeResourceIntoDataFile(
        resourcePath: String,
        targetFile: File,
        options: DefaultMergeOptions?,
    ): DefaultMergeResult {
        val effectiveOptions = options ?: DefaultMergeOptions.copyMissingKeys()
        if (!targetFile.exists()) {
            plugin.saveResource(resourcePath, false)
            return DefaultMergeResult(false, emptyList(), null)
        }

        return try {
            val inputStream = plugin.getResource(resourcePath)
            if (inputStream == null) {
                plugin.logger.warning("Default resource missing from jar: $resourcePath")
                return DefaultMergeResult(false, emptyList(), null)
            }
            inputStream.use { stream ->
                val defaults = YamlConfiguration.loadConfiguration(InputStreamReader(stream, StandardCharsets.UTF_8))
                val existing = YamlConfiguration.loadConfiguration(targetFile)
                val addedKeys = ArrayList<String>()
                for (key in defaults.getKeys(true)) {
                    if (!effectiveOptions.isIncludeSections() && defaults.isConfigurationSection(key)) continue
                    if (!existing.contains(key)) {
                        existing[key] = defaults[key]
                        addedKeys.add(key)
                    }
                }

                var backupFile: File? = null
                if (addedKeys.isNotEmpty() && effectiveOptions.isSaveWhenChanged()) {
                    if (effectiveOptions.isWarnAboutCommentLoss()) {
                        plugin.logger.warning(
                            "Merging YAML defaults rewrites ${targetFile.name} and may drop comments/formatting.",
                        )
                    }
                    if (effectiveOptions.isBackupBeforeSave()) backupFile = backup(targetFile)
                    existing.save(targetFile)
                }
                DefaultMergeResult(addedKeys.isNotEmpty(), addedKeys, backupFile)
            }
        } catch (exception: Exception) {
            plugin.logger.warning("Failed to merge defaults into $resourcePath: ${exception.message}")
            DefaultMergeResult(false, emptyList(), null)
        }
    }

    private fun backup(targetFile: File): File {
        val backupFile = File(
            targetFile.parentFile,
            targetFile.name + ".bak-" + LocalDateTime.now().format(BACKUP_TIMESTAMP),
        )
        Files.copy(targetFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return backupFile
    }

    private companion object {
        val BACKUP_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
