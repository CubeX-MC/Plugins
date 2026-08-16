package org.cubexmc.statecharge.storage

import org.cubexmc.core.CubexLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Logger

class StateStorageTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = CubexLogger(Logger.getLogger("statecharge-test"))
    private val player = UUID.randomUUID()

    private fun storage(): StateStorage = StateStorage(File(tempDir, "states.yml"), logger)

    @Test
    fun setAddRemoveRoundtrip() {
        val storage = storage()
        assertEquals(0L, storage.remaining(player, "fly"))

        storage.setRemaining(player, "fly", 100L)
        assertEquals(100L, storage.remaining(player, "fly"))
        assertEquals(mapOf("fly" to 100L), storage.active(player))

        storage.addSeconds(player, "fly", 50L)
        assertEquals(150L, storage.remaining(player, "fly"))

        storage.removeState(player, "fly")
        assertEquals(0L, storage.remaining(player, "fly"))
        assertTrue(storage.active(player).isEmpty())
        assertEquals(0, storage.size())
    }

    @Test
    fun setToZeroOrNegativeRemovesEntry() {
        val storage = storage()
        storage.setRemaining(player, "small", 10L)
        storage.setRemaining(player, "small", 0L)
        assertTrue(storage.active(player).isEmpty())

        storage.setRemaining(player, "small", 10L)
        storage.setRemaining(player, "small", -3L)
        assertTrue(storage.active(player).isEmpty())
    }

    @Test
    fun removePlayerClearsAllStates() {
        val storage = storage()
        storage.setRemaining(player, "small", 10L)
        storage.setRemaining(player, "fly", 20L)
        storage.removePlayer(player)
        assertTrue(storage.active(player).isEmpty())
        assertEquals(0, storage.size())
    }

    @Test
    fun loadPersistsAndRestores() {
        val first = storage()
        first.setRemaining(player, "fly", 3600L)
        first.flushIfDirty()
        assertTrue(File(tempDir, "states.yml").isFile)

        val second = storage()
        second.load()
        assertEquals(3600L, second.remaining(player, "fly"))
    }

    @Test
    fun flushOnlyWhenDirty() {
        val storage = storage()
        storage.load()
        assertFalse(storage.isDirty())
        storage.flushIfDirty()
        assertFalse(File(tempDir, "states.yml").exists())

        storage.setRemaining(player, "fly", 10L)
        assertTrue(storage.isDirty())
        storage.flushIfDirty()
        assertFalse(storage.isDirty())
        assertTrue(File(tempDir, "states.yml").exists())
    }

    @Test
    fun corruptFileFallsBackToBackup() {
        val file = File(tempDir, "states.yml")
        val storage = storage()
        storage.setRemaining(player, "fly", 1234L)
        storage.flushIfDirty()
        // 第二次保存才会把已有主文件拷成 .bak(备份滞后一个版本)。
        storage.setRemaining(player, "small", 60L)
        storage.flushIfDirty()
        assertTrue(File(tempDir, "states.yml.bak").exists())

        // 把主文件写坏,备份仍是好的(内含上一版本 fly=1234)。
        Files.writeString(file.toPath(), "players: [this is not: a: map", StandardCharsets.UTF_8)

        val reloaded = storage()
        reloaded.load()
        assertEquals(1234L, reloaded.remaining(player, "fly"))
    }

    @Test
    fun invalidPlayerKeyIsSkipped() {
        val file = File(tempDir, "states.yml")
        val yaml = """
            players:
              not-a-uuid:
                fly: 100
        """.trimIndent()
        Files.writeString(file.toPath(), yaml, StandardCharsets.UTF_8)

        val storage = storage()
        storage.load()
        assertEquals(0, storage.size())
    }

    @Test
    fun nonPositiveValuesAreSkippedOnLoad() {
        val file = File(tempDir, "states.yml")
        val yaml = """
            players:
              $player:
                fly: 100
                broken: -5
        """.trimIndent()
        Files.writeString(file.toPath(), yaml, StandardCharsets.UTF_8)

        val storage = storage()
        storage.load()
        assertEquals(mapOf("fly" to 100L), storage.active(player))
    }
}
