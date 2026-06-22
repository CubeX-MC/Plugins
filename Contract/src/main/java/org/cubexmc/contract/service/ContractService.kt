package org.cubexmc.contract.service

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.economy.EconomyService
import org.cubexmc.contract.model.Contract
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.model.ContractType
import org.cubexmc.contract.model.Participant
import org.cubexmc.contract.model.ParticipantRole
import org.cubexmc.contract.model.PayoutCondition
import org.cubexmc.contract.model.PayoutRecipient
import org.cubexmc.contract.model.PayoutRule
import org.cubexmc.contract.storage.ContractStorage
import org.cubexmc.contract.storage.EventLog
import org.cubexmc.contract.storage.PendingTransactionStore
import org.cubexmc.contract.util.Text
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.EnumMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.Objects
import java.util.UUID

class ContractService(
    private val plugin: ContractPlugin,
    private val storage: ContractStorage,
    private val economy: EconomyService,
    private val pending: PendingTransactionStore,
    private val eventLog: EventLog,
) {
    private fun logEvent(contract: Contract, time: Long, type: String, detail: String) {
        contract.addEvent(time, type, detail)
        eventLog.append(contract.id(), type, detail)
    }

    fun create(owner: Player, rewardDouble: Double, days: Int, title: String, description: String): ServiceResult =
        create(owner, rewardDouble, days, title, description, null)

    fun create(
        owner: Player,
        rewardDouble: Double,
        days: Int,
        title: String,
        description: String,
        mediatorName: String?,
    ): ServiceResult {
        if (!rewardDouble.isFinite()) {
            return ServiceResult.fail("奖金必须是有效数字")
        }
        val cleanTitle = Text.stripControl(title)
        if (cleanTitle.isBlank()) {
            return ServiceResult.fail("标题不能为空")
        }
        val maxTitleLength = plugin.config.getInt("limits.max-title-length", 80)
        if (cleanTitle.length > maxTitleLength) {
            return ServiceResult.fail("标题不能超过 $maxTitleLength 个字符")
        }
        var cleanDescription = Text.stripControl(description)
        if (cleanDescription.isBlank()) {
            cleanDescription = cleanTitle
        }
        val maxDescriptionLength = plugin.config.getInt("limits.max-description-length", 500)
        if (cleanDescription.length > maxDescriptionLength) {
            return ServiceResult.fail("描述不能超过 $maxDescriptionLength 个字符")
        }
        val reward = BigDecimal.valueOf(rewardDouble).setScale(2, RoundingMode.HALF_UP)
        val minReward = BigDecimal.valueOf(plugin.config.getDouble("economy.min-reward", 100.0))
        val maxReward = BigDecimal.valueOf(plugin.config.getDouble("economy.max-reward", 100000.0))
        if (reward < minReward || reward > maxReward) {
            return ServiceResult.fail("奖金必须在 ${economy.format(minReward)} 到 ${economy.format(maxReward)} 之间")
        }
        val minDays = plugin.config.getInt("limits.min-deadline-days", 1)
        val maxDays = plugin.config.getInt("limits.max-deadline-days", 7)
        if (days < minDays || days > maxDays) {
            return ServiceResult.fail("有效期必须在 $minDays 到 $maxDays 天之间")
        }
        val openLimit = plugin.config.getInt("limits.max-open-contracts", 3)
        val openCount = storage.all().stream()
            .filter { contract -> contract.ownerUuid() == owner.uniqueId }
            .filter { contract -> contract.status().countsAsOwnerActive() }
            .count()
        if (openCount >= openLimit && !owner.hasPermission("contract.bypass.limit")) {
            return ServiceResult.fail("你的公开或待处理合同数量已达上限 $openLimit")
        }
        val mediator = resolveOptionalMediator(mediatorName, owner.uniqueId)
        if (!mediator.success()) {
            return ServiceResult.fail(mediator.error())
        }

        val creationFee =
            if (owner.hasPermission("contract.bypass.fee")) {
                BigDecimal.ZERO
            } else {
                BigDecimal.valueOf(plugin.config.getDouble("economy.creation-fee", 20.0))
                    .setScale(2, RoundingMode.HALF_UP)
            }
        val totalCost = reward.add(creationFee)
        if (!economy.has(owner, totalCost)) {
            return ServiceResult.fail("余额不足，需要 ${economy.format(totalCost)}")
        }

        val contractId = UUID.randomUUID().toString()
        val pendingId = try {
            pending.beginWithdraw(owner.uniqueId, totalCost, "contract-create", contractId)
        } catch (ex: IOException) {
            return ServiceResult.fail("无法写入待办事务日志: ${ex.message}")
        }

        val withdrawal = economy.withdraw(owner, totalCost)
        if (!withdrawal.success()) {
            tryClearPending(pendingId)
            return ServiceResult.fail("扣款失败: ${withdrawal.reason()}")
        }

        val now = System.currentTimeMillis()
        val expiresAt = now + days * 24L * 60L * 60L * 1000L
        val commissionPercent = clampCommissionPercent(
            plugin.config.getDouble("economy.completion-commission-percent", 5.0),
        )
        val contract = Contract.createService(
            contractId,
            owner.uniqueId,
            owner.name,
            cleanTitle,
            cleanDescription,
            reward,
            creationFee,
            commissionPercent,
            now,
            expiresAt,
        )
        applyOptionalMediator(contract, mediator)

        try {
            storage.put(contract)
            storage.save()
        } catch (ex: IOException) {
            storage.remove(contract.id())
            refundOrKeepPending(owner.uniqueId, totalCost, pendingId)
            return ServiceResult.fail("保存失败，已退回扣款: ${ex.message}")
        }

        eventLog.append(
            contract.id(),
            "CREATED",
            "${owner.name} created the contract and escrowed ${reward.toPlainString()}",
        )
        if (mediator.present()) {
            eventLog.append(contract.id(), "MEDIATOR_ASSIGNED", "${mediator.name()} assigned as optional mediator")
        }
        tryClearPending(pendingId)
        return ServiceResult.ok(contract, reward)
    }

    private fun resolveOptionalMediator(mediatorName: String?, vararg excluded: UUID): MediatorSpec {
        if (mediatorName.isNullOrBlank()) {
            return MediatorSpec.none()
        }
        val mediator = Bukkit.getOfflinePlayer(mediatorName)
        val mediatorUuid = mediator.uniqueId
        for (blocked in excluded) {
            if (mediatorUuid == blocked) {
                return MediatorSpec.fail("中间人不能是合同参与方")
            }
        }
        if (!mediator.isOnline && !mediator.hasPlayedBefore()) {
            return MediatorSpec.fail("找不到中间人 $mediatorName(需在线或曾登录本服)")
        }
        return MediatorSpec.ok(mediatorUuid, mediator.name ?: mediatorName)
    }

    private fun applyOptionalMediator(contract: Contract, mediator: MediatorSpec) {
        if (!mediator.present()) {
            return
        }
        contract.arbiter(Participant(ParticipantRole.MEDIATOR, mediator.uuid(), mediator.name(), listOf()))
        contract.arbiterAccepted(false)
        contract.addEvent(System.currentTimeMillis(), "MEDIATOR_ASSIGNED", "${mediator.name()} assigned as optional mediator")
    }

    private fun clampCommissionPercent(percent: Double): BigDecimal {
        val value = BigDecimal.valueOf(percent).setScale(2, RoundingMode.HALF_UP)
        if (value.signum() < 0) {
            return BigDecimal.ZERO
        }
        val hundred = BigDecimal("100")
        if (value > hundred) {
            return hundred
        }
        return value
    }

    private fun tryClearPending(pendingId: String) {
        try {
            pending.clear(pendingId)
        } catch (ex: IOException) {
            plugin.logger.warning("Failed to clear pending transaction $pendingId: ${ex.message}")
        }
    }

    fun recoverPendingTransactions() {
        for (entry in pending.loadAll()) {
            when (entry.type()) {
                PendingTransactionStore.PendingType.WITHDRAW -> recoverWithdraw(entry)
                PendingTransactionStore.PendingType.DEPOSIT,
                PendingTransactionStore.PendingType.SETTLEMENT,
                -> recoverInterruptedSettlement(entry)
            }
        }
    }

    private fun recoverWithdraw(entry: PendingTransactionStore.PendingEntry) {
        val playerUuid = entry.playerUuid()
        if (playerUuid == null) {
            plugin.logger.warning("Cannot recover pending withdraw ${entry.id()} without player uuid.")
            return
        }
        val contractId = entry.contractId()
        val refund = if (contractId == null || contractId.isBlank()) {
            true
        } else {
            val status = storage.findById(contractId).map { contract -> contract.status() }.orElse(null)
            shouldRefundOrphanWithdraw(entry.purpose(), status)
        }
        if (!refund) {
            plugin.logger.warning(
                "Pending withdraw ${entry.id()} (${entry.purpose()}) already became escrow for contract $contractId; clearing without refund.",
            )
            tryClearPending(entry.id())
            return
        }
        val result = economy.deposit(playerUuid, entry.amount())
        if (!result.success()) {
            plugin.logger.warning("Failed to recover pending withdraw ${entry.id()}: ${result.reason()}")
            return
        }
        plugin.logger.warning(
            "Recovered orphan pending withdraw ${entry.id()} (${entry.purpose()}) refunded ${entry.amount()} to ${entry.playerUuid()}",
        )
        tryClearPending(entry.id())
    }

    private fun refundOrKeepPending(playerUuid: UUID, amount: BigDecimal, pendingId: String) {
        val refund = economy.deposit(playerUuid, amount)
        if (refund.success()) {
            tryClearPending(pendingId)
            return
        }
        plugin.logger.severe(
            "Refund of $amount to $playerUuid failed (${refund.reason()}); keeping pending transaction $pendingId for crash recovery.",
        )
    }

    private fun recoverInterruptedSettlement(entry: PendingTransactionStore.PendingEntry) {
        val contractId = entry.contractId()
        if (contractId == null || contractId.isBlank()) {
            plugin.logger.warning("Pending ${entry.type()} ${entry.id()} has no contract id; leaving it for manual review.")
            return
        }
        val contract = storage.findById(contractId).orElse(null)
        if (contract == null) {
            plugin.logger.warning(
                "Pending ${entry.type()} ${entry.id()} references missing contract $contractId; clearing stale entry.",
            )
            tryClearPending(entry.id())
            return
        }
        if (contract.status().isFinal()) {
            plugin.logger.warning("Clearing stale pending ${entry.type()} ${entry.id()} for finalized contract ${contract.shortId()}")
            tryClearPending(entry.id())
            return
        }

        val now = System.currentTimeMillis()
        contract.status(ContractStatus.DISPUTED)
        contract.disputeReason("结算中断，需要管理员核对 pending transaction ${entry.id()}")
        logEvent(
            contract,
            now,
            "SETTLEMENT_RECOVERY_REQUIRED",
            "pending ${entry.type()} ${entry.id()} purpose=${entry.purpose()} player=${entry.playerUuid()} amount=${entry.amount()} payout=${entry.payoutKey()}",
        )
        try {
            storage.save()
            tryClearPending(entry.id())
        } catch (ex: IOException) {
            plugin.logger.warning("Failed to persist interrupted settlement recovery for ${contract.shortId()}: ${ex.message}")
        }
    }

    fun accept(contractor: Player, contract: Contract): ServiceResult =
        when (contract.type()) {
            ContractType.WAGER -> acceptWager(contractor, contract)
            ContractType.PARTNERSHIP -> acceptPartnership(contractor, contract)
            else -> acceptService(contractor, contract)
        }

    private fun acceptService(contractor: Player, contract: Contract): ServiceResult {
        if (contract.status() != ContractStatus.OPEN) {
            return ServiceResult.fail("合同当前不可接取")
        }
        if (contract.isExpired(System.currentTimeMillis())) {
            return rejectExpiredAcceptance(contract)
        }
        if (contract.ownerUuid() == contractor.uniqueId) {
            return ServiceResult.fail("不能接取自己发布的合同")
        }
        if (isAssignedArbiter(contractor, contract)) {
            return ServiceResult.fail("中间人不能接取自己负责裁决的合同")
        }
        val limit = plugin.config.getInt("limits.max-active-accepted-contracts", 3)
        val activeAccepted = storage.all().stream()
            .filter { existing -> contractor.uniqueId == existing.contractorUuid() }
            .filter { existing -> existing.status().countsAsContractorActive() }
            .count()
        if (activeAccepted >= limit && !contractor.hasPermission("contract.bypass.limit")) {
            return ServiceResult.fail("你的接单数量已达上限 $limit")
        }
        val now = System.currentTimeMillis()
        contract.contractorUuid(contractor.uniqueId)
        contract.contractorName(contractor.name)
        contract.acceptedAt(now)
        contract.status(ContractStatus.IN_PROGRESS)
        logEvent(contract, now, "ACCEPTED", "${contractor.name} accepted the contract")
        return dirty(contract)
    }

    private fun acceptWager(player: Player, contract: Contract): ServiceResult {
        if (contract.status() != ContractStatus.PENDING_ACCEPT) {
            return ServiceResult.fail("对赌合同当前不可接受")
        }
        if (contract.isExpired(System.currentTimeMillis())) {
            return rejectExpiredAcceptance(contract)
        }
        val partyB = contract.participant(ParticipantRole.PARTY_B).orElse(null)
        if (partyB == null || partyB.uuid() == null || partyB.uuid() != player.uniqueId) {
            return ServiceResult.fail("只有被指定的对方可以接受这个对赌")
        }
        val stake = partyB.moneyStake()
        if (!economy.has(player, stake)) {
            return ServiceResult.fail("余额不足,需要 ${economy.format(stake)}")
        }
        val pendingId = try {
            pending.beginWithdraw(player.uniqueId, stake, "wager-accept", contract.id())
        } catch (ex: IOException) {
            return ServiceResult.fail("无法写入待办事务日志: ${ex.message}")
        }
        val withdrawal = economy.withdraw(player, stake)
        if (!withdrawal.success()) {
            tryClearPending(pendingId)
            return ServiceResult.fail("扣款失败: ${withdrawal.reason()}")
        }
        partyB.displayName(player.name)
        val now = System.currentTimeMillis()
        contract.acceptedAt(now)
        contract.status(ContractStatus.IN_PROGRESS)
        logEvent(contract, now, "WAGER_ACCEPTED", "${player.name} matched the wager with stake ${stake.toPlainString()}")

        val result = saveSync(contract, stake)
        if (!result.success()) {
            contract.status(ContractStatus.PENDING_ACCEPT)
            contract.acceptedAt(null)
            refundOrKeepPending(player.uniqueId, stake, pendingId)
            return result
        }
        tryClearPending(pendingId)
        return result
    }

    private fun acceptPartnership(player: Player, contract: Contract): ServiceResult {
        if (contract.status() != ContractStatus.PENDING_ACCEPT) {
            return ServiceResult.fail("合作合同当前不可接受")
        }
        if (contract.isExpired(System.currentTimeMillis())) {
            return rejectExpiredAcceptance(contract)
        }
        val partyB = contract.participant(ParticipantRole.PARTY_B).orElse(null)
        if (partyB == null || partyB.uuid() == null || partyB.uuid() != player.uniqueId) {
            return ServiceResult.fail("只有被邀请的合作方可以接受")
        }
        val stake = partyB.moneyStake()
        if (!economy.has(player, stake)) {
            return ServiceResult.fail("余额不足,需要 ${economy.format(stake)}")
        }
        val pendingId = try {
            pending.beginWithdraw(player.uniqueId, stake, "partnership-accept", contract.id())
        } catch (ex: IOException) {
            return ServiceResult.fail("无法写入待办事务日志: ${ex.message}")
        }
        val withdrawal = economy.withdraw(player, stake)
        if (!withdrawal.success()) {
            tryClearPending(pendingId)
            return ServiceResult.fail("扣款失败: ${withdrawal.reason()}")
        }
        partyB.displayName(player.name)
        val now = System.currentTimeMillis()
        contract.acceptedAt(now)
        contract.status(ContractStatus.IN_PROGRESS)
        logEvent(contract, now, "PARTNERSHIP_ACCEPTED", "${player.name} joined partnership with stake ${stake.toPlainString()}")

        val result = saveSync(contract, stake)
        if (!result.success()) {
            contract.status(ContractStatus.PENDING_ACCEPT)
            contract.acceptedAt(null)
            refundOrKeepPending(player.uniqueId, stake, pendingId)
            return result
        }
        tryClearPending(pendingId)
        return result
    }

    fun createPartnership(
        creator: Player,
        partnerName: String,
        stakeA: BigDecimal,
        stakeB: BigDecimal,
        days: Int,
        title: String,
        description: String,
    ): ServiceResult = createPartnership(creator, partnerName, stakeA, stakeB, days, title, description, null)

    fun createPartnership(
        creator: Player,
        partnerName: String,
        stakeA: BigDecimal,
        stakeB: BigDecimal,
        days: Int,
        title: String,
        description: String,
        mediatorName: String?,
    ): ServiceResult {
        val cleanTitle = Text.stripControl(title)
        if (cleanTitle.isBlank()) {
            return ServiceResult.fail("标题不能为空")
        }
        val maxTitleLength = plugin.config.getInt("limits.max-title-length", 80)
        if (cleanTitle.length > maxTitleLength) {
            return ServiceResult.fail("标题不能超过 $maxTitleLength 个字符")
        }
        var cleanDescription = Text.stripControl(description)
        if (cleanDescription.isBlank()) {
            cleanDescription = cleanTitle
        }
        val minStake = BigDecimal.valueOf(plugin.config.getDouble("economy.min-reward", 100.0))
        val maxStake = BigDecimal.valueOf(plugin.config.getDouble("economy.max-reward", 100000.0))
        val normA = stakeA.setScale(2, RoundingMode.HALF_UP)
        val normB = stakeB.setScale(2, RoundingMode.HALF_UP)
        if (normA < minStake || normA > maxStake || normB < minStake || normB > maxStake) {
            return ServiceResult.fail("双方押注都必须在 ${economy.format(minStake)} 到 ${economy.format(maxStake)} 之间")
        }
        val minDays = plugin.config.getInt("limits.min-deadline-days", 1)
        val maxDays = plugin.config.getInt("limits.max-deadline-days", 7)
        if (days < minDays || days > maxDays) {
            return ServiceResult.fail("有效期必须在 $minDays 到 $maxDays 天之间")
        }

        val partner = Bukkit.getOfflinePlayer(partnerName)
        if (partner.uniqueId == creator.uniqueId) {
            return ServiceResult.fail("不能和自己合作")
        }
        if (!partner.isOnline && !partner.hasPlayedBefore()) {
            return ServiceResult.fail("找不到玩家 $partnerName(需在线或曾登录本服)")
        }
        val mediator = resolveOptionalMediator(mediatorName, creator.uniqueId, partner.uniqueId)
        if (!mediator.success()) {
            return ServiceResult.fail(mediator.error())
        }

        if (!economy.has(creator, normA)) {
            return ServiceResult.fail("余额不足,需要 ${economy.format(normA)}")
        }

        val contractId = UUID.randomUUID().toString()
        val pendingId = try {
            pending.beginWithdraw(creator.uniqueId, normA, "partnership-create", contractId)
        } catch (ex: IOException) {
            return ServiceResult.fail("无法写入待办事务日志: ${ex.message}")
        }
        val withdrawal = economy.withdraw(creator, normA)
        if (!withdrawal.success()) {
            tryClearPending(pendingId)
            return ServiceResult.fail("扣款失败: ${withdrawal.reason()}")
        }

        val now = System.currentTimeMillis()
        val expiresAt = now + days * 24L * 60L * 60L * 1000L
        val commissionPercent = clampCommissionPercent(plugin.config.getDouble("economy.completion-commission-percent", 5.0))
        val contract = Contract.createPartnership(
            contractId,
            creator.uniqueId,
            creator.name,
            partner.uniqueId,
            partner.name ?: partnerName,
            normA,
            normB,
            commissionPercent,
            cleanTitle,
            cleanDescription,
            now,
            expiresAt,
        )
        applyOptionalMediator(contract, mediator)

        try {
            storage.put(contract)
            storage.save()
        } catch (ex: IOException) {
            storage.remove(contract.id())
            refundOrKeepPending(creator.uniqueId, normA, pendingId)
            return ServiceResult.fail("保存失败,已退回扣款: ${ex.message}")
        }
        eventLog.append(
            contract.id(),
            "CREATED",
            "${creator.name} proposed partnership with $partnerName, stakes ${normA.toPlainString()}/${normB.toPlainString()}",
        )
        if (mediator.present()) {
            eventLog.append(contract.id(), "MEDIATOR_ASSIGNED", "${mediator.name()} assigned as optional mediator")
        }
        tryClearPending(pendingId)
        return ServiceResult.ok(contract, normA)
    }

    private fun approvePartnership(player: Player, contract: Contract): ServiceResult {
        if (contract.status() != ContractStatus.IN_PROGRESS) {
            return ServiceResult.fail("只有进行中的合作合同可以确认")
        }
        val me = contract.participantByUuid(player.uniqueId).orElse(null)
        if (me == null || me.role() != ParticipantRole.PARTY_A && me.role() != ParticipantRole.PARTY_B) {
            return ServiceResult.fail("只有合作方可以确认")
        }
        val approved = contract.metadata.getOrDefault("approved-roles", "")
        val set = LinkedHashSet<String>()
        if (approved.isNotEmpty()) {
            for (s in approved.split(",")) {
                set.add(s)
            }
        }
        if (!set.add(me.role().name)) {
            return ServiceResult.fail("你已经确认过了,等待对方")
        }
        contract.metadata["approved-roles"] = set.joinToString(",")
        val now = System.currentTimeMillis()
        logEvent(contract, now, "PARTNERSHIP_APPROVED", "${player.name} (${me.role()}) approved partnership")

        if (set.size >= 2) {
            return settle(
                contract,
                PayoutCondition.SUCCESS,
                ContractStatus.COMPLETED,
                "PARTNERSHIP_COMPLETED",
                "both partners approved",
            )
        }
        return dirty(contract)
    }

    fun createWager(
        creator: Player,
        opponentName: String,
        stake: BigDecimal,
        days: Int,
        arbiterName: String,
        title: String,
        description: String,
    ): ServiceResult {
        val cleanTitle = Text.stripControl(title)
        if (cleanTitle.isBlank()) {
            return ServiceResult.fail("标题不能为空")
        }
        val maxTitleLength = plugin.config.getInt("limits.max-title-length", 80)
        if (cleanTitle.length > maxTitleLength) {
            return ServiceResult.fail("标题不能超过 $maxTitleLength 个字符")
        }
        var cleanDescription = Text.stripControl(description)
        if (cleanDescription.isBlank()) {
            cleanDescription = cleanTitle
        }

        val minStake = BigDecimal.valueOf(plugin.config.getDouble("economy.min-reward", 100.0))
        val maxStake = BigDecimal.valueOf(plugin.config.getDouble("economy.max-reward", 100000.0))
        val normalizedStake = stake.setScale(2, RoundingMode.HALF_UP)
        if (normalizedStake < minStake || normalizedStake > maxStake) {
            return ServiceResult.fail("押注必须在 ${economy.format(minStake)} 到 ${economy.format(maxStake)} 之间")
        }

        val minDays = plugin.config.getInt("limits.min-deadline-days", 1)
        val maxDays = plugin.config.getInt("limits.max-deadline-days", 7)
        if (days < minDays || days > maxDays) {
            return ServiceResult.fail("有效期必须在 $minDays 到 $maxDays 天之间")
        }

        val opponent = Bukkit.getOfflinePlayer(opponentName)
        val arbiter = Bukkit.getOfflinePlayer(arbiterName)
        if (opponent.uniqueId == creator.uniqueId) {
            return ServiceResult.fail("不能和自己对赌")
        }
        if (arbiter.uniqueId == creator.uniqueId || arbiter.uniqueId == opponent.uniqueId) {
            return ServiceResult.fail("仲裁者必须是第三方")
        }
        if (!opponent.isOnline && !opponent.hasPlayedBefore()) {
            return ServiceResult.fail("找不到玩家 $opponentName(需在线或曾登录本服)")
        }
        if (!arbiter.isOnline && !arbiter.hasPlayedBefore()) {
            return ServiceResult.fail("找不到玩家 $arbiterName(需在线或曾登录本服)")
        }

        if (!economy.has(creator, normalizedStake)) {
            return ServiceResult.fail("余额不足,需要 ${economy.format(normalizedStake)}")
        }

        val contractId = UUID.randomUUID().toString()
        val pendingId = try {
            pending.beginWithdraw(creator.uniqueId, normalizedStake, "wager-create", contractId)
        } catch (ex: IOException) {
            return ServiceResult.fail("无法写入待办事务日志: ${ex.message}")
        }
        val withdrawal = economy.withdraw(creator, normalizedStake)
        if (!withdrawal.success()) {
            tryClearPending(pendingId)
            return ServiceResult.fail("扣款失败: ${withdrawal.reason()}")
        }

        val now = System.currentTimeMillis()
        val expiresAt = now + days * 24L * 60L * 60L * 1000L
        val commissionPercent = clampCommissionPercent(plugin.config.getDouble("economy.completion-commission-percent", 5.0))
        val contract = Contract.createWager(
            contractId,
            creator.uniqueId,
            creator.name,
            opponent.uniqueId,
            opponent.name ?: opponentName,
            arbiter.uniqueId,
            arbiter.name ?: arbiterName,
            cleanTitle,
            cleanDescription,
            normalizedStake,
            commissionPercent,
            now,
            expiresAt,
        )

        try {
            storage.put(contract)
            storage.save()
        } catch (ex: IOException) {
            storage.remove(contract.id())
            refundOrKeepPending(creator.uniqueId, normalizedStake, pendingId)
            return ServiceResult.fail("保存失败,已退回扣款: ${ex.message}")
        }
        eventLog.append(contract.id(), "CREATED", "${creator.name} opened wager vs $opponentName, stake ${normalizedStake.toPlainString()}")
        tryClearPending(pendingId)
        return ServiceResult.ok(contract, normalizedStake)
    }

    fun resolveWager(arbiter: Player, contract: Contract, winner: String): ServiceResult {
        if (contract.type() != ContractType.WAGER) {
            return ServiceResult.fail("这不是对赌合同")
        }
        if (contract.status() != ContractStatus.IN_PROGRESS && contract.status() != ContractStatus.SUBMITTED) {
            return ServiceResult.fail("当前状态不可裁决")
        }
        val arbiterParticipant = contract.arbiter()
        if (arbiterParticipant == null || arbiterParticipant.uuid() == null || arbiterParticipant.uuid() != arbiter.uniqueId) {
            return ServiceResult.fail("只有指定的仲裁者可以裁决")
        }

        val condition: PayoutCondition
        val winnerRole: ParticipantRole
        if (winner.equals("a", ignoreCase = true)) {
            condition = PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER
            winnerRole = ParticipantRole.PARTY_A
        } else if (winner.equals("b", ignoreCase = true)) {
            condition = PayoutCondition.DISPUTE_RESOLVED_FOR_CONTRACTOR
            winnerRole = ParticipantRole.PARTY_B
        } else {
            return ServiceResult.fail("裁决结果必须是 a 或 b")
        }
        val winnerName = contract.participant(winnerRole).map { it.displayName() }.orElse(winner)
        return settle(
            contract,
            condition,
            ContractStatus.COMPLETED,
            "WAGER_RESOLVED",
            "${arbiter.name} ruled in favor of ${winner.uppercase(Locale.ROOT)} ($winnerName)",
        )
    }

    fun acceptMediation(mediator: Player, contract: Contract): ServiceResult {
        if (!isAssignedArbiter(mediator, contract)) {
            return ServiceResult.fail("只有指定中间人可以接受此职责")
        }
        if (contract.arbiterAccepted()) {
            return ServiceResult.fail("中间人职责已经接受")
        }
        contract.arbiterAccepted(true)
        val now = System.currentTimeMillis()
        logEvent(contract, now, "MEDIATOR_ACCEPTED", "${mediator.name} accepted mediator duties")
        return dirty(contract)
    }

    fun mediate(mediator: Player, contract: Contract, decision: String): ServiceResult {
        if (!isAssignedArbiter(mediator, contract)) {
            return ServiceResult.fail("只有指定中间人可以裁决这个合同")
        }
        if (decision.equals("accept", ignoreCase = true)) {
            return acceptMediation(mediator, contract)
        }
        if (!contract.arbiterAccepted()) {
            return ServiceResult.fail("中间人需要先接受职责: /contract mediate ${contract.shortId()} accept")
        }
        if (contract.status().isFinal()) {
            return ServiceResult.fail("已结束的合同不能裁决")
        }
        if (contract.status() == ContractStatus.OPEN || contract.status() == ContractStatus.PENDING_ACCEPT) {
            return ServiceResult.fail("合同尚未生效,不能由中间人裁决")
        }
        val normalized = decision.lowercase(Locale.ROOT)
        if (contract.type() == ContractType.WAGER && (normalized == "a" || normalized == "owner")) {
            return resolveWager(mediator, contract, "a")
        }
        if (contract.type() == ContractType.WAGER && (normalized == "b" || normalized == "contractor")) {
            return resolveWager(mediator, contract, "b")
        }

        return when (normalized) {
            "pay", "success" -> settle(contract, PayoutCondition.SUCCESS, ContractStatus.COMPLETED, "MEDIATOR_PAID", "${mediator.name} mediated success")
            "refund", "void", "failure" -> settle(contract, PayoutCondition.FAILURE, ContractStatus.CANCELLED, "MEDIATOR_REFUNDED", "${mediator.name} mediated refund")
            "owner", "a" -> mediateForSide(mediator, contract, PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER, "MEDIATOR_OWNER_WIN", "owner/party A")
            "contractor", "b" -> mediateForSide(mediator, contract, PayoutCondition.DISPUTE_RESOLVED_FOR_CONTRACTOR, "MEDIATOR_CONTRACTOR_WIN", "contractor/party B")
            else -> ServiceResult.fail("裁决结果必须是 accept、pay、refund、owner/a 或 contractor/b")
        }
    }

    private fun mediateForSide(
        mediator: Player,
        contract: Contract,
        condition: PayoutCondition,
        eventType: String,
        label: String,
    ): ServiceResult {
        if (contract.type() == ContractType.SERVICE) {
            if (condition == PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER) {
                return settle(contract, PayoutCondition.FAILURE, ContractStatus.CANCELLED, "MEDIATOR_REFUNDED", "${mediator.name} mediated for owner")
            }
            return settle(contract, PayoutCondition.SUCCESS, ContractStatus.COMPLETED, "MEDIATOR_PAID", "${mediator.name} mediated for contractor")
        }
        return settle(contract, condition, ContractStatus.COMPLETED, eventType, "${mediator.name} mediated for $label")
    }

    private fun isAssignedArbiter(player: Player, contract: Contract): Boolean {
        val arbiterParticipant = contract.arbiter()
        return arbiterParticipant != null && arbiterParticipant.uuid() != null && arbiterParticipant.uuid() == player.uniqueId
    }

    fun submit(player: Player, contract: Contract): ServiceResult {
        if (contract.type() != ContractType.SERVICE) {
            return ServiceResult.fail("这个合同类型不使用提交完成流程")
        }
        if (contract.status() != ContractStatus.IN_PROGRESS) {
            return ServiceResult.fail("只有进行中的合同可以提交完成")
        }
        if (player.uniqueId != contract.contractorUuid()) {
            return ServiceResult.fail("只有接单者可以提交完成")
        }
        val now = System.currentTimeMillis()
        contract.status(ContractStatus.SUBMITTED)
        contract.submittedAt(now)
        logEvent(contract, now, "SUBMITTED", "${player.name} submitted the contract")
        return dirty(contract)
    }

    fun approve(player: Player, contract: Contract): ServiceResult {
        if (contract.type() == ContractType.PARTNERSHIP) {
            return approvePartnership(player, contract)
        }
        if (contract.type() != ContractType.SERVICE) {
            return ServiceResult.fail("这个合同类型不能用 approve 确认")
        }
        // Owner may confirm payment as soon as the work is done — either after the contractor submits
        // (SUBMITTED) or proactively while still IN_PROGRESS (early acceptance). The owner only ever
        // gives away their own escrow to the contractor they chose, so this harms no third party.
        if (contract.status() != ContractStatus.SUBMITTED && contract.status() != ContractStatus.IN_PROGRESS) {
            return ServiceResult.fail("只有进行中或待确认的合同可以确认付款")
        }
        if (player.uniqueId != contract.ownerUuid()) {
            return ServiceResult.fail("只有雇主可以确认这个合同")
        }
        val early = contract.status() == ContractStatus.IN_PROGRESS
        return pay(
            contract,
            if (early) "APPROVED_EARLY" else "APPROVED",
            if (early) "${player.name} approved early before submission" else "${player.name} approved the contract",
        )
    }

    fun cancel(player: Player, contract: Contract): ServiceResult {
        val result = cancelInternal(player, contract)
        // Only count a real cancellation against the canceller; an escalation to dispute is not one.
        if (result.success() && result.contract()?.status() == ContractStatus.CANCELLED) {
            plugin.reputation().recordCancelled(player.uniqueId, player.name)
        }
        return result
    }

    private fun cancelInternal(player: Player, contract: Contract): ServiceResult {
        val playerUuid = player.uniqueId
        val isOwner = playerUuid == contract.ownerUuid()
        val isContractor = playerUuid == contract.contractorUuid()
        if (!isOwner && !isContractor) {
            return ServiceResult.fail("只有合同相关玩家可以取消")
        }
        val now = System.currentTimeMillis()
        if (contract.status() == ContractStatus.OPEN && isOwner) {
            val refundFee = plugin.config.getBoolean("economy.refund-creation-fee-on-cancel", false)
            val extra = if (refundFee) contract.creationFee() else BigDecimal.ZERO
            return refund(contract, ContractStatus.CANCELLED, "CANCELLED", "${player.name} cancelled the open contract", extra)
        }
        if (contract.status() == ContractStatus.PENDING_ACCEPT && isOwner) {
            return refundPendingAcceptance(contract, ContractStatus.CANCELLED, "CANCELLED_PENDING", "${player.name} cancelled the pending invitation")
        }
        if (contract.status() == ContractStatus.IN_PROGRESS && isContractor) {
            return refund(contract, ContractStatus.CANCELLED, "CONTRACTOR_CANCELLED", "${player.name} gave up the contract")
        }
        if (contract.status() == ContractStatus.IN_PROGRESS || contract.status() == ContractStatus.SUBMITTED) {
            contract.status(ContractStatus.DISPUTED)
            contract.disputeReason("取消请求需要管理员处理")
            logEvent(contract, now, "DISPUTED", "${player.name} requested cancellation during active work")
            return dirty(contract)
        }
        return ServiceResult.fail("这个状态下不能取消合同")
    }

    fun dispute(player: Player, contract: Contract, reason: String): ServiceResult {
        val playerUuid = player.uniqueId
        val isOwner = playerUuid == contract.ownerUuid()
        val isContractor = playerUuid == contract.contractorUuid()
        if (!isOwner && !isContractor) {
            return ServiceResult.fail("只有合同相关玩家可以发起争议")
        }
        if (isOwner && !plugin.config.getBoolean("disputes.allow-owner-dispute", true)) {
            return ServiceResult.fail("当前不允许雇主发起争议")
        }
        if (isContractor && !plugin.config.getBoolean("disputes.allow-contractor-dispute", true)) {
            return ServiceResult.fail("当前不允许接单者发起争议")
        }
        if (contract.status().isFinal()) {
            return ServiceResult.fail("已结束的合同不能发起争议")
        }
        val now = System.currentTimeMillis()
        // Remember who raised it and the state to return to, so the initiator can withdraw it later.
        contract.metadata["dispute-by"] = playerUuid.toString()
        contract.metadata["dispute-prev-status"] = contract.status().name
        contract.status(ContractStatus.DISPUTED)
        contract.disputeReason(Text.stripControl(reason))
        logEvent(contract, now, "DISPUTED", "${player.name}: ${contract.disputeReason()}")
        plugin.reputation().recordDisputed(playerUuid, player.name)
        return dirty(contract)
    }

    /**
     * Lets the player who raised a dispute withdraw it, restoring the contract to its pre-dispute
     * state. Only player-initiated disputes carry the `dispute-by`/`dispute-prev-status` markers;
     * system settlement-interruption holds do not, so those still require an admin.
     */
    fun withdrawDispute(player: Player, contract: Contract): ServiceResult {
        if (contract.status() != ContractStatus.DISPUTED) {
            return ServiceResult.fail("只有处于争议中的合同可以撤销争议")
        }
        val raisedBy = contract.metadata["dispute-by"]
        val previousName = contract.metadata["dispute-prev-status"]
        if (raisedBy == null || previousName == null) {
            return ServiceResult.fail("这个争议需要管理员处理，不能自行撤销")
        }
        if (raisedBy != player.uniqueId.toString()) {
            return ServiceResult.fail("只有发起争议的玩家可以撤销争议")
        }
        val previous = try {
            ContractStatus.valueOf(previousName)
        } catch (ex: IllegalArgumentException) {
            return ServiceResult.fail("无法恢复争议前的状态，请联系管理员")
        }
        val now = System.currentTimeMillis()
        contract.status(previous)
        contract.disputeReason(null)
        contract.metadata.remove("dispute-by")
        contract.metadata.remove("dispute-prev-status")
        logEvent(contract, now, "DISPUTE_WITHDRAWN", "${player.name} withdrew the dispute")
        return dirty(contract)
    }

    fun adminPay(contract: Contract, adminName: String): ServiceResult {
        if (contract.contractorUuid() == null) {
            return ServiceResult.fail("没有接单者，不能付款")
        }
        if (contract.status().isFinal()) {
            return ServiceResult.fail("合同已经结束")
        }
        if (contract.payoutsFor(PayoutCondition.SUCCESS).isEmpty()) {
            return ServiceResult.fail("这个合同类型没有默认成功付款规则，请使用对应裁决或退款流程")
        }
        return pay(contract, "ADMIN_PAID", "$adminName forced payment")
    }

    fun adminRefund(contract: Contract, adminName: String): ServiceResult {
        if (contract.status().isFinal()) {
            return ServiceResult.fail("合同已经结束")
        }
        return refund(contract, ContractStatus.CANCELLED, "ADMIN_REFUNDED", "$adminName forced refund")
    }

    fun adminClose(contract: Contract, adminName: String): ServiceResult {
        if (contract.status().isFinal()) {
            return ServiceResult.fail("合同已经结束")
        }
        val now = System.currentTimeMillis()
        contract.status(ContractStatus.CANCELLED)
        contract.completedAt(now)
        logEvent(contract, now, "ADMIN_CLOSED", "$adminName closed the contract without moving funds")
        return saveSync(contract, BigDecimal.ZERO)
    }

    fun cleanupExpired(): Int {
        val now = System.currentTimeMillis()
        var changed = 0
        val submittedAutoApproveHours = plugin.config.getInt("expiry.submitted-auto-approve-hours", 72)
        for (contract in storage.all()) {
            val submittedAt = contract.submittedAt()
            if (contract.status() == ContractStatus.SUBMITTED && submittedAutoApproveHours > 0 && submittedAt != null) {
                val autoApproveAt = submittedAt + submittedAutoApproveHours * 60L * 60L * 1000L
                if (now >= autoApproveAt && pay(contract, "AUTO_APPROVED", "submitted contract auto-approved after timeout").success()) {
                    changed++
                }
                continue
            }
            if (contract.isExpired(now)) {
                val result = expireAwaitingAcceptance(contract)
                if (result.success()) {
                    changed++
                }
            }
        }
        changed += purgeRetiredContracts(now)
        return changed
    }

    private fun purgeRetiredContracts(now: Long): Int {
        val completedDays = plugin.config.getInt("retention.completed-contract-days", 90)
        val closedDays = plugin.config.getInt("retention.closed-contract-days", 30)
        var removed = 0
        for (contract in storage.all()) {
            if (shouldPurgeFinalContract(contract.status(), contract.completedAt(), now, completedDays, closedDays)) {
                storage.remove(contract.id())
                removed++
            }
        }
        if (removed > 0) {
            storage.markDirty()
            try {
                storage.save()
            } catch (ex: IOException) {
                plugin.logger.warning("Failed to persist retired contract purge: ${ex.message}")
            }
        }
        return removed
    }

    private fun rejectExpiredAcceptance(contract: Contract): ServiceResult {
        val result = expireAwaitingAcceptance(contract)
        if (!result.success()) {
            return ServiceResult.fail("合同已过接单截止时间，自动处理失败: ${result.reason()}")
        }
        return ServiceResult.fail("合同已过接单截止时间")
    }

    private fun expireAwaitingAcceptance(contract: Contract): ServiceResult =
        when (contract.status()) {
            ContractStatus.OPEN -> refund(contract, ContractStatus.EXPIRED, "EXPIRED", "contract expired before acceptance")
            ContractStatus.PENDING_ACCEPT -> refundPendingAcceptance(
                contract,
                ContractStatus.CANCELLED,
                "EXPIRED_PENDING",
                "opponent did not accept in time",
            )
            else -> ServiceResult.fail("合同当前状态不按接单截止处理")
        }

    fun openContracts(): List<Contract> = storage.openContracts()

    fun allContracts(): List<Contract> = storage.all()

    private fun pay(contract: Contract, eventType: String, detail: String): ServiceResult =
        settle(contract, PayoutCondition.SUCCESS, ContractStatus.COMPLETED, eventType, detail)

    private fun refund(contract: Contract, status: ContractStatus, eventType: String, detail: String): ServiceResult =
        refund(contract, status, eventType, detail, BigDecimal.ZERO)

    private fun refund(
        contract: Contract,
        status: ContractStatus,
        eventType: String,
        detail: String,
        extra: BigDecimal?,
    ): ServiceResult {
        val condition = if (status == ContractStatus.EXPIRED) PayoutCondition.TIMEOUT else PayoutCondition.FAILURE
        val base = settle(contract, condition, status, eventType, detail)
        if (!base.success() || extra == null || extra.signum() <= 0) {
            return base
        }
        val ownerUuid = contract.ownerUuid() ?: throw NullPointerException("ownerUuid")
        val bonus = economy.deposit(ownerUuid, extra)
        if (!bonus.success()) {
            return ServiceResult.fail("退款附加失败: ${bonus.reason()}")
        }
        val baseContract = base.contract() ?: throw NullPointerException("contract")
        return ServiceResult.ok(baseContract, base.amount().add(extra))
    }

    private fun refundPendingAcceptance(
        contract: Contract,
        status: ContractStatus,
        eventType: String,
        detail: String,
    ): ServiceResult {
        val creatorRole = if (contract.type() == ContractType.SERVICE) ParticipantRole.OWNER else ParticipantRole.PARTY_A
        val creatorRefund = PayoutRule(
            PayoutCondition.FAILURE,
            creatorRole,
            PayoutRecipient.participant(creatorRole),
            BigDecimal("100"),
        )
        return settleWithRules(contract, listOf(creatorRefund), "PENDING_ACCEPT_REFUND", status, eventType, detail)
    }

    private fun settle(
        contract: Contract,
        condition: PayoutCondition,
        newStatus: ContractStatus,
        eventType: String,
        detail: String,
    ): ServiceResult = settleWithRules(contract, contract.payoutsFor(condition), condition.name, newStatus, eventType, detail)

    private fun settleWithRules(
        contract: Contract,
        rules: List<PayoutRule>,
        purpose: String,
        newStatus: ContractStatus,
        eventType: String,
        detail: String,
    ): ServiceResult {
        if (rules.isEmpty()) {
            return ServiceResult.fail("这个合同没有可用的结算规则: $purpose")
        }
        val settlementId = try {
            pending.beginSettlement(contract.id(), "$purpose:$eventType")
        } catch (ex: IOException) {
            return ServiceResult.fail("无法写入结算事务日志: ${ex.message}")
        }

        val outcome = executePayouts(contract, rules, settlementId, purpose)
        if (!outcome.success) {
            if (outcome.externalEffects) {
                return interruptSettlement(contract, settlementId, outcome.error)
            }
            tryClearPending(settlementId)
            return ServiceResult.fail(outcome.error)
        }
        val now = System.currentTimeMillis()
        contract.status(newStatus)
        contract.completedAt(now)
        plugin.reputation().recordSettlement(contract, newStatus)
        val totalParticipantPayout = outcome.toRole.values.stream().reduce(BigDecimal.ZERO) { left, right -> left.add(right) }
        logEvent(contract, now, eventType, "$detail; payouts ${outcome.toRole}; sink ${outcome.toSink.toPlainString()}")
        try {
            storage.save()
            tryClearPending(settlementId)
            return ServiceResult.ok(contract, totalParticipantPayout)
        } catch (ex: IOException) {
            return ServiceResult.fail("保存失败，结算可能已执行，已保留待恢复事务 $settlementId: ${ex.message}")
        }
    }

    private fun interruptSettlement(contract: Contract, settlementId: String, reason: String): ServiceResult {
        val now = System.currentTimeMillis()
        contract.status(ContractStatus.DISPUTED)
        contract.disputeReason("结算中断，需要管理员核对 pending transaction $settlementId")
        logEvent(contract, now, "SETTLEMENT_INTERRUPTED", "$reason; pending $settlementId")
        try {
            storage.save()
            tryClearPending(settlementId)
        } catch (ex: IOException) {
            return ServiceResult.fail("结算中断且保存恢复状态失败: ${ex.message}")
        }
        return ServiceResult.fail("结算中断，合同已转入争议等待管理员核对: $reason")
    }

    private fun executePayouts(
        contract: Contract,
        rules: List<PayoutRule>,
        settlementId: String,
        purpose: String,
    ): PayoutOutcome {
        val outcome = PayoutOutcome()
        var index = 0
        for (rule in rules) {
            index++
            val source = contract.participant(rule.source()).orElse(null) ?: continue
            val sourceAmount = source.moneyStake()
            val share = rule.applyTo(sourceAmount)
            if (share.signum() <= 0) {
                continue
            }
            val recipient = rule.recipient()
            when (recipient.kind()) {
                PayoutRecipient.Kind.PARTICIPANT -> {
                    val recipientRole = recipient.role() ?: throw NullPointerException("recipient.role")
                    val target = contract.participant(recipientRole).orElse(null)
                    val targetUuid = target?.uuid()
                    if (target == null || targetUuid == null) {
                        outcome.success = false
                        outcome.error = "找不到收款方角色 $recipientRole"
                        return outcome
                    }
                    val deposit = depositWithPending(contract, targetUuid, share, purpose, settlementId, "rule-$index-$recipientRole")
                    if (!deposit.success()) {
                        outcome.success = false
                        outcome.error = deposit.reason()
                        return outcome
                    }
                    outcome.externalEffects = true
                    outcome.toRole.merge(recipientRole, share) { left, right -> left.add(right) }
                }
                PayoutRecipient.Kind.SYSTEM_SINK -> outcome.toSink = outcome.toSink.add(share)
                PayoutRecipient.Kind.ARBITER -> {
                    val arbiter = contract.arbiter()
                    val arbiterUuid = arbiter?.uuid()
                    if (arbiter == null || arbiterUuid == null) {
                        outcome.success = false
                        outcome.error = "合同没有 arbiter"
                        return outcome
                    }
                    val deposit = depositWithPending(contract, arbiterUuid, share, purpose, settlementId, "rule-$index-ARBITER")
                    if (!deposit.success()) {
                        outcome.success = false
                        outcome.error = deposit.reason()
                        return outcome
                    }
                    outcome.externalEffects = true
                    outcome.toArbiter = outcome.toArbiter.add(share)
                }
            }
        }
        return outcome
    }

    private fun depositWithPending(
        contract: Contract,
        playerUuid: UUID,
        amount: BigDecimal,
        purpose: String,
        settlementId: String,
        payoutKey: String,
    ): EconomyService.TransactionResult {
        val pendingId = try {
            pending.beginDeposit(playerUuid, amount, purpose, contract.id(), payoutKey, settlementId)
        } catch (ex: IOException) {
            return EconomyService.TransactionResult.fail("无法写入 payout 待办事务日志: ${ex.message}")
        }
        val deposit = economy.deposit(playerUuid, amount)
        if (!deposit.success()) {
            tryClearPending(pendingId)
            return deposit
        }
        tryClearPending(pendingId)
        return deposit
    }

    private class MediatorSpec(
        private val success: Boolean,
        private val present: Boolean,
        private val uuid: UUID?,
        private val name: String?,
        private val error: String,
    ) {
        fun success(): Boolean = success

        fun present(): Boolean = present

        fun uuid(): UUID? = uuid

        fun name(): String? = name

        fun error(): String = error

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is MediatorSpec) {
                return false
            }
            return success == other.success &&
                present == other.present &&
                uuid == other.uuid &&
                name == other.name &&
                error == other.error
        }

        override fun hashCode(): Int = Objects.hash(success, present, uuid, name, error)

        override fun toString(): String = "MediatorSpec[success=$success, present=$present, uuid=$uuid, name=$name, error=$error]"

        companion object {
            fun none(): MediatorSpec = MediatorSpec(true, false, null, null, "")

            fun ok(uuid: UUID, name: String): MediatorSpec = MediatorSpec(true, true, uuid, name, "")

            fun fail(error: String): MediatorSpec = MediatorSpec(false, false, null, null, error)
        }
    }

    private class PayoutOutcome {
        var success: Boolean = true
        var error: String = ""
        val toRole: MutableMap<ParticipantRole, BigDecimal> = EnumMap(ParticipantRole::class.java)
        var toSink: BigDecimal = BigDecimal.ZERO
        var toArbiter: BigDecimal = BigDecimal.ZERO
        var externalEffects: Boolean = false
    }

    private fun markDisputed(contract: Contract, reason: String): ServiceResult {
        val now = System.currentTimeMillis()
        contract.status(ContractStatus.DISPUTED)
        contract.disputeReason(reason)
        logEvent(contract, now, "DISPUTED", reason)
        return dirty(contract)
    }

    private fun dirty(contract: Contract): ServiceResult {
        storage.markDirty()
        return ServiceResult.ok(contract)
    }

    private fun saveSync(contract: Contract, amount: BigDecimal): ServiceResult =
        try {
            storage.save()
            ServiceResult.ok(contract, amount)
        } catch (ex: IOException) {
            ServiceResult.fail("保存失败: ${ex.message}")
        }

    companion object {
        @JvmStatic
        fun shouldRefundOrphanWithdraw(purpose: String?, contractStatusOrNull: ContractStatus?): Boolean {
            if (contractStatusOrNull == null) {
                return true
            }
            if (purpose != null && purpose.endsWith("-accept")) {
                return contractStatusOrNull == ContractStatus.PENDING_ACCEPT
            }
            return false
        }

        @JvmStatic
        fun shouldPurgeFinalContract(
            status: ContractStatus,
            completedAt: Long?,
            now: Long,
            completedDays: Int,
            closedDays: Int,
        ): Boolean {
            val settledAt = completedAt ?: return false
            val retentionDays = when (status) {
                ContractStatus.COMPLETED -> completedDays
                ContractStatus.CANCELLED, ContractStatus.EXPIRED -> closedDays
                else -> return false
            }
            if (retentionDays <= 0) {
                return false
            }
            return now >= settledAt + retentionDays * 24L * 60L * 60L * 1000L
        }
    }
}
