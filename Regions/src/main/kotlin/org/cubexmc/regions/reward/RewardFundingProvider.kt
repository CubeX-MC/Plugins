package org.cubexmc.regions.reward

import java.util.UUID

interface RewardFundingProvider {
    fun check(contractId: String, regionId: String): FundingResult

    fun lock(operationId: String, contractId: String, regionId: String): FundingResult

    fun settle(operationId: String, contractId: String, regionId: String, winnerId: UUID): FundingResult

    fun refund(operationId: String, contractId: String, regionId: String, reason: String): FundingResult
}
