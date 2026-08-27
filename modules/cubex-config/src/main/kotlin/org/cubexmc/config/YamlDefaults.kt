package org.cubexmc.config

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.core.CubexPlugin
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Merges missing default YAML keys. Saving rewrites YAML and may drop comments. */
class YamlDefaults(private val plugin: JavaPlugin) {
    constructor(plugin: CubexPlugin) : this(plugin as JavaPlugin)
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
                check(!effectiveOptions.failOnError()) { "Default resource missing from jar: $resourcePath" }
                return DefaultMergeResult(false, emptyList(), null)
            }
            inputStream.use { stream ->
                val defaults = YamlConfiguration.loadConfiguration(InputStreamReader(stream, StandardCharsets.UTF_8))
                val existing = YamlConfiguration().apply { load(targetFile) }
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
                    if (effectiveOptions.isBackupBeforeSave()) {
                        val customBackup = effectiveOptions.backupFunction()
                        backupFile = if (customBackup == null) backup(targetFile) else customBackup.apply(targetFile)
                        check(backupFile != null) { "Backup failed for ${targetFile.name}; refusing to overwrite it." }
                    }
                    existing.save(targetFile)
                }
                DefaultMergeResult(addedKeys.isNotEmpty(), addedKeys, backupFile)
            }
        } catch (exception: Exception) {
            plugin.logger.warning("Failed to merge defaults into $resourcePath: ${exception.message}")
            if (effectiveOptions.failOnError()) throw IllegalStateException("Cannot merge $resourcePath safely", exception)
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
