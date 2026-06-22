package org.cubexmc.contract.gui

import org.cubexmc.contract.model.ContractType

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
    private var partnerStake: Double? = null
    private var counterparty: String? = null
    private var mediator: String? = null

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

    fun needsCounterparty(): Boolean = type == ContractType.WAGER || type == ContractType.PARTNERSHIP

    fun needsPartnerStake(): Boolean = type == ContractType.PARTNERSHIP

    fun mediatorRequired(): Boolean = type == ContractType.WAGER

    fun validate(minAmount: Double, maxAmount: Double, minDays: Int, maxDays: Int): String? {
        if (title.isNullOrBlank()) {
            return "请先填写标题"
        }
        val currentDays = days ?: return "请先填写期限"
        if (currentDays < minDays || currentDays > maxDays) {
            return "有效期必须在 $minDays 到 $maxDays 天之间"
        }
        val currentAmount = amount ?: return "请先填写金额"
        if (currentAmount < minAmount || currentAmount > maxAmount) {
            return "金额必须在 $minAmount 到 $maxAmount 之间"
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
        return null
    }

    fun isReady(minAmount: Double, maxAmount: Double, minDays: Int, maxDays: Int): Boolean =
        validate(minAmount, maxAmount, minDays, maxDays) == null
}
