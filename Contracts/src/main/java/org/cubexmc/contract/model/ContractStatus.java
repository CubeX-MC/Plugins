package org.cubexmc.contract.model;

public enum ContractStatus {
    /** Service contract waiting for any contractor to accept. */
    OPEN,
    /** Wager/partnership waiting for a specific opponent/partner to accept. */
    PENDING_ACCEPT,
    IN_PROGRESS,
    SUBMITTED,
    COMPLETED,
    CANCELLED,
    EXPIRED,
    DISPUTED;

    public boolean isFinal() {
        return this == COMPLETED || this == CANCELLED || this == EXPIRED;
    }

    public boolean countsAsOwnerActive() {
        return this == OPEN || this == PENDING_ACCEPT || this == IN_PROGRESS
            || this == SUBMITTED || this == DISPUTED;
    }

    public boolean countsAsContractorActive() {
        return this == IN_PROGRESS || this == SUBMITTED || this == DISPUTED;
    }

    /** Whether the contract is waiting for a counterparty signature/acceptance. */
    public boolean awaitsAcceptance() {
        return this == OPEN || this == PENDING_ACCEPT;
    }
}
