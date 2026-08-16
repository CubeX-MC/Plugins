package org.cubexmc.statecharge.service

import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.RegisteredServiceProvider
import org.bukkit.plugin.ServicesManager
import org.cubexmc.core.CubexLogger
import org.cubexmc.scheduler.CubexScheduler
import org.cubexmc.statecharge.StateChargePlugin
import org.cubexmc.statecharge.config.StateDefinitions
import org.cubexmc.statecharge.economy.EconomyService
import org.cubexmc.statecharge.effect.StateEffect
import org.cubexmc.statecharge.model.StateSpec
import org.cubexmc.statecharge.storage.StateStorage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyDouble
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.File
import java.math.BigDecimal
import java.util.UUID
import java.util.logging.Logger

class StateChargeServiceTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var plugin: StateChargePlugin
    private lateinit var vaultEconomy: Economy
    private lateinit var economy: EconomyService
    private lateinit var scheduler: CubexScheduler
    private lateinit var definitions: StateDefinitions
    private lateinit var notifier: I18nStateNotifier
    private lateinit var storage: StateStorage
    private lateinit var service: StateChargeService

    private val smallEffect = RecordingEffect()
    private val giantEffect = RecordingEffect()
    private val flyEffect = RecordingEffect()
    private val lockedEffect = RecordingEffect()
    private val offEffect = RecordingEffect()

    private val smallSpec = StateSpec(
        "small", "变小", BigDecimal.valueOf(100.0), 1800L, 21600L, null, "scale", smallEffect, true,
    )
    private val giantSpec = StateSpec(
        "giant", "变大", BigDecimal.valueOf(100.0), 1800L, 21600L, null, "scale", giantEffect, true,
    )
    private val flySpec = StateSpec(
        "fly", "飞行", BigDecimal.valueOf(200.0), 3600L, 86400L, null, "fly", flyEffect, true,
    )
    private val lockedSpec = StateSpec(
        "locked", "locked", BigDecimal.TEN, 1800L, 0L, "statecharge.buy.locked", null, lockedEffect, true,
    )
    private val offSpec = StateSpec(
        "off", "off", BigDecimal.ZERO, 1800L, 0L, null, null, offEffect, false,
    )

    @BeforeEach
    fun setUp() {
        plugin = mock(StateChargePlugin::class.java)
        vaultEconomy = mock(Economy::class.java)
        scheduler = mock(CubexScheduler::class.java)
        definitions = mock(StateDefinitions::class.java)
        notifier = mock(I18nStateNotifier::class.java)
        storage = StateStorage(File(tempDir, "states.yml"), CubexLogger(Logger.getLogger("statecharge-test")))

        `when`(plugin.storage()).thenReturn(storage)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(plugin.definitions()).thenReturn(definitions)
        `when`(plugin.notifier()).thenReturn(notifier)
        `when`(plugin.log()).thenReturn(mock(CubexLogger::class.java))

        // 不 mock EconomyService(Kotlin 非空参数会拦下 Mockito 的 null matcher),而是 mock 它包裹的
        // Vault Economy 接口(Java 接口无 null 检查),走真实 has/withdraw 逻辑,测试保真度更高。
        economy = EconomyService(plugin)
        mockStatic(Bukkit::class.java).use { bukkit ->
            val pluginManager = mock(PluginManager::class.java)
            `when`(Bukkit.getPluginManager()).thenReturn(pluginManager)
            `when`(pluginManager.getPlugin("Vault")).thenReturn(mock(Plugin::class.java))
            val services = mock(ServicesManager::class.java)
            `when`(Bukkit.getServicesManager()).thenReturn(services)
            @Suppress("UNCHECKED_CAST")
            val registration = mock(RegisteredServiceProvider::class.java) as RegisteredServiceProvider<Economy>
            `when`(services.getRegistration(Economy::class.java)).thenReturn(registration)
            `when`(registration.provider).thenReturn(vaultEconomy)
            assertTrue(economy.setup())
        }
        `when`(plugin.economy()).thenReturn(economy)

        `when`(definitions.byId("small")).thenReturn(smallSpec)
        `when`(definitions.byId("giant")).thenReturn(giantSpec)
        `when`(definitions.byId("fly")).thenReturn(flySpec)
        `when`(definitions.byId("locked")).thenReturn(lockedSpec)
        `when`(definitions.byId("off")).thenReturn(offSpec)

        // 测试里 runAtEntity 直接内联执行,模拟"已经落到玩家区域"。
        `when`(scheduler.runAtEntity(anyK<Entity>(), anyK<Runnable>())).thenAnswer { invocation ->
            (invocation.getArgument<Any>(1) as Runnable).run()
            null
        }

        service = StateChargeService(plugin)
    }

    private fun player(uuid: UUID = UUID.randomUUID(), hasPermission: Boolean = true): Player {
        val p = mock(Player::class.java)
        `when`(p.uniqueId).thenReturn(uuid)
        `when`(p.hasPermission(anyString())).thenReturn(hasPermission)
        return p
    }

    private fun offline(uuid: UUID, online: Player?): OfflinePlayer {
        val o = mock(OfflinePlayer::class.java)
        `when`(o.uniqueId).thenReturn(uuid)
        `when`(o.player).thenReturn(online)
        return o
    }

    private fun economyOk() {
        `when`(vaultEconomy.has(any(Player::class.java), anyDouble())).thenReturn(true)
        `when`(vaultEconomy.withdrawPlayer(any(Player::class.java), anyDouble()))
            .thenReturn(EconomyResponse(100.0, 0.0, EconomyResponse.ResponseType.SUCCESS, ""))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyK(): T {
        any<T>()
        return null as T
    }

    @Test
    fun buySuccessStartsEffectAndStoresDuration() {
        economyOk()
        val p = player()

        val result = service.buy(p, "small", 1)

        assertTrue(result.success())
        assertEquals(1800L, storage.remaining(p.uniqueId, "small"))
        assertEquals(1800L, result.addedSeconds())
        assertEquals(1, smallEffect.startCount)
        assertEquals(0, smallEffect.reapplyCount)
    }

    @Test
    fun buyStacksRemainingAndReappliesInsteadOfStarting() {
        economyOk()
        val p = player()
        storage.setRemaining(p.uniqueId, "small", 100L)

        service.buy(p, "small", 1)

        assertEquals(1900L, storage.remaining(p.uniqueId, "small"))
        assertEquals(0, smallEffect.startCount)
        assertEquals(1, smallEffect.reapplyCount)
    }

    @Test
    fun buyMultipleCounts() {
        economyOk()
        val p = player()

        val result = service.buy(p, "small", 3)

        assertTrue(result.success())
        assertEquals(5400L, storage.remaining(p.uniqueId, "small"))
        assertEquals(BigDecimal.valueOf(300.0), result.price())
    }

    @Test
    fun buyInsufficientFundsDoesNotWithdraw() {
        `when`(vaultEconomy.has(any(Player::class.java), anyDouble())).thenReturn(false)
        val p = player()

        val result = service.buy(p, "small", 1)

        assertEquals(BuyResult.Failure.INSUFFICIENT_FUNDS, result.failure())
        assertEquals(0L, storage.remaining(p.uniqueId, "small"))
        verify(vaultEconomy, never()).withdrawPlayer(any(Player::class.java), anyDouble())
    }

    @Test
    fun buyEconomyFailureKeepsReason() {
        economyOk()
        `when`(vaultEconomy.withdrawPlayer(any(Player::class.java), anyDouble()))
            .thenReturn(EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.FAILURE, "provider down"))
        val p = player()

        val result = service.buy(p, "small", 1)

        assertEquals(BuyResult.Failure.ECONOMY_FAILED, result.failure())
        assertEquals("provider down", result.reason())
        assertEquals(0L, storage.remaining(p.uniqueId, "small"))
    }

    @Test
    fun buyConflictingGroupIsRejected() {
        economyOk()
        val p = player()
        storage.setRemaining(p.uniqueId, "giant", 50L)

        val result = service.buy(p, "small", 1)

        assertEquals(BuyResult.Failure.CONFLICT, result.failure())
        assertEquals("giant", result.conflictStateId())
        assertEquals(0L, storage.remaining(p.uniqueId, "small"))
    }

    @Test
    fun buyMaxStackIsRejected() {
        economyOk()
        val p = player()
        storage.setRemaining(p.uniqueId, "small", 21500L)

        val result = service.buy(p, "small", 1)

        assertEquals(BuyResult.Failure.MAX_STACK, result.failure())
        assertEquals(21500L, storage.remaining(p.uniqueId, "small"))
    }

    @Test
    fun buyDisabledIsRejected() {
        economyOk()
        val p = player()

        val result = service.buy(p, "off", 1)

        assertEquals(BuyResult.Failure.DISABLED, result.failure())
    }

    @Test
    fun buyUnknownStateIsRejected() {
        val p = player()
        val result = service.buy(p, "nope", 1)
        assertEquals(BuyResult.Failure.UNKNOWN_STATE, result.failure())
    }

    @Test
    fun buyPermissionDeniedIsRejected() {
        economyOk()
        val p = player(hasPermission = false)

        val result = service.buy(p, "locked", 1)

        assertEquals(BuyResult.Failure.NO_PERMISSION, result.failure())
    }

    @Test
    fun buyInvalidCountIsRejected() {
        economyOk()
        val p = player()

        assertEquals(BuyResult.Failure.INVALID_COUNT, service.buy(p, "small", 0).failure())
        assertEquals(BuyResult.Failure.INVALID_COUNT, service.buy(p, "small", 1001).failure())
    }

    @Test
    fun tickDecrementsNotifiesAndExpires() {
        val uuid = UUID.randomUUID()
        storage.setRemaining(uuid, "small", 2L)
        val p = player(uuid)

        mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Collection<out Player>> { Bukkit.getOnlinePlayers() }.thenReturn(listOf(p))

            service.tick(1)
            assertEquals(1L, storage.remaining(uuid, "small"))
            verify(notifier).onTick(p, "small", 1L)
            assertEquals(0, smallEffect.removeCount)

            service.tick(1)
            assertEquals(0L, storage.remaining(uuid, "small"))
            verify(notifier).expired(p, "small")
            assertEquals(1, smallEffect.removeCount)
        }
    }

    @Test
    fun tickSkipsPlayersWithoutActiveStates() {
        val uuid = UUID.randomUUID()
        storage.setRemaining(uuid, "small", 10L)
        val withStates = player(uuid)
        val withoutStates = player()

        mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Collection<out Player>> { Bukkit.getOnlinePlayers() }
                .thenReturn(listOf(withStates, withoutStates))

            service.tick(5)

            assertEquals(5L, storage.remaining(uuid, "small"))
        }
    }

    @Test
    fun tickUsesAtLeastOneSecondStep() {
        val uuid = UUID.randomUUID()
        storage.setRemaining(uuid, "fly", 10L)
        val p = player(uuid)

        mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Collection<out Player>> { Bukkit.getOnlinePlayers() }.thenReturn(listOf(p))
            service.tick(0)
        }
        assertEquals(9L, storage.remaining(uuid, "fly"))
    }

    @Test
    fun giveAddsAndStartsEffectWhenOnline() {
        val uuid = UUID.randomUUID()
        val p = player(uuid)

        val result = service.give(offline(uuid, p), "small", 900L)

        assertTrue(result.success())
        assertEquals(900L, storage.remaining(uuid, "small"))
        assertEquals(1, smallEffect.startCount)
    }

    @Test
    fun giveOfflineTargetPersistsWithoutEffect() {
        val uuid = UUID.randomUUID()

        val result = service.give(offline(uuid, null), "fly", 3600L)

        assertTrue(result.success())
        assertEquals(3600L, storage.remaining(uuid, "fly"))
        assertEquals(0, flyEffect.startCount)
    }

    @Test
    fun giveUnknownStateFails() {
        val uuid = UUID.randomUUID()
        val result = service.give(offline(uuid, null), "nope", 100L)
        assertEquals(GiveResult.Failure.UNKNOWN_STATE, result.failure())
    }

    @Test
    fun giveInvalidSecondsFails() {
        val uuid = UUID.randomUUID()
        val result = service.give(offline(uuid, null), "small", 0L)
        assertEquals(GiveResult.Failure.INVALID_SECONDS, result.failure())
    }

    @Test
    fun giveBypassesMaxStack() {
        val uuid = UUID.randomUUID()
        storage.setRemaining(uuid, "small", 21600L)
        service.give(offline(uuid, null), "small", 7200L)
        assertEquals(28800L, storage.remaining(uuid, "small"))
    }

    @Test
    fun clearAllRemovesEffectsAndState() {
        val uuid = UUID.randomUUID()
        val p = player(uuid)
        storage.setRemaining(uuid, "small", 100L)
        storage.setRemaining(uuid, "fly", 200L)

        val cleared = service.clear(offline(uuid, p), null)

        assertEquals(2, cleared)
        assertTrue(storage.active(uuid).isEmpty())
        assertEquals(1, smallEffect.removeCount)
        assertEquals(1, flyEffect.removeCount)
    }

    @Test
    fun clearSingleStateLeavesOthersAlone() {
        val uuid = UUID.randomUUID()
        val p = player(uuid)
        storage.setRemaining(uuid, "small", 100L)
        storage.setRemaining(uuid, "fly", 200L)

        val cleared = service.clear(offline(uuid, p), "fly")

        assertEquals(1, cleared)
        assertEquals(mapOf("small" to 100L), storage.active(uuid))
        assertEquals(0, smallEffect.removeCount)
        assertEquals(1, flyEffect.removeCount)
    }

    @Test
    fun clearWithoutActiveStatesReturnsZero() {
        val uuid = UUID.randomUUID()
        assertEquals(0, service.clear(offline(uuid, null), null))
    }

    @Test
    fun applyAllReappliesEveryActiveEffect() {
        val uuid = UUID.randomUUID()
        storage.setRemaining(uuid, "small", 100L)
        storage.setRemaining(uuid, "fly", 200L)
        val p = player(uuid)

        service.applyAll(p)

        assertEquals(1, smallEffect.reapplyCount)
        assertEquals(1, flyEffect.reapplyCount)
    }

    private class RecordingEffect : StateEffect {
        var startCount = 0
            private set
        var reapplyCount = 0
            private set
        var removeCount = 0
            private set

        override fun start(player: Player) {
            startCount++
        }

        override fun reapply(player: Player) {
            reapplyCount++
        }

        override fun remove(player: Player) {
            removeCount++
        }
    }
}
