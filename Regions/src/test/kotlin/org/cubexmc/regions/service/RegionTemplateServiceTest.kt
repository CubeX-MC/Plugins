package org.cubexmc.regions.service

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.model.RegionTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

    /**
     * 回归防护：`union_war` 曾按 PLAN.md 的设计写了 `reward-source: contract`，而
     * [RegionValidationService] 又把奖励字段当成"首发不支持"直接判 ERROR——插件自带的模板被插件
     * 自己的验证器拒绝，套用后根本发布不了。这两条约定必须一起改，所以在这里钉住。
     *
     * 其余测试都只跑合成模板，随包发布的 templates.yml 之前没有任何测试覆盖。
     */
    @Test
    fun `no shipped template carries a mode field the validator blocks`() {
        val service = loadShippedTemplates()

        val templates = service.all()
        assertTrue(templates.isNotEmpty(), "shipped templates.yml parsed to nothing")
        for (template in templates) {
            for (blocked in BLOCKED_MODE_KEYS) {
                val value = template.mode?.values?.get(blocked)
                assertTrue(
                    value.isNullOrBlank(),
                    "template '${template.id}' sets $blocked='$value'; RegionValidationService rejects that on publish",
                )
            }
        }
    }

    /**
     * 坐标类字段曾经在模板里硬编码成占位值（`respawn: world,0,80,0`）或留空
     * （赛道的 `start` / `finish`）。RegionValidationService 只校验坐标的*格式*
     * （`requireLoadedWorld` 在所有模式调用点都是 false），所以假坐标一路绿灯，直到玩家死在
     * 场地里才暴露；留空的那两个则是发布时才报 ERROR。现在都改成必填参数，创建时问出来。
     */
    @Test
    fun `shipped templates ask for every location instead of shipping a placeholder`() {
        val service = loadShippedTemplates()
        val base = RegionDefinition("venue", "Venue", RegionSourceRef("lands"))

        for (template in service.all()) {
            val locations = template.parameters.values.filter { it.type == TemplateParameterType.LOCATION }
            if (locations.isEmpty()) continue
            for (parameter in locations) {
                assertTrue(parameter.required, "${template.id}.${parameter.id} must be required")
            }

            // 不填就套不上，没有中间的"悄悄用默认值"。
            assertFalse(service.apply(template.id, base).success, "${template.id} applied with no locations")

            val supplied = locations.associate { it.id to "arena,12,64,-30" }
            val applied = service.apply(template.id, base, supplied)
            assertTrue(applied.success, "${template.id} rejected valid locations: ${applied.errors}")
            for (parameter in locations) {
                assertEquals(
                    "arena,12,64,-30",
                    applied.region?.mode?.values?.get(parameter.id),
                    "${template.id}.${parameter.id} did not reach the mode",
                )
            }
        }
    }

    /**
     * 赛道的起点和终点是 [RegionValidationService.addModeRuleIssues] 里 `required = true` 的两个
     * 位置，模板必须两个都问，否则套完直接卡在发布校验上。
     */
    @Test
    fun `race templates ask for both a start and a finish`() {
        val service = loadShippedTemplates()

        val races = service.all().filter { it.mode?.type in RACE_MODES }
        assertEquals(RACE_MODES.size, races.size, "expected one shipped template per race mode")
        for (race in races) {
            val locations = race.parameters.values
                .filter { it.type == TemplateParameterType.LOCATION }
                .map { it.id }
            assertTrue(locations.containsAll(listOf("start", "finish")), "${race.id} only asks for $locations")
        }
    }

    @Test
    fun `location parameters reject anything the publish validator would reject`() {
        val parameter = TemplateParameter("respawn", TemplateParameterType.LOCATION, required = true)

        assertNull(parameter.validate("arena,12,64,-30"))
        assertNull(parameter.validate("arena,12.5,64.0,-30.5,90,0"))
        assertNotNull(parameter.validate("arena,12,64"), "three components is not a location")
        assertNotNull(parameter.validate(",12,64,-30"), "a blank world is not a location")
        assertNotNull(parameter.validate("arena,x,64,-30"), "non-numeric coordinates are not a location")
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

    /** 加载随包发布的那份 templates.yml，而不是测试里现造的合成模板。 */
    private fun loadShippedTemplates(): RegionTemplateService {
        val file = tempDir.resolve("shipped-templates.yml").toFile()
        val stream = requireNotNull(javaClass.getResourceAsStream("/templates.yml")) { "missing shipped templates.yml" }
        stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        return RegionTemplateService(file).apply { load() }
    }

    private companion object {
        /** Mode 字段里 [RegionValidationService.addModeRuleIssues] 会直接判 ERROR 的那些。 */
        val BLOCKED_MODE_KEYS = listOf("reward-source", "reward-contract")

        /** 需要起点/终点的模式，和 [RegionValidationService.addModeRuleIssues] 的分支一致。 */
        val RACE_MODES = setOf("run_race", "boat_race", "horse_race")
    }
}
