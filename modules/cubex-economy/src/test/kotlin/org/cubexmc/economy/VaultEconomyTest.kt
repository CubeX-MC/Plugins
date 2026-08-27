package org.cubexmc.economy

import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.OfflinePlayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyDouble
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.cubexmc.core.CubexLogger
import java.math.BigDecimal
import java.util.UUID
import java.util.logging.Logger

class VaultEconomyTest {

    private lateinit var economy: Economy
    private lateinit var payer: OfflinePlayer
    private lateinit var bankHolder: OfflinePlayer

    private val bankUuid: UUID = UUID.fromString("6b1a3f2c-8d4e-4f7a-9b0c-1d2e3f4a5b6c")
    private val amount: BigDecimal = BigDecimal("100.00")

    @BeforeEach
    fun setUp() {
        economy = mock(Economy::class.java)
        `when`(economy.name).thenReturn("TestEconomy")
        `when`(economy.withdrawPlayer(any(OfflinePlayer::class.java), anyDouble())).thenReturn(success())
        `when`(economy.depositPlayer(any(OfflinePlayer::class.java), anyDouble())).thenReturn(success())
        `when`(economy.depositPlayer(anyString(), anyDouble())).thenReturn(success())
        `when`(economy.bankDeposit(anyString(), anyDouble())).thenReturn(success())
        `when`(economy.bankBalance(anyString())).thenReturn(success())
        `when`(economy.hasBankSupport()).thenReturn(true)
        `when`(economy.hasAccount(any(OfflinePlayer::class.java))).thenReturn(true)
        `when`(economy.format(anyDouble())).thenAnswer { "$" + it.getArgument<Double>(0) }

        payer = player(UUID.randomUUID(), "Steve")
        bankHolder = player(bankUuid, "cubex_bank")
    }

    // ---- 路由 ----

    @Test
    fun `an unconfigured account keeps the old behaviour of destroying the money`() {
        val vault = economyWith(EconomyAccount.None)

        val result = vault.charge(payer, amount)

        assertTrue(result.success())
        assertFalse(result.depositFailed())
        verify(economy).withdrawPlayer(payer, 100.0)
        verify(economy, never()).depositPlayer(any(OfflinePlayer::class.java), anyDouble())
        verify(economy, never()).bankDeposit(anyString(), anyDouble())
    }

    @Test
    fun `a player account receives exactly what the payer was charged`() {
        val vault = economyWith(EconomyAccount.PlayerUuid(bankUuid))

        val result = vault.charge(payer, amount)

        assertTrue(result.success())
        assertFalse(result.depositFailed())
        verify(economy).withdrawPlayer(payer, 100.0)
        verify(economy).depositPlayer(bankHolder, 100.0)
    }

    @Test
    fun `a name configured account resolves through the lookup`() {
        val vault = economyWith(EconomyAccount.PlayerName("cubex_bank"))

        vault.charge(payer, amount)

        verify(economy).depositPlayer(bankHolder, 100.0)
    }

    @Test
    fun `a vault bank account is credited through the bank api`() {
        val vault = economyWith(EconomyAccount.Bank("CubeXBank"))

        val result = vault.charge(payer, amount)

        assertTrue(result.success())
        verify(economy).bankDeposit("CubeXBank", 100.0)
        verify(economy, never()).depositPlayer(any(OfflinePlayer::class.java), anyDouble())
    }

    // ---- 失败路径 ----

    @Test
    fun `nothing is deposited when the player cannot pay`() {
        `when`(economy.withdrawPlayer(any(OfflinePlayer::class.java), anyDouble()))
            .thenReturn(failure("Insufficient funds"))
        val vault = economyWith(EconomyAccount.PlayerUuid(bankUuid))

        val result = vault.charge(payer, amount)

        assertFalse(result.success())
        assertEquals("Insufficient funds", result.reason())
        verify(economy, never()).depositPlayer(any(OfflinePlayer::class.java), anyDouble())
    }

    @Test
    fun `a failed deposit is reported but never rolled back onto the payer`() {
        // 玩家已经用掉了服务,退款等于白送 —— 这条不变量由 charge 的注释背书。
        `when`(economy.depositPlayer(any(OfflinePlayer::class.java), anyDouble()))
            .thenReturn(failure("bank account locked"))
        val vault = economyWith(EconomyAccount.PlayerUuid(bankUuid))

        val result = vault.charge(payer, amount)

        assertTrue(result.success())
        assertTrue(result.depositFailed())
        assertEquals("bank account locked", result.reason())
        verify(economy).withdrawPlayer(payer, 100.0)
        verify(economy, never()).depositPlayer(payer, 100.0)
    }

    @Test
    fun `a bank account is unusable when the economy plugin has no bank support`() {
        `when`(economy.hasBankSupport()).thenReturn(false)
        val vault = economyWith(EconomyAccount.Bank("CubeXBank"))

        assertFalse(vault.accountUsable())

        val result = vault.charge(payer, amount)

        assertTrue(result.success())
        assertTrue(result.depositFailed())
        verify(economy, never()).bankDeposit(anyString(), anyDouble())
    }

    @Test
    fun `an unknown player name is refused instead of guessing an offline uuid`() {
        val vault = economyWith(EconomyAccount.PlayerName("never_seen"))

        assertFalse(vault.accountUsable())

        val result = vault.charge(payer, amount)

        assertTrue(result.success())
        assertTrue(result.depositFailed())
        verify(economy, never()).depositPlayer(any(OfflinePlayer::class.java), anyDouble())
    }

    @Test
    fun `a raw name goes straight to the vault name overload without resolving a uuid`() {
        // 从不登录的虚拟银行账户走这条:UUID 解析不出来也不影响,由经济插件认账户。
        `when`(economy.hasAccount("cubex_bank")).thenReturn(true)
        val vault = economyWith(EconomyAccount.RawName("cubex_bank"))

        val result = vault.charge(payer, amount)

        assertTrue(result.success())
        assertFalse(result.depositFailed())
        verify(economy).depositPlayer("cubex_bank", 100.0)
        verify(economy, never()).depositPlayer(any(OfflinePlayer::class.java), anyDouble())
    }

    @Test
    fun `a raw name still routes even if the economy plugin does not know it yet`() {
        // 账户还没建时只警告,不把目标标成坏的 —— 经济插件通常首次入账就会创建。
        `when`(economy.hasAccount("cubex_bank")).thenReturn(false)
        val vault = economyWith(EconomyAccount.RawName("cubex_bank"))

        assertTrue(vault.accountUsable())
        vault.charge(payer, amount)

        verify(economy).depositPlayer("cubex_bank", 100.0)
    }

    @Test
    fun `a fabricated offline uuid is refused instead of being paid into`() {
        // profile 查询失败时 Bukkit 会按名字编造一个 v3 UUID —— 那是另一个账户。
        val vault = economyWith(EconomyAccount.PlayerName("never_joined"))

        assertFalse(vault.accountUsable())

        val result = vault.charge(payer, amount)

        assertTrue(result.success())
        assertTrue(result.depositFailed())
        verify(economy, never()).depositPlayer(any(OfflinePlayer::class.java), anyDouble())
        verify(economy, never()).depositPlayer(anyString(), anyDouble())
    }

    // ---- 金额边界 ----

    @Test
    fun `a zero charge touches neither side`() {
        val vault = economyWith(EconomyAccount.PlayerUuid(bankUuid))

        assertTrue(vault.charge(payer, BigDecimal.ZERO).success())

        verify(economy, never()).withdrawPlayer(any(OfflinePlayer::class.java), anyDouble())
        verify(economy, never()).depositPlayer(any(OfflinePlayer::class.java), anyDouble())
    }

    @Test
    fun `a negative charge is refused`() {
        val vault = economyWith(EconomyAccount.PlayerUuid(bankUuid))

        assertFalse(vault.charge(payer, BigDecimal("-1")).success())

        verify(economy, never()).withdrawPlayer(any(OfflinePlayer::class.java), anyDouble())
    }

    @Test
    fun `enable does not crash when the economy plugin returns nulls`() {
        // Vault 的 getName / bankBalance 都没有可空标注 —— Kotlin 拿到的是 platform type,
        // 当成非空用就会在 enable 路径上抛 NPE,插件直接起不来。
        `when`(economy.name).thenReturn(null)
        `when`(economy.hasBankSupport()).thenReturn(true)
        `when`(economy.bankBalance(anyString())).thenReturn(null)

        val vault = newEconomy()
        vault.useAccount(EconomyAccount.Bank("CubeXBank"))

        assertEquals("unknown economy provider", vault.provider())
        assertTrue(vault.accountUsable())
    }

    @Test
    fun `a null response from the economy plugin is a failure, not a crash`() {
        `when`(economy.withdrawPlayer(any(OfflinePlayer::class.java), anyDouble())).thenReturn(null)
        val vault = economyWith(EconomyAccount.PlayerUuid(bankUuid))

        val result = vault.charge(payer, amount)

        assertFalse(result.success())
        verify(economy, never()).depositPlayer(any(OfflinePlayer::class.java), anyDouble())
    }

    @Test
    fun `amount formatting falls back when the economy plugin returns null`() {
        // 入账失败的警告里就要调 format;那里再抛 NPE 会把"钱丢了"的线索一起吃掉。
        `when`(economy.format(anyDouble())).thenReturn(null)
        val vault = economyWith(EconomyAccount.None)

        assertEquals("100.00", vault.format(amount))
    }

    // ---- reload ----

    @Test
    fun `an unchanged spec is not resolved twice so reload does not repeat the profile lookup`() {
        val vault = newEconomy()
        vault.useAccount(EconomyAccount.PlayerName("cubex_bank"))
        vault.useAccount(EconomyAccount.PlayerName("cubex_bank"))

        // 解析一次会查一次 hasAccount;第二次 useAccount 应当整个跳过。
        verify(economy).hasAccount(bankHolder)
    }

    @Test
    fun `a broken target is retried on reload so a fixed profile service can recover it`() {
        val vault = newEconomy()
        vault.useAccount(EconomyAccount.RawName("cubex_bank"))
        assertTrue(vault.accountUsable())

        // Broken 的目标每次 useAccount 都重解析:bank 支持恢复后一次 reload 就能救回来。
        `when`(economy.hasBankSupport()).thenReturn(false)
        vault.useAccount(EconomyAccount.Bank("CubeXBank"))
        assertFalse(vault.accountUsable())

        `when`(economy.hasBankSupport()).thenReturn(true)
        vault.useAccount(EconomyAccount.Bank("CubeXBank"))
        assertTrue(vault.accountUsable())
    }

    @Test
    fun `useAccount replaces the target so reload can retarget the money`() {
        val vault = economyWith(EconomyAccount.None)
        vault.charge(payer, amount)
        verify(economy, never()).depositPlayer(any(OfflinePlayer::class.java), anyDouble())

        vault.useAccount(EconomyAccount.PlayerUuid(bankUuid))
        vault.charge(payer, amount)

        verify(economy).depositPlayer(bankHolder, 100.0)
    }

    @Test
    fun `legacy transfer names use a recognised virtual Vault account without fabricating a player`() {
        val lookup = mock(OfflinePlayerLookup::class.java)
        `when`(lookup.byUuid(payer.uniqueId)).thenReturn(payer)
        `when`(lookup.knownByName("cubex_bank")).thenReturn(NameLookup.Unknown)
        `when`(economy.hasAccount("cubex_bank")).thenReturn(true)
        `when`(economy.has(payer, 100.0)).thenReturn(true)

        assertEquals(VaultTransfers.Result.SUCCESS, VaultTransfers(economy, lookup)
            .transfer("uuid:${payer.uniqueId}", "cubex_bank", 100.0))

        verify(economy).depositPlayer("cubex_bank", 100.0)
        verify(lookup, never()).byName("cubex_bank")
        verify(economy, never()).depositPlayer(bankHolder, 100.0)
    }

    @Test
    fun `explicit named transfers never resolve Bukkit players`() {
        val lookup = mock(OfflinePlayerLookup::class.java)
        `when`(economy.has("payer", 100.0)).thenReturn(true)
        `when`(economy.withdrawPlayer("payer", 100.0)).thenReturn(success())

        assertEquals(VaultTransfers.Result.SUCCESS,
            VaultTransfers(economy, lookup).transfer("name:payer", "name:cubex_bank", 100.0))

        org.mockito.Mockito.verifyNoInteractions(lookup)
        verify(economy).depositPlayer("cubex_bank", 100.0)
    }

    @Test
    fun `unknown fabricated transfer recipient stops before any withdrawal`() {
        val lookup = mock(OfflinePlayerLookup::class.java)
        `when`(lookup.knownByName("unknown")).thenReturn(NameLookup.Unknown)
        `when`(lookup.byName("unknown")).thenReturn(NameLookup.Fabricated)

        assertEquals(VaultTransfers.Result.FAILED,
            VaultTransfers(economy, lookup).transfer("name:payer", "unknown", 100.0))

        verify(economy, never()).withdrawPlayer(anyString(), anyDouble())
    }

    @Test
    fun `a known online player keeps its UUID route ahead of a same-name account`() {
        val lookup = mock(OfflinePlayerLookup::class.java)
        `when`(lookup.knownByName("Steve")).thenReturn(NameLookup.Found(payer, "online player"))
        `when`(economy.has(payer, 100.0)).thenReturn(true)

        assertEquals(VaultTransfers.Result.SUCCESS,
            VaultTransfers(economy, lookup).transfer("Steve", "name:cubex_bank", 100.0))

        verify(economy).withdrawPlayer(payer, 100.0)
        verify(lookup, never()).byName(anyString())
    }

    @Test
    fun `ambiguous transfer deposit requires reconciliation and is not blindly refunded`() {
        `when`(economy.has("payer", 100.0)).thenReturn(true)
        `when`(economy.withdrawPlayer("payer", 100.0)).thenReturn(success())
        `when`(economy.depositPlayer("cubex_bank", 100.0)).thenThrow(IllegalStateException("provider unavailable"))

        assertEquals(VaultTransfers.Result.REVIEW_REQUIRED,
            VaultTransfers(economy).transfer("name:payer", "name:cubex_bank", 100.0))

        verify(economy, never()).depositPlayer("payer", 100.0)
    }

    @Test
    fun `null transfer deposit response does not trigger a speculative refund`() {
        `when`(economy.has("payer", 100.0)).thenReturn(true)
        `when`(economy.withdrawPlayer("payer", 100.0)).thenReturn(success())
        `when`(economy.depositPlayer("cubex_bank", 100.0)).thenReturn(null)

        assertEquals(VaultTransfers.Result.REVIEW_REQUIRED,
            VaultTransfers(economy).transfer("name:payer", "name:cubex_bank", 100.0))

        verify(economy, never()).depositPlayer("payer", 100.0)
    }

    @Test
    fun `invalid transfer amount is rejected before account lookup`() {
        val lookup = mock(OfflinePlayerLookup::class.java)
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(VaultTransfers.Result.INVALID_AMOUNT,
                VaultTransfers(economy, lookup).transfer("payer", "cubex_bank", invalid))
        }
        org.mockito.Mockito.verifyNoInteractions(lookup)
    }

    // ---- helpers ----

    private fun economyWith(spec: EconomyAccount): VaultEconomy =
        newEconomy().apply { useAccount(spec) }

    private fun newEconomy(): VaultEconomy {
        val lookup = FakeLookup(
            uuids = mapOf(bankUuid to bankHolder),
            names = mapOf("cubex_bank" to bankHolder),
            fabricated = setOf("never_joined"),
        )
        return VaultEconomy(economy, CubexLogger(Logger.getLogger("VaultEconomyTest")), lookup)
    }

    private fun player(uuid: UUID, name: String): OfflinePlayer {
        val offline = mock(OfflinePlayer::class.java)
        `when`(offline.uniqueId).thenReturn(uuid)
        `when`(offline.name).thenReturn(name)
        return offline
    }

    private fun success(): EconomyResponse =
        EconomyResponse(100.0, 0.0, EconomyResponse.ResponseType.SUCCESS, null)

    private fun failure(reason: String): EconomyResponse =
        EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.FAILURE, reason)

    private class FakeLookup(
        private val uuids: Map<UUID, OfflinePlayer>,
        private val names: Map<String, OfflinePlayer>,
        private val fabricated: Set<String> = emptySet(),
    ) : OfflinePlayerLookup {
        override fun byUuid(uuid: UUID): OfflinePlayer =
            uuids[uuid] ?: throw IllegalStateException("unexpected uuid lookup: $uuid")

        override fun byName(name: String): NameLookup = when {
            names.containsKey(name) -> NameLookup.Found(names.getValue(name), "profile lookup")
            name in fabricated -> NameLookup.Fabricated
            else -> NameLookup.Unknown
        }
    }
}
