package org.cubexmc.contract.model

enum class ResolutionRule {
    OWNER_APPROVE,
    BOTH_APPROVE,
    ALL_APPROVE,
    ARBITER,
    SYSTEM_OBJECTIVE,
    TIMEOUT,
    // EVENT was removed with BOUNTY: SYSTEM_OBJECTIVE already covers automatic settlement.
}
