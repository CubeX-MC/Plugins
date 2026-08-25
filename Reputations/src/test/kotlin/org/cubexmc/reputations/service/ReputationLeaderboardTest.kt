package org.cubexmc.reputations.service

import org.cubexmc.reputations.api.ReputationField
import org.cubexmc.reputations.integration.ReputationPlaceholders
import org.cubexmc.reputations.storage.ReputationStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

class ReputationLeaderboardTest {
    @Test
    fun `sorts by field direction assigns shared ranks and invalidates cache`(@TempDir directory: Path) {
        val fixture = fixture(directory)
        val alex = fixture.player("Alex", 10.0)
        val steve = fixture.player("Steve", 10.0)
        val zed = fixture.player("Zed", 3.0)

        assertEquals(listOf(1, 1, 3), fixture.leaderboard.entries(FIELD_KEY).map { it.rank })
        assertEquals(listOf("Alex", "Steve", "Zed"), fixture.leaderboard.entries(FIELD_KEY).map { it.playerName })

        fixture.service.set(zed, FIELD_KEY, 12.0)
        assertEquals(listOf(zed, alex, steve), fixture.leaderboard.entries(FIELD_KEY).map { it.playerId })
    }

    @Test
    fun `lower values rank first when field says lower is better`(@TempDir directory: Path) {
        val store = ReputationStore(File(directory.toFile(), "reputation.yml"), LOG)
        store.load()
        val service = ReputationServiceImpl(store, LOG)
        service.registerField(ReputationField.builder("test", "bad").higherIsBetter(false).build())
        val leaderboard = ReputationLeaderboard(store, service)
        val low = UUID.randomUUID()
        val high = UUID.randomUUID()
        store.cacheName(low, "Low")
        store.cacheName(high, "High")
        service.set(low, "test:bad", 1.0)
        service.set(high, "test:bad", 9.0)

        assertEquals(listOf(low, high), leaderboard.entries("test:bad").map { it.playerId })
    }

    @Test
    fun `placeholder queries support values ranks and top rows`(@TempDir directory: Path) {
        val fixture = fixture(directory)
        val alex = fixture.player("Alex", 10.0)
        fixture.player("Steve", 4.0)
        val placeholders = ReputationPlaceholders(fixture.service, fixture.leaderboard)

        assertEquals("10", placeholders.resolve(alex, "value_TEST:SCORE"))
        assertEquals("1", placeholders.resolve(alex, "rank_test:score"))
        assertEquals("Alex", placeholders.resolve(null, "top_name_1_test:score"))
        assertEquals("4", placeholders.resolve(null, "top_value_2_test:score"))
        assertEquals("", placeholders.resolve(null, "top_name_3_test:score"))
        assertNull(placeholders.resolve(alex, "value_unknown:field"))
        assertNull(placeholders.resolve(alex, "not_a_placeholder"))
    }

    private fun fixture(directory: Path): Fixture {
        val store = ReputationStore(File(directory.toFile(), "reputation.yml"), LOG)
        store.load()
        val service = ReputationServiceImpl(store, LOG)
        service.registerField(ReputationField.builder("test", "score").build())
        return Fixture(store, service, ReputationLeaderboard(store, service))
    }

    private data class Fixture(
        val store: ReputationStore,
        val service: ReputationServiceImpl,
        val leaderboard: ReputationLeaderboard,
    ) {
        fun player(name: String, value: Double): UUID = UUID.randomUUID().also { playerId ->
            store.cacheName(playerId, name)
            service.set(playerId, FIELD_KEY, value)
        }
    }

    private companion object {
        const val FIELD_KEY = "test:score"
        val LOG: Logger = Logger.getLogger(ReputationLeaderboardTest::class.java.name)
    }
}
