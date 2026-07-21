package org.cubexmc.regions.effect

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.EffectScope
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.logging.Logger
import java.nio.file.Path

class ScopedEffectServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var plugin: RegionsPlugin
    private lateinit var player: Player
    private lateinit var service: ScopedEffectService
    private val playerId = UUID.randomUUID()
    private val region = RegionDefinition(
        id = "arena",
        name = "Arena",
        source = RegionSourceRef("lands", mapOf("land" to "capital", "area" to "arena")),
    )

    @BeforeEach
    fun setUp() {
        plugin = mock(RegionsPlugin::class.java)
        player = mock(Player::class.java)
        val config = YamlConfiguration().apply {
            set("effects.default-duration-ticks", 400)
        }
        `when`(plugin.config).thenReturn(config)
        `when`(plugin.logger).thenReturn(Logger.getLogger("ScopedEffectServiceTest"))
        `when`(plugin.dataFolder).thenReturn(tempDir.toFile())
        `when`(player.uniqueId).thenReturn(playerId)
        `when`(player.name).thenReturn("Tester")
        service = ScopedEffectService(plugin).apply { registerDefaults() }
    }

    @Test
    fun `until mode end effects restore while inside effects remain managed`() {
        `when`(player.walkSpeed).thenReturn(0.2f)
        val result = service.apply(
            player,
            region,
            EffectConfig("walk_speed", EffectScope.UNTIL_MODE_END, mapOf("value" to "0.6")),
        )

        assertTrue(result.success)
        assertEquals(1, service.cleanupModeEffects(player, region.id, "test-mode-end"))
        verify(player).walkSpeed = 0.6f
        verify(player).walkSpeed = 0.2f
    }

    @Test
    fun `timed effects expire during refresh and restore their snapshot`() {
        `when`(player.allowFlight).thenReturn(false)
        `when`(player.isFlying).thenReturn(false)
        val result = service.apply(
            player,
            region,
            EffectConfig(
                "allow_flight",
                EffectScope.TIMED,
                mapOf("value" to "true", "lease-duration-ticks" to "20"),
            ),
        )

        assertTrue(result.success)
        service.refreshPlayer(player, Long.MAX_VALUE)
        verify(player).allowFlight = true
        verify(player).allowFlight = false
    }

    @Test
    fun `declared overlap effects can be reconciled without clearing trigger effects`() {
        `when`(player.walkSpeed).thenReturn(0.2f)
        `when`(player.flySpeed).thenReturn(0.1f)
        service.applyDeclared(
            player,
            region,
            EffectConfig("walk_speed", values = mapOf("value" to "0.6")),
        )
        service.apply(
            player,
            region,
            EffectConfig("fly_speed", values = mapOf("value" to "0.4")),
        )

        assertEquals(1, service.cleanupDeclaredEffects(player, "overlap-change"))
        verify(player).walkSpeed = 0.2f
        assertEquals(1, service.cleanupPlayer(player, "final-cleanup"))
        verify(player).flySpeed = 0.1f
    }

    @Test
    fun `persisted leases restore after service recreation`() {
        `when`(player.walkSpeed).thenReturn(0.2f)
        assertTrue(service.apply(
            player,
            region,
            EffectConfig("walk_speed", values = mapOf("value" to "0.7")),
        ).success)

        val recreated = ScopedEffectService(plugin).apply { registerDefaults() }
        assertEquals(1, recreated.pendingLeaseCount(playerId))
        assertEquals(1, recreated.restoreIfPending(player, "restart-test"))
        assertEquals(0, recreated.pendingLeaseCount(playerId))
        verify(player).walkSpeed = 0.2f
    }
}
