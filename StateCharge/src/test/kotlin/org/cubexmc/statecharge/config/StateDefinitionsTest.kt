package org.cubexmc.statecharge.config

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.core.CubexLogger
import org.cubexmc.statecharge.StateChargePlugin
import org.cubexmc.statecharge.effect.FlightEffect
import org.cubexmc.statecharge.effect.ScaleEffect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.math.BigDecimal

class StateDefinitionsTest {

    private lateinit var plugin: StateChargePlugin
    private lateinit var config: YamlConfiguration

    @BeforeEach
    fun setUp() {
        plugin = mock(StateChargePlugin::class.java)
        config = YamlConfiguration()
        `when`(plugin.config).thenReturn(config)
        `when`(plugin.log()).thenReturn(mock(CubexLogger::class.java))
    }

    private fun parse(): StateDefinitions {
        val definitions = StateDefinitions(plugin)
        definitions.reload()
        return definitions
    }

    @Test
    fun missingStatesSectionYieldsEmptyCatalog() {
        val definitions = parse()
        assertTrue(definitions.all().isEmpty())
        assertTrue(definitions.purchasable().isEmpty())
    }

    @Test
    fun parsesBuiltInStates() {
        config.set("states.small.enabled", true)
        config.set("states.small.display", "变小")
        config.set("states.small.price", 100.0)
        config.set("states.small.unit-seconds", 1800)
        config.set("states.small.effect.type", "scale")
        config.set("states.small.effect.scale", 0.5)

        config.set("states.fly.enabled", true)
        config.set("states.fly.price", 200.0)
        config.set("states.fly.unit-seconds", 3600)
        config.set("states.fly.effect.type", "fly")
        config.set("states.fly.effect.auto-start", false)

        val definitions = parse()
        assertTrue(definitions.problems().isEmpty())

        val small = definitions.byId("small")
        assertNotNull(small)
        assertEquals("变小", small!!.display())
        assertEquals(BigDecimal.valueOf(100.0), small.price())
        assertEquals(1800L, small.unitSeconds())
        assertEquals("scale", small.conflictGroup())
        assertNull(small.permission())
        assertTrue(small.effect() is ScaleEffect)
        assertTrue(small.enabled())

        val fly = definitions.byId("fly")
        assertNotNull(fly)
        assertEquals("fly", fly!!.conflictGroup())
        assertTrue(fly.effect() is FlightEffect)
        // display 缺省用 id。
        assertEquals("fly", fly.display())
    }

    @Test
    fun defaultsFillIn() {
        config.set("states.plain.effect.type", "fly")
        val definitions = parse()
        val plain = definitions.byId("plain")
        assertNotNull(plain)
        assertEquals(1800L, plain!!.unitSeconds())
        assertEquals(BigDecimal.valueOf(0.0), plain.price())
        assertTrue(plain.enabled())
        assertEquals("plain", plain.display())
        assertEquals("fly", plain.conflictGroup())
    }

    @Test
    fun invalidEntriesAreSkippedWithProblems() {
        config.set("states.bad-id!!.effect.type", "fly")
        config.set("states.no-effect.enabled", true)
        config.set("states.unknown-type.effect.type", "teleport")
        config.set("states.bad-scale.effect.type", "scale")
        config.set("states.bad-scale.effect.scale", 0.001)
        config.set("states.bad-unit.effect.type", "fly")
        config.set("states.bad-unit.unit-seconds", 0)

        val definitions = parse()
        assertTrue(definitions.all().isEmpty())
        assertEquals(5, definitions.problems().size)
    }

    @Test
    fun disabledStateIsNotPurchasableButStillKnown() {
        config.set("states.off.enabled", false)
        config.set("states.off.effect.type", "fly")
        val definitions = parse()
        assertNotNull(definitions.byId("off"))
        assertFalse(definitions.byId("off")!!.enabled())
        assertTrue(definitions.purchasable().isEmpty())
    }

    @Test
    fun explicitBlankConflictGroupDisablesExclusivity() {
        config.set("states.free.effect.type", "scale")
        config.set("states.free.effect.scale", 2.0)
        config.set("states.free.conflict-group", "")
        val definitions = parse()
        assertNull(definitions.byId("free")!!.conflictGroup())
    }

    @Test
    fun customConflictGroupOverridesDerivedOne() {
        config.set("states.team.effect.type", "scale")
        config.set("states.team.effect.scale", 2.0)
        config.set("states.team.conflict-group", "morph")
        val definitions = parse()
        assertEquals("morph", definitions.byId("team")!!.conflictGroup())
    }

    @Test
    fun permissionIsReadWhenSet() {
        config.set("states.vip.effect.type", "fly")
        config.set("states.vip.permission", "statecharge.buy.vip")
        val definitions = parse()
        assertEquals("statecharge.buy.vip", definitions.byId("vip")!!.permission())
    }

    @Test
    fun `icon falls back to the default when the material is unknown`() {
        config.set("states.bogus.effect.type", "fly")
        config.set("states.bogus.icon", "NOT_A_MATERIAL")

        val definitions = parse()

        // 图标只影响交易页展示,写错不该让整个状态加载失败
        assertEquals(org.bukkit.Material.NAME_TAG, definitions.byId("bogus")!!.icon())
        assertTrue(definitions.problems().any { it.contains("icon") })
    }

    @Test
    fun `a configured icon is used as-is`() {
        config.set("states.shiny.effect.type", "fly")
        config.set("states.shiny.icon", "DIAMOND")

        assertEquals(org.bukkit.Material.DIAMOND, parse().byId("shiny")!!.icon())
    }
}
