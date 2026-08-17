package org.cubexmc.regions.reward

import org.cubexmc.regions.model.RegionDefinition

fun interface RewardFundingValidator {
    fun check(region: RegionDefinition): FundingResult
}
