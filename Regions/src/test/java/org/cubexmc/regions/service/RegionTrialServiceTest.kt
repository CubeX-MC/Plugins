package org.cubexmc.regions.service

import org.bukkit.entity.Player
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.effect.ScopedEffectService
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.model.RegionSourceRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

class RegionTrialServiceTest {
    private lateinit var plugin: RegionsPlugin
    private lateinit var publishing: RegionPublishingService
    private lateinit var authority: RegionAuthorityService
    private lateinit var effects: ScopedEffectService
    private lateinit var audit: RegionAuditService
    private lateinit var player: Player
    private lateinit var service: RegionTrialService
    private val playerId = UUID.randomUUID()
    private val draft = RegionDefinition(
        id = "arena",
        name = "Arena",
        source = RegionSourceRef("lands"),
        lifecycle = RegionLifecycle.DRAFT,
        revision = 4,
        effects = listOf(EffectConfig("walk_speed", values = mapOf("value" to "0.5"))),
    )

    @BeforeEach
    fun setUp() {
        plugin = mock(RegionsPlugin::class.java)
        publishing = mock(RegionPublishingService::class.java)
        authority = mock(RegionAuthorityService::class.java)
        effects = mock(ScopedEffectService::class.java)
        audit = mock(RegionAuditService::class.java)
        player = mock(Player::class.java)
        `when`(plugin.publishing()).thenReturn(publishing)
        `when`(plugin.authority()).thenReturn(authority)
        `when`(plugin.effects()).thenReturn(effects)
        `when`(plugin.audit()).thenReturn(audit)
        `when`(plugin.overlaps()).thenReturn(RegionOverlapResolver())
        `when`(player.uniqueId).thenReturn(playerId)
        `when`(publishing.draft("arena")).thenReturn(draft)
        `when`(publishing.previewIssues(player, "arena")).thenReturn(emptyList())
        `when`(authority.canManage(player, draft)).thenReturn(AuthorityDecision.allow())
        val syntheticId = "trial_${playerId.toString().replace("-", "").take(12)}"
        val synthetic = draft.copy(id = syntheticId, name = "${draft.name} [Trial]")
        `when`(effects.apply(player, synthetic, draft.effects.first())).thenReturn(ServiceResult.ok())
        service = RegionTrialService(plugin)
    }

    @Test
    fun `trial applies only to a synthetic player-local region and restores on stop`() {
        val started = service.start(player, "arena")

        assertTrue(started.success)
        val trial = service.active(playerId)
        assertNotNull(trial)
        assertEquals(draft, service.activeDraft(playerId))
        val stopped = service.stop(player, "test-stop")

        assertTrue(stopped.success)
        verify(effects).cleanupRegion(player, trial!!.syntheticRegionId, "test-stop")
        assertEquals(null, service.active(playerId))
    }

    @Test
    fun `trial rolls back when a declared effect cannot be applied`() {
        val syntheticId = "trial_${playerId.toString().replace("-", "").take(12)}"
        val synthetic = draft.copy(id = syntheticId, name = "${draft.name} [Trial]")
        `when`(effects.apply(player, synthetic, draft.effects.first()))
            .thenReturn(ServiceResult.fail("unsupported effect"))

        val started = service.start(player, "arena")

        assertFalse(started.success)
        assertTrue(started.reason.contains("rolled back"))
        assertEquals(null, service.active(playerId))
        verify(effects).cleanupRegion(player, syntheticId, "trial-start-failed")
    }
}
