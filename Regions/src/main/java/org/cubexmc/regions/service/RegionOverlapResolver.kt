package org.cubexmc.regions.service

import org.cubexmc.regions.model.FlagConfig
import org.cubexmc.regions.model.EffectCombination
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.EffectScope
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionGeometry
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.model.ValidationIssue
import org.cubexmc.regions.model.ValidationSeverity
import java.util.Locale

data class ResolvedFlag(
    val key: String,
    val config: FlagConfig,
    val sourceRegionId: String,
)

data class ResolvedEffect(
    val config: EffectConfig,
    val sourceRegionId: String,
    val sourcePriority: Int,
    val sourceIndex: Int,
) {
    val identity: String =
        "$sourceRegionId:$sourceIndex:${config.type}:${config.scope}:${config.combination}:${config.values.toSortedMap()}"
}

data class OverlapResolution(
    val orderedRegions: List<RegionDefinition>,
    val primaryModeRegion: RegionDefinition?,
    val suppressedModeRegions: List<RegionDefinition>,
    val primaryTriggerRegion: RegionDefinition?,
    val flags: Map<String, ResolvedFlag>,
    val effects: List<ResolvedEffect>,
)

class RegionOverlapResolver {
    fun resolve(regions: Collection<RegionDefinition>): OverlapResolution {
        val ordered = regions.sortedWith(REGION_ORDER)
        val stateful = ordered.filter { hasStatefulMode(it) }
        val resolvedFlags = LinkedHashMap<String, ResolvedFlag>()
        for (region in ordered) {
            for ((key, flag) in region.flags.toSortedMap()) {
                val normalized = key.lowercase(Locale.ROOT)
                if (resolvedFlags.containsKey(normalized) || flag.value.equals("pass", ignoreCase = true)) continue
                resolvedFlags[normalized] = ResolvedFlag(normalized, flag, region.id)
            }
        }
        return OverlapResolution(
            orderedRegions = ordered,
            primaryModeRegion = stateful.firstOrNull(),
            suppressedModeRegions = stateful.drop(1),
            primaryTriggerRegion = stateful.firstOrNull() ?: ordered.firstOrNull(),
            flags = resolvedFlags,
            effects = resolveEffects(ordered),
        )
    }

    fun validateCandidate(
        candidate: RegionDefinition,
        existing: Collection<RegionDefinition>,
        geometry: (RegionDefinition) -> RegionGeometry? = { null },
    ): List<ValidationIssue> {
        if (!candidate.enabled || candidate.lifecycle == RegionLifecycle.ARCHIVED) return emptyList()
        val definiteOverlaps = overlappingRegions(candidate, existing, geometry)
        if (definiteOverlaps.isEmpty()) return emptyList()
        val group = definiteOverlaps + candidate
        val resolution = resolve(group)
        val issues = ArrayList<ValidationIssue>()
        if (resolution.suppressedModeRegions.isNotEmpty()) {
            val modes = (listOfNotNull(resolution.primaryModeRegion) + resolution.suppressedModeRegions)
                .joinToString { "${it.id}:${it.mode?.type}" }
            issues.add(ValidationIssue(
                candidate.id,
                ValidationSeverity.ERROR,
                "Stateful modes overlap ($modes). Keep only one stateful mode in the overlapping area.",
            ))
        }
        val flagKeys = group.flatMap { it.flags.keys }.map { it.lowercase(Locale.ROOT) }.toSortedSet()
        for (key in flagKeys) {
            val values = group.mapNotNull { region ->
                region.flags.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
                    ?.takeUnless { it.value.equals("pass", ignoreCase = true) }
                    ?.let { region.id to it.value.lowercase(Locale.ROOT) }
            }
            if (values.map { it.second }.distinct().size > 1) {
                val winner = resolution.flags[key] ?: continue
                issues.add(ValidationIssue(
                    candidate.id,
                    ValidationSeverity.WARNING,
                    "Overlapping flag '$key' resolves to '${winner.config.value}' from '${winner.sourceRegionId}' by priority.",
                ))
            }
        }
        val effectGroups = group.associateWith { region ->
            region.effects.withIndex().groupBy { effectFamily(it.value) }
        }
        val families = effectGroups.values.flatMap { it.keys }.toSortedSet()
        for (family in families) {
            val entries = resolution.orderedRegions.flatMap { region ->
                effectGroups[region]?.get(family).orEmpty().map { region to it.value }
            }
            if (entries.size < 2) continue
            val strategies = entries.map { it.second.combination }.distinct()
            val winningStrategy = entries.first().second.combination
            if (strategies.size > 1) {
                issues.add(ValidationIssue(
                    candidate.id,
                    ValidationSeverity.WARNING,
                    "Overlapping effect '$family' uses different combination strategies; " +
                        "${winningStrategy.name.lowercase(Locale.ROOT)} from '${entries.first().first.id}' takes precedence.",
                ))
            }
            if (winningStrategy == EffectCombination.EXCLUSIVE) {
                issues.add(ValidationIssue(
                    candidate.id,
                    ValidationSeverity.ERROR,
                    "Effect '$family' is exclusive but is provided by overlapping regions: " +
                        entries.joinToString { it.first.id },
                ))
            }
        }
        return issues
    }

    fun overlappingRegions(
        candidate: RegionDefinition,
        existing: Collection<RegionDefinition>,
        geometry: (RegionDefinition) -> RegionGeometry? = { null },
    ): List<RegionDefinition> =
        existing.filter { other ->
            other.id != candidate.id &&
                other.enabled &&
                other.lifecycle == RegionLifecycle.PUBLISHED &&
                (
                    other.source == candidate.source ||
                        geometry(candidate)?.let { candidateGeometry ->
                            geometry(other)?.let { candidateGeometry.overlaps(it) }
                        } == true
                    )
        }

    private fun resolveEffects(ordered: List<RegionDefinition>): List<ResolvedEffect> {
        val indexed = ordered.flatMap { region ->
            effectiveRegionEffects(region).mapIndexed { index, effect ->
                ResolvedEffect(effect, region.id, region.priority, index)
            }
        }
        val result = ArrayList<ResolvedEffect>()
        for ((_, group) in indexed.groupBy { effectFamily(it.config) }) {
            val strategy = group.first().config.combination
            when (strategy) {
                EffectCombination.EXCLUSIVE,
                EffectCombination.HIGHEST_PRIORITY,
                -> result.add(group.first())
                EffectCombination.STACK -> result.addAll(group.asReversed())
                EffectCombination.MERGE_BY_TYPE -> {
                    val winners = LinkedHashMap<String, ResolvedEffect>()
                    for (entry in group) {
                        winners.putIfAbsent(effectMergeKey(entry.config), entry)
                    }
                    result.addAll(winners.values.reversed())
                }
            }
        }
        return result
    }

    private fun effectiveRegionEffects(region: RegionDefinition): List<EffectConfig> {
        if (!region.flags["vanish"]?.value.equals("deny", ignoreCase = true)) {
            return region.effects
        }
        return region.effects + EffectConfig(
            "invisibility_suppression",
            EffectScope.WHILE_INSIDE,
            combination = EffectCombination.HIGHEST_PRIORITY,
        )
    }

    private fun effectFamily(config: EffectConfig): String =
        config.type.lowercase(Locale.ROOT)

    private fun effectMergeKey(config: EffectConfig): String =
        when (effectFamily(config)) {
            "potion" -> "potion:${config.values["effect"]?.lowercase(Locale.ROOT) ?: config.values["name"]?.lowercase(Locale.ROOT) ?: "unknown"}"
            else -> effectFamily(config)
        }

    fun hasStatefulMode(region: RegionDefinition): Boolean {
        val type = region.mode?.type?.lowercase(Locale.ROOT) ?: return false
        return type != "free_event" && type != "none"
    }

    companion object {
        val REGION_ORDER: Comparator<RegionDefinition> =
            compareByDescending<RegionDefinition> { it.priority }.thenBy { it.id }
    }
}
