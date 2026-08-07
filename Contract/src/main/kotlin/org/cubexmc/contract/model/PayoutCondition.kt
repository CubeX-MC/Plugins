package org.cubexmc.contract.model

enum class PayoutCondition {
    SUCCESS,
    FAILURE,
    TIMEOUT,
    DISPUTE_RESOLVED_FOR_OWNER,
    DISPUTE_RESOLVED_FOR_CONTRACTOR,
}
