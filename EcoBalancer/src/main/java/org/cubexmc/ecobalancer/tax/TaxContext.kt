package org.cubexmc.ecobalancer.tax

import org.cubexmc.ecobalancer.utils.AnalysisFilters

class TaxContext(
    val operationId: Int,
    val policyName: String,
    val operationType: TaxOperationType,
    val currentTime: Long,
    val isBatchRun: Boolean,
    val criteria: AnalysisFilters.FilterCriteria?,
)
