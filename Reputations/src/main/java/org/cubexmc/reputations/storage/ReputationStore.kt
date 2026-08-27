package org.cubexmc.reputations.storage

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.reputations.ReputationsPlugin
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.logging.Logger

/**
 * Persists per-player reputation values (keyed by `namespace:id`) plus a cached name for lookups
 * and the viewer. Stored in `reputation-data.yml`; field keys contain `:` but never `.`, so they are
 * safe single YAML keys under each player's `values` section.
 */
class ReputationStore {
    private val file: File
    private val logger: Logger
    private val values: MutableMap<UUID, MutableMap<String, Double>> = HashMap()
    private val names: MutableMap<UUID, String> = HashMap()
    private var dirty = false
    private var revision = 0L

    constructor(plugin: ReputationsPlugin) : this(File(plugin.dataFolder, "reputation-data.yml"), plugin.logger)

    constructor(file: File, logger: Logger) {
        this.file = file
        this.logger = logger
    }

    @Synchronized
    fun isDirty(): Boolean = dirty

    @Synchronized
    fun get(playerId: UUID, key: String, fallback: Double): Double = values[playerId]?.get(key) ?: fallback

    @Synchronized
    fun set(playerId: UUID, key: String, value: Double) {
        values.getOrPut(playerId) { HashMap() }[key] = value
        dirty = true
        revision++
    }

    @Synchronized
    fun add(playerId: UUID, key: String, delta: Double, fallback: Double): Double {
        val next = get(playerId, key, fallback) + delta
        set(playerId, key, next)
        return next
    }

    @Synchronized
    fun reset(playerId: UUID, key: String) {
        val map = values[playerId] ?: return
        if (map.remove(key) != null) {
            dirty = true
            revision++
        }
    }

    @Synchronized
    fun valuesOf(playerId: UUID): Map<String, Double> = values[playerId]?.toMap() ?: emptyMap()

    @Synchronized
    fun cacheName(playerId: UUID, name: String?) {
        if (!name.isNullOrBlank() && names[playerId] != name) {
            names[playerId] = name
            dirty = true
            revision++
        }
    }

    @Synchronized
    fun nameOf(playerId: UUID): String? = names[playerId]

    @Synchronized
    fun findByName(name: String): UUID? =
        names.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }?.key

    @Synchronized
    fun entriesFor(fieldKey: String): List<StoredReputationEntry> =
        values.mapNotNull { (playerId, playerValues) ->
            playerValues[fieldKey]?.let { value ->
                StoredReputationEntry(playerId, names[playerId] ?: playerId.toString(), value)
            }
        }

    @Synchronized
    fun revision(): Long = revision

    @Synchronized
    fun load() {
        values.clear()
        names.clear()
        if (!file.exists()) {
            dirty = false
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        for (key in yaml.getKeys(false)) {
            val uuid = try {
                UUID.fromString(key)
            } catch (ex: IllegalArgumentException) {
                continue
            }
            val section = yaml.getConfigurationSection(key) ?: continue
            section.getString("name")?.let { names[uuid] = it }
            val valuesSection = section.getConfigurationSection("values") ?: continue
            val playerValues = HashMap<String, Double>()
            for (fieldKey in valuesSection.getKeys(false)) {
                playerValues[fieldKey] = valuesSection.getDouble(fieldKey)
            }
            if (playerValues.isNotEmpty()) {
                values[uuid] = playerValues
            }
        }
        dirty = false
        revision++
    }

    @Synchronized
    fun flushIfDirty() {
        if (dirty) {
            save()
        }
    }

    @Synchronized
    fun save() {
        val yaml = YamlConfiguration()
        val ids = HashSet<UUID>()
        ids.addAll(values.keys)
        ids.addAll(names.keys)
        for (uuid in ids) {
            val path = uuid.toString()
            names[uuid]?.let { yaml.set("$path.name", it) }
            for ((fieldKey, value) in values[uuid].orEmpty()) {
                yaml.set("$path.values.$fieldKey", value)
            }
        }
        try {
            file.parentFile?.mkdirs()
            yaml.save(file)
            dirty = false
        } catch (ex: IOException) {
            logger.warning("Failed to save reputation data: ${ex.message}")
        }
    }
}

data class StoredReputationEntry(val playerId: UUID, val playerName: String, val value: Double)
