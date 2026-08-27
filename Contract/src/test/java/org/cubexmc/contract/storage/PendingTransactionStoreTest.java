package org.cubexmc.contract.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.cubexmc.core.CubexLogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingTransactionStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsWithdrawDepositAndSettlementEntries() throws Exception {
        PendingTransactionStore store = new PendingTransactionStore(
            tempDir.resolve("pending-transactions.yml").toFile(),
            new CubexLogger(Logger.getAnonymousLogger())
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

    @Test
    void withdrawCarriesContractIdForRecovery() throws Exception {
        PendingTransactionStore store = new PendingTransactionStore(
            tempDir.resolve("pending-transactions.yml").toFile(),
            new CubexLogger(Logger.getAnonymousLogger())
        );
        UUID player = UUID.randomUUID();

        String id = store.beginWithdraw(player, new BigDecimal("250.00"), "contract-create", "contract-xyz");

        PendingTransactionStore.PendingEntry entry = find(store.loadAll(), id);
        assertEquals(PendingTransactionStore.PendingType.WITHDRAW, entry.type());
        assertEquals(player, entry.playerUuid());
        assertEquals(new BigDecimal("250.00"), entry.amount());
        assertEquals("contract-xyz", entry.contractId());
    }

    @Test
    void allianceFundingPhasesSurviveReloadAndRejectStaleTransitions() throws Exception {
        var file = tempDir.resolve("pending-transactions.yml").toFile();
        var logger = new CubexLogger(Logger.getAnonymousLogger());
        PendingTransactionStore store = new PendingTransactionStore(file, logger);
        UUID player = UUID.randomUUID();
        String legacy = store.beginWithdraw(player, new BigDecimal("1.00"), "contract-create");
        String id = store.beginAllianceWithdraw(player, new BigDecimal("12.34"), "alliance-accept", "alliance");
        var reloaded = new PendingTransactionStore(file, logger);
        assertEquals(PendingTransactionStore.FundingPhase.PREPARED, find(reloaded.loadAll(), id).fundingPhase());
        assertNull(find(reloaded.loadAll(), legacy).fundingPhase());
        reloaded.advanceFunding(id, PendingTransactionStore.FundingPhase.PREPARED, PendingTransactionStore.FundingPhase.WITHDRAWN);
        assertThrows(IllegalArgumentException.class, () -> reloaded.advanceFunding(id,
            PendingTransactionStore.FundingPhase.PREPARED, PendingTransactionStore.FundingPhase.REJECTED));
        reloaded.advanceFunding(id, PendingTransactionStore.FundingPhase.WITHDRAWN, PendingTransactionStore.FundingPhase.REFUNDING);
        reloaded.advanceFunding(id, PendingTransactionStore.FundingPhase.REFUNDING, PendingTransactionStore.FundingPhase.REFUNDED);
        assertEquals(PendingTransactionStore.FundingPhase.REFUNDED, find(store.loadAll(), id).fundingPhase());
        assertThrows(IllegalArgumentException.class, () -> store.advanceFunding(id,
            PendingTransactionStore.FundingPhase.REFUNDED, PendingTransactionStore.FundingPhase.WITHDRAWN));
        assertThrows(IllegalArgumentException.class, () -> store.beginWithdraw(player, BigDecimal.ONE, "alliance-accept", "a"));
        assertThrows(IllegalArgumentException.class, () -> store.beginAllianceWithdraw(player, new BigDecimal("0.001"), "alliance-create", "a"));
    }

    @Test
    void malformedFundingOrYamlCannotSilentlyBecomeAnEmptyJournal() throws Exception {
        var file = tempDir.resolve("pending-transactions.yml").toFile();
        PendingTransactionStore store = new PendingTransactionStore(file, new CubexLogger(Logger.getAnonymousLogger()));
        String id = store.beginAllianceWithdraw(UUID.randomUUID(), BigDecimal.ONE, "alliance-create", "a");
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        yaml.set("pending." + id + ".funding-phase", null);
        yaml.save(file);
        assertThrows(IllegalStateException.class, store::loadAll);
        java.nio.file.Files.writeString(file.toPath(), "pending: [invalid");
        String before = java.nio.file.Files.readString(file.toPath());
        assertThrows(Exception.class, () -> store.beginAllianceWithdraw(UUID.randomUUID(), BigDecimal.ONE, "alliance-create", "b"));
        assertEquals(before, java.nio.file.Files.readString(file.toPath()));
        try (var entries = java.nio.file.Files.list(tempDir)) {
            assertTrue(entries.noneMatch(p -> p.toString().endsWith(".tmp")));
        }
    }

    private PendingTransactionStore.PendingEntry find(List<PendingTransactionStore.PendingEntry> entries, String id) {
        return entries.stream()
            .filter(entry -> entry.id().equals(id))
            .findFirst()
            .orElseThrow();
    }
}
