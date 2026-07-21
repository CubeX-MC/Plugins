package org.cubexmc.contract.service;

import org.cubexmc.contract.model.ContractStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the crash-recovery decision for lingering write-ahead withdraw entries (H3). */
class ContractServiceRecoveryTest {

    @Test
    void refundsWhenNoContractPersisted() {
        // Contract absent ⇒ the operation never committed ⇒ refund the withdrawn money.
        assertTrue(ContractService.shouldRefundOrphanWithdraw("contract-create", null));
        assertTrue(ContractService.shouldRefundOrphanWithdraw("wager-accept", null));
    }

    @Test
    void doesNotRefundCreatedContractThatPersisted() {
        // *-create: the contract exists at all ⇒ creation persisted ⇒ money is rightful escrow ⇒ no refund.
        assertFalse(ContractService.shouldRefundOrphanWithdraw("contract-create", ContractStatus.OPEN));
        assertFalse(ContractService.shouldRefundOrphanWithdraw("wager-create", ContractStatus.PENDING_ACCEPT));
        assertFalse(ContractService.shouldRefundOrphanWithdraw("partnership-create", ContractStatus.COMPLETED));
    }

    @Test
    void refundsAcceptOnlyWhileStillPending() {
        // Counterparty stake only "lands" once acceptance advanced the contract past PENDING_ACCEPT.
        assertTrue(ContractService.shouldRefundOrphanWithdraw("wager-accept", ContractStatus.PENDING_ACCEPT));
        assertFalse(ContractService.shouldRefundOrphanWithdraw("wager-accept", ContractStatus.IN_PROGRESS));
        assertFalse(ContractService.shouldRefundOrphanWithdraw("partnership-accept", ContractStatus.COMPLETED));
    }

    @Test
    void purgesOnlyFinalContractsAfterConfiguredRetention() {
        long now = 10L * 24L * 60L * 60L * 1000L;
        long fiveDaysAgo = now - 5L * 24L * 60L * 60L * 1000L;
        long twoDaysAgo = now - 2L * 24L * 60L * 60L * 1000L;

        assertTrue(ContractService.shouldPurgeFinalContract(ContractStatus.CANCELLED, fiveDaysAgo, now, 3, 3));
        assertTrue(ContractService.shouldPurgeFinalContract(ContractStatus.EXPIRED, fiveDaysAgo, now, 3, 3));
        assertFalse(ContractService.shouldPurgeFinalContract(ContractStatus.COMPLETED, fiveDaysAgo, now, 7, 3));
        assertFalse(ContractService.shouldPurgeFinalContract(ContractStatus.CANCELLED, twoDaysAgo, now, 3, 3));
        assertFalse(ContractService.shouldPurgeFinalContract(ContractStatus.DISPUTED, fiveDaysAgo, now, 3, 3));
        assertFalse(ContractService.shouldPurgeFinalContract(ContractStatus.IN_PROGRESS, fiveDaysAgo, now, 3, 3));
    }

    @Test
    void doesNotPurgeOldFinalDataWithoutCompletedTimestampOrDisabledRetention() {
        long now = 10L * 24L * 60L * 60L * 1000L;
        long fiveDaysAgo = now - 5L * 24L * 60L * 60L * 1000L;

        assertFalse(ContractService.shouldPurgeFinalContract(ContractStatus.CANCELLED, null, now, 3, 3));
        assertFalse(ContractService.shouldPurgeFinalContract(ContractStatus.COMPLETED, fiveDaysAgo, now, 0, 3));
        assertFalse(ContractService.shouldPurgeFinalContract(ContractStatus.CANCELLED, fiveDaysAgo, now, 3, 0));
    }

    @Test
    void batchOpenLimitAccountsForEveryNewContract() {
        assertFalse(ContractService.exceedsOpenLimit(1, 2, 3));
        assertTrue(ContractService.exceedsOpenLimit(2, 2, 3));
        assertTrue(ContractService.exceedsOpenLimit(3, 1, 3));
    }

    @Test
    void onlyMultipleContractsRequireBatchPermission() {
        assertFalse(ContractService.requiresBatchPermission(1));
        assertTrue(ContractService.requiresBatchPermission(2));
        assertTrue(ContractService.requiresBatchPermission(64));
    }

    @Test
    void itemRewardsMustSplitEvenlyAcrossBatch() {
        assertEquals(16, ContractService.perContractItemAmount(64, 4));
        assertEquals(64, ContractService.perContractItemAmount(64, 1));
        assertNull(ContractService.perContractItemAmount(63, 4));
        assertNull(ContractService.perContractItemAmount(1, 0));
    }

    @Test
    void disputesCannotBeOpenedOrRestoredFromDisputedOrFinalStates() {
        assertTrue(ContractService.canInitiatePlayerDispute(ContractStatus.OPEN));
        assertTrue(ContractService.isRestorableDisputeStatus(ContractStatus.SUBMITTED));
        assertFalse(ContractService.canInitiatePlayerDispute(ContractStatus.DISPUTED));
        assertFalse(ContractService.isRestorableDisputeStatus(ContractStatus.DISPUTED));
        assertFalse(ContractService.isRestorableDisputeStatus(ContractStatus.COMPLETED));
    }
}
