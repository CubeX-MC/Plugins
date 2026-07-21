package org.cubexmc.regions.service

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.model.RegionTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RegionTemplateServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `template loads applies parameters and preserves source identity`() {
        val file = tempDir.resolve("templates.yml").toFile()
        val yaml = YamlConfiguration()
        yaml.set("templates.duel.name", "Configurable Duel")
        yaml.set("templates.duel.description", "A test template")
        yaml.set("templates.duel.parameters.players.type", "integer")
        yaml.set("templates.duel.parameters.players.required", true)
        yaml.set("templates.duel.parameters.players.min", 2)
        yaml.set("templates.duel.parameters.players.max", 8)
        yaml.set("templates.duel.mode.type", "dual_pvp")
        yaml.set("templates.duel.mode.min-players", "\${players}")
        yaml.set("templates.duel.flags.pvp.value", "allow")
        yaml.set("templates.duel.effects", listOf(mapOf("type" to "scale", "scope" to "while_inside", "value" to "0.8")))
        yaml.set(
            "templates.duel.triggers.on_enter",
            listOf(
                mapOf(
                    "if" to listOf(mapOf("type" to "permission", "permission" to "regions.play")),
                    "then" to listOf(mapOf("type" to "message", "message" to "Ready")),
                ),
            ),
        )
        yaml.save(file)
        val service = RegionTemplateService(file).apply { load() }
        val source = RegionSourceRef("lands", mapOf("land" to "Capital", "area" to "arena"))
        val base = RegionDefinition("venue", "Venue", source)

        val result = service.apply("duel", base, mapOf("players" to "4"))

        assertTrue(result.success)
        assertEquals(source, result.region?.source)
        assertEquals("dual_pvp", result.region?.mode?.type)
        assertEquals("4", result.region?.mode?.values?.get("min-players"))
        assertEquals("allow", result.region?.flags?.get("pvp")?.value)
        assertEquals("permission", result.region?.triggers?.get(RegionTrigger.ON_ENTER)?.first()?.conditions?.first()?.type)
        assertEquals("duel", result.region?.metadata?.get("template-id"))
    }

    @Test
    fun `template parameter schema rejects bad ranges and unknown inputs`() {
        val file = tempDir.resolve("templates.yml").toFile()
        val yaml = YamlConfiguration()
        yaml.set("templates.race.name", "Race")
        yaml.set("templates.race.parameters.radius.type", "double")
        yaml.set("templates.race.parameters.radius.required", true)
        yaml.set("templates.race.parameters.radius.min", 1.0)
        yaml.set("templates.race.parameters.radius.max", 10.0)
        yaml.set("templates.race.mode.type", "run_race")
        yaml.set("templates.race.mode.radius", "\${radius}")
        yaml.save(file)
        val service = RegionTemplateService(file).apply { load() }
        val base = RegionDefinition("race", "Race", RegionSourceRef("lands"))

        assertFalse(service.apply("race", base, mapOf("radius" to "20")).success)
        assertFalse(service.apply("race", base, mapOf("radius" to "3", "mystery" to "x")).success)
        assertTrue(service.apply("race", base, mapOf("radius" to "3.5")).success)
    }
}
