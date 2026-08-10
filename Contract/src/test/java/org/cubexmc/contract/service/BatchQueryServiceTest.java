package org.cubexmc.contract.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.cubexmc.contract.model.BatchSummary;
import org.cubexmc.contract.model.Contract;
import org.cubexmc.contract.model.ContractStatus;
import org.junit.jupiter.api.Test;

class BatchQueryServiceTest {
    @Test
    void aggregatesOnlyExplicitBatchIdsAndPreservesHistoricalCounters() {
        Contract open = child("batch-a", 1, 3);
        Contract submitted = child("batch-a", 2, 3);
        submitted.acceptedAt(20L);
        submitted.submittedAt(30L);
        submitted.status(ContractStatus.SUBMITTED);
        Contract completed = child("batch-a", 3, 3);
        completed.acceptedAt(21L);
        completed.submittedAt(31L);
        completed.status(ContractStatus.COMPLETED);
        Contract sameTitleWithoutBatch = contract("Repeated title", 50_000L);

        var summaries = BatchQueryService.summaries(List.of(open, submitted, completed, sameTitleWithoutBatch));

        assertEquals(1, summaries.size());
        BatchSummary summary = summaries.get("batch-a");
        assertEquals(3, summary.getTotal());
        assertEquals(1, summary.getAvailable());
        assertEquals(2, summary.getAccepted());
        assertEquals(2, summary.getSubmitted());
        assertEquals(1, summary.getCompleted());
        assertFalse(summaries.containsKey("Repeated title"));
    }

    @Test
    void nextAvailableSelectsExactlyOneOpenChildByExpiryThenIndex() {
        Contract later = child("batch-a", 1, 2);
        Contract sooner = child("batch-a", 2, 2);
        later.metadata.put("test-marker", "later");
        sooner.metadata.put("test-marker", "sooner");

        assertSame(sooner, BatchQueryService.nextAvailable(List.of(later, sooner), "batch-a"));
    }

    private Contract child(String batchId, int index, int size) {
        Contract contract = contract("Repeated title", index == 2 ? 20_000L : 50_000L);
        contract.metadata.put("batch-id", batchId);
        contract.metadata.put("batch-index", Integer.toString(index));
        contract.metadata.put("batch-size", Integer.toString(size));
        return contract;
    }

    private Contract contract(String title, long expiresAt) {
        return Contract.createService(
                UUID.randomUUID().toString(), UUID.randomUUID(), "Owner", title, "Description",
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO, 1_000L, expiresAt);
    }
}

