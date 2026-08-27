package org.cubexmc.contract.model

enum class ContractStatus {
    /** Escrow is reserved, but the contract is hidden until its one-time publication time. */
    SCHEDULED,

    /** Service contract waiting for any contractor to accept. */
    OPEN,

    /** Wager/partnership waiting for a specific opponent/partner to accept. */
    PENDING_ACCEPT,
    /** Alliance waiting for all invited members to fund and sign their own stakes. */
    PENDING_ACCEPT_MULTI,
    IN_PROGRESS,
    SUBMITTED,
    COMPLETED,
    CANCELLED,
    EXPIRED,
    DISPUTED,
    ;

    fun isFinal(): Boolean = this == COMPLETED || this == CANCELLED || this == EXPIRED

    fun countsAsOwnerActive(): Boolean =
        this == SCHEDULED || this == OPEN || this == PENDING_ACCEPT || this == PENDING_ACCEPT_MULTI || this == IN_PROGRESS || this == SUBMITTED || this == DISPUTED

    fun countsAsContractorActive(): Boolean = this == IN_PROGRESS || this == SUBMITTED || this == DISPUTED

    /** Whether the contract is waiting for a counterparty signature/acceptance. */
    fun awaitsAcceptance(): Boolean = this == OPEN || this == PENDING_ACCEPT || this == PENDING_ACCEPT_MULTI
}
