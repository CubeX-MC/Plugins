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
}
