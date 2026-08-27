package org.cubexmc.reputations.service

import org.cubexmc.reputations.api.ReputationChangeEvent
import org.cubexmc.reputations.api.ReputationField
import org.cubexmc.reputations.storage.ReputationStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

class ReputationServiceImplTest {
    @Test
    fun `publishes effective value changes and skips no-op mutations`(@TempDir directory: Path) {
        val store = ReputationStore(File(directory.toFile(), "reputation.yml"), LOG)
        store.load()
        val changes = ArrayList<PublishedChange>()
        val service = ReputationServiceImpl(store, LOG) { playerId, fieldKey, previous, next, changeType ->
            changes += PublishedChange(playerId, fieldKey, previous, next, changeType)
        }
        service.registerField(ReputationField.builder("test", "score").defaultValue(5.0).build())
        val playerId = UUID.randomUUID()

        service.set(playerId, "test:score", 8.0)
        service.add(playerId, "test:score", 2.0)
        service.add(playerId, "test:score", 0.0)
        service.reset(playerId, "test:score")
        service.reset(playerId, "test:score")

        assertEquals(3, changes.size)
        assertEquals(PublishedChange(playerId, "test:score", 5.0, 8.0, ReputationChangeEvent.ChangeType.SET), changes[0])
        assertEquals(PublishedChange(playerId, "test:score", 8.0, 10.0, ReputationChangeEvent.ChangeType.ADD), changes[1])
        assertEquals(PublishedChange(playerId, "test:score", 10.0, 5.0, ReputationChangeEvent.ChangeType.RESET), changes[2])
    }

    private data class PublishedChange(
        val playerId: UUID,
        val fieldKey: String,
        val previous: Double,
        val next: Double,
        val changeType: ReputationChangeEvent.ChangeType,
    )

    private companion object {
        val LOG: Logger = Logger.getLogger(ReputationServiceImplTest::class.java.name)
    }
}
