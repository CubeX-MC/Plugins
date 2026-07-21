package org.cubexmc.contract.gui

import org.cubexmc.contract.model.ContractType
import org.cubexmc.contract.model.BatchRepeatPolicy
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

    fun systemVerified(): Boolean = type == ContractType.SERVICE && objectiveType != null

    fun needsCounterparty(): Boolean = type == ContractType.WAGER || type == ContractType.PARTNERSHIP

    fun needsPartnerStake(): Boolean = type == ContractType.PARTNERSHIP

    fun mediatorRequired(): Boolean = type == ContractType.WAGER

    fun validate(minAmount: Double, maxAmount: Double, minDays: Int, maxDays: Int): String? =
        validate(minAmount, maxAmount, minDays, maxDays, DEFAULT_MAX_BATCH_CONTRACTS, DEFAULT_MAX_REPEAT_COOLDOWN_HOURS)

    fun validate(minAmount: Double, maxAmount: Double, minDays: Int, maxDays: Int, maxBatchContracts: Int): String? =
        validate(minAmount, maxAmount, minDays, maxDays, maxBatchContracts, DEFAULT_MAX_REPEAT_COOLDOWN_HOURS)

    fun validate(
        minAmount: Double,
        maxAmount: Double,
        minDays: Int,
        maxDays: Int,
        maxBatchContracts: Int,
        maxRepeatCooldownHours: Int,
    ): String? {
        if (title.isNullOrBlank()) {
            return "请先填写标题"
        }
        val currentDays = days ?: return "请先填写期限"
        if (currentDays < minDays || currentDays > maxDays) {
            return "有效期必须在 $minDays 到 $maxDays 天之间"
        }
        if (type == ContractType.SERVICE && (contractCount < 1 || contractCount > maxBatchContracts.coerceAtLeast(1))) {
            return "发布份数必须在 1 到 ${maxBatchContracts.coerceAtLeast(1)} 之间"
        }
        if (
            type == ContractType.SERVICE && contractCount > 1 && repeatPolicy == BatchRepeatPolicy.COOLDOWN &&
            repeatCooldownHours !in 1..maxRepeatCooldownHours.coerceAtLeast(1)
        ) {
            return "重复接取冷却必须在 1 到 ${maxRepeatCooldownHours.coerceAtLeast(1)} 小时之间"
        }
        val currentAmount = amount
        if (!(type == ContractType.SERVICE && itemReward)) {
            val requiredAmount = currentAmount ?: return "请先填写金额"
            if (requiredAmount < minAmount || requiredAmount > maxAmount) {
                return "金额必须在 $minAmount 到 $maxAmount 之间"
            }
        }
        if (needsCounterparty() && counterparty.isNullOrBlank()) {
            return "请先填写对方玩家"
        }
        if (mediatorRequired() && mediator.isNullOrBlank()) {
            return "请先填写仲裁者"
        }
        if (needsPartnerStake()) {
            val currentPartnerStake = partnerStake ?: return "请先填写对方押注"
            if (currentPartnerStake < minAmount || currentPartnerStake > maxAmount) {
                return "对方押注必须在 $minAmount 到 $maxAmount 之间"
            }
        }
        if (systemVerified()) {
            val currentType = objectiveType ?: return "请先选择系统验收目标"
            if (!objectiveAllowsAny(currentType) && objectiveTarget.isNullOrBlank()) {
                return "请先填写系统验收目标"
            }
            val required = objectiveRequired ?: return "请先填写目标数量"
            if (required <= 0) {
                return "目标数量必须大于 0"
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

    private companion object {
        const val DEFAULT_MAX_BATCH_CONTRACTS = 64
        const val DEFAULT_REPEAT_COOLDOWN_HOURS = 24
        const val DEFAULT_MAX_REPEAT_COOLDOWN_HOURS = 8760
    }
}
