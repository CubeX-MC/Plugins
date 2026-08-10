package org.cubexmc.contract.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.model.Contract
import org.cubexmc.contract.model.BatchSummary
import org.cubexmc.contract.model.BatchRepeatPolicy
import org.cubexmc.contract.model.ContractObjective
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.model.ContractType
import org.cubexmc.contract.model.ObjectiveType
import org.cubexmc.contract.model.ParticipantRole
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Pure presentation: builds the contract item icons, the detail icon and the create-wizard preview
 * text. Depends only on [ContractPlugin] services (lang/economy/config), never on the GUI's
 * navigation state — so it has no coupling back to [ContractGui].
 *
 * Every player-visible string resolves through `lang().ui(...)` at render time, so a language
 * change picked up by `/contract admin reload` applies without restarting the server.
 */
internal class ContractRenderer(private val plugin: ContractPlugin) {

    private fun ui(key: String, placeholders: Map<String, String> = emptyMap()): String = plugin.lang().ui(key, placeholders)

    private fun unset(): String = ui("value-unset")

    fun contractItem(contract: Contract, actionLabel: String?, progressLabel: String? = null): ItemStack {
        val lore = ArrayList<String>()
        lore.add(ui("item-description", mapOf("value" to ContractTerms.preview(contract.description(), unset()))))
        lore.add(ui("item-reward", mapOf("value" to rewardSummary(contract))))
        val objective = contract.objective()
        if (objective != null) {
            lore.add(ui("item-objective", mapOf("value" to objectiveSummary(objective, false))))
        }
        if (!progressLabel.isNullOrBlank()) lore.add(ui("item-progress", mapOf("value" to progressLabel)))
        if (!actionLabel.isNullOrBlank()) lore.add(ui("item-action", mapOf("value" to actionLabel)))
        return named(materialFor(contract.type(), contract.status()), ui("item-title", mapOf("value" to contract.title())), lore)
    }

    fun batchItem(summary: BatchSummary, actionLabel: String?): ItemStack {
        val contract = summary.representative
        val lore = arrayListOf(
            ui("batch-description", mapOf("value" to ContractTerms.preview(contract.description(), unset()))),
            ui("batch-reward", mapOf("value" to rewardSummary(contract))),
            ui("batch-available", mapOf("value" to "${summary.available}/${summary.total}")),
            ui("batch-accepted", mapOf("value" to "${summary.accepted}/${summary.total}")),
            ui("batch-submitted", mapOf("value" to "${summary.submitted}/${summary.total}")),
            ui("batch-completed", mapOf("value" to "${summary.completed}/${summary.total}")),
        )
        if (!actionLabel.isNullOrBlank()) lore.add(ui("batch-action", mapOf("value" to actionLabel)))
        lore.add(ui("batch-inspect"))
        return named(Material.PAPER, ui("batch-card-title", mapOf("title" to contract.title(), "total" to summary.total.toString())), lore).also {
            it.amount = summary.available.coerceIn(1, it.maxStackSize)
        }
    }

    fun detailItem(contract: Contract): ItemStack {
        val lore = ArrayList<String>()
        lore.add(ui("detail-type", mapOf("value" to plugin.lang().type(contract.type()))))
        lore.add(ui("detail-status", mapOf("value" to plugin.lang().status(contract.status()))))
        for (participant in contract.participants()) {
            lore.add(
                ui(
                    "detail-participant",
                    mapOf(
                        "role" to plugin.lang().role(participant.role()),
                        "name" to (participant.displayName() ?: ui("value-none")),
                        "stake" to stakeSummary(participant),
                    ),
                ),
            )
            val uuid = participant.uuid()
            if (uuid != null) {
                lore.add(ui("detail-reputation", mapOf("value" to repSummary(uuid))))
            }
        }
        val arbiter = contract.arbiter()
        if (arbiter != null) {
            val state = ui(if (contract.arbiterAccepted()) "arbiter-accepted" else "arbiter-pending")
            lore.add(ui("detail-arbiter", mapOf("name" to (arbiter.displayName() ?: ui("value-none")), "state" to state)))
        }
        lore.add(ui("detail-commission", mapOf("value" to contract.commissionPercent().toPlainString())))
        val objective = contract.objective()
        if (objective != null) {
            lore.add(ui("detail-verification", mapOf("value" to ui("verify-label-system"))))
            lore.add(ui("detail-objective", mapOf("value" to objectiveSummary(objective, true))))
        } else if (contract.type() == ContractType.SERVICE) {
            lore.add(ui("detail-verification", mapOf("value" to ui("verify-label-manual"))))
        }
        batchRepeatSummary(contract)?.let { lore.add(ui("detail-repeat", mapOf("value" to it))) }
        if (contract.hasDeliveryItems()) {
            lore.add(ui("detail-stored-delivery", mapOf("count" to contract.deliveryItemCount().toString())))
        }
        if (contract.hasRewardItems()) {
            lore.add(ui("detail-stored-reward", mapOf("count" to contract.rewardItemCount().toString())))
        }
        contract.publishAt()?.let { lore.add(ui("schedule-publish-at", mapOf("time" to DATE_FORMAT.format(Instant.ofEpochMilli(it))))) }
        lore.add(ui("detail-deadline", mapOf("time" to DATE_FORMAT.format(Instant.ofEpochMilli(contract.expiresAt())))))
        lore.add("")
        lore.add(ui("detail-description", mapOf("value" to (contract.description() ?: ""))))
        val disputeReason = contract.disputeReason()
        if (!disputeReason.isNullOrBlank()) {
            lore.add("")
            lore.add(ui("detail-dispute", mapOf("value" to disputeReason)))
        }
        return named(materialFor(contract.type(), contract.status()), ui("item-title", mapOf("value" to contract.title())), lore)
    }

    fun draftPreview(draft: CreateDraft): List<String> {
        val lines = ArrayList<String>()
        lines.add(ui("preview-type", mapOf("value" to plugin.lang().type(draft.type()))))
        lines.add(ui("preview-title-line", mapOf("value" to valueOr(draft.title()))))
        lines.add(ui("preview-description", mapOf("value" to ContractTerms.preview(draft.description(), unset()))))
        if (draft.needsCounterparty()) lines.add(ui("preview-counterparty", mapOf("value" to resolveName(draft.counterparty()))))
        val mediatorLabel = ui(if (draft.mediatorRequired()) "field-arbiter" else "field-mediator-short")
        lines.add(ui("preview-mediator", mapOf("label" to mediatorLabel, "value" to resolveName(draft.mediator()))))
        lines.add(ui("preview-days", mapOf("value" to (draft.days()?.let { ui("value-days", mapOf("days" to it.toString())) } ?: unset()))))
        lines.add(
            ui(
                "schedule-publish-at",
                mapOf("time" to (draft.publishAt()?.let { DATE_FORMAT.format(Instant.ofEpochMilli(it)) } ?: ui("schedule-immediate"))),
            ),
        )
        if (draft.type() == ContractType.SERVICE) {
            val fee = plugin.config.getDouble("economy.creation-fee", 20.0)
            val contractCount = draft.contractCount()
            lines.add(ui("preview-count", mapOf("value" to contractCount.toString())))
            if (contractCount > 1) {
                lines.add(ui("preview-repeat", mapOf("value" to batchRepeatSummary(draft.repeatPolicy(), draft.repeatCooldownHours()))))
            }
            lines.add(ui("preview-verification", mapOf("value" to ui(if (draft.systemVerified()) "verify-label-system" else "verify-label-manual"))))
            if (draft.systemVerified()) {
                val type = draft.objectiveType()
                lines.add(
                    ui(
                        "preview-objective",
                        mapOf(
                            "type" to (if (type == null) unset() else plugin.lang().objective(type)),
                            "target" to draftObjectiveTarget(type, draft.objectiveTarget()),
                            "amount" to (draft.objectiveRequired()?.toString() ?: unset()),
                        ),
                    ),
                )
                lines.add(ui("preview-objective-auto"))
            }
            if (draft.itemReward()) {
                lines.add(ui("preview-escrow-item", mapOf("count" to contractCount.toString())))
            } else {
                lines.add(ui("preview-reward-each", mapOf("value" to valueOr(num(draft.amount())))))
            }
            lines.add(ui("preview-fee-each", mapOf("value" to trimNumber(fee))))
            val amount = draft.amount()
            if (draft.itemReward()) {
                lines.add(ui("preview-total-item", mapOf("value" to trimNumber(fee * contractCount))))
            } else if (amount != null) {
                lines.add(ui("preview-total-money", mapOf("value" to trimNumber((amount + fee) * contractCount))))
            }
        } else if (draft.type() == ContractType.WAGER) {
            lines.add(ui("preview-my-stake", mapOf("value" to valueOr(num(draft.amount())))))
            lines.add(ui("preview-wager-match"))
            lines.add(ui("preview-deduct-now", mapOf("value" to valueOr(num(draft.amount())))))
        } else {
            lines.add(ui("preview-my-stake", mapOf("value" to valueOr(num(draft.amount())))))
            lines.add(ui("preview-partner-stake", mapOf("value" to valueOr(num(draft.partnerStake())))))
            lines.add(ui("preview-deduct-mine", mapOf("value" to valueOr(num(draft.amount())))))
        }
        lines.add(ui("preview-escrow-note"))
        return lines
    }

    /** Resolves a typed player name to its canonical name for the confirm echo, flagging typos. */
    private fun resolveName(typed: String?): String {
        if (typed.isNullOrBlank()) {
            return unset()
        }
        @Suppress("DEPRECATION")
        val offline = Bukkit.getOfflinePlayer(typed)
        return if (offline.isOnline || offline.hasPlayedBefore()) {
            offline.name ?: typed
        } else {
            ui("name-not-found", mapOf("name" to typed))
        }
    }

    fun acceptConsequences(contract: Contract): List<String> {
        val partyB = contract.participant(ParticipantRole.PARTY_B).orElse(null)
        val stake = partyB?.moneyStake() ?: BigDecimal.ZERO
        return listOf(
            ui("confirm-accept-invite-1", mapOf("value" to plugin.economy().format(stake))),
            ui("confirm-accept-invite-2"),
        )
    }

    /** Compact track record for a player, e.g. "completed 12 · cancelled 1 · expired 0 · disputed 0". */
    private fun repSummary(uuid: UUID?): String {
        val record = plugin.reputation().snapshot(uuid) ?: return ui("rep-none")
        return ui(
            "rep-summary",
            mapOf(
                "completed" to record.completed.toString(),
                "cancelled" to record.cancelled.toString(),
                "expired" to record.expired.toString(),
                "disputed" to record.disputed.toString(),
            ),
        )
    }

    fun trimNumber(value: Double): String =
        if (value == Math.rint(value)) value.toLong().toString() else BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun valueOr(value: String?): String = if (value.isNullOrBlank()) unset() else value

    private fun num(value: Double?): String? = value?.let { trimNumber(it) }

    private fun rewardSummary(contract: Contract): String {
        val parts = ArrayList<String>()
        if (contract.reward().signum() > 0) {
            parts.add(plugin.economy().format(contract.reward()))
        }
        if (contract.hasRewardItems()) {
            parts.add(ui("item-count", mapOf("count" to contract.rewardItemCount().toString())))
        }
        return if (parts.isEmpty()) plugin.economy().format(BigDecimal.ZERO) else parts.joinToString(" + ")
    }

    private fun stakeSummary(participant: org.cubexmc.contract.model.Participant): String {
        val parts = ArrayList<String>()
        val money = participant.moneyStake()
        if (money.signum() > 0) {
            parts.add(plugin.economy().format(money))
        }
        if (participant.itemStakeCount() > 0) {
            parts.add(ui("item-stake-count", mapOf("count" to participant.itemStakeCount().toString())))
        }
        return if (parts.isEmpty()) plugin.economy().format(BigDecimal.ZERO) else parts.joinToString(" + ")
    }

    private fun objectiveSummary(objective: ContractObjective, includeProgress: Boolean): String {
        val target = if (objective.type() == ObjectiveType.DELIVER_MONEY) ui("objective-money-target") else objective.target()
        val count = if (includeProgress) objective.progressText() else "x${objective.required()}"
        return "${plugin.lang().objective(objective.type())} $target $count"
    }

    private fun batchRepeatSummary(contract: Contract): String? {
        if (contract.metadata["batch-id"].isNullOrBlank()) {
            return null
        }
        val policy = BatchRepeatPolicy.fromStored(contract.metadata["repeat-policy"])
        val cooldownHours = contract.metadata["repeat-cooldown-hours"]?.toIntOrNull() ?: 24
        return batchRepeatSummary(policy, cooldownHours)
    }

    private fun batchRepeatSummary(policy: BatchRepeatPolicy, cooldownHours: Int): String =
        when (policy) {
            BatchRepeatPolicy.UNLIMITED -> ui("repeat-summary-unlimited")
            BatchRepeatPolicy.ONCE -> ui("repeat-summary-once")
            BatchRepeatPolicy.COOLDOWN -> ui("repeat-summary-cooldown", mapOf("hours" to cooldownHours.toString()))
        }

    private fun draftObjectiveTarget(type: ObjectiveType?, target: String?): String =
        if (type == ObjectiveType.DELIVER_MONEY) ui("objective-money-target") else valueOr(target)

    private fun materialFor(type: ContractType, status: ContractStatus): Material =
        when (status) {
            ContractStatus.SCHEDULED -> Material.CLOCK
            ContractStatus.COMPLETED -> Material.EMERALD
            ContractStatus.CANCELLED -> GuiIcons.INACTIVE
            ContractStatus.EXPIRED -> Material.CLOCK
            ContractStatus.DISPUTED -> Material.REDSTONE
            ContractStatus.PENDING_ACCEPT -> Material.YELLOW_BANNER
            else -> when (type) {
                ContractType.SERVICE -> Material.PAPER
                ContractType.WAGER -> Material.TARGET
                ContractType.PARTNERSHIP -> Material.AMETHYST_CLUSTER
                ContractType.ALLIANCE -> Material.SHIELD
                ContractType.BOUNTY -> Material.CROSSBOW
                ContractType.SALE -> Material.CHEST
                ContractType.LOAN -> Material.GOLD_INGOT
            }
        }

    private companion object {
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault())
    }
}
