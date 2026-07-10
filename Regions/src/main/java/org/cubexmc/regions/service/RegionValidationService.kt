package org.cubexmc.regions.service

import org.cubexmc.regions.effect.ScopedEffectService
import org.cubexmc.regions.flag.RegionFlagRegistry
import org.cubexmc.regions.integration.RegionSourceRegistry
import org.cubexmc.regions.mode.RegionModeRegistry
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.ValidationIssue
import org.cubexmc.regions.model.ValidationSeverity

class RegionValidationService(
    private val sources: RegionSourceRegistry,
    private val modes: RegionModeRegistry,
    private val flags: RegionFlagRegistry,
    private val effects: ScopedEffectService,
    private val actions: RegionActionRegistry,
) {
    fun validateAll(regions: Collection<RegionDefinition>): List<ValidationIssue> =
        regions.flatMap { validate(it) }

    fun validate(region: RegionDefinition): List<ValidationIssue> {
        val issues = ArrayList<ValidationIssue>()
        if (region.id.isBlank()) {
            issues.add(error(region.id, "Region id cannot be blank."))
        }
        if (region.name.isBlank()) {
            issues.add(error(region.id, "Region name cannot be blank."))
        }

        val source = sources.find(region.source.type)
        if (source == null) {
            issues.add(error(region.id, "Unknown region source '${region.source.type}'."))
        } else if (!source.isAvailable()) {
            issues.add(warning(region.id, "Region source '${region.source.type}' is not currently available."))
        } else if (source.resolve(region.source) == null) {
            issues.add(warning(region.id, "Region source '${region.source.describe()}' could not be resolved."))
        }

        val mode = region.mode
        if (mode != null && !modes.isRegistered(mode.type)) {
            issues.add(error(region.id, "Unknown mode '${mode.type}'."))
        }

        for (flag in region.flags.values) {
            if (!flags.isRegistered(flag.key)) {
                issues.add(warning(region.id, "Unknown flag '${flag.key}'."))
            }
        }

        for (effect in region.effects) {
            if (!effects.isRegistered(effect.type)) {
                issues.add(error(region.id, "Unknown effect '${effect.type}'."))
            }
        }

        for ((trigger, blocks) in region.triggers) {
            for (block in blocks) {
                for (action in block.thenActions + block.elseActions) {
                    if (!actions.isRegistered(action.type)) {
                        issues.add(error(region.id, "Unknown action '${action.type}' in trigger '${trigger.key}'."))
                    }
                }
            }
        }
        return issues
    }

    private fun error(regionId: String, message: String): ValidationIssue =
        ValidationIssue(regionId.ifBlank { "<unknown>" }, ValidationSeverity.ERROR, message)

    private fun warning(regionId: String, message: String): ValidationIssue =
        ValidationIssue(regionId.ifBlank { "<unknown>" }, ValidationSeverity.WARNING, message)
}
