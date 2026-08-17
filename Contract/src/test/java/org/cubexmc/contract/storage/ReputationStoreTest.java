package org.cubexmc.contract.storage;

import org.cubexmc.contract.integration.reputation.ReputationDeltaSink;
import org.cubexmc.contract.model.Contract;
import org.cubexmc.contract.model.ContractStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.cubexmc.core.CubexLogger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReputationStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void withdrawingDisputeRollsBackCountWithoutGoingNegative() {
        List<Delta> deltas = new ArrayList<>();
        ReputationStore store = new ReputationStore(
            tempDir.resolve("reputation.yml").toFile(),
            new CubexLogger(Logger.getLogger("ReputationStoreTest")),
            (playerId, fieldId, delta) -> deltas.add(new Delta(playerId, fieldId, delta))
        );
        UUID player = UUID.randomUUID();

        store.recordDisputed(player, "Alex");
        store.recordDisputed(player, "Alex");
        store.recordDisputeWithdrawn(player, "Alex");
        assertEquals(1, store.snapshot(player).getDisputed());

        store.recordDisputeWithdrawn(player, "Alex");
        store.recordDisputeWithdrawn(player, "Alex");
        assertEquals(0, store.snapshot(player).getDisputed());
        assertEquals(List.of(
            new Delta(player, "disputed", 1.0),
            new Delta(player, "disputed", 1.0),
            new Delta(player, "disputed", -1.0),
            new Delta(player, "disputed", -1.0)
        ), deltas);
    }

    @Test
    void mirrorFailureNeverRollsBackTheLocalRecord() {
        ReputationDeltaSink failingSink = (playerId, fieldId, delta) -> {
            throw new IllegalStateException("provider stopped");
        };
        ReputationStore store = new ReputationStore(
            tempDir.resolve("reputation.yml").toFile(),
            new CubexLogger(Logger.getLogger("ReputationStoreTest")),
            failingSink
        );
        UUID player = UUID.randomUUID();

        store.recordCancelled(player, "Alex");

        assertEquals(1, store.snapshot(player).getCancelled());
    }

    @Test
    void settlementMirrorsCompletedPartiesAndExpiredContractor() {
        List<Delta> deltas = new ArrayList<>();
        ReputationStore store = new ReputationStore(
            tempDir.resolve("reputation.yml").toFile(),
            new CubexLogger(Logger.getLogger("ReputationStoreTest")),
            (playerId, fieldId, delta) -> deltas.add(new Delta(playerId, fieldId, delta))
        );
        UUID owner = UUID.randomUUID();
        UUID contractor = UUID.randomUUID();
        Contract contract = Contract.createService(
            owner, "Owner", "Title", "Description",
            BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, 1L, 2L
        );
        contract.contractorUuid(contractor);
        contract.contractorName("Contractor");

        store.recordSettlement(contract, ContractStatus.COMPLETED);
        store.recordSettlement(contract, ContractStatus.EXPIRED);

        assertEquals(List.of(
            new Delta(owner, "completed", 1.0),
            new Delta(contractor, "completed", 1.0),
            new Delta(contractor, "expired", 1.0)
        ), deltas);
        assertEquals(1, store.snapshot(owner).getCompleted());
        assertEquals(1, store.snapshot(contractor).getCompleted());
        assertEquals(1, store.snapshot(contractor).getExpired());
    }

    private record Delta(UUID playerId, String fieldId, double value) {}
}
