package org.cubexmc.regions.service

import org.bukkit.Material
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.cubexmc.regions.capability.CapabilityCatalog
import org.cubexmc.regions.capability.CapabilityKind
import org.cubexmc.regions.effect.ScopedEffectService
import org.cubexmc.regions.flag.RegionFlagRegistry
import org.cubexmc.regions.integration.RegionSourceRegistry
import org.cubexmc.regions.mode.RegionModeRegistry
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.ValidationIssue
import org.cubexmc.regions.model.ValidationSeverity
import java.util.Locale

class RegionValidationService(
    private val sources: RegionSourceRegistry,
    private val modes: RegionModeRegistry,
    private val flags: RegionFlagRegistry,
    private val effects: ScopedEffectService,
    private val actions: RegionActionRegistry,
    private val conditions: RegionConditionRegistry,
    private val capabilities: CapabilityCatalog,
    private val overlaps: RegionOverlapResolver = RegionOverlapResolver(),
) {
    fun validateAll(regions: Collection<RegionDefinition>): List<ValidationIssue> {
        val definitions = regions.toList()
        val issues = definitions.flatMapTo(ArrayList()) { validate(it) }
        for (candidate in definitions) {
            issues.addAll(overlaps.validateCandidate(candidate, definitions) { region ->
                sources.find(region.source.type)?.geometry(region.source)
            })
        }
        return issues.distinctBy { Triple(it.regionId, it.severity, it.message) }
    }

    fun validate(region: RegionDefinition): List<ValidationIssue> {
        val issues = ArrayList<ValidationIssue>()
        if (region.id.isBlank()) {
            issues.add(error(region.id, "Region id cannot be blank."))
        } else if (!REGION_ID.matches(region.id)) {
            issues.add(error(
                region.id,
                "Region id must contain 2-48 lowercase letters, numbers, underscores, or hyphens.",
            ))
        }
        if (region.name.isBlank()) {
            issues.add(error(region.id, "Region name cannot be blank."))
        }

        val source = sources.find(region.source.type)
        if (source == null) {
            issues.add(error(region.id, "Unknown region source '${region.source.type}'."))
        } else if (!source.isAvailable()) {
            issues.add(error(region.id, "Region source '${region.source.type}' is not currently available. Install or enable its required integration."))
        } else if (source.resolve(region.source) == null) {
            issues.add(error(region.id, "Region source '${region.source.describe()}' could not be resolved. Rebind it to an existing owned area."))
        }
        addCapabilityIssues(issues, region.id, CapabilityKind.SOURCE, region.source.type, region.source.values)

        val mode = region.mode
        if (mode != null && !modes.isRegistered(mode.type)) {
            issues.add(error(region.id, "Unknown mode '${mode.type}'."))
        }
        if (mode != null) {
            addCapabilityIssues(issues, region.id, CapabilityKind.MODE, mode.type, mode.values)
            addModeRuleIssues(issues, region)
        }

        for (flag in region.flags.values) {
            if (!flags.isRegistered(flag.key)) {
                issues.add(error(region.id, "Unknown or unavailable flag '${flag.key}'."))
            }
            addCapabilityIssues(
                issues,
                region.id,
                CapabilityKind.FLAG,
                flag.key,
                linkedMapOf("value" to flag.value).apply { putAll(flag.values) },
            )
        }

        for (effect in region.effects) {
            if (!effects.isRegistered(effect.type)) {
                issues.add(error(region.id, "Unknown effect '${effect.type}'."))
            }
            addCapabilityIssues(issues, region.id, CapabilityKind.EFFECT, effect.type, effect.values)
            validateEffectRuntime(issues, region.id, effect.type, effect.values)
        }

        for ((trigger, blocks) in region.triggers) {
            for (block in blocks) {
                for (condition in block.conditions) {
                    if (!conditions.isRegistered(condition.type)) {
                        issues.add(error(region.id, "Unknown condition '${condition.type}' in trigger '${trigger.key}'."))
                    }
                    addCapabilityIssues(issues, region.id, CapabilityKind.CONDITION, condition.type, condition.values, trigger.key)
                }
                for (action in block.thenActions + block.elseActions) {
                    if (!actions.isRegistered(action.type)) {
                        issues.add(error(region.id, "Unknown action '${action.type}' in trigger '${trigger.key}'."))
                    }
                    addCapabilityIssues(issues, region.id, CapabilityKind.ACTION, action.type, action.values, trigger.key)
                    validateActionRuntime(issues, region.id, trigger.key, action.type, action.values)
                }
            }
        }
        return issues
    }

    private fun error(regionId: String, message: String): ValidationIssue =
        ValidationIssue(regionId.ifBlank { "<unknown>" }, ValidationSeverity.ERROR, message)

    private fun warning(regionId: String, message: String): ValidationIssue =
        ValidationIssue(regionId.ifBlank { "<unknown>" }, ValidationSeverity.WARNING, message)

    private fun addCapabilityIssues(
        issues: MutableList<ValidationIssue>,
        regionId: String,
        kind: CapabilityKind,
        id: String,
        values: Map<String, String>,
        context: String? = null,
    ) {
        for (issue in capabilities.validate(kind, id, values)) {
            val message = if (context == null) issue.message else "${issue.message} in trigger '$context'."
            issues.add(if (issue.error) error(regionId, message) else warning(regionId, message))
        }
    }

    private fun addModeRuleIssues(issues: MutableList<ValidationIssue>, region: RegionDefinition) {
        val mode = region.mode ?: return
        val values = mode.values
        if (!values["reward-source"].isNullOrBlank() || !values["reward-contract"].isNullOrBlank()) {
            issues.add(error(
                region.id,
                "Mode rewards are not available in the first release. Remove reward-source/reward-contract before publishing.",
            ))
        }
        val minPlayers = values["min-players"]?.toIntOrNull()
        val maxPlayers = values["max-players"]?.toIntOrNull()
        if (minPlayers != null && maxPlayers != null && maxPlayers > 0 && maxPlayers < minPlayers) {
            issues.add(error(region.id, "Mode max-players must be 0 (unlimited) or >= min-players."))
        }
        when (mode.type.lowercase(Locale.ROOT)) {
            "dual_pvp", "union_war" -> {
                if (minPlayers != null && minPlayers < 2) {
                    issues.add(error(region.id, "${mode.type} requires min-players >= 2."))
                }
                validateItemList(issues, region.id, "kit", values["kit"])
                validateItemList(issues, region.id, "armor", values["armor"])
                validateItemList(issues, region.id, "offhand", values["offhand"], maxEntries = 1)
                validateLocation(issues, region.id, "respawn", values["respawn"] ?: values["outside"], required = false)
                if (mode.type.equals("union_war", ignoreCase = true)) {
                    val minUnions = values["min-unions"]?.toIntOrNull()
                    if (minUnions != null && minPlayers != null && minUnions > minPlayers) {
                        issues.add(error(region.id, "union_war min-unions cannot exceed min-players."))
                    }
                }
            }
            "run_race", "boat_race", "horse_race" -> {
                val timeout = values["timeout-seconds"]
                    ?: values["max-duration-seconds"]
                    ?: values["duration-seconds"]
                if (timeout != null && (timeout.toLongOrNull() ?: 0L) <= 0L) {
                    issues.add(error(region.id, "Race timeout must be a positive number of seconds."))
                }
                if (values["require-start"]?.toBooleanStrictOrNull() != false) {
                    validateLocation(issues, region.id, "start", values["start"], required = true)
                } else {
                    validateLocation(issues, region.id, "start", values["start"], required = false)
                }
                validateLocation(issues, region.id, "finish", values["finish"], required = true)
                val checkpoints = values["checkpoints"]
                    ?.split(';')
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                checkpoints
                    ?.forEachIndexed { index, checkpoint ->
                        validateLocation(issues, region.id, "checkpoint ${index + 1}", checkpoint, required = true)
                    }
                val checkpointVehicles = values["checkpoint-vehicles"]
                    ?.split(';')
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                if (checkpointVehicles.isNotEmpty() && checkpointVehicles.size != checkpoints.size) {
                    issues.add(error(region.id, "checkpoint-vehicles must contain one entry for each checkpoint."))
                }
            }
            "hide_and_seek" -> {
                val hideSeconds = values["hide-seconds"]?.toLongOrNull()
                val roundSeconds = values["round-seconds"]?.toLongOrNull()
                if (hideSeconds != null && roundSeconds != null && roundSeconds > 0 && roundSeconds <= hideSeconds) {
                    issues.add(error(region.id, "hide_and_seek round-seconds must be greater than hide-seconds."))
                }
                val seekers = values["seekers"]?.toIntOrNull()
                if (seekers != null && minPlayers != null && seekers >= minPlayers) {
                    issues.add(error(region.id, "hide_and_seek seekers must be lower than min-players."))
                }
                validateItemList(issues, region.id, "seeker-kit", values["seeker-kit"])
                validateItemList(issues, region.id, "hider-kit", values["hider-kit"])
                validateLocation(
                    issues,
                    region.id,
                    "respawn",
                    values["respawn"] ?: values["outside"] ?: values["spectator"],
                    required = false,
                )
            }
        }
    }

    private fun validateActionRuntime(
        issues: MutableList<ValidationIssue>,
        regionId: String,
        trigger: String,
        type: String,
        values: Map<String, String>,
    ) {
        when (type.lowercase(Locale.ROOT)) {
            "effect_apply" -> {
                val effectType = values["effect"] ?: values["effect-type"] ?: return
                if (!effects.isRegistered(effectType)) {
                    issues.add(error(regionId, "Unknown effect '$effectType' used by effect_apply in trigger '$trigger'."))
                    return
                }
                val effectValues = LinkedHashMap(values).apply {
                    remove("effect")
                    remove("effect-type")
                    remove("scope")
                }
                addCapabilityIssues(
                    issues,
                    regionId,
                    CapabilityKind.EFFECT,
                    effectType,
                    effectValues,
                    "$trigger/effect_apply",
                )
                validateEffectRuntime(issues, regionId, effectType, effectValues)
            }
            "teleport" -> validateLocation(
                issues,
                regionId,
                "teleport action in trigger '$trigger'",
                values["location"] ?: values["value"] ?: values["to"],
                required = true,
                requireLoadedWorld = true,
            )
            "give_item", "take_item" -> validateItemList(
                issues,
                regionId,
                "$type action in trigger '$trigger'",
                values["item"] ?: values["value"],
                maxEntries = 1,
            )
            "sound" -> {
                val sound = values["sound"] ?: values["name"] ?: return
                val normalized = sound.lowercase(Locale.ROOT)
                val key = runCatching {
                    NamespacedKey.fromString(normalized)
                        ?: NamespacedKey.minecraft(normalized.replace('_', '.'))
                }.getOrNull()
                if (key == null) {
                    issues.add(error(regionId, "Invalid sound key '$sound' in trigger '$trigger'."))
                    return
                }
                val serverAvailable = runCatching { Bukkit.getServer() }.getOrNull() != null
                if (serverAvailable && Registry.SOUND_EVENT.get(key) == null) {
                    issues.add(error(regionId, "Unknown sound '$sound' in trigger '$trigger'."))
                }
            }
        }
    }

    private fun validateEffectRuntime(
        issues: MutableList<ValidationIssue>,
        regionId: String,
        type: String,
        values: Map<String, String>,
    ) {
        if (!type.equals("potion", ignoreCase = true)) return
        val raw = values["effect"] ?: values["name"] ?: return
        val normalized = raw.lowercase(Locale.ROOT)
        val key = runCatching {
            NamespacedKey.fromString(normalized) ?: NamespacedKey.minecraft(normalized)
        }.getOrNull()
        if (key == null) {
            issues.add(error(regionId, "Invalid potion effect key '$raw'."))
            return
        }
        val serverAvailable = runCatching { Bukkit.getServer() }.getOrNull() != null
        if (serverAvailable && Registry.MOB_EFFECT.get(key) == null) {
            issues.add(error(regionId, "Unknown potion effect '$raw'."))
        }
    }

    private fun validateItemList(
        issues: MutableList<ValidationIssue>,
        regionId: String,
        label: String,
        raw: String?,
        maxEntries: Int = Int.MAX_VALUE,
    ) {
        if (raw.isNullOrBlank()) return
        val entries = raw.split(',', ';').filter { it.isNotBlank() }
        if (entries.size > maxEntries) {
            issues.add(error(regionId, "Mode $label accepts at most $maxEntries item entry."))
        }
        for (entry in entries) {
            val parts = entry.trim().split(':')
            val material = parts.firstOrNull().orEmpty()
            val amount = parts.getOrNull(1)?.toIntOrNull() ?: 1
            if (
                !ITEM_ID.matches(material) ||
                Material.matchMaterial(material.uppercase(Locale.ROOT)) == null ||
                amount !in 1..64 ||
                parts.size > 2
            ) {
                issues.add(error(regionId, "Mode $label item '$entry' must use MATERIAL[:amount] with amount 1-64."))
            }
        }
    }

    private fun validateLocation(
        issues: MutableList<ValidationIssue>,
        regionId: String,
        label: String,
        raw: String?,
        required: Boolean,
        requireLoadedWorld: Boolean = false,
    ) {
        if (raw.isNullOrBlank()) {
            if (required) issues.add(error(regionId, "Mode requires a $label location."))
            return
        }
        val parts = raw.split(',')
        val valid = parts.size >= 4 &&
            parts[0].isNotBlank() &&
            parts[1].trim().toDoubleOrNull() != null &&
            parts[2].trim().toDoubleOrNull() != null &&
            parts[3].trim().toDoubleOrNull() != null
        if (!valid) {
            issues.add(error(regionId, "Mode $label must use world,x,y,z[,yaw,pitch]."))
        } else if (requireLoadedWorld) {
            val server = runCatching { Bukkit.getServer() }.getOrNull()
            if (server != null && server.getWorld(parts[0].trim()) == null) {
                issues.add(error(regionId, "Mode $label references unloaded world '${parts[0].trim()}'."))
            }
        }
    }

    private companion object {
        val REGION_ID = Regex("[a-z0-9_-]{2,48}")
        val ITEM_ID = Regex("[A-Za-z0-9_]+")
    }
}
