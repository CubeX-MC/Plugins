package org.cubexmc.regions.reward

import org.cubexmc.regions.model.RegionDefinition
import java.util.UUID

interface RewardFundingRuntime : RewardFundingValidator {
    fun reserve(region: RegionDefinition): FundingResult
    fun settle(region: RegionDefinition, winnerCandidates: Set<UUID>): FundingResult
    fun refund(region: RegionDefinition, reason: String): FundingResult
    fun reconcile(): List<FundingResult>
}
