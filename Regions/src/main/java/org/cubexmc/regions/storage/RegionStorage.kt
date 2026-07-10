package org.cubexmc.regions.storage

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.ActionBlockConfig
import org.cubexmc.regions.model.ActionConfig
import org.cubexmc.regions.model.ConditionConfig
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.EffectScope
import org.cubexmc.regions.model.FlagConfig
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.OwnerPolicy
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.model.RegionTrigger
import java.io.File
import java.io.IOException
import java.util.Locale

class RegionStorage(private val plugin: RegionsPlugin) {
    private val file = File(plugin.dataFolder, "regions.yml")
    private val regions: MutableMap<String, RegionDefinition> = LinkedHashMap()
    private var dirty = false

    fun load() {
        regions.clear()
        if (!file.exists()) {
            dirty = false
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        val root = yaml.getConfigurationSection("regions")
        if (root != null) {
            for (id in root.getKeys(false)) {
                val section = root.getConfigurationSection(id) ?: continue
                try {
                    val region = parseRegion(id, section)
                    regions[region.id] = region
                } catch (ex: RuntimeException) {
                    plugin.logger.warning("Failed to load region $id: ${ex.message}")
                }
            }
        }
        dirty = false
    }

    fun all(): List<RegionDefinition> = regions.values.toList()

    fun find(id: String): RegionDefinition? = regions[id]

    fun put(region: RegionDefinition) {
        regions[region.id] = region
        dirty = true
    }

    fun remove(id: String): Boolean {
        val removed = regions.remove(id) != null
        if (removed) {
            dirty = true
        }
        return removed
    }

    fun markDirty() {
        dirty = true
    }

    fun flushIfDirty() {
        if (dirty) {
            save()
        }
    }

    fun save() {
        val yaml = YamlConfiguration()
        yaml.set("regions-version", 1)
        for (region in regions.values) {
            val path = "regions.${region.id}"
            yaml.set("$path.name", region.name)
            yaml.set("$path.enabled", region.enabled)
            yaml.set("$path.priority", region.priority)
            yaml.set("$path.owner-policy", region.ownerPolicy.name.lowercase(Locale.ROOT))
            yaml.set("$path.source.type", region.source.type)
            for ((key, value) in region.source.values) {
                yaml.set("$path.source.$key", value)
            }
            val mode = region.mode
            if (mode != null) {
                yaml.set("$path.mode.type", mode.type)
                for ((key, value) in mode.values) {
                    yaml.set("$path.mode.$key", value)
                }
            }
            for ((key, flag) in region.flags) {
                yaml.set("$path.flags.$key.value", flag.value)
                for ((valueKey, value) in flag.values) {
                    yaml.set("$path.flags.$key.$valueKey", value)
                }
            }
            if (region.effects.isNotEmpty()) {
                yaml.set("$path.effects", region.effects.map { effect ->
                    val values = LinkedHashMap<String, Any>()
                    values["type"] = effect.type
                    values["scope"] = effect.scope.name.lowercase(Locale.ROOT)
                    values.putAll(effect.values)
                    values
                })
            }
            if (region.metadata.isNotEmpty()) {
                for ((key, value) in region.metadata) {
                    yaml.set("$path.metadata.$key", value)
                }
            }
            if (region.triggers.isNotEmpty()) {
                for ((trigger, blocks) in region.triggers) {
                    yaml.set("$path.triggers.${trigger.key}", blocks.map { block ->
                        val values = LinkedHashMap<String, Any>()
                        block.name?.let { values["name"] = it }
                        if (block.conditions.isNotEmpty()) {
                            values["if"] = block.conditions.map { condition ->
                                val conditionValues = LinkedHashMap<String, Any>()
                                conditionValues["type"] = condition.type
                                conditionValues.putAll(condition.values)
                                if (condition.negated) {
                                    conditionValues["negated"] = true
                                }
                                conditionValues
                            }
                        }
                        if (block.thenActions.isNotEmpty()) {
                            values["then"] = block.thenActions.map { action ->
                                val actionValues = LinkedHashMap<String, Any>()
                                actionValues["type"] = action.type
                                actionValues.putAll(action.values)
                                actionValues
                            }
                        }
                        if (block.elseActions.isNotEmpty()) {
                            values["else"] = block.elseActions.map { action ->
                                val actionValues = LinkedHashMap<String, Any>()
                                actionValues["type"] = action.type
                                actionValues.putAll(action.values)
                                actionValues
                            }
                        }
                        values
                    })
                }
            }
        }
        try {
            file.parentFile?.mkdirs()
            yaml.save(file)
            dirty = false
        } catch (ex: IOException) {
            plugin.logger.warning("Failed to save regions.yml: ${ex.message}")
        }
    }

    private fun parseRegion(id: String, section: ConfigurationSection): RegionDefinition {
        val sourceSection = section.getConfigurationSection("source")
        val sourceType = sourceSection?.getString("type") ?: "lands"
        val sourceValues = if (sourceSection == null) emptyMap() else valuesOf(sourceSection, setOf("type"))
        val mode = parseMode(section.get("mode"), section.getConfigurationSection("mode"))
        val flags = parseFlags(section.getConfigurationSection("flags"))
        val effects = parseEffects(section.getMapList("effects"))
        val triggers = parseTriggers(section.getConfigurationSection("triggers"))
        val metadata = section.getConfigurationSection("metadata")?.let { valuesOf(it) } ?: emptyMap()
        return RegionDefinition(
            id = id,
            name = section.getString("name", id) ?: id,
            source = RegionSourceRef(sourceType, sourceValues),
            ownerPolicy = parseOwnerPolicy(section.getString("owner-policy")),
            enabled = section.getBoolean("enabled", true),
            priority = section.getInt("priority", 0),
            mode = mode,
            flags = flags,
            effects = effects,
            triggers = triggers,
            metadata = metadata,
        )
    }

    private fun parseMode(raw: Any?, section: ConfigurationSection?): ModeConfig? {
        if (raw is String) {
            return ModeConfig(raw)
        }
        if (section == null) {
            return ModeConfig("free_event")
        }
        val type = section.getString("type", "free_event") ?: "free_event"
        return ModeConfig(type, valuesOf(section, setOf("type")))
    }

    private fun parseFlags(section: ConfigurationSection?): Map<String, FlagConfig> {
        if (section == null) {
            return emptyMap()
        }
        val result = LinkedHashMap<String, FlagConfig>()
        for (key in section.getKeys(false)) {
            val child = section.getConfigurationSection(key)
            if (child == null) {
                result[key] = FlagConfig(key, section.getString(key, "pass") ?: "pass")
            } else {
                val values = valuesOf(child, setOf("value"))
                result[key] = FlagConfig(key, child.getString("value", "pass") ?: "pass", values)
            }
        }
        return result
    }

    private fun parseEffects(list: List<Map<*, *>>): List<EffectConfig> {
        val result = ArrayList<EffectConfig>()
        for (entry in list) {
            val values = stringify(entry)
            val type = values["type"] ?: continue
            val scope = parseScope(values["scope"])
            val arguments = LinkedHashMap(values)
            arguments.remove("type")
            arguments.remove("scope")
            result.add(EffectConfig(type, scope, arguments))
        }
        return result
    }

    private fun parseTriggers(section: ConfigurationSection?): Map<RegionTrigger, List<ActionBlockConfig>> {
        if (section == null) {
            return emptyMap()
        }
        val result = LinkedHashMap<RegionTrigger, List<ActionBlockConfig>>()
        for (triggerKey in section.getKeys(false)) {
            val trigger = RegionTrigger.fromKey(triggerKey) ?: continue
            val blocks = ArrayList<ActionBlockConfig>()
            for (entry in section.getMapList(triggerKey)) {
                blocks.add(parseActionBlock(stringifyNested(entry)))
            }
            result[trigger] = blocks
        }
        return result
    }

    private fun parseActionBlock(values: Map<String, Any?>): ActionBlockConfig {
        val name = values["name"]?.toString()
        val conditions = parseConditions(values["if"])
        val thenActions = parseActions(values["then"])
        val elseActions = parseActions(values["else"])
        return ActionBlockConfig(name, conditions, thenActions, elseActions)
    }

    private fun parseConditions(raw: Any?): List<ConditionConfig> {
        val list = raw as? List<*> ?: return emptyList()
        val result = ArrayList<ConditionConfig>()
        for (entry in list) {
            val map = (entry as? Map<*, *>) ?: continue
            val values = stringify(map)
            if (values.size == 1) {
                val first = values.entries.first()
                result.add(ConditionConfig(first.key, mapOf("value" to first.value)))
            } else {
                val type = values["type"] ?: continue
                val args = LinkedHashMap(values)
                args.remove("type")
                result.add(ConditionConfig(type, args))
            }
        }
        return result
    }

    private fun parseActions(raw: Any?): List<ActionConfig> {
        val list = raw as? List<*> ?: return emptyList()
        val result = ArrayList<ActionConfig>()
        for (entry in list) {
            val map = (entry as? Map<*, *>) ?: continue
            val values = stringify(map)
            val type = values["type"] ?: continue
            val args = LinkedHashMap(values)
            args.remove("type")
            result.add(ActionConfig(type, args))
        }
        return result
    }

    private fun parseOwnerPolicy(value: String?): OwnerPolicy =
        when (value?.lowercase(Locale.ROOT)) {
            "lands_owner", "lands-owner" -> OwnerPolicy.LANDS_OWNER
            "source_owner", "source-owner" -> OwnerPolicy.SOURCE_OWNER
            else -> OwnerPolicy.ADMIN
        }

    private fun parseScope(value: String?): EffectScope =
        when (value?.lowercase(Locale.ROOT)) {
            "until_mode_end", "until-mode-end" -> EffectScope.UNTIL_MODE_END
            "timed" -> EffectScope.TIMED
            else -> EffectScope.WHILE_INSIDE
        }

    private fun valuesOf(section: ConfigurationSection, excluded: Set<String> = emptySet()): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        for (key in section.getKeys(false)) {
            if (excluded.contains(key)) {
                continue
            }
            if (section.isConfigurationSection(key)) {
                continue
            }
            if (section.isList(key)) {
                values[key] = section.getStringList(key).joinToString(",")
                continue
            }
            val value = section.get(key) ?: continue
            values[key] = value.toString()
        }
        return values
    }

    private fun stringify(map: Map<*, *>): MutableMap<String, String> {
        val values = LinkedHashMap<String, String>()
        for ((key, value) in map) {
            if (key == null || value == null || value is Map<*, *> || value is List<*>) {
                continue
            }
            values[key.toString()] = value.toString()
        }
        return values
    }

    private fun stringifyNested(map: Map<*, *>): Map<String, Any?> {
        val values = LinkedHashMap<String, Any?>()
        for ((key, value) in map) {
            if (key != null) {
                values[key.toString()] = value
            }
        }
        return values
    }
}
