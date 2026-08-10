package org.cubexmc.contract.gui

import org.cubexmc.contract.model.ContractType
import org.cubexmc.contract.model.BatchRepeatPolicy
import org.cubexmc.contract.model.ContractSpec
import org.cubexmc.contract.model.ObjectiveType

/**
 * Mutable draft for the GUI creation wizard. Holds the fields a player fills in
 * before previewing and signing. [validate] is a pure function so the wizard's
 * field/amount rules can be unit tested without a server.
 */
class CreateDraft(private val type: ContractType) {
    private var title: String? = null
    private var description: String? = null
    private var days: Int? = null
    private var amount: Double? = null
    private var itemReward: Boolean = false
    private var partnerStake: Double? = null
    private var counterparty: String? = null
    private var mediator: String? = null
    private var objectiveType: ObjectiveType? = null
    private var objectiveTarget: String? = null
    private var objectiveRequired: Int? = null
    private var contractCount: Int = 1
    private var repeatPolicy: BatchRepeatPolicy = BatchRepeatPolicy.ONCE
    private var repeatCooldownHours: Int = DEFAULT_REPEAT_COOLDOWN_HOURS
    private var publishAt: Long? = null

    fun type(): ContractType = type

    fun title(): String? = title

    fun title(title: String?) {
        this.title = title
    }

    fun description(): String? = description

    fun description(description: String?) {
        this.description = description
    }

    fun days(): Int? = days

    fun days(days: Int?) {
        this.days = days
    }

    fun amount(): Double? = amount

    fun amount(amount: Double?) {
        this.amount = amount
    }

    fun itemReward(): Boolean = itemReward

    fun itemReward(itemReward: Boolean) {
        this.itemReward = itemReward
        if (itemReward && type == ContractType.SERVICE) {
            amount = 0.0
        }
    }

    fun partnerStake(): Double? = partnerStake

    fun partnerStake(partnerStake: Double?) {
        this.partnerStake = partnerStake
    }

    fun counterparty(): String? = counterparty

    fun counterparty(counterparty: String?) {
        this.counterparty = counterparty
    }

    fun mediator(): String? = mediator

    fun mediator(mediator: String?) {
        this.mediator = mediator
    }

    fun objectiveType(): ObjectiveType? = objectiveType

    fun objectiveType(objectiveType: ObjectiveType?) {
        this.objectiveType = objectiveType
        if (objectiveType == null) {
            objectiveTarget = null
            objectiveRequired = null
        } else if (objectiveType == ObjectiveType.DELIVER_MONEY) {
            objectiveTarget = "MONEY"
        } else if (objectiveAllowsAny(objectiveType) && objectiveTarget.isNullOrBlank()) {
            objectiveTarget = "ANY"
        }
    }

    fun objectiveTarget(): String? = objectiveTarget

    fun objectiveTarget(objectiveTarget: String?) {
        this.objectiveTarget = objectiveTarget
    }

    fun objectiveRequired(): Int? = objectiveRequired

    fun objectiveRequired(objectiveRequired: Int?) {
        this.objectiveRequired = objectiveRequired
    }

    fun contractCount(): Int = contractCount

    fun contractCount(contractCount: Int) {
        this.contractCount = contractCount
    }

    fun repeatPolicy(): BatchRepeatPolicy = repeatPolicy

    fun repeatPolicy(repeatPolicy: BatchRepeatPolicy) {
        this.repeatPolicy = repeatPolicy
    }

    fun repeatCooldownHours(): Int = repeatCooldownHours

    fun repeatCooldownHours(repeatCooldownHours: Int) {
        this.repeatCooldownHours = repeatCooldownHours
    }

    fun publishAt(): Long? = publishAt

    fun publishAt(publishAt: Long?) {
        this.publishAt = publishAt
    }

    fun toSpec(): ContractSpec = ContractSpec(
        type,
        title,
        description,
        days,
        amount,
        itemReward,
        partnerStake,
        counterparty,
        mediator,
        objectiveType,
        objectiveTarget,
        objectiveRequired,
        contractCount,
        repeatPolicy,
        repeatCooldownHours,
    )

    fun systemVerified(): Boolean = type == ContractType.SERVICE && objectiveType != null

    fun needsCounterparty(): Boolean = type == ContractType.WAGER || type == ContractType.PARTNERSHIP

    fun needsPartnerStake(): Boolean = type == ContractType.PARTNERSHIP

    fun mediatorRequired(): Boolean = type == ContractType.WAGER

    fun validate(minAmount: Double, maxAmount: Double, minDays: Int, maxDays: Int): DraftProblem? =
        validate(minAmount, maxAmount, minDays, maxDays, DEFAULT_MAX_BATCH_CONTRACTS, DEFAULT_MAX_REPEAT_COOLDOWN_HOURS)

    fun validate(minAmount: Double, maxAmount: Double, minDays: Int, maxDays: Int, maxBatchContracts: Int): DraftProblem? =
        validate(minAmount, maxAmount, minDays, maxDays, maxBatchContracts, DEFAULT_MAX_REPEAT_COOLDOWN_HOURS)

    /**
     * Returns the first rule the draft violates as a translatable [DraftProblem], or `null` when the
     * draft is ready to sign. Deliberately returns a key + placeholders rather than a rendered
     * sentence so this stays a pure function and the caller owns the player's locale.
     */
    fun validate(
        minAmount: Double,
        maxAmount: Double,
        minDays: Int,
        maxDays: Int,
        maxBatchContracts: Int,
        maxRepeatCooldownHours: Int,
    ): DraftProblem? {
        if (title.isNullOrBlank()) {
            return DraftProblem("draft-need-title")
        }
        val currentDays = days ?: return DraftProblem("draft-need-days")
        if (currentDays < minDays || currentDays > maxDays) {
            return DraftProblem("draft-days-range", mapOf("min" to minDays.toString(), "max" to maxDays.toString()))
        }
        if (type == ContractType.SERVICE && (contractCount < 1 || contractCount > maxBatchContracts.coerceAtLeast(1))) {
            return DraftProblem("draft-count-range", mapOf("max" to maxBatchContracts.coerceAtLeast(1).toString()))
        }
        if (
            type == ContractType.SERVICE && contractCount > 1 && repeatPolicy == BatchRepeatPolicy.COOLDOWN &&
            repeatCooldownHours !in 1..maxRepeatCooldownHours.coerceAtLeast(1)
        ) {
            return DraftProblem("draft-cooldown-range", mapOf("max" to maxRepeatCooldownHours.coerceAtLeast(1).toString()))
        }
        val currentAmount = amount
        if (!(type == ContractType.SERVICE && itemReward)) {
            val requiredAmount = currentAmount ?: return DraftProblem("draft-need-amount")
            if (requiredAmount < minAmount || requiredAmount > maxAmount) {
                return DraftProblem("draft-amount-range", mapOf("min" to trim(minAmount), "max" to trim(maxAmount)))
            }
        }
        if (needsCounterparty() && counterparty.isNullOrBlank()) {
            return DraftProblem("draft-need-counterparty")
        }
        if (mediatorRequired() && mediator.isNullOrBlank()) {
            return DraftProblem("draft-need-arbiter")
        }
        if (needsPartnerStake()) {
            val currentPartnerStake = partnerStake ?: return DraftProblem("draft-need-partner-stake")
            if (currentPartnerStake < minAmount || currentPartnerStake > maxAmount) {
                return DraftProblem("draft-partner-stake-range", mapOf("min" to trim(minAmount), "max" to trim(maxAmount)))
            }
        }
        if (systemVerified()) {
            val currentType = objectiveType ?: return DraftProblem("draft-need-objective-type")
            if (!objectiveAllowsAny(currentType) && objectiveTarget.isNullOrBlank()) {
                return DraftProblem("draft-need-objective-target")
            }
            val required = objectiveRequired ?: return DraftProblem("draft-need-objective-amount")
            if (required <= 0) {
                return DraftProblem("draft-objective-amount-positive")
            }
        }
        return null
    }

    fun isReady(minAmount: Double, maxAmount: Double, minDays: Int, maxDays: Int): Boolean =
        validate(minAmount, maxAmount, minDays, maxDays) == null

    private fun objectiveAllowsAny(type: ObjectiveType): Boolean =
        when (type) {
            ObjectiveType.FISH,
            ObjectiveType.KILL_PLAYER,
            ObjectiveType.SHEAR,
            ObjectiveType.BREED,
            ObjectiveType.TAME,
            ObjectiveType.CHAT,
            ObjectiveType.BLOCK_INTERACT,
            ObjectiveType.USE_ITEM,
            ObjectiveType.DELIVER_MONEY,
            -> true
            else -> false
        }

    companion object {
        private fun trim(value: Double): String =
            if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()

        @JvmStatic
        fun fromSpec(spec: ContractSpec): CreateDraft = CreateDraft(spec.type).also { draft ->
            draft.title(spec.title)
            draft.description(spec.description)
            draft.days(spec.days)
            draft.amount(spec.amount)
            draft.itemReward(spec.itemReward)
            draft.partnerStake(spec.partnerStake)
            draft.counterparty(spec.counterparty)
            draft.mediator(spec.mediator)
            draft.objectiveType(spec.objectiveType)
            draft.objectiveTarget(spec.objectiveTarget)
            draft.objectiveRequired(spec.objectiveRequired)
            draft.contractCount(spec.contractCount)
            draft.repeatPolicy(spec.repeatPolicy)
            draft.repeatCooldownHours(spec.repeatCooldownHours)
        }

        const val DEFAULT_MAX_BATCH_CONTRACTS = 64
        const val DEFAULT_REPEAT_COOLDOWN_HOURS = 24
        const val DEFAULT_MAX_REPEAT_COOLDOWN_HOURS = 8760
    }
}
