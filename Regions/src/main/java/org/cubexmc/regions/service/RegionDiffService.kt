package org.cubexmc.regions.service

import org.cubexmc.regions.model.RegionDefinition

data class RegionChange(
    val path: String,
    val before: String?,
    val after: String?,
)

object RegionDiffService {
    fun compare(published: RegionDefinition?, draft: RegionDefinition): List<RegionChange> {
        val before = published?.let { flatten(it) } ?: emptyMap()
        val after = flatten(draft)
        return (before.keys + after.keys)
            .toSortedSet()
            .mapNotNull { path ->
                val oldValue = before[path]
                val newValue = after[path]
                if (oldValue == newValue) null else RegionChange(path, oldValue, newValue)
            }
    }

    private fun flatten(region: RegionDefinition): Map<String, String> = linkedMapOf<String, String>().apply {
        put("name", region.name)
        put("enabled", region.enabled.toString())
        put("priority", region.priority.toString())
        put("source.type", region.source.type)
        region.source.values.toSortedMap().forEach { (key, value) -> put("source.$key", value) }
        region.mode?.let { mode ->
            put("mode.type", mode.type)
            mode.values.toSortedMap().forEach { (key, value) -> put("mode.$key", value) }
        }
        region.flags.toSortedMap().forEach { (key, flag) ->
            put("flags.$key.value", flag.value)
            flag.values.toSortedMap().forEach { (valueKey, value) -> put("flags.$key.$valueKey", value) }
        }
        region.effects.forEachIndexed { index, effect ->
            put("effects.$index.type", effect.type)
            put("effects.$index.scope", effect.scope.name.lowercase())
            put("effects.$index.combination", effect.combination.name.lowercase())
            effect.values.toSortedMap().forEach { (key, value) -> put("effects.$index.$key", value) }
        }
        region.triggers.toSortedMap(compareBy { it.key }).forEach { (trigger, blocks) ->
            blocks.forEachIndexed { blockIndex, block ->
                val base = "triggers.${trigger.key}.$blockIndex"
                block.name?.let { put("$base.name", it) }
                put("$base.execution", block.execution.name.lowercase())
                block.conditions.forEachIndexed { index, condition ->
                    put("$base.if.$index.type", condition.type)
                    put("$base.if.$index.negated", condition.negated.toString())
                    condition.values.toSortedMap().forEach { (key, value) -> put("$base.if.$index.$key", value) }
                }
                block.thenActions.forEachIndexed { index, action ->
                    put("$base.then.$index.type", action.type)
                    action.values.toSortedMap().forEach { (key, value) -> put("$base.then.$index.$key", value) }
                }
                block.elseActions.forEachIndexed { index, action ->
                    put("$base.else.$index.type", action.type)
                    action.values.toSortedMap().forEach { (key, value) -> put("$base.else.$index.$key", value) }
                }
            }
        }
        region.metadata.toSortedMap()
            .filterKeys { it != RegionAuthorityService.SOURCE_OWNER_METADATA }
            .forEach { (key, value) -> put("metadata.$key", value) }
    }
}
