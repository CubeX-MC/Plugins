package org.cubexmc.regions.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegionBaselineTest {

    @Test
    fun `every baseline file gets a migration plan`() {
        val plans = RegionBaseline.plans()

        assertEquals(RegionBaseline.files.size, plans.size)
        assertEquals(
            RegionBaseline.files.map { it.path }.toSet(),
            plans.map { it.resourcePath() }.toSet(),
        )
    }

    @Test
    fun `each plan carries that file's own version key and target version`() {
        val byPath = RegionBaseline.plans().associateBy { it.resourcePath() }

        for (baseline in RegionBaseline.files) {
            val plan = byPath.getValue(baseline.path)
            assertEquals(baseline.versionKey, plan.versionKey(), baseline.path)
            assertEquals(baseline.version, plan.targetVersion(), baseline.path)
        }
    }

    @Test
    fun `a file with no version key is treated as already at the baseline`() {
        // 首个公开版本就是起点:没有版本键的文件是刚生成的默认文件,不该被当成"更旧的格式"。
        for (plan in RegionBaseline.plans()) {
            assertEquals(plan.targetVersion(), plan.missingVersion(), plan.resourcePath())
        }
    }

    @Test
    fun `no migration steps exist yet, so the first public release is the starting point`() {
        // 这条会在第一次改格式时失败 —— 那正是提醒:版本号 +1 的同时必须补 addStep。
        assertTrue(
            RegionBaseline.plans().all { it.steps().isEmpty() },
            "baseline plans should have no steps until a format actually changes",
        )
    }

    @Test
    fun `the two language files share a version key but are migrated separately`() {
        val langPlans = RegionBaseline.plans().filter { it.resourcePath().startsWith("lang/") }

        assertEquals(2, langPlans.size)
        assertTrue(langPlans.all { it.versionKey() == "lang-version" })
    }
}
