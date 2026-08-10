package org.cubexmc.regions.service

import org.cubexmc.regions.model.FlagConfig
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegionDiffServiceTest {
    @Test
    fun `diff reports structured changes and ignores revision bookkeeping`() {
        val published = region().copy(revision = 1, publishedRevision = 1)
        val draft = region().copy(
            name = "New Arena",
            revision = 8,
            publishedRevision = 1,
            mode = ModeConfig("dual_pvp", mapOf("min-players" to "2")),
            flags = mapOf("pvp" to FlagConfig("pvp", "allow")),
        )

        val changes = RegionDiffService.compare(published, draft)

        assertEquals("New Arena", changes.single { it.path == "name" }.after)
        assertEquals("dual_pvp", changes.single { it.path == "mode.type" }.after)
        assertEquals("allow", changes.single { it.path == "flags.pvp.value" }.after)
        assertTrue(changes.none { it.path.contains("revision") })
    }

    @Test
    fun `new draft preview treats configuration as additions`() {
        val changes = RegionDiffService.compare(null, region())

        assertEquals(null, changes.single { it.path == "name" }.before)
        assertEquals("Arena", changes.single { it.path == "name" }.after)
    }

    private fun region(): RegionDefinition = RegionDefinition(
        id = "arena",
        name = "Arena",
        source = RegionSourceRef("lands", mapOf("land" to "capital", "area" to "arena")),
        mode = ModeConfig("free_event"),
    )
}
