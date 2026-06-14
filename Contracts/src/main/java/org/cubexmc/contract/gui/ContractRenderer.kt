package org.cubexmc.contract.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.model.Contract
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.model.ContractType
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

    fun contractItem(contract: Contract, actionLabel: String?): ItemStack {
        val lore = ArrayList<String>()
        lore.add(Text.color("&#CFD8DCID: &#FFE066#${contract.shortId()}"))
        lore.add(Text.color("&#CFD8DC类型: &#FFFFFF${plugin.lang().type(contract.type())}"))
        lore.add(Text.color("&#CFD8DC状态: &#FFFFFF${plugin.lang().status(contract.status())}"))
        lore.add(Text.color("&#CFD8DC发起: &#FFFFFF${contract.ownerName()}"))
        lore.add(Text.color("&#CFD8DC发起方信誉: &#FFFFFF${repSummary(contract.ownerUuid())}"))
        lore.add(Text.color("&#CFD8DC对方: &#FFFFFF${contract.contractorName() ?: "无"}"))
        lore.add(Text.color("&#CFD8DC金额: &#69DB7C${plugin.economy().format(contract.reward())}"))
        lore.add(Text.color("&#CFD8DC截止: &#FFFFFF${DATE_FORMAT.format(Instant.ofEpochMilli(contract.expiresAt()))}"))
        lore.add("")
        if (actionLabel != null) lore.add(Text.color("&#E63946▶ $actionLabel"))
        lore.add(Text.color("&#FFE066点击查看详情"))
        return named(materialFor(contract.type(), contract.status()), "&#F4D03F${contract.title()}", lore)
    }

    fun detailItem(contract: Contract): ItemStack {
        val lore = ArrayList<String>()
        lore.add(Text.color("&#CFD8DC类型: &#FFFFFF${plugin.lang().type(contract.type())}"))
        lore.add(Text.color("&#CFD8DC状态: &#FFFFFF${plugin.lang().status(contract.status())}"))
        for (participant in contract.participants()) {
            lore.add(Text.color("&#CFD8DC${plugin.lang().role(participant.role())}: &#FFFFFF${participant.displayName() ?: "无"} &#69DB7C${plugin.economy().format(participant.moneyStake())}"))
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
        lore.add(Text.color("&#CFD8DC截止: &#FFFFFF${DATE_FORMAT.format(Instant.ofEpochMilli(contract.expiresAt()))}"))
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
            lines.add("&#CFD8DC奖金托管: &#69DB7C${valueOr(num(draft.amount()))}")
            lines.add("&#CFD8DC创建费: &#FFE066${trimNumber(fee)} &#CFD8DC(普通取消不退)")
            val amount = draft.amount()
            if (amount != null) lines.add("&#CFD8DC签署后共扣除: &#E63946${trimNumber(amount + fee)}")
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
