package org.cubexmc.contract.integration.regions

internal object RegionFundingMetadata {
    const val REGION_ID = "region-funding-region"
    const val LOCK_OPERATION = "region-funding-lock-operation"
    const val TERMINAL_OPERATION = "region-funding-terminal-operation"
    const val TERMINAL_ACTION = "region-funding-terminal-action"
    const val TERMINAL_STATE = "region-funding-terminal-state"
    const val TERMINAL_AMOUNT = "region-funding-terminal-amount"

    const val PROCESSING = "PROCESSING"
    const val COMPLETE = "COMPLETE"
    const val REVIEW = "REVIEW_REQUIRED"
}
