package org.cubexmc.contract.storage

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.model.BatchRepeatPolicy
import org.cubexmc.contract.model.ContractSpec
import org.cubexmc.contract.model.ContractTemplate
import org.cubexmc.contract.model.ContractType
import org.cubexmc.contract.model.ObjectiveType
import org.cubexmc.contract.model.TemplateVisibility
import org.cubexmc.core.Reloadable
import org.cubexmc.core.Terminable
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import java.util.UUID
import org.cubexmc.core.CubexLogger

/**
 * Crash-safe YAML store for reusable terms. Templates never contain escrow or live contract state.
 *
 * Implements [Reloadable] so it can be a named stage in the plugin's reload chain, and [Terminable]
 * so `bind(this)` flushes it on disable without a hand-written lambda.
 */
class ContractTemplateStore(
    private val file: File,
    private val logger: CubexLogger,
) : Reloadable, Terminable {
    private val templates: MutableMap<String, ContractTemplate> = LinkedHashMap()
    @Volatile private var dirty = false

    constructor(plugin: ContractPlugin) : this(File(plugin.dataFolder, "templates.yml"), plugin.log())

    @Synchronized
    fun load() {
        val loaded = LinkedHashMap<String, ContractTemplate>()
        if (file.exists()) {
            val yaml = YamlConfiguration.loadConfiguration(file)
            val root = yaml.getConfigurationSection("templates")
            if (root != null) {
                for (id in root.getKeys(false)) {
                    try {
                        val section = root.getConfigurationSection(id) ?: continue
                        loaded[id] = read(id, section)
                    } catch (ex: RuntimeException) {
                        logger.warn("Skipping malformed contract template $id: ${ex.message}")
                    }
                }
            }
        }
        templates.clear()
        templates.putAll(loaded)
        dirty = false
    }

    @Synchronized fun all(): List<ContractTemplate> = templates.values.sortedByDescending { it.updatedAt }
    @Synchronized fun find(id: String): ContractTemplate? = templates[id]
    @Synchronized fun put(template: ContractTemplate) { templates[template.id] = template; dirty = true }
    @Synchronized fun remove(id: String): ContractTemplate? = templates.remove(id).also { if (it != null) dirty = true }
    @Synchronized fun isDirty(): Boolean = dirty

    /** Reload stage: re-read the backing file. */
    override fun reload() {
        load()
    }

    /** Terminable: flush pending writes when the plugin shuts down. */
    override fun close() {
        flushIfDirty()
    }

    @Synchronized
    @Throws(IOException::class)
    fun flushIfDirty() {
        if (!dirty) return
        val yaml = YamlConfiguration()
        val root = yaml.createSection("templates")
        for (template in templates.values) write(root.createSection(template.id), template)
        val target = file.toPath()
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "templates", ".tmp")
        try {
            yaml.save(temp.toFile())
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            dirty = false
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun read(id: String, section: ConfigurationSection): ContractTemplate {
        val spec = section.getConfigurationSection("spec") ?: error("missing spec")
        return ContractTemplate(
            id,
            UUID.fromString(section.getString("owner-uuid") ?: error("missing owner UUID")),
            section.getString("owner-name", "Unknown") ?: "Unknown",
            section.getString("name") ?: error("missing name"),
            enumValue(section.getString("visibility"), TemplateVisibility.PRIVATE),
            ContractSpec(
                enumValue(spec.getString("type"), ContractType.SERVICE),
                nullableString(spec, "title"),
                nullableString(spec, "description"),
                nullableInt(spec, "days"),
                nullableDouble(spec, "amount"),
                spec.getBoolean("item-reward", false),
                nullableDouble(spec, "partner-stake"),
                nullableString(spec, "counterparty"),
                nullableString(spec, "mediator"),
                nullableEnum<ObjectiveType>(spec.getString("objective-type")),
                nullableString(spec, "objective-target"),
                nullableInt(spec, "objective-required"),
                spec.getInt("contract-count", 1),
                enumValue(spec.getString("repeat-policy"), BatchRepeatPolicy.ONCE),
                spec.getInt("repeat-cooldown-hours", 24),
            ),
            section.getLong("created-at"),
            section.getLong("updated-at"),
        )
    }

    private fun write(section: ConfigurationSection, template: ContractTemplate) {
        section["owner-uuid"] = template.ownerUuid.toString()
        section["owner-name"] = template.ownerName
        section["name"] = template.name
        section["visibility"] = template.visibility.name
        section["created-at"] = template.createdAt
        section["updated-at"] = template.updatedAt
        val spec = section.createSection("spec")
        with(template.spec) {
            spec["type"] = type.name
            spec["title"] = title
            spec["description"] = description
            spec["days"] = days
            spec["amount"] = amount
            spec["item-reward"] = itemReward
            spec["partner-stake"] = partnerStake
            spec["counterparty"] = counterparty
            spec["mediator"] = mediator
            spec["objective-type"] = objectiveType?.name
            spec["objective-target"] = objectiveTarget
            spec["objective-required"] = objectiveRequired
            spec["contract-count"] = contractCount
            spec["repeat-policy"] = repeatPolicy.name
            spec["repeat-cooldown-hours"] = repeatCooldownHours
        }
    }

    private fun nullableString(section: ConfigurationSection, key: String): String? =
        if (section.isSet(key)) section.getString(key) else null
    private fun nullableInt(section: ConfigurationSection, key: String): Int? =
        if (section.isSet(key)) section.getInt(key) else null
    private fun nullableDouble(section: ConfigurationSection, key: String): Double? =
        if (section.isSet(key)) section.getDouble(key) else null
    private inline fun <reified T : Enum<T>> nullableEnum(value: String?): T? = value?.let { enumValueOf<T>(it) }
    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
}
