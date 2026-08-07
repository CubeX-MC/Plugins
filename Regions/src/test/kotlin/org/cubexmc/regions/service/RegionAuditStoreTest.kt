package org.cubexmc.regions.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class RegionAuditStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `audit events persist and can be filtered newest first`() {
        val file = File(tempDir.toFile(), "audit.yml")
        val store = RegionAuditStore(file)
        store.append(RegionAuditEvent(regionId = "alpha", actorId = "owner", actorName = "Alice", action = "region.create"))
        store.append(RegionAuditEvent(regionId = "beta", actorId = null, actorName = "system", action = "region.freeze", reason = "owner-changed"))
        store.append(RegionAuditEvent(regionId = "alpha", actorId = "owner", actorName = "Alice", action = "region.update"))

        val reloaded = RegionAuditStore(file)
        reloaded.load()

        assertEquals(listOf("region.update", "region.create"), reloaded.recent("alpha").map { it.action })
        assertEquals("owner-changed", reloaded.recent("beta", 1).single().reason)
    }

    @Test
    fun `audit retention keeps bounded newest events`() {
        val store = RegionAuditStore(File(tempDir.toFile(), "bounded.yml"), maxEvents = 100)
        repeat(105) { index ->
            store.append(RegionAuditEvent(regionId = "venue", actorId = null, actorName = "system", action = "event-$index"))
        }

        assertEquals(100, store.recent("venue", 100).size)
        assertEquals("event-104", store.recent("venue", 1).single().action)
    }
}
