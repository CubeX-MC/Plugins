package org.cubexmc.contract.service;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.cubexmc.contract.ContractPlugin;
import org.cubexmc.contract.config.LanguageManager;
import org.cubexmc.contract.economy.EconomyService;
import org.cubexmc.contract.model.*;
import org.cubexmc.contract.storage.*;
import org.cubexmc.core.CubexLogger;
import org.cubexmc.core.CubexText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static org.cubexmc.contract.model.AllianceSettlement.Phase.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real contract/journal files with failures at each external-effect boundary. */
class AllianceSettlementServiceTest {
    @TempDir Path directory;
    private static final UUID OWNER = new UUID(0, 1), BOB = new UUID(0, 2), CARA = new UUID(0, 3);
    private ContractPlugin plugin;
    private EconomyService economy;
    private ContractStorage storage;
    private PendingTransactionStore pending;
    private EventLog events;
    private ContractService service;
    private CubexLogger logger;
    private final Map<UUID, BigDecimal> credited = new LinkedHashMap<>();

    @BeforeEach
    void setup() {
        plugin = mock(ContractPlugin.class);
        logger = new CubexLogger(Logger.getAnonymousLogger());
        when(plugin.log()).thenReturn(logger);
        when(plugin.text()).thenReturn(new CubexText());
        var lang = mock(LanguageManager.class);
        when(plugin.lang()).thenReturn(lang);
        when(lang.ui(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.reputation()).thenReturn(mock(ReputationStore.class));
        economy = mock(EconomyService.class);
        when(economy.deposit(any(), any())).thenAnswer(i -> {
            credit(i.getArgument(0), i.getArgument(1));
            return EconomyService.TransactionResult.ok();
        });
        events = mock(EventLog.class);
        stores(false);
    }

    private void credit(UUID recipient, BigDecimal amount) { credited.merge(recipient, amount, BigDecimal::add); }
    private void stores(boolean load) {
        storage = spy(new ContractStorage(directory.resolve("contract.yml").toFile(), logger));
        pending = spy(new PendingTransactionStore(directory.resolve("pending.yml").toFile(), logger));
        if (load) storage.load();
        service = new ContractService(plugin, storage, economy, pending, events, mock(BatchAcceptanceStore.class));
    }
    private Contract restart(Contract old) {
        stores(true);
        return storage.findById(old.id()).orElseThrow();
    }
    private Player player(UUID id) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(id.toString());
        when(p.hasPermission(anyString())).thenReturn(true);
        return p;
    }
    private Participant member(ParticipantRole role, UUID id, String amount) {
        return new Participant(role, id, id.toString(), List.of(Asset.money(new BigDecimal(amount))));
    }
    private Contract contract(boolean full, boolean expired) throws Exception {
        long now = System.currentTimeMillis();
        Contract c = Contract.createAlliance("alliance", member(ParticipantRole.OWNER, OWNER, "10.01"),
            List.of(member(ParticipantRole.ALLY, CARA, "30.00"), member(ParticipantRole.ALLY, BOB, "20.00")),
            "title", "terms", now - 10000, expired ? now - 1000 : now + 100000);
        c.allianceAgreement(c.allianceAgreement().accept(CARA, now - 9000));
        if (full) {
            c.allianceAgreement(c.allianceAgreement().accept(BOB, now - 8000));
            c.status(ContractStatus.IN_PROGRESS);
            c.acceptedAt(now - 8000);
        }
        storage.put(c);
        storage.save();
        return c;
    }
    private void firstApprovals(Contract c) {
        assertTrue(service.approve(player(OWNER), c).success());
        assertTrue(service.approve(player(CARA), c).success());
        verify(economy, never()).deposit(any(), any());
    }
    private PendingTransactionStore.PendingEntry intent() {
        assertEquals(1, pending.loadAll().size());
        return pending.loadAll().get(0);
    }
    private void assertFullRefund() {
        assertEquals(Map.of(OWNER, new BigDecimal("10.01"), BOB, new BigDecimal("20.00"), CARA, new BigDecimal("30.00")), credited);
        verify(economy, times(3)).deposit(any(), any());
    }

    @Test
    void unanimousApprovalsPersistAndPayEachUuidOnceEvenUnderConcurrentLastApproval() throws Exception {
        Contract c = contract(true, false);
        firstApprovals(c);
        assertFalse(service.approve(player(CARA), c).success());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> service.approve(player(BOB), c));
            var b = executor.submit(() -> service.approve(player(BOB), c));
            assertNotEquals(a.get().success(), b.get().success());
        } finally { executor.shutdownNow(); }
        assertEquals(ContractStatus.COMPLETED, c.status());
        assertFullRefund();
        assertTrue(pending.loadAll().isEmpty());
        Contract restored = restart(c);
        service.recoverPendingTransactions();
        assertTrue(restored.allianceAgreement().allApproved());
        assertFalse(service.approve(player(BOB), restored).success());
        assertFalse(service.adminRefund(restored, "Admin").success());
        assertFullRefund();
    }

    @Test
    void partialCancellationRefundsOnlyFundedMembersAndRejectsInvitees() throws Exception {
        Contract c = contract(false, false);
        assertFalse(service.approve(player(CARA), c).success());
        assertFalse(service.cancel(player(BOB), c).success());
        assertTrue(service.cancel(player(CARA), c).success());
        assertEquals(ContractStatus.CANCELLED, restart(c).status());
        assertEquals(Map.of(OWNER, new BigDecimal("10.01"), CARA, new BigDecimal("30.00")), credited);
        verify(economy, times(2)).deposit(any(), any());
    }

    @Test
    void unsignedTimeoutRefundsWhileFullySignedDeadlineDoesNotAutoPay() throws Exception {
        Contract c = contract(false, true);
        assertEquals(1, service.cleanupExpired());
        assertEquals(ContractStatus.EXPIRED, restart(c).status());
        assertEquals(new BigDecimal("40.01"), credited.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(0, service.cleanupExpired());
        Contract active = contract(true, true);
        assertEquals(0, service.cleanupExpired());
        assertEquals(ContractStatus.IN_PROGRESS, active.status());
        verify(economy, times(2)).deposit(any(), any());
    }

    @Test
    void activeCancellationDisputesThenAdminRefundReturnsEveryPrincipal() throws Exception {
        Contract c = contract(true, false);
        assertTrue(service.cancel(player(BOB), c).success());
        assertEquals(ContractStatus.DISPUTED, c.status());
        verify(economy, never()).deposit(any(), any());
        assertFalse(service.adminClose(c, "Admin").success());
        assertTrue(service.adminRefund(c, "Admin").success());
        assertFullRefund();
        assertEquals(ContractStatus.CANCELLED, restart(c).status());
    }

    @Test
    void namedBreachUsesUuidSortedRemainderAndRequiresAdminDisputedFullySignedContract() throws Exception {
        Contract c = contract(true, false);
        assertFalse(service.resolveAlliance(player(OWNER), c, OWNER).success());
        assertTrue(service.dispute(player(BOB), c, "breach").success());
        Player denied = player(CARA);
        when(denied.hasPermission("contract.admin.settle")).thenReturn(false);
        assertFalse(service.resolveAlliance(denied, c, OWNER).success());
        assertFalse(service.resolveAlliance(player(CARA), c, UUID.randomUUID()).success());
        assertTrue(service.resolveAlliance(player(CARA), c, OWNER).success());
        assertEquals(Map.of(BOB, new BigDecimal("25.01"), CARA, new BigDecimal("35.00")), credited);
        verify(economy, never()).deposit(eq(OWNER), any());
        assertEquals(ContractStatus.COMPLETED, restart(c).status());
    }

    @Test
    void staleObjectsForeignPlayersAndPermissionsCannotApproveOrCancel() throws Exception {
        Contract old = contract(true, false);
        Contract current = restart(old);
        assertFalse(service.approve(player(OWNER), old).success());
        assertFalse(service.cancel(player(OWNER), old).success());
        assertFalse(service.approve(player(UUID.randomUUID()), current).success());
        Player denied = player(CARA);
        when(denied.hasPermission(anyString())).thenReturn(false);
        assertFalse(service.approve(denied, current).success());
        assertFalse(service.cancel(denied, current).success());
        verifyNoInteractions(economy);
    }

    @Test
    void failedApprovalAuditOrSaveDoesNotLeavePhantomApproval() throws Exception {
        Contract c = contract(true, false);
        doThrow(new IOException("audit")).when(events).appendRequired(anyString(), eq("ALLIANCE_APPROVED"), any());
        assertFalse(service.approve(player(OWNER), c).success());
        assertTrue(c.allianceAgreement().approvals().isEmpty());
        reset(events);
        doThrow(new IOException("disk")).when(storage).save();
        assertFalse(service.approve(player(OWNER), c).success());
        assertTrue(c.allianceAgreement().approvals().isEmpty());
        verifyNoInteractions(economy);
        assertTrue(restart(c).allianceAgreement().approvals().isEmpty());
    }

    @Test
    void failedJournalPreparationAfterLastApprovalCanBeRetriedWithoutDoubleApprovalOrPayment() throws Exception {
        Contract c = contract(true, false);
        firstApprovals(c);
        doThrow(new IOException("journal")).when(pending).beginAllianceSettlement(anyString(), any());
        assertFalse(service.approve(player(BOB), c).success());
        assertTrue(c.allianceAgreement().allApproved());
        verifyNoInteractions(economy);
        c = restart(c);
        assertTrue(service.approve(player(BOB), c).success());
        assertFullRefund();
    }

    enum Boundary { ANCHOR_AUDIT, ANCHOR_SAVE, PAYOUT_AUDIT, PAYOUT_INTENT, FINAL_AUDIT, FINAL_SAVE, CLEAR }

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void knownPaymentsAndUnstartedPaymentsRecoverAcrossEveryPersistenceBoundary(Boundary boundary) throws Exception {
        Contract c = contract(true, false);
        firstApprovals(c);
        switch (boundary) {
            case ANCHOR_AUDIT -> doThrow(new IOException("audit")).when(events)
                .appendRequired(anyString(), eq("ALLIANCE_SETTLEMENT_PREPARED"), any());
            case ANCHOR_SAVE -> doAnswer(i -> {
                if (c.metadata.containsKey("alliance-settlement-op")) throw new IOException("anchor");
                return i.callRealMethod();
            }).when(storage).save();
            case PAYOUT_AUDIT -> doThrow(new IOException("audit")).when(events)
                .appendRequired(anyString(), eq("ALLIANCE_PAYOUT_INTENT"), any());
            case PAYOUT_INTENT -> doThrow(new IOException("intent")).when(pending)
                .advanceAlliancePayment(anyString(), any(), eq(READY), eq(PAYING));
            case FINAL_AUDIT -> doThrow(new IOException("audit")).when(events)
                .appendRequired(anyString(), eq("ALLIANCE_SETTLED"), any());
            case FINAL_SAVE -> doAnswer(i -> {
                if (c.status().isFinal()) throw new IOException("terminal");
                return i.callRealMethod();
            }).when(storage).save();
            case CLEAR -> doThrow(new IOException("clear")).when(pending).clear(anyString());
        }
        ServiceResult result = service.approve(player(BOB), c);
        assertEquals(boundary == Boundary.CLEAR, result.success());
        assertFalse(pending.loadAll().isEmpty());
        if (boundary == Boundary.FINAL_SAVE) {
            assertEquals(ContractStatus.IN_PROGRESS, c.status());
            assertNull(c.completedAt());
        }
        reset(events);
        Contract restored = restart(c);
        service.recoverPendingTransactions();
        service.recoverPendingTransactions();
        assertEquals(ContractStatus.COMPLETED, restored.status());
        assertTrue(pending.loadAll().isEmpty());
        assertFullRefund();
    }

    @Test
    void crashAfterConfirmedFirstPaymentSkipsItAndFinishesRemainingRecipients() throws Exception {
        Contract c = contract(true, false);
        firstApprovals(c);
        doAnswer(i -> {
            i.callRealMethod();
            throw new SimulatedCrash();
        }).when(pending).advanceAlliancePayment(anyString(), eq(OWNER), eq(PAYING), eq(PAID));
        assertThrows(SimulatedCrash.class, () -> service.approve(player(BOB), c));
        assertEquals(PAID, intent().allianceSettlement().phase(OWNER));
        Contract restored = restart(c);
        service.recoverPendingTransactions();
        assertFullRefund();
        assertEquals(ContractStatus.COMPLETED, restored.status());
    }

    enum Uncertain { FAILURE_REPLY, THROWN_REPLY, CRASH_AFTER_CREDIT, CONFIRM_SAVE }

    @ParameterizedTest
    @EnumSource(Uncertain.class)
    void uncertainPayoutNeverReplaysAndBlocksAllAlternateRoutesAfterRestart(Uncertain boundary) throws Exception {
        Contract c = contract(true, false);
        firstApprovals(c);
        if (boundary == Uncertain.CONFIRM_SAVE) {
            doThrow(new IOException("confirm")).when(pending)
                .advanceAlliancePayment(anyString(), eq(CARA), eq(PAYING), eq(PAID));
        } else {
            doAnswer(i -> {
                credit(CARA, i.getArgument(1)); // A failed response can still follow a real credit.
                if (boundary == Uncertain.FAILURE_REPLY) return EconomyService.TransactionResult.fail("failed reply");
                if (boundary == Uncertain.THROWN_REPLY) throw new IllegalStateException("lost reply");
                throw new SimulatedCrash();
            }).when(economy).deposit(eq(CARA), any());
        }
        if (boundary == Uncertain.CRASH_AFTER_CREDIT)
            assertThrows(SimulatedCrash.class, () -> service.approve(player(BOB), c));
        else assertFalse(service.approve(player(BOB), c).success());
        assertEquals(PAID, intent().allianceSettlement().phase(OWNER));
        assertEquals(PAYING, intent().allianceSettlement().phase(CARA));
        Map<UUID, BigDecimal> before = Map.copyOf(credited);
        BigDecimal remaining = intent().allianceSettlement().payments().entrySet().stream()
            .filter(e -> intent().allianceSettlement().phase(e.getKey()) == READY)
            .map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("60.01"), credited.values().stream().reduce(remaining, BigDecimal::add));
        Contract restored = restart(c);
        service.recoverPendingTransactions();
        service.recoverPendingTransactions();
        assertEquals(before, credited);
        assertFalse(service.adminRefund(restored, "Admin").success());
        assertFalse(service.adminClose(restored, "Admin").success());
        assertFalse(service.resolveAlliance(player(OWNER), restored, BOB).success());
        assertFalse(service.approve(player(BOB), restored).success());
        assertFalse(service.cancel(player(OWNER), restored).success());
        assertFalse(service.dispute(player(OWNER), restored, "retry").success());
        assertFalse(pending.loadAll().isEmpty());
        verify(economy, times(2)).deposit(any(), any());
    }

    @Test
    void crashAfterPayingIntentBeforeVaultCallRemainsAmbiguousAndDoesNotCredit() throws Exception {
        Contract c = contract(false, false);
        doAnswer(i -> { i.callRealMethod(); throw new SimulatedCrash(); }).when(pending)
            .advanceAlliancePayment(anyString(), eq(OWNER), eq(READY), eq(PAYING));
        assertThrows(SimulatedCrash.class, () -> service.cancel(player(OWNER), c));
        assertEquals(PAYING, intent().allianceSettlement().phase(OWNER));
        restart(c);
        service.recoverPendingTransactions();
        verifyNoInteractions(economy);
    }

    @Test
    void pendingFundingBlocksTimeoutCancellationApprovalsResolutionAndFinalRetention() throws Exception {
        Contract c = contract(false, true);
        pending.beginAllianceWithdraw(BOB, new BigDecimal("20.00"), "alliance-accept", c.id());
        assertEquals(0, service.cleanupExpired());
        assertFalse(service.cancel(player(CARA), c).success());
        assertFalse(service.adminRefund(c, "Admin").success());
        assertFalse(service.adminClose(c, "Admin").success());
        c.status(ContractStatus.COMPLETED);
        c.completedAt(0L);
        storage.save();
        assertEquals(0, service.cleanupExpired());
        assertTrue(storage.findById(c.id()).isPresent());
        restart(c);
        service.recoverPendingTransactions();
        assertFalse(pending.loadAll().isEmpty());
        verifyNoInteractions(economy);
    }

    @Test
    void readySettlementIsBlockedUntilPendingFundingIsResolved() throws Exception {
        Contract c = contract(false, false);
        var plan = AllianceSettlement.create(c, AllianceSettlement.Outcome.REFUND, null);
        pending.beginAllianceSettlement(c.id(), plan);
        pending.beginAllianceWithdraw(BOB, new BigDecimal("20.00"), "alliance-accept", c.id());
        restart(c);
        service.recoverPendingTransactions();
        verifyNoInteractions(economy);
        assertEquals(2, pending.loadAll().size());
    }

    @Test
    void oldGenericAllianceJournalIsRetainedWithoutRoleBasedRecoveryOrRefund() throws Exception {
        Contract c = contract(false, false);
        pending.beginSettlement(c.id(), "legacy");
        pending.beginWithdraw(BOB, new BigDecimal("20.00"), "legacy-accept", c.id());
        service.recoverPendingTransactions();
        assertEquals(2, pending.loadAll().size());
        verifyNoInteractions(economy);
    }

    @Test
    void allocationTamperingOrMissingContractCannotPayOrClearItsJournal() throws Exception {
        Contract c = contract(false, false);
        pending.beginAllianceSettlement(c.id(), AllianceSettlement.create(c, AllianceSettlement.Outcome.REFUND, null));
        var yaml = YamlConfiguration.loadConfiguration(directory.resolve("pending.yml").toFile());
        String id = intent().id();
        List<Map<Object, Object>> rows = new ArrayList<>();
        for (Map<?, ?> row : yaml.getMapList("pending." + id + ".alliance-settlement.transfers")) rows.add(new LinkedHashMap<>(row));
        rows.get(0).put("amount", "999.00");
        yaml.set("pending." + id + ".alliance-settlement.transfers", rows);
        yaml.save(directory.resolve("pending.yml").toFile());
        service.recoverPendingTransactions();
        assertFalse(pending.loadAll().isEmpty());
        storage.remove(c.id());
        service.recoverPendingTransactions();
        assertFalse(pending.loadAll().isEmpty());
        verifyNoInteractions(economy);
    }

    @Test
    void unreadableJournalBlocksFinancialActionsAndCannotBeOverwritten() throws Exception {
        Contract c = contract(false, true);
        Files.writeString(directory.resolve("pending.yml"), "pending: [broken");
        String before = Files.readString(directory.resolve("pending.yml"));
        assertFalse(service.cancel(player(OWNER), c).success());
        assertFalse(service.adminRefund(c, "Admin").success());
        assertEquals(0, service.cleanupExpired());
        assertEquals(before, Files.readString(directory.resolve("pending.yml")));
        verifyNoInteractions(economy);
    }

    private static final class SimulatedCrash extends Error {}
}
