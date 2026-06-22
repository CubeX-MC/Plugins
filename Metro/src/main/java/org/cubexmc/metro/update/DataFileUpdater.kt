package org.cubexmc.metro.update

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import java.util.regex.Pattern
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin

/**
 * Migrates persistent Metro data files across schema changes.
 */
object DataFileUpdater {
    const val SCHEMA_VERSION_KEY: String = "schema_version"
    const val CURRENT_SCHEMA_VERSION: Int = 1

    private val ID_PATTERN: Pattern = Pattern.compile("[A-Za-z0-9_-]+")

    @JvmStatic
    fun migrateAll(plugin: JavaPlugin) {
        val stopIdMappings = migrateStops(plugin)
        migrateLines(plugin, stopIdMappings)
        migratePortals(plugin)
    }

    @JvmStatic
    fun migrateLines(plugin: JavaPlugin, stopIdMappings: Map<String, String>) {
        val file = File(plugin.dataFolder, "lines.yml")
        if (!file.exists()) {
            return
        }

        val config = YamlConfiguration.loadConfiguration(file)
        var changed = false
        var hasDataSections = false

        for (lineId in config.getKeys(false)) {
            if (SCHEMA_VERSION_KEY == lineId) {
                continue
            }
            val section = config.getConfigurationSection(lineId) ?: continue
            hasDataSections = true

            if (!section.contains("ordered_stop_ids") && section.contains("ordered_platform_ids")) {
                section.set("ordered_stop_ids", section.getStringList("ordered_platform_ids"))
                section.set("ordered_platform_ids", null)
                changed = true
            }
            if (!section.contains("route_points")) {
                section.set("route_points", Collections.emptyList<Any>())
                changed = true
            }
            if (!section.contains("rail_protected")) {
                section.set("rail_protected", false)
                changed = true
            }

            // Update stop ID references if any were migrated
            if (stopIdMappings.isNotEmpty() && section.contains("ordered_stop_ids")) {
                val stopIds = section.getStringList("ordered_stop_ids")
                val updated = ArrayList<String>()
                var stopIdsChanged = false
                for (stopId in stopIds) {
                    val mapped = stopIdMappings[stopId]
                    if (mapped != null) {
                        updated.add(mapped)
                        stopIdsChanged = true
                    } else {
                        updated.add(stopId)
                    }
                }
                if (stopIdsChanged) {
                    section.set("ordered_stop_ids", updated)
                    changed = true
                }
            }
        }

        if (hasDataSections && config.getInt(SCHEMA_VERSION_KEY, 0) < CURRENT_SCHEMA_VERSION) {
            config.set(SCHEMA_VERSION_KEY, CURRENT_SCHEMA_VERSION)
            changed = true
        }
        saveIfChanged(plugin, file, config, changed, "lines.yml")
    }

    @JvmStatic
    fun migrateStops(plugin: JavaPlugin): Map<String, String> {
        val file = File(plugin.dataFolder, "stops.yml")
        if (!file.exists()) {
            return Collections.emptyMap()
        }

        val config = YamlConfiguration.loadConfiguration(file)
        var changed = false
        var hasDataSections = false
        val idMappings: MutableMap<String, String> = HashMap()

        // Phase 1: field migrations
        for (stopId in config.getKeys(false)) {
            if (SCHEMA_VERSION_KEY == stopId) {
                continue
            }
            val section = config.getConfigurationSection(stopId) ?: continue
            hasDataSections = true

            changed = renameIfMissing(section, "name", "display_name") or changed
            changed = renameIfMissing(section, "corner1", "corner1_location") or changed
            changed = renameIfMissing(section, "corner2", "corner2_location") or changed
            changed = renameIfMissing(section, "stopPoint", "stoppoint_location") or changed
            changed = renameIfMissing(section, "stop_point", "stoppoint_location") or changed
            changed = renameIfMissing(section, "launchYaw", "launch_yaw") or changed
            if (!section.contains("transferable_lines")) {
                section.set("transferable_lines", Collections.emptyList<Any>())
                changed = true
            }
        }

        // Phase 2: sanitize illegal stop IDs
        val existingIds: MutableSet<String> = HashSet()
        for (stopId in config.getKeys(false)) {
            if (SCHEMA_VERSION_KEY != stopId && config.isConfigurationSection(stopId)) {
                existingIds.add(stopId)
            }
        }
        for (stopId in HashSet(existingIds)) {
            if (ID_PATTERN.matcher(stopId).matches()) {
                continue
            }
            val sanitized = stopId.replace("[^A-Za-z0-9_-]".toRegex(), "-")
            var newId = sanitized
            var suffix = 2
            while (existingIds.contains(newId)) {
                newId = "$sanitized-$suffix"
                suffix++
            }

            // Move section data to new key
            val oldSection = config.getConfigurationSection(stopId)
            val newSection = config.createSection(newId)
            if (oldSection != null) {
                for ((key, value) in oldSection.getValues(false)) {
                    newSection.set(key, value)
                }
            }
            config.set(stopId, null)
            existingIds.remove(stopId)
            existingIds.add(newId)
            idMappings[stopId] = newId
            changed = true
            plugin.logger.warning("Stop ID '$stopId' contains illegal characters, migrated to '$newId'")
        }

        if (hasDataSections && config.getInt(SCHEMA_VERSION_KEY, 0) < CURRENT_SCHEMA_VERSION) {
            config.set(SCHEMA_VERSION_KEY, CURRENT_SCHEMA_VERSION)
            changed = true
        }
        saveIfChanged(plugin, file, config, changed, "stops.yml")
        return idMappings
    }

    @JvmStatic
    fun migratePortals(plugin: JavaPlugin) {
        val file = File(plugin.dataFolder, "portals.yml")
        if (!file.exists()) {
            return
        }

        val config = YamlConfiguration.loadConfiguration(file)
        var changed = false

        if (!config.isConfigurationSection("portals")) {
            changed = moveRootPortalSections(config)
        }

        val portalsSection = config.getConfigurationSection("portals")
        if (portalsSection != null) {
            for (portalId in portalsSection.getKeys(false)) {
                val section = portalsSection.getConfigurationSection(portalId) ?: continue
                changed = migratePortalLocation(section, "entrance", false) or changed
                changed = migratePortalLocation(section, "destination", true) or changed
                changed = renameIfMissing(section, "destWorld", "dest_world") or changed
                changed = renameIfMissing(section, "destX", "dest_x") or changed
                changed = renameIfMissing(section, "destY", "dest_y") or changed
                changed = renameIfMissing(section, "destZ", "dest_z") or changed
                changed = renameIfMissing(section, "destYaw", "dest_yaw") or changed
            }
        }

        if (portalsSection != null && config.getInt(SCHEMA_VERSION_KEY, 0) < CURRENT_SCHEMA_VERSION) {
            config.set(SCHEMA_VERSION_KEY, CURRENT_SCHEMA_VERSION)
            changed = true
        }
        saveIfChanged(plugin, file, config, changed, "portals.yml")
    }

    private fun moveRootPortalSections(config: YamlConfiguration): Boolean {
        val rootKeys: Set<String> = HashSet(config.getKeys(false))
        var changed = false
        for (portalId in rootKeys) {
            if (SCHEMA_VERSION_KEY == portalId) {
                continue
            }
            val section = config.getConfigurationSection(portalId)
            if (section == null || !looksLikePortal(section)) {
                continue
            }
            val target = config.createSection("portals.$portalId")
            for ((key, value) in section.getValues(false)) {
                target.set(key, value)
            }
            config.set(portalId, null)
            changed = true
        }
        return changed
    }

    private fun looksLikePortal(section: ConfigurationSection): Boolean =
        section.contains("world") || section.contains("entrance") || section.contains("dest_world") ||
            section.contains("destination") || section.contains("destX")

    private fun migratePortalLocation(section: ConfigurationSection, key: String, destination: Boolean): Boolean {
        val value = section.getString(key)
        if (value == null || value.isBlank()) {
            return false
        }
        val parts = value.split(",")
        if ((!destination && parts.size != 4) || (destination && parts.size < 4)) {
            return false
        }

        if (destination) {
            if (!section.contains("dest_world")) section.set("dest_world", parts[0])
            if (!section.contains("dest_x")) section.set("dest_x", parseDouble(parts[1], 0.0))
            if (!section.contains("dest_y")) section.set("dest_y", parseDouble(parts[2], 0.0))
            if (!section.contains("dest_z")) section.set("dest_z", parseDouble(parts[3], 0.0))
            if (parts.size >= 5 && !section.contains("dest_yaw")) {
                section.set("dest_yaw", parseDouble(parts[4], 0.0))
            }
        } else {
            if (!section.contains("world")) section.set("world", parts[0])
            if (!section.contains("x")) section.set("x", parseInt(parts[1], 0))
            if (!section.contains("y")) section.set("y", parseInt(parts[2], 0))
            if (!section.contains("z")) section.set("z", parseInt(parts[3], 0))
        }
        section.set(key, null)
        return true
    }

    private fun renameIfMissing(section: ConfigurationSection, oldKey: String, newKey: String): Boolean {
        if (!section.contains(oldKey) || section.contains(newKey)) {
            return false
        }
        section.set(newKey, section.get(oldKey))
        section.set(oldKey, null)
        return true
    }

    private fun parseInt(value: String, fallback: Int): Int =
        try {
            value.trim().toInt()
        } catch (ignored: NumberFormatException) {
            fallback
        }

    private fun parseDouble(value: String, fallback: Double): Double =
        try {
            value.trim().toDouble()
        } catch (ignored: NumberFormatException) {
            fallback
        }

    private fun saveIfChanged(
        plugin: JavaPlugin,
        file: File,
        config: YamlConfiguration,
        changed: Boolean,
        fileName: String,
    ) {
        if (!changed) {
            return
        }
        try {
            val backupFile = backupFile(file)
            Files.copy(file.toPath(), backupFile.toPath())
            config.save(file)
            plugin.logger.info("Migrated Metro data file: $fileName (backup: ${backupFile.name})")
        } catch (ex: IOException) {
            plugin.logger.warning("Failed to migrate $fileName: ${ex.message}")
        }
    }

    private fun backupFile(file: File): File {
        var backup = File(file.parentFile, file.name + ".bak-" + CURRENT_SCHEMA_VERSION)
        var suffix = 2
        while (backup.exists()) {
            backup = File(file.parentFile, file.name + ".bak-" + CURRENT_SCHEMA_VERSION + "." + suffix)
            suffix++
        }
        return backup
    }
}
