package org.cubexmc.contract.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.model.Contract
import org.cubexmc.contract.model.BatchRepeatPolicy
import org.cubexmc.contract.model.ContractObjective
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.model.ContractType
import org.cubexmc.contract.model.ObjectiveType
import org.cubexmc.contract.model.ParticipantRole
import org.cubexmc.contract.util.Text
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
 */
internal class ContractRenderer(private val plugin: ContractPlugin) {

    fun contractItem(contract: Contract, actionLabel: String?, progressLabel: String? = null): ItemStack {
        val lore = ArrayList<String>()
        lore.add(Text.color("&#CFD8DC描述: &#FFFFFF${ContractTerms.preview(contract.description())}"))
        lore.add(Text.color("&#CFD8DC奖励: &#69DB7C${rewardSummary(contract)}"))
        val objective = contract.objective()
        if (objective != null) {
            lore.add(Text.color("&#CFD8DC任务要求: &#FFFFFF${objectiveSummary(objective, false)}"))
        }
        return named(materialFor(contract.type(), contract.status()), "&#F4D03F${contract.title()}", lore)
    }

    fun detailItem(contract: Contract): ItemStack {
        val lore = ArrayList<String>()
        lore.add(Text.color("&#CFD8DC类型: &#FFFFFF${plugin.lang().type(contract.type())}"))
        lore.add(Text.color("&#CFD8DC状态: &#FFFFFF${plugin.lang().status(contract.status())}"))
        for (participant in contract.participants()) {
            lore.add(Text.color("&#CFD8DC${plugin.lang().role(participant.role())}: &#FFFFFF${participant.displayName() ?: "无"} &#69DB7C${stakeSummary(participant)}"))
            val uuid = participant.uuid()
            if (uuid != null) {
                lore.add(Text.color("&#CFD8DC   信誉: &#FFFFFF${repSummary(uuid)}"))
            }
        }
        val arbiter = contract.arbiter()
        if (arbiter != null) {
            lore.add(Text.color("&#CFD8DC仲裁者: &#FFFFFF${arbiter.displayName()} &#CFD8DC(${if (contract.arbiterAccepted()) "已接受" else "待接受"})"))
        }
        lore.add(Text.color("&#CFD8DC佣金率: &#FFE066${contract.commissionPercent().toPlainString()}%"))
        val objective = contract.objective()
        if (objective != null) {
            lore.add(Text.color("&#CFD8DC验收方式: &#69DB7C系统验收"))
            lore.add(Text.color("&#CFD8DC目标: &#FFFFFF${objectiveSummary(objective, true)}"))
        } else if (contract.type() == ContractType.SERVICE) {
            lore.add(Text.color("&#CFD8DC验收方式: &#FFFFFF人工验收"))
        }
        batchRepeatSummary(contract)?.let { lore.add(Text.color("&#CFD8DC重复接取: &#FFFFFF$it")) }
        if (contract.hasDeliveryItems()) {
            lore.add(Text.color("&#CFD8DC合同暂存: &#FFFFFF${contract.deliveryItemCount()} 个交付物品"))
        }
        if (contract.hasRewardItems()) {
            lore.add(Text.color("&#CFD8DC奖励暂存: &#FFFFFF${contract.rewardItemCount()} 个物品"))
        }
        lore.add(Text.color("&#CFD8DC接单截止: &#FFFFFF${DATE_FORMAT.format(Instant.ofEpochMilli(contract.expiresAt()))}"))
        lore.add("")
        lore.add(Text.color("&#F1F5F9${contract.description()}"))
        val disputeReason = contract.disputeReason()
        if (!disputeReason.isNullOrBlank()) {
            lore.add("")
            lore.add(Text.color("&#E63946争议: &#F1F5F9$disputeReason"))
        }
        return named(materialFor(contract.type(), contract.status()), "&#F4D03F${contract.title()}", lore)
    }

    fun draftPreview(draft: CreateDraft): List<String> {
        val lines = ArrayList<String>()
        lines.add("&#CFD8DC类型: &#FFFFFF${plugin.lang().type(draft.type())}")
        lines.add("&#CFD8DC标题: &#FFFFFF${valueOr(draft.title())}")
        lines.add("&#CFD8DC描述: &#FFFFFF${ContractTerms.preview(draft.description())}")
        if (draft.needsCounterparty()) lines.add("&#CFD8DC对方: &#FFFFFF${resolveName(draft.counterparty())}")
        lines.add("&#CFD8DC${if (draft.mediatorRequired()) "仲裁者" else "中间人"}: &#FFFFFF${resolveName(draft.mediator())}")
        lines.add("&#CFD8DC有效期: &#FFFFFF${draft.days()?.let { "$it 天" } ?: "未填"}")
        if (draft.type() == ContractType.SERVICE) {
            val fee = plugin.config.getDouble("economy.creation-fee", 20.0)
            val contractCount = draft.contractCount()
            lines.add("&#CFD8DC发布份数: &#FFFFFF$contractCount")
            if (contractCount > 1) {
                lines.add("&#CFD8DC重复接取: &#FFFFFF${batchRepeatSummary(draft.repeatPolicy(), draft.repeatCooldownHours())}")
            }
            lines.add("&#CFD8DC验收方式: &#FFFFFF${if (draft.systemVerified()) "系统验收" else "人工验收"}")
            if (draft.systemVerified()) {
                val type = draft.objectiveType()
                lines.add("&#CFD8DC系统目标: &#FFFFFF${if (type == null) "未选" else objectiveTypeLabel(type)} ${draftObjectiveTarget(type, draft.objectiveTarget())} x${draft.objectiveRequired() ?: "未填"}")
                lines.add("&#E63946目标达成后系统会自动付款。")
            }
            if (draft.itemReward()) {
                lines.add("&#CFD8DC奖励托管: &#69DB7C主手整组平均分配到 $contractCount 份")
            } else {
                lines.add("&#CFD8DC每份奖金: &#69DB7C${valueOr(num(draft.amount()))}")
            }
            lines.add("&#CFD8DC每份创建费: &#FFE066${trimNumber(fee)} &#CFD8DC(普通取消不退)")
            val amount = draft.amount()
            if (draft.itemReward()) {
                lines.add("&#CFD8DC签署后共扣除: &#E63946${trimNumber(fee * contractCount)} &#CFD8DC并托管主手整组物品")
            } else if (amount != null) {
                lines.add("&#CFD8DC签署后共扣除: &#E63946${trimNumber((amount + fee) * contractCount)}")
            }
        } else if (draft.type() == ContractType.WAGER) {
            lines.add("&#CFD8DC我的押注: &#69DB7C${valueOr(num(draft.amount()))}")
            lines.add("&#CFD8DC对方需匹配等额押注。")
            lines.add("&#CFD8DC签署后立即扣除: &#E63946${valueOr(num(draft.amount()))}")
        } else {
            lines.add("&#CFD8DC我的押注: &#69DB7C${valueOr(num(draft.amount()))}")
            lines.add("&#CFD8DC对方押注: &#69DB7C${valueOr(num(draft.partnerStake()))}")
            lines.add("&#CFD8DC签署后立即扣除我的押注: &#E63946${valueOr(num(draft.amount()))}")
        }
        lines.add("&#CFD8DC资金由服务器托管,不交给对方或中间人。")
        return lines
    }

    /** Resolves a typed player name to its canonical name for the confirm echo, flagging typos. */
    private fun resolveName(typed: String?): String {
        if (typed.isNullOrBlank()) {
            return "未填"
        }
        @Suppress("DEPRECATION")
        val offline = Bukkit.getOfflinePlayer(typed)
        return if (offline.isOnline || offline.hasPlayedBefore()) {
            offline.name ?: typed
        } else {
            "$typed &#E63946(⚠ 未找到)"
        }
    }

    fun acceptConsequences(contract: Contract): List<String> {
        val partyB = contract.participant(ParticipantRole.PARTY_B).orElse(null)
        val stake = partyB?.moneyStake() ?: BigDecimal.ZERO
        return listOf("签署后立即从你的余额扣除押注 ${plugin.economy().format(stake)} 托管到服务器。", "资金不会交给对方或中间人,由服务器托管。")
    }

    /** Compact track record for a player, e.g. "完成 12 · 取消 1 · 逾期 0 · 争议 0". */
    private fun repSummary(uuid: UUID?): String {
        val record = plugin.reputation().snapshot(uuid) ?: return "暂无记录"
        return "完成 ${record.completed} · 取消 ${record.cancelled} · 逾期 ${record.expired} · 争议 ${record.disputed}"
    }

    fun trimNumber(value: Double): String =
        if (value == Math.rint(value)) value.toLong().toString() else BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun valueOr(value: String?): String = if (value.isNullOrBlank()) "未填" else value

    private fun num(value: Double?): String? = value?.let { trimNumber(it) }

    private fun rewardSummary(contract: Contract): String {
        val parts = ArrayList<String>()
        if (contract.reward().signum() > 0) {
            parts.add(plugin.economy().format(contract.reward()))
        }
        if (contract.hasRewardItems()) {
            parts.add("${contract.rewardItemCount()} 个物品")
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
            parts.add("${participant.itemStakeCount()} 项物品")
        }
        return if (parts.isEmpty()) plugin.economy().format(BigDecimal.ZERO) else parts.joinToString(" + ")
    }

    private fun objectiveSummary(objective: ContractObjective, includeProgress: Boolean): String {
        val target = if (objective.type() == ObjectiveType.DELIVER_MONEY) "默认货币" else objective.target()
        val count = if (includeProgress) objective.progressText() else "x${objective.required()}"
        return "${objectiveTypeLabel(objective.type())} $target $count"
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
            BatchRepeatPolicy.UNLIMITED -> "不限制"
            BatchRepeatPolicy.ONCE -> "每名玩家仅一次"
            BatchRepeatPolicy.COOLDOWN -> "每 $cooldownHours 小时可接一次,且同时只能进行一份"
        }

    private fun draftObjectiveTarget(type: ObjectiveType?, target: String?): String =
        if (type == ObjectiveType.DELIVER_MONEY) "默认货币" else valueOr(target)

    private fun objectiveTypeLabel(type: ObjectiveType): String =
        when (type) {
            ObjectiveType.CRAFT_ITEM -> "合成物品"
            ObjectiveType.BLOCK_BREAK -> "挖掘方块"
            ObjectiveType.FISH -> "钓鱼"
            ObjectiveType.BLOCK_PLACE -> "放置方块"
            ObjectiveType.KILL_ENTITY -> "击杀生物"
            ObjectiveType.KILL_PLAYER -> "击杀玩家"
            ObjectiveType.CONSUME_ITEM -> "消耗物品"
            ObjectiveType.DELIVER_ITEM -> "提交物品"
            ObjectiveType.ENCHANT_ITEM -> "附魔物品"
            ObjectiveType.SHEAR -> "使用剪刀"
            ObjectiveType.BREED -> "繁殖动物"
            ObjectiveType.TAME -> "驯服动物"
            ObjectiveType.CHAT -> "发送聊天"
            ObjectiveType.BLOCK_INTERACT -> "交互方块"
            ObjectiveType.RUN_COMMAND -> "执行指令"
            ObjectiveType.USE_ITEM -> "使用物品"
            ObjectiveType.DELIVER_MONEY -> "提交货币"
        }

    private fun stageName(status: ContractStatus): String =
        when (status) {
            ContractStatus.OPEN, ContractStatus.PENDING_ACCEPT -> "开放"
            ContractStatus.IN_PROGRESS -> "进行中"
            ContractStatus.SUBMITTED -> "待确认"
            ContractStatus.DISPUTED -> "申诉中"
            ContractStatus.COMPLETED -> "已完成"
            ContractStatus.CANCELLED, ContractStatus.EXPIRED -> "已关闭"
        }

    private fun materialFor(type: ContractType, status: ContractStatus): Material =
        when (status) {
            ContractStatus.COMPLETED -> Material.EMERALD
            ContractStatus.CANCELLED -> Material.BARRIER
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
