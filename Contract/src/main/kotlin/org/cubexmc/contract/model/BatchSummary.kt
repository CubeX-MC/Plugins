package org.cubexmc.contract.model

/** Immutable aggregate of the independently settled child contracts in one explicit batch. */
data class BatchSummary(
    val batchId: String,
    val representative: Contract,
    val children: List<Contract>,
    val total: Int,
    val available: Int,
    val accepted: Int,
    val submitted: Int,
    val completed: Int,
)

