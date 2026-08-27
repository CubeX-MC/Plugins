package org.cubexmc.contract.service;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.cubexmc.contract.storage.PendingTransactionStore.FundingPhase.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AllianceFundingServiceTest {
    @TempDir Path directory;
    private final UUID ownerId = new UUID(0, 1), bobId = new UUID(0, 2), caraId = new UUID(0, 3);
    private final BigDecimal ownerStake = new BigDecimal("10.00"), bobStake = new BigDecimal("20.00"), caraStake = new BigDecimal("30.00");
    private ContractPlugin plugin;
    private EconomyService economy;
    private ContractStorage storage;
    private PendingTransactionStore pending;
    private EventLog events;
    private ContractService service;
    private Player owner, bob, cara;
    private YamlConfiguration config;

    @BeforeEach
    void setup() {
        plugin = mock(ContractPlugin.class);
        economy = mock(EconomyService.class);
        var logger = new CubexLogger(Logger.getAnonymousLogger());
        when(plugin.log()).thenReturn(logger);
        when(plugin.text()).thenReturn(new CubexText());
        LanguageManager lang = mock(LanguageManager.class);
        when(plugin.lang()).thenReturn(lang);
        when(lang.ui(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));
        config = new YamlConfiguration();
        config.set("economy.min-reward", 1.0);
        config.set("economy.max-reward", 1000.0);
        when(plugin.getConfig()).thenReturn(config);
        when(economy.has(any(), any())).thenReturn(true);
        when(economy.withdraw(any(), any())).thenReturn(EconomyService.TransactionResult.ok());
        when(economy.deposit(any(), any())).thenReturn(EconomyService.TransactionResult.ok());
        when(economy.format(any())).thenAnswer(i -> i.getArgument(0).toString());
        storage = spy(new ContractStorage(directory.resolve("contract.yml").toFile(), logger));
        pending = spy(new PendingTransactionStore(directory.resolve("pending-transactions.yml").toFile(), logger));
        events = mock(EventLog.class);
        service = new ContractService(plugin, storage, economy, pending, events, mock(BatchAcceptanceStore.class));
        owner = player(ownerId, "Owner"); bob = player(bobId, "Bob"); cara = player(caraId, "Cara");
    }

    private Player player(UUID id, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private ServiceResult create() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer("Bob")).thenReturn(bob);
            bukkit.when(() -> Bukkit.getOfflinePlayer("Cara")).thenReturn(cara);
            return service.createAlliance(owner, ownerStake, Map.of("Bob", bobStake, "Cara", caraStake), 1, "Alliance", "terms");
        }
    }

    private Contract created() {
        ServiceResult result = create();
        assertTrue(result.success(), result.reason());
        return result.contract();
    }

    private PendingTransactionStore.PendingEntry onlyPending() {
        assertEquals(1, pending.loadAll().size());
        return pending.loadAll().get(0);
    }

    @Test
    void createsThenFundsEachUuidExactlyOnceAndOnlyLastSignatureActivates() {
        Contract contract = created();
        verify(economy).withdraw(owner, ownerStake);
        verify(economy, never()).withdraw(bob, bobStake);
        assertEquals(ContractStatus.PENDING_ACCEPT_MULTI, contract.status());
        assertTrue(service.accept(bob, contract).success());
        assertEquals(ContractStatus.PENDING_ACCEPT_MULTI, contract.status());
        assertNull(contract.acceptedAt());
        assertFalse(service.accept(bob, contract).success());
        assertTrue(service.accept(cara, contract).success());
        assertEquals(ContractStatus.IN_PROGRESS, contract.status());
        assertEquals(contract.allianceAgreement().signatures().values().stream().max(Long::compareTo).orElseThrow(), contract.acceptedAt());
        assertFalse(service.accept(cara, contract).success());
        verify(economy, times(1)).withdraw(bob, bobStake);
        verify(economy, times(1)).withdraw(cara, caraStake);
        verify(economy, never()).deposit(any(), any());
        assertTrue(pending.loadAll().isEmpty());
        var disk = new ContractStorage(directory.resolve("contract.yml").toFile(), plugin.log());
        disk.load();
        Contract restored = disk.findById(contract.id()).orElseThrow();
        assertTrue(restored.allianceAgreement().allAccepted());
        assertNotNull(restored.metadata.get("alliance-funding-op-" + bobId));
    }

    @Test
    void concurrentDoubleSignatureOnlyWithdrawsOnce() throws Exception {
        Contract contract = created();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.accept(bob, contract));
            var second = executor.submit(() -> service.accept(bob, contract));
            assertNotEquals(first.get().success(), second.get().success());
            verify(economy, times(1)).withdraw(bob, bobStake);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void permissionFundsDeadlineOwnershipAndStaleObjectAreCheckedBeforeWithdrawal() {
        when(owner.hasPermission("contract.create")).thenReturn(false);
        assertFalse(create().success());
        verifyNoInteractions(economy);
        when(owner.hasPermission("contract.create")).thenReturn(true);
        Contract contract = created();
        when(bob.hasPermission("contract.accept")).thenReturn(false);
        assertFalse(service.accept(bob, contract).success());
        when(bob.hasPermission("contract.accept")).thenReturn(true);
        when(economy.has(bob, bobStake)).thenReturn(false);
        assertFalse(service.accept(bob, contract).success());
        assertFalse(service.accept(player(UUID.randomUUID(), "Stranger"), contract).success());
        assertFalse(service.accept(owner, contract).success());
        var expired = Contract.createAlliance("expired",
            new Participant(ParticipantRole.OWNER, ownerId, "Owner", List.of(Asset.money(ownerStake))),
            List.of(new Participant(ParticipantRole.ALLY, bobId, "Bob", List.of(Asset.money(bobStake))),
                new Participant(ParticipantRole.ALLY, caraId, "Cara", List.of(Asset.money(caraStake)))), "t", "d", 0L, 1L);
        storage.put(expired);
        assertFalse(service.accept(bob, expired).success());
        storage.remove(contract.id());
        assertFalse(service.accept(cara, contract).success());
        verify(economy, never()).withdraw(bob, bobStake);
        verify(economy, never()).withdraw(cara, caraStake);
    }

    @Test
    void creationValidatesUniqueResolvedRosterAmountsAndOpenLimit() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer(anyString())).thenReturn(bob);
            assertFalse(service.createAlliance(owner, ownerStake, Map.of("Bob", bobStake, "Alias", caraStake), 1, "t", "d").success());
            assertFalse(service.createAlliance(owner, new BigDecimal("1.001"), Map.of("Bob", bobStake, "Cara", caraStake), 1, "t", "d").success());
            assertFalse(service.createAlliance(owner, ownerStake, Map.of("Bob", bobStake), 1, "t", "d").success());
            verifyNoInteractions(economy);
        }
        config.set("limits.max-open-contracts", 1);
        created();
        assertFalse(create().success());
        verify(economy, times(1)).withdraw(owner, ownerStake);
    }

    @Test
    void partiallyFundedAllianceCountsTowardAllysLimitButInvitationsDoNot() {
        config.set("limits.max-active-accepted-contracts", 1);
        Contract first = created(), second = created();
        assertTrue(service.accept(bob, first).success());
        assertFalse(service.accept(bob, second).success());
        assertTrue(service.accept(cara, second).success());
        verify(economy, times(1)).withdraw(bob, bobStake);
    }

    @Test
    void journalWriteFailureNeverCallsVault() throws Exception {
        doThrow(new IOException("disk")).when(pending).beginAllianceWithdraw(any(), any(), anyString(), anyString());
        assertFalse(create().success());
        verify(economy, never()).withdraw(any(), any());
        assertTrue(storage.all().isEmpty());
    }

    @Test
    void definiteWithdrawFailureWithClearFailureIsNotRefundedOnRestart() throws Exception {
        when(economy.withdraw(owner, ownerStake)).thenReturn(EconomyService.TransactionResult.fail("denied"));
        doThrow(new IOException("clear")).when(pending).clear(anyString());
        assertFalse(create().success());
        assertEquals(REJECTED, onlyPending().fundingPhase());
        doCallRealMethod().when(pending).clear(anyString());
        service.recoverPendingTransactions();
        assertTrue(pending.loadAll().isEmpty());
        verify(economy, never()).deposit(any(), any());
    }

    @Test
    void unknownWithdrawOutcomeRemainsPreparedAndBlocksRetryWithoutRefund() {
        when(economy.withdraw(owner, ownerStake)).thenThrow(new IllegalStateException("connection lost"));
        assertFalse(create().success());
        assertEquals(PREPARED, onlyPending().fundingPhase());
        assertFalse(create().success());
        service.recoverPendingTransactions();
        verify(economy, times(1)).withdraw(owner, ownerStake);
        verify(economy, never()).deposit(any(), any());
        assertEquals(PREPARED, onlyPending().fundingPhase());
    }

    @Test
    void withdrawnPhaseSaveFailureDoesNotCommitOrGuessAtRefund() throws Exception {
        doThrow(new IOException("phase")).when(pending).advanceFunding(anyString(), eq(PREPARED), eq(WITHDRAWN));
        assertFalse(create().success());
        assertTrue(storage.all().isEmpty());
        assertEquals(PREPARED, onlyPending().fundingPhase());
        service.recoverPendingTransactions();
        verify(economy, never()).deposit(any(), any());
    }

    @Test
    void failedSignatureSaveRestoresSnapshotAndRefundsOnce() throws Exception {
        Contract contract = created();
        var before = contract.allianceAgreement();
        doThrow(new IOException("contract save")).when(storage).save();
        assertFalse(service.accept(bob, contract).success());
        assertSame(before, contract.allianceAgreement());
        assertNull(contract.metadata.get("alliance-funding-op-" + bobId));
        assertEquals(ContractStatus.PENDING_ACCEPT_MULTI, contract.status());
        assertTrue(pending.loadAll().isEmpty());
        service.recoverPendingTransactions();
        verify(economy, times(1)).deposit(bobId, bobStake);
        doCallRealMethod().when(storage).save();
        assertTrue(service.accept(bob, contract).success());
    }

    @Test
    void failedFinalSignatureSaveRestoresGlobalStatusAndAcceptedTime() throws Exception {
        Contract contract = created();
        assertTrue(service.accept(bob, contract).success());
        doThrow(new IOException("save")).when(storage).save();
        assertFalse(service.accept(cara, contract).success());
        assertEquals(ContractStatus.PENDING_ACCEPT_MULTI, contract.status());
        assertNull(contract.acceptedAt());
        assertFalse(contract.allianceAgreement().hasAccepted(caraId));
        verify(economy).deposit(caraId, caraStake);
    }

    @Test
    void failedCreationSaveRemovesUncommittedContractAndCompensates() throws Exception {
        doThrow(new IOException("save")).when(storage).save();
        assertFalse(create().success());
        assertTrue(storage.all().isEmpty());
        assertTrue(pending.loadAll().isEmpty());
        verify(economy).deposit(ownerId, ownerStake);
    }

    @Test
    void refundRejectionCanRetryButConfirmedRefundWithClearFailureCannotDoublePay() throws Exception {
        Contract contract = created();
        doThrow(new IOException("save")).when(storage).save();
        when(economy.deposit(bobId, bobStake)).thenReturn(EconomyService.TransactionResult.fail("offline"));
        assertFalse(service.accept(bob, contract).success());
        assertEquals(WITHDRAWN, onlyPending().fundingPhase());
        assertFalse(service.accept(cara, contract).success()); // same contract remains locked
        when(economy.deposit(bobId, bobStake)).thenReturn(EconomyService.TransactionResult.ok());
        doThrow(new IOException("clear")).when(pending).clear(anyString());
        service.recoverPendingTransactions();
        assertEquals(REFUNDED, onlyPending().fundingPhase());
        doCallRealMethod().when(pending).clear(anyString());
        service.recoverPendingTransactions();
        verify(economy, times(2)).deposit(bobId, bobStake); // rejected call + one successful call
        assertTrue(pending.loadAll().isEmpty());
        verify(economy, never()).withdraw(cara, caraStake);
    }

    @Test
    void refundAcknowledgmentFailureLeavesRefundingAndNeverReplaysDeposit() throws Exception {
        Contract contract = created();
        doThrow(new IOException("save")).when(storage).save();
        doThrow(new IOException("ack")).when(pending).advanceFunding(anyString(), eq(REFUNDING), eq(REFUNDED));
        assertFalse(service.accept(bob, contract).success());
        assertEquals(REFUNDING, onlyPending().fundingPhase());
        service.recoverPendingTransactions();
        service.recoverPendingTransactions();
        verify(economy, times(1)).deposit(bobId, bobStake);
    }

    @Test
    void committedSignatureWithUnclearedJournalSurvivesReloadWithoutRefundEvenWhilePending() throws Exception {
        Contract contract = created();
        doThrow(new IOException("clear")).when(pending).clear(anyString());
        assertTrue(service.accept(bob, contract).success());
        assertEquals(WITHDRAWN, onlyPending().fundingPhase());
        assertEquals(ContractStatus.PENDING_ACCEPT_MULTI, contract.status());
        var reloaded = new ContractStorage(directory.resolve("contract.yml").toFile(), plugin.log());
        reloaded.load();
        var restarted = new ContractService(plugin, reloaded, economy,
            new PendingTransactionStore(directory.resolve("pending-transactions.yml").toFile(), plugin.log()), events, mock(BatchAcceptanceStore.class));
        restarted.recoverPendingTransactions();
        restarted.recoverPendingTransactions();
        verify(economy, never()).deposit(any(), any());
        assertTrue(pending.loadAll().isEmpty());
    }

    @Test
    void refundWriteAheadFailureDoesNotCallVaultUntilIntentCanBeSaved() throws Exception {
        Contract contract = created();
        doThrow(new IOException("save")).when(storage).save();
        doThrow(new IOException("refund intent")).when(pending).advanceFunding(anyString(), eq(WITHDRAWN), eq(REFUNDING));
        assertFalse(service.accept(bob, contract).success());
        assertEquals(WITHDRAWN, onlyPending().fundingPhase());
        verify(economy, never()).deposit(any(), any());
        doCallRealMethod().when(pending).advanceFunding(anyString(), eq(WITHDRAWN), eq(REFUNDING));
        service.recoverPendingTransactions();
        verify(economy, times(1)).deposit(bobId, bobStake);
        assertTrue(pending.loadAll().isEmpty());
    }

    @Test
    void throwingRefundIsAmbiguousAndCannotAutomaticallyReplay() throws Exception {
        Contract contract = created();
        doThrow(new IOException("save")).when(storage).save();
        when(economy.deposit(bobId, bobStake)).thenThrow(new IllegalStateException("unknown response"));
        assertFalse(service.accept(bob, contract).success());
        assertEquals(REFUNDING, onlyPending().fundingPhase());
        service.recoverPendingTransactions();
        service.recoverPendingTransactions();
        assertFalse(service.accept(bob, contract).success());
        verify(economy, times(1)).deposit(bobId, bobStake);
    }

    @Test
    void corruptJournalBlocksFundingBeforeBalanceOrWithdrawalCalls() throws Exception {
        java.nio.file.Files.writeString(directory.resolve("pending-transactions.yml"), "pending: [broken");
        assertFalse(create().success());
        verifyNoInteractions(economy);
        assertTrue(storage.all().isEmpty());
    }

    @Test
    void mismatchedOperationCannotBeClearedOrRefundedUsingSomeOtherMembersSignature() throws Exception {
        Contract contract = created();
        assertTrue(service.accept(bob, contract).success());
        String wrong = pending.beginAllianceWithdraw(bobId, bobStake, "alliance-accept", contract.id());
        pending.advanceFunding(wrong, PREPARED, WITHDRAWN);
        service.recoverPendingTransactions();
        assertEquals(wrong, onlyPending().id());
        verify(economy, never()).deposit(any(), any());
    }

    @Test
    void orphanConfirmedCreatorWithdrawalIsCompensatedButMissingAcceptanceIsManual() throws Exception {
        String create = pending.beginAllianceWithdraw(ownerId, ownerStake, "alliance-create", "orphan");
        pending.advanceFunding(create, PREPARED, WITHDRAWN);
        service.recoverPendingTransactions();
        assertTrue(pending.loadAll().isEmpty());
        verify(economy).deposit(ownerId, ownerStake);
        String accept = pending.beginAllianceWithdraw(bobId, bobStake, "alliance-accept", "missing");
        pending.advanceFunding(accept, PREPARED, WITHDRAWN);
        service.recoverPendingTransactions();
        assertEquals(accept, onlyPending().id());
        verify(economy, never()).deposit(bobId, bobStake);
    }
}
