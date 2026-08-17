package org.cubexmc.contract.integration.regions

import org.cubexmc.contract.api.escrow.ContractEscrowCode
import org.cubexmc.contract.model.Contract
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.service.ServiceResult
import org.cubexmc.contract.storage.ContractStorage
import org.cubexmc.core.CubexLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

class ContractEscrowServiceImplTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `lock and settlement are replayed by stable operation id`() {
        val fixture = fixture()

        val firstLock = fixture.service.lock("match-1", fixture.contract.id(), "arena")
        val replayedLock = fixture.service.lock("match-1", fixture.contract.id(), "arena")
        val settlement = fixture.service.settle("match-1", fixture.contract.id(), "arena", fixture.partyA)
        val replayedSettlement = fixture.service.settle("match-1", fixture.contract.id(), "arena", fixture.partyA)

        assertTrue(firstLock.successful())
        assertEquals(ContractEscrowCode.REPLAYED, replayedLock.code())
        assertTrue(settlement.successful())
        assertEquals(BigDecimal("19.00"), settlement.amount())
        assertEquals(ContractEscrowCode.REPLAYED, replayedSettlement.code())
        assertEquals(1, fixture.executor.settlements)
    }

    @Test
    fun `different operations and unrelated winners cannot move locked funds`() {
        val fixture = fixture()
        fixture.service.lock("match-1", fixture.contract.id(), "arena")

        val lockConflict = fixture.service.lock("match-2", fixture.contract.id(), "arena")
        val operationConflict = fixture.service.settle(
            "match-2",
            fixture.contract.id(),
            "arena",
            fixture.partyA,
        )
        val wrongWinner = fixture.service.settle(
            "match-1",
            fixture.contract.id(),
            "arena",
            UUID.randomUUID(),
        )

        assertEquals(ContractEscrowCode.LOCK_CONFLICT, lockConflict.code())
        assertEquals(ContractEscrowCode.OPERATION_CONFLICT, operationConflict.code())
        assertEquals(ContractEscrowCode.NOT_ELIGIBLE, wrongWinner.code())
        assertFalse(wrongWinner.successful())
        assertEquals(0, fixture.executor.settlements)
    }

    @Test
    fun `partial settlement failure becomes review-required and is never retried`() {
        val fixture = fixture(failWithDispute = true)
        fixture.service.lock("match-1", fixture.contract.id(), "arena")

        val failed = fixture.service.settle("match-1", fixture.contract.id(), "arena", fixture.partyB)
        val replay = fixture.service.settle("match-1", fixture.contract.id(), "arena", fixture.partyB)

        assertEquals(ContractEscrowCode.REVIEW_REQUIRED, failed.code())
        assertEquals(ContractEscrowCode.REVIEW_REQUIRED, replay.code())
        assertEquals(1, fixture.executor.settlements)
    }

    private fun fixture(failWithDispute: Boolean = false): Fixture {
        val partyA = UUID.randomUUID()
        val partyB = UUID.randomUUID()
        val contract = Contract.createWager(
            "wager-contract-id",
            partyA,
            "Party A",
            partyB,
            "Party B",
            UUID.randomUUID(),
            "Arbiter",
            "Arena wager",
            "Fund a Regions match",
            BigDecimal.TEN,
            BigDecimal("5"),
            1L,
            10_000L,
        )
        contract.status(ContractStatus.IN_PROGRESS)
        val storage = ContractStorage(
            tempDir.resolve("contract.yml").toFile(),
            CubexLogger(Logger.getLogger("ContractEscrowServiceImplTest")),
        )
        storage.put(contract)
        storage.save()
        val executor = FakeExecutor(failWithDispute)
        val service = ContractEscrowServiceImpl(
            storage,
            executor,
            CubexLogger(Logger.getLogger("ContractEscrowServiceImplTest")),
        )
        return Fixture(service, executor, contract, partyA, partyB)
    }

    private class FakeExecutor(private val failWithDispute: Boolean) : RegionFundingExecutor {
        var settlements = 0

        override fun recordLock(contract: Contract, regionId: String, operationId: String) = Unit

        override fun settle(
            contract: Contract,
            winnerId: UUID,
            operationId: String,
            regionId: String,
        ): ServiceResult {
            settlements++
            if (failWithDispute) {
                contract.status(ContractStatus.DISPUTED)
                return ServiceResult.fail("partial payout")
            }
            contract.status(ContractStatus.COMPLETED)
            return ServiceResult.ok(contract, BigDecimal("19.00"))
        }

        override fun refund(
            contract: Contract,
            operationId: String,
            regionId: String,
            reason: String,
        ): ServiceResult {
            contract.status(ContractStatus.CANCELLED)
            return ServiceResult.ok(contract, BigDecimal("20.00"))
        }
    }

    private data class Fixture(
        val service: ContractEscrowServiceImpl,
        val executor: FakeExecutor,
        val contract: Contract,
        val partyA: UUID,
        val partyB: UUID,
    )
}
