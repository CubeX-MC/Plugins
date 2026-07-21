package org.cubexmc.regions.storage

import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.ActionBlockConfig
import org.cubexmc.regions.model.ActionConfig
import org.cubexmc.regions.model.ConditionConfig
import org.cubexmc.regions.model.EffectCombination
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.model.RegionTrigger
import org.cubexmc.regions.model.TriggerExecution
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Path
import java.util.logging.Logger

class RegionStorageRevisionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `drafts and immutable revisions survive a storage reload`() {
        val storage = RegionStorage(plugin())
        val published = region(1, "Published")
        val draft = region(2, "Draft").copy(
            lifecycle = RegionLifecycle.DRAFT,
            publishedRevision = 1,
        )

        storage.put(published)
        storage.recordRevision(published)
        storage.putDraft(draft)
        storage.flushIfDirty()

        val reloaded = RegionStorage(plugin()).apply { load() }

        assertEquals("Published", reloaded.find("arena")?.name)
        assertEquals("Draft", reloaded.findDraft("arena")?.name)
        assertEquals(1, reloaded.revisionHistory("arena").single().revision)
        val condition = reloaded.findRevision("arena", 1)
            ?.triggers
            ?.get(RegionTrigger.ON_ENTER)
            ?.single()
            ?.conditions
            ?.single()
        assertNotNull(condition)
        assertEquals(true, condition?.negated)
        val snapshot = reloaded.findRevision("arena", 1)!!
        assertEquals(EffectCombination.STACK, snapshot.effects.single().combination)
        assertEquals(
            TriggerExecution.PRIMARY_REGION,
            snapshot.triggers.getValue(RegionTrigger.ON_ENTER).single().execution,
        )
    }

    @Test
    fun `revision retention removes the oldest snapshots`() {
        val storage = RegionStorage(plugin())
        repeat(5) { index -> storage.recordRevision(region((index + 1).toLong(), "r${index + 1}"), 3) }

        assertEquals(listOf(3L, 4L, 5L), storage.revisionHistory("arena").map { it.revision })
    }

    private fun plugin(): RegionsPlugin {
        val plugin = mock(RegionsPlugin::class.java)
        `when`(plugin.dataFolder).thenReturn(tempDir.toFile())
        `when`(plugin.logger).thenReturn(Logger.getLogger("RegionStorageRevisionTest"))
        return plugin
    }

    private fun region(revision: Long, name: String): RegionDefinition = RegionDefinition(
        id = "arena",
        name = name,
        source = RegionSourceRef("lands", mapOf("land" to "capital", "area" to "arena")),
        revision = revision,
        publishedRevision = revision,
        effects = listOf(
            EffectConfig("potion", values = mapOf("effect" to "speed"), combination = EffectCombination.STACK),
        ),
        triggers = mapOf(
            RegionTrigger.ON_ENTER to listOf(
                ActionBlockConfig(
                    conditions = listOf(ConditionConfig("permission", mapOf("value" to "regions.enter"), negated = true)),
                    thenActions = listOf(ActionConfig("message", mapOf("text" to "Welcome"))),
                    execution = TriggerExecution.PRIMARY_REGION,
                ),
            ),
        ),
    )
}
