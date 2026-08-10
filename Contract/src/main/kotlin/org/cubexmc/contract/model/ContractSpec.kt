package org.cubexmc.contract.model

/** Reusable contract terms. Deliberately excludes IDs, participants' live state and escrow. */
data class ContractSpec(
    val type: ContractType,
    val title: String?,
    val description: String?,
    val days: Int?,
    val amount: Double?,
    val itemReward: Boolean,
    val partnerStake: Double?,
    val counterparty: String?,
    val mediator: String?,
    val objectiveType: ObjectiveType?,
    val objectiveTarget: String?,
    val objectiveRequired: Int?,
    val contractCount: Int,
    val repeatPolicy: BatchRepeatPolicy,
    val repeatCooldownHours: Int,
)

