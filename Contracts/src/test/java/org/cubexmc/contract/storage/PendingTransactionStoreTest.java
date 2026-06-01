package org.cubexmc.contract.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PendingTransactionStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsWithdrawDepositAndSettlementEntries() throws Exception {
        PendingTransactionStore store = new PendingTransactionStore(
            tempDir.resolve("pending-transactions.yml").toFile(),
            Logger.getAnonymousLogger()
        );
        UUID withdrawPlayer = UUID.randomUUID();
        UUID depositPlayer = UUID.randomUUID();

        String withdrawId = store.beginWithdraw(withdrawPlayer, new BigDecimal("125.50"), "contract-create");
        String depositId = store.beginDeposit(depositPlayer, new BigDecimal("95.00"), "SUCCESS",
            "contract-1", "rule-1-CONTRACTOR", "settlement-1");
        String settlementId = store.beginSettlement("contract-1", "SUCCESS:APPROVED");

        List<PendingTransactionStore.PendingEntry> entries = store.loadAll();

        PendingTransactionStore.PendingEntry withdraw = find(entries, withdrawId);
        assertEquals(PendingTransactionStore.PendingType.WITHDRAW, withdraw.type());
        assertEquals(withdrawPlayer, withdraw.playerUuid());
        assertEquals(new BigDecimal("125.50"), withdraw.amount());
        assertEquals("contract-create", withdraw.purpose());

        PendingTransactionStore.PendingEntry deposit = find(entries, depositId);
        assertEquals(PendingTransactionStore.PendingType.DEPOSIT, deposit.type());
        assertEquals(depositPlayer, deposit.playerUuid());
        assertEquals(new BigDecimal("95.00"), deposit.amount());
        assertEquals("contract-1", deposit.contractId());
        assertEquals("rule-1-CONTRACTOR", deposit.payoutKey());
        assertEquals("settlement-1", deposit.settlementId());

        PendingTransactionStore.PendingEntry settlement = find(entries, settlementId);
        assertEquals(PendingTransactionStore.PendingType.SETTLEMENT, settlement.type());
        assertEquals("contract-1", settlement.contractId());
        assertEquals("SUCCESS:APPROVED", settlement.purpose());
        assertEquals(BigDecimal.ZERO, settlement.amount());
        assertNull(settlement.playerUuid());
    }

    private PendingTransactionStore.PendingEntry find(List<PendingTransactionStore.PendingEntry> entries, String id) {
        return entries.stream()
            .filter(entry -> entry.id().equals(id))
            .findFirst()
            .orElseThrow();
    }
}
