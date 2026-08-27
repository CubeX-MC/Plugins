package org.cubexmc.regions.reward

import org.cubexmc.core.CubexLogger
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.storage.RewardFundingStore
import org.cubexmc.regions.storage.RewardFundingStore.Lease
import org.cubexmc.regions.storage.RewardFundingStore.LeaseState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

class RewardFundingServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `dual winner settles once and clears the durable lease`() {
        val partyA = UUID.randomUUID()
        val partyB = UUID.randomUUID()
        val provider = FakeProvider(partyA, partyB)
        val store = store()
        val service = service(provider, store)
        val region = region("dual_pvp")

        assertTrue(service.reserve(region).successful)
        assertTrue(service.settle(region, setOf(partyA)).successful)

        assertEquals(1, provider.locks)
        assertEquals(listOf(partyA), provider.winners)
        assertTrue(store.all().isEmpty())
        assertEquals(2, provider.operations.size)
        assertEquals(provider.operations[0], provider.operations[1])
    }

    @Test
    fun `restart turns a preparing lease into an idempotent lock then refund`() {
        val partyA = UUID.randomUUID()
        val partyB = UUID.randomUUID()
        val provider = FakeProvider(partyA, partyB)
        val store = store()
        store.put(Lease("arena", "wager-1", "match-id", LeaseState.PREPARING))
        assertTrue(store.save())

        val results = service(provider, store).reconcile()

        assertTrue(results.all(FundingResult::successful))
        assertEquals(1, provider.locks)
        assertEquals(1, provider.refunds)
        assertEquals(listOf("match-id", "match-id"), provider.operations)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `union winner maps through the funded party's union`() {
        val partyA = UUID.randomUUID()
        val partyB = UUID.randomUUID()
        val winningMember = UUID.randomUUID()
        val provider = FakeProvider(partyA, partyB)
        val unions = mapOf(partyA to "red", partyB to "blue", winningMember to "blue")
        val store = store()
        val service = RewardFundingService(provider, store, unions::get, logger())
        val region = region("union_war")

        service.reserve(region)
        val result = service.settle(region, setOf(winningMember))

        assertTrue(result.successful)
        assertEquals(listOf(partyB), provider.winners)
    }

    @Test
    fun `unsupported modes remain blocked before any provider mutation`() {
        val provider = FakeProvider(UUID.randomUUID(), UUID.randomUUID())
        val service = service(provider, store())

        val result = service.check(region("run_race"))

        assertFalse(result.successful)
        assertEquals("MODE_UNSUPPORTED", result.code)
        assertEquals(0, provider.locks)
    }

    @Test
    fun `provider outage after match records settlement intent and retries instead of refunding`() {
        val partyA = UUID.randomUUID()
        val provider = FakeProvider(partyA, UUID.randomUUID())
        val store = store()
        val service = service(provider, store)
        val region = region("dual_pvp")
        assertTrue(service.reserve(region).successful)
        val operationId = provider.operations.single()

        provider.checkAvailable = false
        val unavailable = service.settle(region, setOf(partyA))
        assertFalse(unavailable.successful)
        assertEquals(LeaseState.SETTLING, store.get("arena")?.state)
        assertEquals(setOf(partyA.toString()), store.get("arena")?.winnerKeys)

        provider.checkAvailable = true
        val results = service(provider, store).reconcile()

        assertTrue(results.single().successful)
        assertEquals(0, provider.refunds)
        assertEquals(listOf(partyA), provider.winners)
        assertEquals(operationId, provider.operations.last())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `settlement intent survives store reload and replays the same operation id`() {
        val partyA = UUID.randomUUID()
        val provider = FakeProvider(partyA, UUID.randomUUID())
        val firstStore = store()
        val region = region("dual_pvp")
        val firstService = service(provider, firstStore)
        assertTrue(firstService.reserve(region).successful)
        val operationId = provider.operations.single()

        provider.checkAvailable = false
        val interrupted = firstService.settle(region, setOf(partyA))
        assertFalse(interrupted.successful)

        val reloadedStore = RewardFundingStore(
            tempDir.resolve("reward-funding.yml").toFile(),
            logger(),
        ).apply { reload() }
        assertEquals(LeaseState.SETTLING, reloadedStore.get("arena")?.state)
        assertEquals(operationId, reloadedStore.get("arena")?.operationId)

        provider.checkAvailable = true
        val recovered = service(provider, reloadedStore).reconcile()

        assertTrue(recovered.single().successful)
        assertEquals(operationId, provider.operations.last())
        assertEquals(listOf(partyA), provider.winners)
        assertEquals(0, provider.refunds)
        assertTrue(reloadedStore.all().isEmpty())
    }

    @Test
    fun `review-required settlement remains durable and never falls back to refund`() {
        val partyA = UUID.randomUUID()
        val provider = FakeProvider(partyA, UUID.randomUUID())
        val firstStore = store()
        val region = region("dual_pvp")
        val firstService = service(provider, firstStore)
        assertTrue(firstService.reserve(region).successful)
        val operationId = provider.operations.single()
        provider.settlementFailure = FundingResult.fail("REVIEW_REQUIRED", "partial payout")

        val failed = firstService.settle(region, setOf(partyA))
        assertEquals("REVIEW_REQUIRED", failed.code)

        val reloadedStore = RewardFundingStore(
            tempDir.resolve("reward-funding.yml").toFile(),
            logger(),
        ).apply { reload() }
        val replay = service(provider, reloadedStore).reconcile()

        assertEquals("REVIEW_REQUIRED", replay.single().code)
        assertEquals(LeaseState.SETTLING, reloadedStore.get("arena")?.state)
        assertTrue(provider.operations.all { it == operationId })
        assertEquals(0, provider.refunds)
    }

    @Test
    fun `union lookup outage retains raw winner candidates for later reconciliation`() {
        val partyA = UUID.randomUUID()
        val partyB = UUID.randomUUID()
        val winner = UUID.randomUUID()
        val provider = FakeProvider(partyA, partyB)
        val store = store()
        var unionsAvailable = false
        val resolver: (UUID) -> String? = { id ->
            if (!unionsAvailable) null else mapOf(partyA to "red", partyB to "blue", winner to "blue")[id]
        }
        val region = region("union_war")
        val service = RewardFundingService(provider, store, resolver, logger())
        assertTrue(service.reserve(region).successful)

        val unavailable = service.settle(region, setOf(winner))
        assertEquals("WINNER_CONTEXT_UNAVAILABLE", unavailable.code)
        assertEquals(setOf(winner.toString()), store.get("arena")?.winnerKeys)

        unionsAvailable = true
        val recovered = RewardFundingService(provider, store, resolver, logger()).reconcile()
        assertTrue(recovered.single().successful)
        assertEquals(listOf(partyB), provider.winners)
        assertEquals(0, provider.refunds)
    }

    @Test
    fun `malformed durable lease fails closed without replacing live leases`() {
        val store = store()
        store.put(Lease("live", "wager-live", "operation-live", LeaseState.LOCKED))
        tempDir.resolve("reward-funding.yml").toFile().writeText(
            "funding-version: 1\nleases:\n  broken:\n    state: LOCKED\n",
        )

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) { store.reload() }
        assertEquals("operation-live", store.get("live")?.operationId)
    }

    private fun store(): RewardFundingStore = RewardFundingStore(
        tempDir.resolve("reward-funding.yml").toFile(),
        logger(),
    ).apply { reload() }

    private fun service(provider: RewardFundingProvider, store: RewardFundingStore): RewardFundingService =
        RewardFundingService(provider, store, { null }, logger())

    private fun region(type: String): RegionDefinition = RegionDefinition(
        id = "arena",
        name = "Arena",
        source = RegionSourceRef("cuboid"),
        mode = ModeConfig(type, mapOf("reward-source" to "contract", "reward-contract" to "wager-1")),
    )

    private fun logger(): CubexLogger = CubexLogger(Logger.getLogger("RewardFundingServiceTest"))

    private class FakeProvider(private val partyA: UUID, private val partyB: UUID) : RewardFundingProvider {
        var locks = 0
        var refunds = 0
        val winners = mutableListOf<UUID>()
        val operations = mutableListOf<String>()
        var checkAvailable = true
        var settlementFailure: FundingResult? = null

        override fun check(contractId: String, regionId: String): FundingResult =
            if (checkAvailable) FundingResult.ok(contractId, partyA, partyB)
            else FundingResult.fail("PROVIDER_UNAVAILABLE", "temporary outage")

        override fun lock(operationId: String, contractId: String, regionId: String): FundingResult {
            locks++
            operations += operationId
            return FundingResult.ok(contractId, partyA, partyB)
        }

        override fun settle(
            operationId: String,
            contractId: String,
            regionId: String,
            winnerId: UUID,
        ): FundingResult {
            operations += operationId
            winners += winnerId
            return settlementFailure ?: FundingResult.ok(contractId, partyA, partyB)
        }

        override fun refund(
            operationId: String,
            contractId: String,
            regionId: String,
            reason: String,
        ): FundingResult {
            operations += operationId
            refunds++
            return FundingResult.ok(contractId, partyA, partyB)
        }
    }
}
