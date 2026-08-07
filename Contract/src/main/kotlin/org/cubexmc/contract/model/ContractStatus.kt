package org.cubexmc.contract.model

enum class ContractStatus {
    /** Service contract waiting for any contractor to accept. */
    OPEN,

    /** Wager/partnership waiting for a specific opponent/partner to accept. */
    PENDING_ACCEPT,
    IN_PROGRESS,
    SUBMITTED,
    COMPLETED,
    CANCELLED,
    EXPIRED,
    DISPUTED,
    ;

    fun isFinal(): Boolean = this == COMPLETED || this == CANCELLED || this == EXPIRED

    fun countsAsOwnerActive(): Boolean =
        this == OPEN || this == PENDING_ACCEPT || this == IN_PROGRESS || this == SUBMITTED || this == DISPUTED

    fun countsAsContractorActive(): Boolean = this == IN_PROGRESS || this == SUBMITTED || this == DISPUTED

    /** Whether the contract is waiting for a counterparty signature/acceptance. */
    fun awaitsAcceptance(): Boolean = this == OPEN || this == PENDING_ACCEPT
}
