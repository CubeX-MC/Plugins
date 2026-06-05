package org.cubexmc.contract.service;

import org.cubexmc.contract.model.ContractStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
