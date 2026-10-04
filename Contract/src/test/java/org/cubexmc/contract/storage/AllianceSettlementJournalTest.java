package org.cubexmc.contract.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.contract.ContractPlugin;
import org.cubexmc.contract.model.*;
import org.cubexmc.core.CubexLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.cubexmc.contract.model.AllianceSettlement.Phase.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AllianceSettlementJournalTest {
    @TempDir Path directory;
    private static final UUID OWNER = new UUID(0, 1), BOB = new UUID(0, 2), CARA = new UUID(0, 3);

    private Contract contract() {
        return Contract.createAlliance("alliance", member(ParticipantRole.OWNER, OWNER),
            List.of(member(ParticipantRole.ALLY, CARA), member(ParticipantRole.ALLY, BOB)), "title", "terms", 100, 1000);
    }
    private Participant member(ParticipantRole role, UUID id) {
        return new Participant(role, id, id.toString(), List.of(Asset.money(new BigDecimal("0.01"))));
    }
    private PendingTransactionStore store() {
        return new PendingTransactionStore(directory.resolve("pending.yml").toFile(), new CubexLogger(Logger.getAnonymousLogger()));
    }
    private AllianceSettlement plan(Contract c) {
        return AllianceSettlement.create(c, AllianceSettlement.Outcome.REFUND, null, OWNER.toString());
    }

    @Test
    void uuidPlanAndPhasesRoundTripBesideLegacyJournalWithoutReinterpretingSmallTimestamps() throws Exception {
        Contract c = contract();
        PendingTransactionStore store = store();
        String legacy = store.beginWithdraw(BOB, new BigDecimal("12.00"), "contract-create", "other");
        String id = store.beginAllianceSettlement(c.id(), plan(c));
        var restored = store().loadAll().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
        restored.allianceSettlement().validate(c);
        assertEquals(OWNER.toString(), restored.allianceSettlement().getActor());
        assertEquals(Map.of(OWNER, new BigDecimal("0.01")), restored.allianceSettlement().payments());
        assertNull(store().loadAll().stream().filter(e -> e.id().equals(legacy)).findFirst().orElseThrow().allianceSettlement());
        assertThrows(IllegalArgumentException.class, () -> store.advanceAlliancePayment(id, OWNER, READY, PAID));
        store.advanceAlliancePayment(id, OWNER, READY, PAYING);
        assertThrows(IllegalArgumentException.class, () -> store.advanceAlliancePayment(id, OWNER, READY, PAYING));
        store.advanceAlliancePayment(id, OWNER, PAYING, PAID);
        assertTrue(store().loadAll().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow().allianceSettlement().allPaid());
        assertThrows(IllegalArgumentException.class, () -> store.advanceAlliancePayment(id, OWNER, PAID, READY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"version", "outcome", "actor", "principals", "agreement", "transfers", "payments"})
    void incompletePayoutSnapshotStopsReadingAndCannotBeOverwritten(String missing) throws Exception {
        var store = store();
        String id = store.beginAllianceSettlement("alliance", plan(contract()));
        Path file = directory.resolve("pending.yml");
        var yaml = YamlConfiguration.loadConfiguration(file.toFile());
        yaml.set("pending." + id + ".alliance-settlement." + missing, null);
        yaml.save(file.toFile());
        String before = Files.readString(file);
        assertThrows(RuntimeException.class, store::loadAll);
        assertThrows(RuntimeException.class, () -> store.beginAllianceSettlement("other", plan(contract())));
        assertEquals(before, Files.readString(file));
    }

    @Test
    void snapshotDoesNotExposeMutableSignatureOrTransferStructures() {
        AllianceSettlement plan = plan(contract());
        Map<?, ?> agreement = (Map<?, ?>) plan.toMap().get("agreement");
        assertThrows(UnsupportedOperationException.class, agreement::clear);
        List<?> transfers = (List<?>) plan.toMap().get("transfers");
        assertThrows(UnsupportedOperationException.class, transfers::clear);
        assertThrows(UnsupportedOperationException.class, ((Map<?, ?>) transfers.get(0))::clear);
    }

    @Test
    void requiredAuditPropagatesIoFailureButLegacyBestEffortAppendRemainsUsable() throws Exception {
        ContractPlugin plugin = mock(ContractPlugin.class);
        Path blocked = directory.resolve("not-a-folder");
        Files.writeString(blocked, "file");
        when(plugin.getDataFolder()).thenReturn(blocked.toFile());
        when(plugin.log()).thenReturn(new CubexLogger(Logger.getAnonymousLogger()));
        EventLog events = new EventLog(plugin);
        assertThrows(IOException.class, () -> events.appendRequired("a", "ALLIANCE_PAYOUT_INTENT", "details"));
        assertDoesNotThrow(() -> events.append("a", "LEGACY", "details"));
    }
}
