package org.cubexmc.contract.api.escrow

/** Stable machine-readable outcomes for the optional region funding service. */
enum class ContractEscrowCode {
    OK,
    REPLAYED,
    INVALID_REQUEST,
    CONTRACT_NOT_FOUND,
    NOT_ELIGIBLE,
    LOCK_CONFLICT,
    OPERATION_CONFLICT,
    PERSISTENCE_FAILED,
    SETTLEMENT_FAILED,
    REVIEW_REQUIRED,
}
