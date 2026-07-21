package org.cubexmc.regions.service

import org.cubexmc.regions.model.FlagConfig
import org.cubexmc.regions.model.EffectCombination
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionGeometry
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.model.ValidationSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegionOverlapResolverTest {
    private val resolver = RegionOverlapResolver()

    @Test
    fun `highest priority stateful mode and first non-pass flag win deterministically`() {
        val low = region("low", 1, "run_race", "deny")
        val high = region("high", 10, "dual_pvp", "allow")

        val result = resolver.resolve(listOf(low, high))

        assertEquals("high", result.primaryModeRegion?.id)
        assertEquals(listOf("low"), result.suppressedModeRegions.map { it.id })
        assertEquals("allow", result.flags["pvp"]?.config?.value)
        assertEquals("high", result.flags["pvp"]?.sourceRegionId)
    }

    @Test
    fun `pass flag delegates to the next overlapping region`() {
        val high = region("high", 10, "free_event", "pass")
        val low = region("low", 1, "free_event", "deny")

        assertEquals("low", resolver.resolve(listOf(high, low)).flags["pvp"]?.sourceRegionId)
    }

    @Test
    fun `same source stateful modes fail publishing while flag conflicts are explained`() {
        val live = region("live", 10, "dual_pvp", "deny")
        val candidate = region("draft", 5, "run_race", "allow")

        val issues = resolver.validateCandidate(candidate, listOf(live))

        assertTrue(issues.any { it.severity == ValidationSeverity.ERROR && it.message.contains("Stateful modes overlap") })
        assertTrue(issues.any { it.severity == ValidationSeverity.WARNING && it.message.contains("resolves to 'deny'") })
    }

    @Test
    fun `equal priority uses region id as stable tie breaker`() {
        val later = region("z-region", 10, "run_race", "deny")
        val earlier = region("a-region", 10, "dual_pvp", "allow")

        val result = resolver.resolve(listOf(later, earlier))

        assertEquals("a-region", result.primaryModeRegion?.id)
        assertEquals("a-region", result.flags["pvp"]?.sourceRegionId)
    }

    @Test
    fun `publishing validation treats flag keys case insensitively`() {
        val live = region("live", 10, "free_event", "deny")
        val candidate = region("draft", 5, "free_event", "allow").copy(
            flags = mapOf("PvP" to FlagConfig("pvp", "allow")),
        )

        val issues = resolver.validateCandidate(candidate, listOf(live))

        assertTrue(issues.any { it.severity == ValidationSeverity.WARNING && it.message.contains("resolves to 'deny'") })
    }

    @Test
    fun `effect strategies resolve deterministically`() {
        val high = region("high", 10, "free_event", "pass").copy(
            effects = listOf(
                EffectConfig("walk_speed", values = mapOf("value" to "0.6"), combination = EffectCombination.HIGHEST_PRIORITY),
                EffectConfig("potion", values = mapOf("effect" to "speed"), combination = EffectCombination.MERGE_BY_TYPE),
            ),
        )
        val low = region("low", 1, "free_event", "pass").copy(
            effects = listOf(
                EffectConfig("walk_speed", values = mapOf("value" to "0.3"), combination = EffectCombination.HIGHEST_PRIORITY),
                EffectConfig("potion", values = mapOf("effect" to "jump_boost"), combination = EffectCombination.MERGE_BY_TYPE),
            ),
        )

        val effects = resolver.resolve(listOf(low, high)).effects

        assertEquals(listOf("jump_boost", "speed"), effects.filter { it.config.type == "potion" }.map { it.config.values["effect"] })
        assertEquals(listOf("high"), effects.filter { it.config.type == "walk_speed" }.map { it.sourceRegionId })
    }

    @Test
    fun `exclusive overlapping effects block publishing`() {
        val live = region("live", 10, "free_event", "pass").copy(
            effects = listOf(EffectConfig("scale", values = mapOf("value" to "0.5"), combination = EffectCombination.EXCLUSIVE)),
        )
        val draft = region("draft", 5, "free_event", "pass").copy(
            effects = listOf(EffectConfig("scale", values = mapOf("value" to "1.5"), combination = EffectCombination.EXCLUSIVE)),
        )

        val issues = resolver.validateCandidate(draft, listOf(live))

        assertTrue(issues.any { it.severity == ValidationSeverity.ERROR && it.message.contains("exclusive") })
    }

    @Test
    fun `geometry detects overlap across different source references`() {
        val live = region("live", 10, "free_event", "deny")
        val draft = region("draft", 5, "free_event", "allow").copy(source = RegionSourceRef("cuboid", mapOf("id" to "other")))
        val geometries = mapOf(
            "live" to RegionGeometry("world", 0.0, 0.0, 0.0, 10.0, 100.0, 10.0),
            "draft" to RegionGeometry("world", 5.0, 0.0, 5.0, 15.0, 100.0, 15.0),
        )

        val issues = resolver.validateCandidate(draft, listOf(live)) { geometries[it.id] }

        assertTrue(issues.any { it.message.contains("Overlapping flag") })
    }

    private fun region(id: String, priority: Int, mode: String, pvp: String): RegionDefinition = RegionDefinition(
        id = id,
        name = id,
        source = RegionSourceRef("lands", mapOf("land" to "capital", "area" to "arena")),
        priority = priority,
        mode = ModeConfig(mode),
        flags = mapOf("pvp" to FlagConfig("pvp", pvp)),
    )
}
