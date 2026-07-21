package org.cubexmc.regions.mode

import org.bukkit.Server
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.PlayerInventory
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.effect.ScopedEffectService
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.service.RegionAuditService
import org.cubexmc.regions.service.RegionRegistry
import org.cubexmc.regions.service.RegionSessionService
import org.cubexmc.regions.service.RegionTriggerService
import org.cubexmc.regions.service.ServiceResult
import org.cubexmc.scheduler.CubexScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

class ModeLifecycleServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `race timeout ends current race and stale timeout cannot end replacement race`() {
        val harness = harness()
        val region = region(
            "race",
            "run_race",
            mapOf(
                "min-players" to "1",
                "require-start" to "false",
                "vehicle" to "pass",
                "timeout-seconds" to "2",
            ),
        )
        val player = player("Runner")
        harness.register(region, player)
        val service = RaceModeService(harness.plugin)

        service.onEnter(player, region)
        assertTrue(service.ready(player, region.id))
        assertTrue(service.status(region.id).startsWith("race active"))
        val firstTimeout = harness.delayed.single { it.delay == 40L }.task

        assertTrue(service.forceEnd(region.id, "test-restart"))
        service.onEnter(player, region)
        assertTrue(service.ready(player, region.id))
        assertTrue(service.status(region.id).startsWith("race active"))

        firstTimeout.run()
        assertTrue(service.status(region.id).startsWith("race active"))

        val currentTimeout = harness.delayed.last { it.delay == 40L }.task
        currentTimeout.run()
        assertEquals("race idle", service.status(region.id))
    }

    @Test
    fun `round timers from an ended round cannot mutate replacement round`() {
        val harness = harness()
        val region = region(
            "hide",
            "hide_and_seek",
            mapOf(
                "min-players" to "2",
                "hide-seconds" to "5",
                "round-seconds" to "30",
                "replace-gear" to "false",
            ),
        )
        val first = player("First")
        val second = player("Second")
        harness.register(region, first, second)
        val service = RoundModeService(harness.plugin)

        startRound(service, region, first, second)
        val oldRelease = harness.delayed.single { it.delay == 100L }.task
        val oldTimeout = harness.delayed.single { it.delay == 600L }.task

        assertTrue(service.forceEnd(region.id, "test-restart"))
        startRound(service, region, first, second)
        assertTrue(service.status(region.id).contains("released=false"))

        oldRelease.run()
        oldTimeout.run()

        assertTrue(service.status(region.id).startsWith("round active"))
        assertTrue(service.status(region.id).contains("released=false"))
    }

    @Test
    fun `combat start task cannot replace gear after player leaves`() {
        val harness = harness(runEntityTasksImmediately = false)
        val region = region(
            "duel",
            "dual_pvp",
            mapOf(
                "min-players" to "1",
                "require-ready" to "false",
                "replace-gear" to "true",
                "kit" to "stone_sword:1",
            ),
        )
        val player = player("Fighter")
        val inventory = mock(PlayerInventory::class.java)
        `when`(player.inventory).thenReturn(inventory)
        harness.register(region, player)
        val service = CombatModeService(harness.plugin)

        service.onEnter(player, region)
        val staleStart = harness.entityTasks.single()
        service.onLeave(player, region.id, "left-before-start-task")

        staleStart.run()

        assertEquals("idle", service.status(region.id))
        verify(inventory, never()).clear()
    }

    @Test
    fun `race start validation rechecks minimum roster after a player leaves`() {
        val harness = harness(runEntityTasksImmediately = false)
        val region = region(
            "race-roster",
            "run_race",
            mapOf(
                "min-players" to "2",
                "require-start" to "false",
                "vehicle" to "pass",
            ),
        )
        val first = player("RunnerOne")
        val second = player("RunnerTwo")
        harness.register(region, first, second)
        val service = RaceModeService(harness.plugin)

        service.onEnter(first, region)
        service.onEnter(second, region)
        service.ready(first, region.id)
        service.ready(second, region.id)
        service.onLeave(second, region.id, "left-during-start-check")

        harness.entityTasks.toList().forEach(Runnable::run)

        assertTrue(service.status(region.id).startsWith("race waiting"))
    }

    private fun startRound(
        service: RoundModeService,
        region: RegionDefinition,
        first: Player,
        second: Player,
    ) {
        service.onEnter(first, region)
        service.onEnter(second, region)
        assertTrue(service.ready(first, region.id))
        assertTrue(service.ready(second, region.id))
        assertTrue(service.status(region.id).startsWith("round active"))
    }

    private fun region(id: String, type: String, values: Map<String, String>) = RegionDefinition(
        id = id,
        name = id,
        source = RegionSourceRef("cuboid"),
        mode = ModeConfig(type, values),
    )

    private fun player(name: String): Player = mock(Player::class.java).also {
        `when`(it.uniqueId).thenReturn(UUID.randomUUID())
        `when`(it.name).thenReturn(name)
    }

    private fun harness(runEntityTasksImmediately: Boolean = true): Harness {
        val plugin = mock(RegionsPlugin::class.java)
        val server = mock(Server::class.java)
        val scheduler = mock(CubexScheduler::class.java)
        val registry = mock(RegionRegistry::class.java)
        val sessions = mock(RegionSessionService::class.java)
        val triggers = mock(RegionTriggerService::class.java)
        val effects = mock(ScopedEffectService::class.java)
        val audit = mock(RegionAuditService::class.java)
        val delayed = mutableListOf<DelayedTask>()
        val entityTasks = mutableListOf<Runnable>()

        `when`(plugin.server).thenReturn(server)
        `when`(plugin.dataFolder).thenReturn(tempDir.toFile())
        `when`(plugin.logger).thenReturn(Logger.getLogger("ModeLifecycleServiceTest"))
        `when`(plugin.regionScheduler()).thenReturn(scheduler)
        `when`(plugin.regions()).thenReturn(registry)
        `when`(plugin.sessions()).thenReturn(sessions)
        `when`(plugin.triggers()).thenReturn(triggers)
        `when`(plugin.effects()).thenReturn(effects)
        `when`(plugin.audit()).thenReturn(audit)
        `when`(scheduler.isFolia).thenReturn(false)
        `when`(effects.apply(anyK(), anyK(), anyK()))
            .thenReturn(ServiceResult.ok())
        doAnswer { invocation ->
            val task = invocation.getArgument<Runnable>(1)
            entityTasks += task
            if (runEntityTasksImmediately) task.run()
            null
        }.`when`(scheduler).runAtEntity(any(Entity::class.java), any(Runnable::class.java))
        doAnswer { invocation ->
            delayed += DelayedTask(invocation.getArgument(0), invocation.getArgument(1))
            null
        }.`when`(scheduler).runGlobalLater(any(Runnable::class.java), anyLong())

        return Harness(plugin, server, registry, delayed, entityTasks)
    }

    private data class Harness(
        val plugin: RegionsPlugin,
        val server: Server,
        val registry: RegionRegistry,
        val delayed: MutableList<DelayedTask>,
        val entityTasks: MutableList<Runnable>,
    ) {
        fun register(region: RegionDefinition, vararg players: Player) {
            `when`(registry.find(region.id)).thenReturn(region)
            players.forEach { `when`(server.getPlayer(it.uniqueId)).thenReturn(it) }
        }
    }

    private data class DelayedTask(val task: Runnable, val delay: Long)

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyK(): T {
        org.mockito.Mockito.any<T>()
        return null as T
    }
}
