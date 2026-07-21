package org.cubexmc.regions.service

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.regions.model.ActionBlockConfig
import org.cubexmc.regions.model.ActionConfig
import org.cubexmc.regions.model.ConditionConfig
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.EffectCombination
import org.cubexmc.regions.model.EffectScope
import org.cubexmc.regions.model.FlagConfig
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionTrigger
import org.cubexmc.regions.model.TriggerExecution
import java.io.File
import java.util.Locale

enum class TemplateParameterType { STRING, INTEGER, DOUBLE, BOOLEAN }

data class TemplateParameter(
    val id: String,
    val type: TemplateParameterType = TemplateParameterType.STRING,
    val required: Boolean = false,
    val defaultValue: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val options: Set<String> = emptySet(),
) {
    fun validate(value: String?): String? {
        if (value.isNullOrBlank()) return if (required && defaultValue == null) "$id is required" else null
        val numeric = when (type) {
            TemplateParameterType.INTEGER -> value.toIntOrNull()?.toDouble()
                ?: return "$id must be an integer"
            TemplateParameterType.DOUBLE -> value.toDoubleOrNull()
                ?: return "$id must be a number"
            TemplateParameterType.BOOLEAN -> if (value.toBooleanStrictOrNull() == null) return "$id must be true or false" else null
            TemplateParameterType.STRING -> null
        }
        if (numeric != null && min != null && numeric < min) return "$id must be at least $min"
        if (numeric != null && max != null && numeric > max) return "$id must be at most $max"
        if (options.isNotEmpty() && options.none { it.equals(value, ignoreCase = true) }) {
            return "$id must be one of: ${options.joinToString(", ")}"
        }
        return null
    }
}

data class RegionTemplate(
    val id: String,
    val name: String,
    val description: String,
    val parameters: Map<String, TemplateParameter> = emptyMap(),
    val mode: ModeConfig? = null,
    val flags: Map<String, FlagConfig> = emptyMap(),
    val effects: List<EffectConfig> = emptyList(),
    val triggers: Map<RegionTrigger, List<ActionBlockConfig>> = emptyMap(),
)

data class TemplateApplyResult(
    val region: RegionDefinition? = null,
    val errors: List<String> = emptyList(),
) {
    val success: Boolean get() = region != null && errors.isEmpty()
}

class RegionTemplateService(private val file: File) {
    private val templates = LinkedHashMap<String, RegionTemplate>()

    fun load() {
        templates.clear()
        val yaml = YamlConfiguration.loadConfiguration(file)
        val root = yaml.getConfigurationSection("templates") ?: return
        for (id in root.getKeys(false)) {
            val section = root.getConfigurationSection(id) ?: continue
            parseTemplate(id.lowercase(Locale.ROOT), section)?.let { templates[it.id] = it }
        }
    }

    fun all(): List<RegionTemplate> = templates.values.toList()

    fun find(id: String): RegionTemplate? = templates[id.lowercase(Locale.ROOT)]

    fun apply(templateId: String, base: RegionDefinition, supplied: Map<String, String> = emptyMap()): TemplateApplyResult {
        val template = find(templateId) ?: return TemplateApplyResult(errors = listOf("Unknown template: $templateId"))
        val unknown = supplied.keys.filterNot { template.parameters.containsKey(it) }
        val errors = unknown.map { "Unknown template parameter: $it" }.toMutableList()
        val resolved = LinkedHashMap<String, String>()
        for ((id, definition) in template.parameters) {
            val value = supplied[id] ?: definition.defaultValue
            definition.validate(value)?.let { errors.add(it) }
            if (value != null) resolved[id] = value
        }
        if (errors.isNotEmpty()) return TemplateApplyResult(errors = errors)
        fun value(raw: String): String = PARAMETER_PATTERN.replace(raw) { match ->
            resolved[match.groupValues[1]] ?: match.value
        }
        fun values(raw: Map<String, String>): Map<String, String> = raw.mapValues { value(it.value) }
        fun action(raw: ActionConfig): ActionConfig = raw.copy(values = values(raw.values))
        fun condition(raw: ConditionConfig): ConditionConfig = raw.copy(values = values(raw.values))
        fun block(raw: ActionBlockConfig): ActionBlockConfig = raw.copy(
            conditions = raw.conditions.map { condition(it) },
            thenActions = raw.thenActions.map { action(it) },
            elseActions = raw.elseActions.map { action(it) },
        )
        val metadata = LinkedHashMap(base.metadata)
        metadata["template-id"] = template.id
        return TemplateApplyResult(region = base.copy(
            mode = template.mode?.let { it.copy(values = values(it.values)) },
            flags = template.flags.mapValues { (_, flag) -> flag.copy(values = values(flag.values)) },
            effects = template.effects.map { it.copy(values = values(it.values)) },
            triggers = template.triggers.mapValues { (_, blocks) -> blocks.map { block(it) } },
            metadata = metadata,
        ))
    }

    private fun parseTemplate(id: String, section: ConfigurationSection): RegionTemplate? {
        val name = section.getString("name") ?: return null
        val modeSection = section.getConfigurationSection("mode")
        val mode = modeSection?.getString("type")?.let { type ->
            ModeConfig(type, sectionValues(modeSection, setOf("type")))
        }
        val flags = LinkedHashMap<String, FlagConfig>()
        section.getConfigurationSection("flags")?.let { root ->
            for (key in root.getKeys(false)) {
                val flag = root.getConfigurationSection(key) ?: continue
                flags[key] = FlagConfig(key, flag.getString("value", "pass") ?: "pass", sectionValues(flag, setOf("value")))
            }
        }
        val effects = section.getMapList("effects").mapNotNull { raw ->
            val type = raw["type"]?.toString() ?: return@mapNotNull null
            EffectConfig(
                type,
                parseScope(raw["scope"]?.toString()),
                rawValues(raw, setOf("type", "scope", "combination")),
                parseCombination(raw["combination"]?.toString()),
            )
        }
        val triggers = LinkedHashMap<RegionTrigger, List<ActionBlockConfig>>()
        section.getConfigurationSection("triggers")?.let { root ->
            for (key in root.getKeys(false)) {
                val trigger = RegionTrigger.fromKey(key) ?: continue
                triggers[trigger] = root.getMapList(key).map { parseBlock(it) }
            }
        }
        return RegionTemplate(
            id = id,
            name = name,
            description = section.getString("description", "") ?: "",
            parameters = parseParameters(section.getConfigurationSection("parameters")),
            mode = mode,
            flags = flags,
            effects = effects,
            triggers = triggers,
        )
    }

    private fun parseParameters(root: ConfigurationSection?): Map<String, TemplateParameter> {
        if (root == null) return emptyMap()
        val result = LinkedHashMap<String, TemplateParameter>()
        for (id in root.getKeys(false)) {
            val section = root.getConfigurationSection(id) ?: continue
            val type = runCatching {
                TemplateParameterType.valueOf((section.getString("type", "string") ?: "string").uppercase(Locale.ROOT))
            }.getOrDefault(TemplateParameterType.STRING)
            result[id] = TemplateParameter(
                id = id,
                type = type,
                required = section.getBoolean("required", false),
                defaultValue = section.getString("default"),
                min = section.getString("min")?.toDoubleOrNull(),
                max = section.getString("max")?.toDoubleOrNull(),
                options = section.getStringList("options").toSet(),
            )
        }
        return result
    }

    private fun parseBlock(raw: Map<*, *>): ActionBlockConfig = ActionBlockConfig(
        name = raw["name"]?.toString(),
        conditions = listMaps(raw["if"] ?: raw["conditions"]).map { condition ->
            ConditionConfig(
                type = condition["type"]?.toString() ?: "unknown",
                values = rawValues(condition, setOf("type", "not", "negated")),
                negated = condition["not"]?.toString()?.toBooleanStrictOrNull()
                    ?: condition["negated"]?.toString()?.toBooleanStrictOrNull()
                    ?: false,
            )
        },
        thenActions = listMaps(raw["then"]).map { parseAction(it) },
        elseActions = listMaps(raw["else"]).map { parseAction(it) },
        execution = parseTriggerExecution(raw["execution"]?.toString()),
    )

    private fun parseAction(raw: Map<*, *>): ActionConfig =
        ActionConfig(raw["type"]?.toString() ?: "unknown", rawValues(raw, setOf("type")))

    private fun listMaps(value: Any?): List<Map<*, *>> =
        (value as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()

    private fun sectionValues(section: ConfigurationSection, excluded: Set<String>): Map<String, String> =
        section.getKeys(false).filterNot { excluded.contains(it) }.associateWith { section.get(it)?.toString().orEmpty() }

    private fun rawValues(raw: Map<*, *>, excluded: Set<String>): Map<String, String> =
        raw.entries.filterNot { excluded.contains(it.key.toString()) }
            .associate { it.key.toString() to it.value.toString() }

    private fun parseScope(raw: String?): EffectScope = when (raw?.lowercase(Locale.ROOT)) {
        "timed" -> EffectScope.TIMED
        "until_mode_end", "until-mode-end" -> EffectScope.UNTIL_MODE_END
        else -> EffectScope.WHILE_INSIDE
    }

    private fun parseCombination(raw: String?): EffectCombination = when (raw?.lowercase(Locale.ROOT)) {
        "exclusive" -> EffectCombination.EXCLUSIVE
        "stack" -> EffectCombination.STACK
        "merge_by_type", "merge-by-type" -> EffectCombination.MERGE_BY_TYPE
        else -> EffectCombination.HIGHEST_PRIORITY
    }

    private fun parseTriggerExecution(raw: String?): TriggerExecution = when (raw?.lowercase(Locale.ROOT)) {
        "primary", "primary_region", "primary-region" -> TriggerExecution.PRIMARY_REGION
        else -> TriggerExecution.ALL_ACTIVE
    }

    companion object {
        private val PARAMETER_PATTERN = Regex("\\$\\{([a-zA-Z0-9_-]+)}")
    }
}
