package org.cubexmc.regions.service

import org.bukkit.Location
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.capability.BuiltInRegionCapabilities
import org.cubexmc.regions.capability.CapabilityCatalog
import org.cubexmc.regions.capability.CapabilityDescriptor
import org.cubexmc.regions.capability.CapabilityKind
import org.cubexmc.regions.effect.ScopedEffectService
import org.cubexmc.regions.flag.RegionFlagRegistry
import org.cubexmc.regions.integration.RegionSource
import org.cubexmc.regions.integration.RegionSourceRegistry
import org.cubexmc.regions.mode.RegionModeRegistry
import org.cubexmc.regions.model.ActionBlockConfig
import org.cubexmc.regions.model.ActionConfig
import org.cubexmc.regions.model.ConditionConfig
import org.cubexmc.regions.model.ExternalRegion
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.FlagConfig
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.model.RegionTrigger
import org.cubexmc.regions.model.ValidationSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.util.UUID

class CapabilityTruthTest {
    @Test
    fun `default registries expose only implemented capabilities`() {
        val flags = RegionFlagRegistry().apply { registerDefaults() }
        val actions = RegionActionRegistry().apply { registerDefaults() }
        val conditions = RegionConditionRegistry().apply { registerDefaults() }
        val effects = ScopedEffectService(mock(RegionsPlugin::class.java)).apply { registerDefaults() }
        val modes = RegionModeRegistry().apply {
            listOf("free_event", "dual_pvp", "union_war", "run_race", "boat_race", "horse_race", "hide_and_seek").forEach(::register)
        }
        val catalog = CapabilityCatalog().apply { BuiltInRegionCapabilities.registerAll(this) }

        assertTrue(flags.isRegistered("pvp"))
        assertFalse(flags.isRegistered("block_break"))
        assertTrue(actions.isRegistered("teleport"))
        assertFalse(actions.isRegistered("economy"))
        assertTrue(conditions.isRegistered("permission"))
        assertFalse(conditions.isRegistered("placeholder"))
        assertTrue(effects.isRegistered("scale"))
        assertFalse(effects.isRegistered("scoreboard"))
        assertEquals(flags.all(), catalog.stableIds(CapabilityKind.FLAG))
        assertEquals(actions.all(), catalog.stableIds(CapabilityKind.ACTION))
        assertEquals(conditions.all(), catalog.stableIds(CapabilityKind.CONDITION))
        assertEquals(effects.allTypes(), catalog.stableIds(CapabilityKind.EFFECT))
        assertEquals(modes.all(), catalog.stableIds(CapabilityKind.MODE))
    }

    @Test
    fun `validation rejects unknown conditions and unavailable flags`() {
        val sources = RegionSourceRegistry().apply { register(AlwaysAvailableSource()) }
        val modes = RegionModeRegistry().apply { register("free_event") }
        val flags = RegionFlagRegistry().apply { registerDefaults() }
        val effects = ScopedEffectService(mock(RegionsPlugin::class.java)).apply { registerDefaults() }
        val actions = RegionActionRegistry().apply { registerDefaults() }
        val conditions = RegionConditionRegistry().apply { registerDefaults() }
        val catalog = CapabilityCatalog().apply {
            BuiltInRegionCapabilities.registerAll(this)
            register(CapabilityDescriptor(CapabilityKind.SOURCE, "test"))
        }
        val validation = RegionValidationService(sources, modes, flags, effects, actions, conditions, catalog)
        val region = RegionDefinition(
            id = "venue",
            name = "Venue",
            source = RegionSourceRef("test"),
            flags = mapOf("block_break" to FlagConfig("block_break", "deny")),
            triggers = mapOf(
                RegionTrigger.ON_ENTER to listOf(
                    ActionBlockConfig(conditions = listOf(ConditionConfig("unknown_condition"))),
                ),
            ),
        )

        val errors = validation.validate(region).filter { it.severity == ValidationSeverity.ERROR }

        assertTrue(errors.any { it.message.contains("block_break") })
        assertTrue(errors.any { it.message.contains("unknown_condition") })
    }

    @Test
    fun `schema validates required types ranges and unknown strict parameters`() {
        val catalog = CapabilityCatalog().apply { BuiltInRegionCapabilities.registerAll(this) }

        assertTrue(catalog.validate(CapabilityKind.EFFECT, "scale", mapOf("value" to "0.35")).isEmpty())
        assertTrue(catalog.validate(CapabilityKind.EFFECT, "scale", mapOf("value" to "8.0")).isNotEmpty())
        assertTrue(catalog.validate(CapabilityKind.EFFECT, "potion", emptyMap()).isNotEmpty())
        assertTrue(catalog.validate(CapabilityKind.CONDITION, "chance", mapOf("percent" to "101")).isNotEmpty())
        assertTrue(catalog.validate(CapabilityKind.FLAG, "pvp", mapOf("value" to "sometimes")).isNotEmpty())
        assertTrue(catalog.validate(CapabilityKind.EFFECT, "scale", mapOf("value" to "0.5", "mystery" to "1")).isNotEmpty())
    }

    @Test
    fun `service validation rejects ids that can escape yaml paths`() {
        val sources = RegionSourceRegistry().apply { register(AlwaysAvailableSource()) }
        val modes = RegionModeRegistry().apply { register("free_event") }
        val flags = RegionFlagRegistry().apply { registerDefaults() }
        val effects = ScopedEffectService(mock(RegionsPlugin::class.java)).apply { registerDefaults() }
        val actions = RegionActionRegistry().apply { registerDefaults() }
        val conditions = RegionConditionRegistry().apply { registerDefaults() }
        val catalog = CapabilityCatalog().apply {
            BuiltInRegionCapabilities.registerAll(this)
            register(CapabilityDescriptor(CapabilityKind.SOURCE, "test"))
        }
        val validation = RegionValidationService(sources, modes, flags, effects, actions, conditions, catalog)

        val issues = validation.validate(RegionDefinition(
            id = "arena.escape",
            name = "Unsafe id",
            source = RegionSourceRef("test"),
        ))

        assertTrue(issues.any { it.severity == ValidationSeverity.ERROR && it.message.contains("Region id") })
    }

    @Test
    fun `mode validation catches broken player ranges and race locations before publishing`() {
        val sources = RegionSourceRegistry().apply { register(AlwaysAvailableSource()) }
        val modes = RegionModeRegistry().apply { register("run_race") }
        val flags = RegionFlagRegistry().apply { registerDefaults() }
        val effects = ScopedEffectService(mock(RegionsPlugin::class.java)).apply { registerDefaults() }
        val actions = RegionActionRegistry().apply { registerDefaults() }
        val conditions = RegionConditionRegistry().apply { registerDefaults() }
        val catalog = CapabilityCatalog().apply {
            BuiltInRegionCapabilities.registerAll(this)
            register(CapabilityDescriptor(CapabilityKind.SOURCE, "test"))
        }
        val validation = RegionValidationService(sources, modes, flags, effects, actions, conditions, catalog)
        val region = RegionDefinition(
            id = "race",
            name = "Broken race",
            source = RegionSourceRef("test"),
            mode = ModeConfig("run_race", mapOf(
                "min-players" to "4",
                "max-players" to "2",
                "start" to "world,not-a-number,64,0",
            )),
        )

        val errors = validation.validate(region).filter { it.severity == ValidationSeverity.ERROR }

        assertTrue(errors.any { it.message.contains("max-players") })
        assertTrue(errors.any { it.message.contains("start must use") })
        assertTrue(errors.any { it.message.contains("finish location") })
    }

    @Test
    fun `mode validation rejects invalid kits checkpoint mapping and seeker counts`() {
        val validation = validationForModes("run_race", "hide_and_seek", "union_war")
        val race = RegionDefinition(
            id = "race",
            name = "Race",
            source = RegionSourceRef("test"),
            mode = ModeConfig("run_race", mapOf(
                "start" to "world,0,64,0",
                "finish" to "world,20,64,0",
                "checkpoints" to "world,5,64,0;world,10,64,0",
                "checkpoint-vehicles" to "boat",
            )),
        )
        val hiding = RegionDefinition(
            id = "hiding",
            name = "Hiding",
            source = RegionSourceRef("test"),
            mode = ModeConfig("hide_and_seek", mapOf(
                "min-players" to "2",
                "seekers" to "2",
                "seeker-kit" to "NOT_A_MATERIAL:99",
            )),
        )
        val war = RegionDefinition(
            id = "war",
            name = "War",
            source = RegionSourceRef("test"),
            mode = ModeConfig("union_war", mapOf(
                "min-players" to "2",
                "min-unions" to "3",
            )),
        )

        val errors = listOf(race, hiding, war).flatMap(validation::validate)
            .filter { it.severity == ValidationSeverity.ERROR }

        assertTrue(errors.any { it.message.contains("checkpoint-vehicles") })
        assertTrue(errors.any { it.message.contains("seekers must be lower") })
        assertTrue(errors.any { it.message.contains("NOT_A_MATERIAL") })
        assertTrue(errors.any { it.message.contains("min-unions") })
    }

    @Test
    fun `action validation rejects unknown nested effects and malformed runtime values`() {
        val validation = validationForModes("free_event")
        val region = RegionDefinition(
            id = "actions",
            name = "Broken actions",
            source = RegionSourceRef("test"),
            effects = listOf(EffectConfig("potion", values = mapOf("effect" to "bad effect key"))),
            triggers = mapOf(
                RegionTrigger.ON_ENTER to listOf(
                    ActionBlockConfig(
                        thenActions = listOf(
                            ActionConfig("effect_apply", mapOf("effect" to "unknown_effect")),
                            ActionConfig("teleport", mapOf("location" to "not-a-location")),
                            ActionConfig("give_item", mapOf("item" to "NOT_A_MATERIAL:99")),
                            ActionConfig("sound", mapOf("sound" to "bad sound key")),
                        ),
                    ),
                ),
            ),
        )

        val errors = validation.validate(region).filter { it.severity == ValidationSeverity.ERROR }

        assertTrue(errors.any { it.message.contains("unknown_effect") })
        assertTrue(errors.any { it.message.contains("teleport action") })
        assertTrue(errors.any { it.message.contains("NOT_A_MATERIAL") })
        assertTrue(errors.any { it.message.contains("Invalid potion effect key") })
        assertTrue(errors.any { it.message.contains("Invalid sound key") })
    }

    private fun validationForModes(vararg modeIds: String): RegionValidationService {
        val sources = RegionSourceRegistry().apply { register(AlwaysAvailableSource()) }
        val modes = RegionModeRegistry().apply { modeIds.forEach(::register) }
        val flags = RegionFlagRegistry().apply { registerDefaults() }
        val effects = ScopedEffectService(mock(RegionsPlugin::class.java)).apply { registerDefaults() }
        val actions = RegionActionRegistry().apply { registerDefaults() }
        val conditions = RegionConditionRegistry().apply { registerDefaults() }
        val catalog = CapabilityCatalog().apply {
            BuiltInRegionCapabilities.registerAll(this)
            register(CapabilityDescriptor(CapabilityKind.SOURCE, "test"))
        }
        return RegionValidationService(sources, modes, flags, effects, actions, conditions, catalog)
    }

    private class AlwaysAvailableSource : RegionSource {
        override val type: String = "test"

        override fun isAvailable(): Boolean = true

        override fun resolve(ref: RegionSourceRef): ExternalRegion = ExternalRegion("test", "Test", type)

        override fun contains(ref: RegionSourceRef, location: Location): Boolean = false

        override fun getOwnedRegions(playerId: UUID): List<ExternalRegion> = emptyList()

        override fun isOwner(ref: RegionSourceRef, playerId: UUID): Boolean = true
    }
}
