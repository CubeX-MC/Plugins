package org.cubexmc.statecharge.storage

import java.io.File
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Logger
import org.cubexmc.core.CubexLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StateStorageTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = CubexLogger(Logger.getLogger("statecharge-test"))
    private val player = UUID.randomUUID()

    private fun storage(): StateStorage = StateStorage(File(tempDir, StateStorage.FILE_NAME), logger)

    @Test
    fun `toggling a state on and off round-trips`() {
        val storage = storage()
        assertFalse(storage.isActive(player, "small"))

        storage.setActive(player, "small", true)
        assertTrue(storage.isActive(player, "small"))
        assertEquals(setOf("small"), storage.activeStates(player))

        storage.setActive(player, "small", false)
        assertFalse(storage.isActive(player, "small"))
        assertTrue(storage.activeStates(player).isEmpty())
    }

    @Test
    fun `accrued seconds add up and clear`() {
        val storage = storage()

        storage.addAccrued(player, "small", 30L)
        storage.addAccrued(player, "small", 12L)
        assertEquals(42L, storage.accruedSeconds(player, "small"))

        storage.clearAccrued(player, "small")
        assertEquals(0L, storage.accruedSeconds(player, "small"))
    }

    @Test
    fun `a non-positive accrual is ignored`() {
        val storage = storage()

        storage.addAccrued(player, "small", 0L)
        storage.addAccrued(player, "small", -5L)

        assertEquals(0L, storage.accruedSeconds(player, "small"))
    }

    @Test
    fun `guard defaults to null so the service can fall back to config`() {
        val storage = storage()
        assertNull(storage.guard(player))

        storage.setGuard(player, BigDecimal("500"))
        assertEquals(BigDecimal("500"), storage.guard(player))

        storage.setGuard(player, null)
        assertNull(storage.guard(player))
    }

    @Test
    fun `active states, accrual and guard all survive a save-load cycle`() {
        val storage = storage()
        storage.setActive(player, "small", true)
        storage.addAccrued(player, "small", 42L)
        storage.setGuard(player, BigDecimal("250.5"))
        storage.flushIfDirty()

        val reloaded = storage()
        reloaded.load()

        assertTrue(reloaded.isActive(player, "small"))
        assertEquals(42L, reloaded.accruedSeconds(player, "small"))
        assertEquals(0, BigDecimal("250.5").compareTo(reloaded.guard(player)))
    }

    @Test
    fun `anyActive reports whether the billing loop has anything to do`() {
        val storage = storage()
        assertFalse(storage.anyActive())

        storage.setActive(player, "small", true)
        assertTrue(storage.anyActive())

        storage.setActive(player, "small", false)
        assertFalse(storage.anyActive())
    }

    @Test
    fun `removePlayer drops everything for that player`() {
        val storage = storage()
        storage.setActive(player, "small", true)
        storage.addAccrued(player, "small", 10L)
        storage.setGuard(player, BigDecimal.TEN)

        storage.removePlayer(player)

        assertFalse(storage.isActive(player, "small"))
        assertEquals(0L, storage.accruedSeconds(player, "small"))
        assertNull(storage.guard(player))
    }

    @Test
    fun `session totals accumulate across billing cycles`() {
        val storage = storage()
        assertEquals(BigDecimal.ZERO, storage.sessionCharged(player, "fly"))

        // 一个开着一小时的状态会按周期结算很多次;关闭提示要报的是它们的和。
        storage.addSessionCharged(player, "fly", BigDecimal("2.00"))
        storage.addSessionCharged(player, "fly", BigDecimal("2.00"))
        storage.addSessionCharged(player, "fly", BigDecimal("2.06"))

        assertEquals(0, BigDecimal("6.06").compareTo(storage.sessionCharged(player, "fly")))
    }

    @Test
    fun `session totals are per state and survive a save-load round trip`() {
        val storage = storage()
        storage.setActive(player, "fly", true)
        storage.addSessionCharged(player, "fly", BigDecimal("2.06"))
        storage.addSessionCharged(player, "small", BigDecimal("2.00"))
        storage.flushIfDirty()

        // 离线不计费但状态保留,所以累计额也必须跨重启活下来。
        val reloaded = storage()
        reloaded.load()

        assertEquals(0, BigDecimal("2.06").compareTo(reloaded.sessionCharged(player, "fly")))
        assertEquals(0, BigDecimal("2.00").compareTo(reloaded.sessionCharged(player, "small")))
    }

    @Test
    fun `clearing a session total leaves the other states alone`() {
        val storage = storage()
        storage.addSessionCharged(player, "fly", BigDecimal("2.06"))
        storage.addSessionCharged(player, "small", BigDecimal("2.00"))

        storage.clearSessionCharged(player, "fly")

        assertEquals(BigDecimal.ZERO, storage.sessionCharged(player, "fly"))
        assertEquals(0, BigDecimal("2.00").compareTo(storage.sessionCharged(player, "small")))
    }

    @Test
    fun `a v2 file without session totals still loads`() {
        // session-charged 是 v2 里后加的可选段:旧文件缺了它只该从零重新累计,不该整段作废。
        val file = File(tempDir, StateStorage.FILE_NAME)
        Files.writeString(
            file.toPath(),
            """
            storage-version: 2
            players:
              $player:
                active:
                - fly
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        val storage = storage()
        storage.load()

        assertTrue(storage.isActive(player, "fly"))
        assertEquals(BigDecimal.ZERO, storage.sessionCharged(player, "fly"))
    }

    @Test
    fun `a v1 file is ignored rather than mis-converted`() {
        // v1 存的是"预购的剩余时长",与按开启时长计费无法换算 —— 宁可忽略也不猜折算比例。
        val file = File(tempDir, StateStorage.FILE_NAME)
        Files.writeString(
            file.toPath(),
            "players:\n  $player:\n    small: 1800\n",
            StandardCharsets.UTF_8,
        )

        val storage = storage()
        storage.load()

        assertFalse(storage.isActive(player, "small"))
        assertEquals(0, storage.size())
    }

    @Test
    fun `an unreadable file falls back to the backup`() {
        val storage = storage()
        storage.setActive(player, "small", true)
        storage.flushIfDirty()
        // 再存一次,让 .bak 里留着上一份完好数据
        storage.setActive(player, "giant", true)
        storage.flushIfDirty()

        File(tempDir, StateStorage.FILE_NAME).writeText("{ this is not yaml", StandardCharsets.UTF_8)

        val recovered = storage()
        recovered.load()

        assertTrue(recovered.isActive(player, "small"))
    }

    @Test
    fun `unreadable primary and backup fail closed and preserve live state`() {
        val storage = storage()
        storage.setActive(player, "fly", true)
        File(tempDir, StateStorage.FILE_NAME).writeText("{broken", StandardCharsets.UTF_8)
        File(tempDir, StateStorage.FILE_NAME + ".bak").writeText("{also-broken", StandardCharsets.UTF_8)

        assertThrows(IllegalStateException::class.java) { storage.load() }
        assertTrue(storage.isActive(player, "fly"))
    }
}
