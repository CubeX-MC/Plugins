package org.cubexmc.regions.service

import org.bukkit.GameMode
import org.bukkit.Server
import org.bukkit.entity.Player
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.effect.DeclaredEffect
import org.cubexmc.regions.effect.ScopedEffectService
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.EffectScope
import org.cubexmc.regions.model.FlagConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.logging.Logger
import org.cubexmc.scheduler.CubexScheduler

class RegionSessionServiceTest {
    @Test
    fun `failed overlap application is retried while signature is unchanged`() {
        val plugin = mock(RegionsPlugin::class.java)
        val effects = mock(ScopedEffectService::class.java)
        val player = mock(Player::class.java)
        val playerId = UUID.randomUUID()
        val region = RegionDefinition(
            "arena",
            "Arena",
            RegionSourceRef("cuboid"),
            effects = listOf(EffectConfig("walk_speed", values = mapOf("value" to "0.4"))),
        )
        `when`(plugin.overlaps()).thenReturn(RegionOverlapResolver())
        `when`(plugin.logger).thenReturn(Logger.getLogger("RegionSessionServiceTest"))
        `when`(player.uniqueId).thenReturn(playerId)
        `when`(player.name).thenReturn("Tester")
        val expected = listOf(DeclaredEffect(region, region.effects.single()))
        `when`(effects.applyDeclaredSet(player, expected))
            .thenReturn(ServiceResult.fail("temporary failure"), ServiceResult.ok())
        val service = RegionSessionService(plugin, effects)

        service.reconcileEffects(player, listOf(region))
        service.reconcileEffects(player, listOf(region))

        verify(effects, times(2)).applyDeclaredSet(player, expected)
    }

    @Test
    fun `bridged deny flags are skipped for players who bypass flags`() {
        val plugin = mock(RegionsPlugin::class.java)
        val effects = mock(ScopedEffectService::class.java)
        val player = mock(Player::class.java)
        val playerId = UUID.randomUUID()
        val region = RegionDefinition(
            "arena",
            "Arena",
            RegionSourceRef("cuboid"),
            flags = mapOf("fly" to FlagConfig("fly", "deny")),
        )
        `when`(plugin.overlaps()).thenReturn(RegionOverlapResolver())
        `when`(plugin.logger).thenReturn(Logger.getLogger("RegionSessionServiceTest"))
        `when`(player.uniqueId).thenReturn(playerId)
        `when`(player.name).thenReturn("Tester")
        `when`(player.hasPermission("regions.bypass.flags")).thenReturn(true)
        `when`(effects.applyDeclaredSet(player, emptyList())).thenReturn(ServiceResult.ok())
        val service = RegionSessionService(plugin, effects)

        service.reconcileEffects(player, listOf(region))

        verify(effects).applyDeclaredSet(player, emptyList())
    }

    @Test
    fun `bridged fly deny is skipped in creative so the game mode is not fought`() {
        val plugin = mock(RegionsPlugin::class.java)
        val effects = mock(ScopedEffectService::class.java)
        val player = mock(Player::class.java)
        val playerId = UUID.randomUUID()
        val region = RegionDefinition(
            "arena",
            "Arena",
            RegionSourceRef("cuboid"),
            flags = mapOf("fly" to FlagConfig("fly", "deny"), "vanish" to FlagConfig("vanish", "deny")),
        )
        `when`(plugin.overlaps()).thenReturn(RegionOverlapResolver())
        `when`(plugin.logger).thenReturn(Logger.getLogger("RegionSessionServiceTest"))
        `when`(player.uniqueId).thenReturn(playerId)
        `when`(player.name).thenReturn("Tester")
        `when`(player.gameMode).thenReturn(GameMode.CREATIVE)
        val expected = listOf(
            DeclaredEffect(region, EffectConfig("invisibility_suppression", EffectScope.WHILE_INSIDE)),
        )
        `when`(effects.applyDeclaredSet(player, expected)).thenReturn(ServiceResult.ok())
        val service = RegionSessionService(plugin, effects)

        service.reconcileEffects(player, listOf(region))

        // vanish still applies; only the flight bridge is skipped.
        verify(effects).applyDeclaredSet(player, expected)
    }

    @Test
    fun `paper shutdown cleanup restores online players synchronously`() {
        val plugin = mock(RegionsPlugin::class.java)
        val effects = mock(ScopedEffectService::class.java)
        val player = mock(Player::class.java)
        val server = mock(Server::class.java)
        val scheduler = mock(CubexScheduler::class.java)
        val playerId = UUID.randomUUID()
        val region = RegionDefinition("arena", "Arena", RegionSourceRef("cuboid"))
        `when`(plugin.server).thenReturn(server)
        `when`(plugin.regionScheduler()).thenReturn(scheduler)
        `when`(server.onlinePlayers).thenReturn(setOf(player))
        `when`(scheduler.isFolia).thenReturn(false)
        `when`(player.uniqueId).thenReturn(playerId)
        val service = RegionSessionService(plugin, effects)
        service.enter(player, region, modeActive = false)

        service.cleanupAll("plugin-disable", shuttingDown = true)

        assertTrue(service.activeSessions(playerId).isEmpty())
        verify(effects).cleanupPlayer(player, "plugin-disable")
    }
}
