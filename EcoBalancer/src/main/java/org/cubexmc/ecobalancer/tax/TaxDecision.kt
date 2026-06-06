package org.cubexmc.ecobalancer.tax

class TaxDecision(
    val oldBalance: Double,
    val requestedDeduction: Double,
    val actualDeduction: Double,
    val newBalance: Double,
    val result: TaxDecisionResult,
    val reason: String?,
)
