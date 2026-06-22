package org.cubexmc.contract.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractStatusTest {
    @Test
    void isFinalCoversTerminalStates() {
        assertTrue(ContractStatus.COMPLETED.isFinal());
        assertTrue(ContractStatus.CANCELLED.isFinal());
        assertTrue(ContractStatus.EXPIRED.isFinal());
        assertFalse(ContractStatus.OPEN.isFinal());
        assertFalse(ContractStatus.IN_PROGRESS.isFinal());
        assertFalse(ContractStatus.SUBMITTED.isFinal());
        assertFalse(ContractStatus.DISPUTED.isFinal());
    }

    @Test
    void ownerActiveExcludesFinalStates() {
        assertTrue(ContractStatus.OPEN.countsAsOwnerActive());
        assertTrue(ContractStatus.IN_PROGRESS.countsAsOwnerActive());
        assertTrue(ContractStatus.SUBMITTED.countsAsOwnerActive());
        assertTrue(ContractStatus.DISPUTED.countsAsOwnerActive());
        assertFalse(ContractStatus.COMPLETED.countsAsOwnerActive());
        assertFalse(ContractStatus.CANCELLED.countsAsOwnerActive());
        assertFalse(ContractStatus.EXPIRED.countsAsOwnerActive());
    }

    @Test
    void contractorActiveExcludesOpen() {
        assertFalse(ContractStatus.OPEN.countsAsContractorActive());
        assertTrue(ContractStatus.IN_PROGRESS.countsAsContractorActive());
        assertTrue(ContractStatus.SUBMITTED.countsAsContractorActive());
        assertTrue(ContractStatus.DISPUTED.countsAsContractorActive());
    }

    @Test
    void pendingAcceptCountsAsOwnerActiveOnly() {
        assertTrue(ContractStatus.PENDING_ACCEPT.countsAsOwnerActive());
        assertFalse(ContractStatus.PENDING_ACCEPT.countsAsContractorActive());
        assertFalse(ContractStatus.PENDING_ACCEPT.isFinal());
        assertTrue(ContractStatus.PENDING_ACCEPT.awaitsAcceptance());
        assertTrue(ContractStatus.OPEN.awaitsAcceptance());
        assertFalse(ContractStatus.IN_PROGRESS.awaitsAcceptance());
    }
}
