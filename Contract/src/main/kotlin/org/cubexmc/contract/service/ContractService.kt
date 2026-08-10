package org.cubexmc.contract.service

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.economy.EconomyService
import org.cubexmc.contract.model.Asset
import org.cubexmc.contract.model.BatchRepeatPolicy
import org.cubexmc.contract.model.Contract
import org.cubexmc.contract.model.ContractObjective
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.model.ContractType
import org.cubexmc.contract.model.ObjectiveType
import org.cubexmc.contract.model.Participant
import org.cubexmc.contract.model.ParticipantRole
import org.cubexmc.contract.model.PayoutCondition
import org.cubexmc.contract.model.PayoutRecipient
import org.cubexmc.contract.model.PayoutRule
import org.cubexmc.contract.storage.ContractStorage
import org.cubexmc.contract.storage.BatchAcceptanceStore
import org.cubexmc.contract.storage.EventLog
import org.cubexmc.contract.storage.PendingTransactionStore
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
    private val batchAcceptances: BatchAcceptanceStore,
) {
    /**
     * Resolves a `ui.*` language key for a player-facing failure reason. Resolved per call rather
     * than cached, so `/contract admin reload` picks up a language change immediately.
     */
    private fun ui(key: String, placeholders: Map<String, String> = emptyMap()): String =
        plugin.lang().ui(key, placeholders)

    private fun logEvent(contract: Contract, time: Long, type: String, detail: String) {
        contract.addEvent(time, type, detail)
        eventLog.append(contract.id(), type, detail)
    }

    @Synchronized
    fun create(owner: Player, rewardDouble: Double, days: Int, title: String, description: String): ServiceResult =
        create(owner, rewardDouble, days, title, description, null, null)

    @Synchronized
    fun create(
        owner: Player,
        rewardDouble: Double,
        days: Int,
        title: String,
        description: String,
        mediatorName: String?,
    ): ServiceResult = create(owner, rewardDouble, days, title, description, mediatorName, null)

    @Synchronized
    fun create(
        owner: Player,
        rewardDouble: Double,
        days: Int,
        title: String,
        description: String,
        mediatorName: String?,
        objective: ContractObjective?,
    ): ServiceResult = create(owner, rewardDouble, days, title, description, mediatorName, objective, 1)

    @Synchronized
    fun create(
        owner: Player,
        rewardDouble: Double,
        days: Int,
        title: String,
        description: String,
        mediatorName: String?,
        objective: ContractObjective?,
        contractCount: Int,
    ): ServiceResult = create(
        owner,
        rewardDouble,
        days,
        title,
        description,
        mediatorName,
        objective,
        contractCount,
        defaultRepeatPolicy(contractCount),
        DEFAULT_REPEAT_COOLDOWN_HOURS,
    )

    @Synchronized
    fun create(
        owner: Player,
        rewardDouble: Double,
        days: Int,
        title: String,
        description: String,
        mediatorName: String?,
        objective: ContractObjective?,
        contractCount: Int,
        repeatPolicy: BatchRepeatPolicy,
        repeatCooldownHours: Int,
    ): ServiceResult = create(owner, rewardDouble, days, title, description, mediatorName, objective, contractCount, repeatPolicy, repeatCooldownHours, null)

    @Synchronized
    fun create(
        owner: Player,
        rewardDouble: Double,
        days: Int,
        title: String,
        description: String,
        mediatorName: String?,
        objective: ContractObjective?,
        contractCount: Int,
        repeatPolicy: BatchRepeatPolicy,
        repeatCooldownHours: Int,
        publishAt: Long?,
    ): ServiceResult = createBatch(
        owner,
        rewardDouble,
        days,
        title,
        description,
        mediatorName,
        objective,
        emptyList(),
        false,
        contractCount,
        repeatPolicy,
        repeatCooldownHours,
        publishAt,
    )

    @Synchronized
    fun createWithItemReward(
        owner: Player,
        days: Int,
        title: String,
        description: String,
        mediatorName: String?,
        objective: ContractObjective?,
    ): ServiceResult = createWithItemReward(owner, days, title, description, mediatorName, objective, 1)

    @Synchronized
    fun createWithItemReward(
        owner: Player,
        days: Int,
        title: String,
        description: String,
        mediatorName: String?,
        objective: ContractObjective?,
        contractCount: Int,
    ): ServiceResult = createWithItemReward(
        owner,
        days,
        title,
        description,
        mediatorName,
        objective,
        contractCount,
        defaultRepeatPolicy(contractCount),
        DEFAULT_REPEAT_COOLDOWN_HOURS,
    )

    @Synchronized
    fun createWithItemReward(
        owner: Player,
        days: Int,
        title: String,
        description: String,
        mediatorName: String?,
        objective: ContractObjective?,
        contractCount: Int,
        repeatPolicy: BatchRepeatPolicy,
        repeatCooldownHours: Int,
    ): ServiceResult = createWithItemReward(owner, days, title, description, mediatorName, objective, contractCount, repeatPolicy, repeatCooldownHours, null)

    @Synchronized
    fun createWithItemReward(
        owner: Player,
        days: Int,
        title: String,
        description: String,
        mediatorName: String?,
        objective: ContractObjective?,
        contractCount: Int,
        repeatPolicy: BatchRepeatPolicy,
        repeatCooldownHours: Int,
        publishAt: Long?,
    ): ServiceResult {
        val hand = owner.inventory.itemInMainHand
        if (hand.type == Material.AIR || hand.amount <= 0) {
            return ServiceResult.fail(ui("err-item-reward-in-hand"))
        }
        if (isRuleGemItem(hand)) {
            return ServiceResult.fail(ui("err-rulegems-reward"))
        }
        val perContractAmount = perContractItemAmount(hand.amount, contractCount)
            ?: return ServiceResult.fail(ui("err-item-not-divisible", mapOf("amount" to hand.amount.toString(), "count" to contractCount.toString())))
        val rewardItem = hand.clone()
        rewardItem.amount = perContractAmount
        return createBatch(
            owner,
            0.0,
            days,
            title,
            description,
            mediatorName,
            objective,
            listOf(rewardItem),
            true,
            contractCount,
            repeatPolicy,
            repeatCooldownHours,
            publishAt,
        )
    }

    private fun createBatch(
        owner: Player,
        rewardDouble: Double,
        days: Int,
        title: String,
        description: String,
        mediatorName: String?,
        objective: ContractObjective?,
        rewardItems: List<ItemStack>,
        itemReward: Boolean,
        contractCount: Int,
        repeatPolicy: BatchRepeatPolicy,
        repeatCooldownHours: Int,
        publishAt: Long?,
    ): ServiceResult {
        if (!rewardDouble.isFinite()) {
            return ServiceResult.fail(ui("err-reward-not-number"))
        }
        if (objective != null && objective.target().isBlank()) {
            return ServiceResult.fail(ui("err-objective-empty"))
        }
        val cleanTitle = plugin.text().stripControl(title)
        if (cleanTitle.isBlank()) {
            return ServiceResult.fail(ui("err-title-empty"))
        }
        val maxTitleLength = plugin.config.getInt("limits.max-title-length", 80)
        if (cleanTitle.length > maxTitleLength) {
            return ServiceResult.fail(ui("err-title-too-long", mapOf("max" to maxTitleLength.toString())))
        }
        var cleanDescription = plugin.text().stripControl(description)
        if (cleanDescription.isBlank()) {
            cleanDescription = cleanTitle
        }
        val maxDescriptionLength = plugin.config.getInt("limits.max-description-length", 500)
        if (cleanDescription.length > maxDescriptionLength) {
            return ServiceResult.fail(ui("err-description-too-long", mapOf("max" to maxDescriptionLength.toString())))
        }
        val reward = BigDecimal.valueOf(rewardDouble).setScale(2, RoundingMode.HALF_UP)
        val minReward = BigDecimal.valueOf(plugin.config.getDouble("economy.min-reward", 100.0))
        val maxReward = BigDecimal.valueOf(plugin.config.getDouble("economy.max-reward", 100000.0))
        if (!itemReward && (reward < minReward || reward > maxReward)) {
            return ServiceResult.fail(ui("err-reward-range", mapOf("min" to economy.format(minReward), "max" to economy.format(maxReward))))
        }
        if (itemReward && rewardItems.isEmpty()) {
            return ServiceResult.fail(ui("err-item-reward-empty"))
        }
        val maxBatchContracts = plugin.config.getInt("limits.max-batch-contracts", 64).coerceAtLeast(1)
        if (contractCount < 1 || contractCount > maxBatchContracts) {
            return ServiceResult.fail(ui("err-count-range", mapOf("max" to maxBatchContracts.toString())))
        }
        if (requiresBatchPermission(contractCount) && !owner.hasPermission(BATCH_CREATE_PERMISSION)) {
            return ServiceResult.fail(ui("err-no-batch-permission"))
        }
        val effectiveRepeatPolicy = if (contractCount > 1) repeatPolicy else BatchRepeatPolicy.UNLIMITED
        val maxRepeatCooldownHours = plugin.config.getInt("limits.max-repeat-cooldown-hours", 8760).coerceAtLeast(1)
        if (effectiveRepeatPolicy == BatchRepeatPolicy.COOLDOWN && repeatCooldownHours !in 1..maxRepeatCooldownHours) {
            return ServiceResult.fail(ui("err-cooldown-range", mapOf("max" to maxRepeatCooldownHours.toString())))
        }
        val minDays = plugin.config.getInt("limits.min-deadline-days", 1)
        val maxDays = plugin.config.getInt("limits.max-deadline-days", 7)
        if (days < minDays || days > maxDays) {
            return ServiceResult.fail(ui("err-days-range", mapOf("min" to minDays.toString(), "max" to maxDays.toString())))
        }
        val now = System.currentTimeMillis()
        val effectivePublishAt = publishAt?.takeIf { it > now }
        if (publishAt != null && effectivePublishAt == null) {
            return ServiceResult.fail(plugin.lang().ui("schedule-invalid"))
        }
        if (effectivePublishAt != null) {
            if (!owner.hasPermission(SCHEDULE_CREATE_PERMISSION)) return ServiceResult.fail(plugin.lang().ui("schedule-no-permission"))
            val maxAhead = plugin.config.getInt("scheduling.max-days-ahead", 30).coerceAtLeast(1) * SchedulingRules.DAY_MILLIS
            if (effectivePublishAt - now > maxAhead) return ServiceResult.fail(plugin.lang().ui("schedule-too-far", mapOf("days" to (maxAhead / SchedulingRules.DAY_MILLIS).toString())))
            val scheduledLimit = plugin.config.getInt("limits.max-scheduled-contracts", 64).coerceAtLeast(1)
            val scheduledCount = storage.all().count { it.ownerUuid() == owner.uniqueId && it.status() == ContractStatus.SCHEDULED }
            if (scheduledCount + contractCount > scheduledLimit && !owner.hasPermission("contract.bypass.limit")) {
                return ServiceResult.fail(plugin.lang().ui("schedule-limit", mapOf("limit" to scheduledLimit.toString())))
            }
        }
        val openLimit = plugin.config.getInt("limits.max-open-contracts", 3)
        val openCount = storage.all().stream()
            .filter { contract -> contract.ownerUuid() == owner.uniqueId }
            .filter { contract -> contract.status().countsAsOwnerActive() }
            .count()
        if (exceedsOpenLimit(openCount, contractCount, openLimit) && !owner.hasPermission("contract.bypass.limit")) {
            return ServiceResult.fail(ui("err-open-limit", mapOf("limit" to openLimit.toString())))
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
        val totalReward = reward.multiply(BigDecimal.valueOf(contractCount.toLong()))
        val totalCost = reward.add(creationFee).multiply(BigDecimal.valueOf(contractCount.toLong()))
        if (!economy.has(owner, totalCost)) {
            return ServiceResult.fail(ui("err-insufficient-funds", mapOf("value" to economy.format(totalCost))))
        }

        val contractIds = List(contractCount) { UUID.randomUUID().toString() }
        val pendingPurpose = when {
            effectivePublishAt != null -> "contract-schedule-create"
            contractCount == 1 -> "contract-create"
            else -> "contract-batch-create"
        }
        val pendingId = try {
            pending.beginWithdraw(owner.uniqueId, totalCost, pendingPurpose, contractIds.first())
        } catch (ex: IOException) {
            return ServiceResult.fail(ui("err-pending-log", mapOf("error" to (ex.message ?: ""))))
        }

        val withdrawal = economy.withdraw(owner, totalCost)
        if (!withdrawal.success()) {
            tryClearPending(pendingId)
            return ServiceResult.fail(ui("err-withdraw-failed", mapOf("reason" to withdrawal.reason())))
        }
        val handBefore = if (itemReward) owner.inventory.itemInMainHand.clone() else null
        if (itemReward) {
            owner.inventory.setItemInMainHand(ItemStack(Material.AIR))
            owner.updateInventory()
        }

        val expiresAt = SchedulingRules.expiryAt(now, effectivePublishAt, days)
        val commissionPercent = clampCommissionPercent(
            plugin.config.getDouble("economy.completion-commission-percent", 5.0),
        )
        val batchId = if (contractCount > 1) UUID.randomUUID().toString() else null
        val contracts = contractIds.mapIndexed { index, contractId ->
            val objectiveCopy = objective?.let { ContractObjective.of(it.type(), it.target(), it.required()) }
            val contract = Contract.createScheduledService(
                contractId,
                owner.uniqueId,
                owner.name,
                cleanTitle,
                cleanDescription,
                reward,
                rewardItems.map { it.clone() },
                creationFee,
                commissionPercent,
                now,
                expiresAt,
                objectiveCopy,
                effectivePublishAt,
            )
            applyOptionalMediator(contract, mediator)
            if (batchId != null) {
                contract.metadata["batch-id"] = batchId
                contract.metadata["batch-index"] = (index + 1).toString()
                contract.metadata["batch-size"] = contractCount.toString()
                contract.metadata["repeat-policy"] = effectiveRepeatPolicy.name
                if (effectiveRepeatPolicy == BatchRepeatPolicy.COOLDOWN) {
                    contract.metadata["repeat-cooldown-hours"] = repeatCooldownHours.toString()
                }
            }
            contract
        }

        try {
            contracts.forEach(storage::put)
            storage.save()
        } catch (ex: IOException) {
            contracts.forEach { storage.remove(it.id()) }
            if (handBefore != null) {
                owner.inventory.setItemInMainHand(handBefore)
                owner.updateInventory()
            }
            refundOrKeepPending(owner.uniqueId, totalCost, pendingId)
            return ServiceResult.fail(ui("err-save-refunded", mapOf("error" to (ex.message ?: ""))))
        }

        val escrowDetail = if (itemReward) "${rewardItems.sumOf { it.amount }} reward items" else reward.toPlainString()
        for (contract in contracts) {
            eventLog.append(
                contract.id(),
                if (effectivePublishAt == null) "CREATED" else "SCHEDULED",
                "${owner.name} ${if (effectivePublishAt == null) "created" else "scheduled"} the contract and escrowed $escrowDetail" +
                    if (batchId == null) "" else " as batch $batchId",
            )
            if (mediator.present()) {
                eventLog.append(contract.id(), "MEDIATOR_ASSIGNED", "${mediator.name()} assigned as optional mediator")
            }
        }
        tryClearPending(pendingId)
        return ServiceResult.ok(contracts.first(), totalReward)
    }

    private fun resolveOptionalMediator(mediatorName: String?, vararg excluded: UUID): MediatorSpec {
        if (mediatorName.isNullOrBlank()) {
            return MediatorSpec.none()
        }
        val mediator = Bukkit.getOfflinePlayer(mediatorName)
        val mediatorUuid = mediator.uniqueId
        for (blocked in excluded) {
            if (mediatorUuid == blocked) {
                return MediatorSpec.fail(ui("err-mediator-is-party"))
            }
        }
        if (!mediator.isOnline && !mediator.hasPlayedBefore()) {
            return MediatorSpec.fail(ui("err-mediator-not-found", mapOf("name" to mediatorName)))
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
            plugin.log().warn("Failed to clear pending transaction $pendingId: ${ex.message}")
        }
    }

    @Synchronized
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
            plugin.log().warn("Cannot recover pending withdraw ${entry.id()} without player uuid.")
            return
        }
        val contractId = entry.contractId()
        if (entry.purpose() == "contract-deliver-money" && !contractId.isNullOrBlank()) {
            val contract = storage.findById(contractId).orElse(null)
            if (contract != null && contract.participant(ParticipantRole.CONTRACTOR).map { it.moneyStake().signum() > 0 }.orElse(false)) {
                plugin.log().warn(
                    "Pending withdraw ${entry.id()} (${entry.purpose()}) already became contractor escrow for contract $contractId; clearing without refund.",
                )
                tryClearPending(entry.id())
                return
            }
        }
        val refund = if (contractId == null || contractId.isBlank()) {
            true
        } else if (entry.purpose() == "contract-deliver-money") {
            true
        } else {
            val status = storage.findById(contractId).map { contract -> contract.status() }.orElse(null)
            shouldRefundOrphanWithdraw(entry.purpose(), status)
        }
        if (!refund) {
            plugin.log().warn(
                "Pending withdraw ${entry.id()} (${entry.purpose()}) already became escrow for contract $contractId; clearing without refund.",
            )
            tryClearPending(entry.id())
            return
        }
        val result = economy.deposit(playerUuid, entry.amount())
        if (!result.success()) {
            plugin.log().warn("Failed to recover pending withdraw ${entry.id()}: ${result.reason()}")
            return
        }
        plugin.log().warn(
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
        plugin.log().severe(
            "Refund of $amount to $playerUuid failed (${refund.reason()}); keeping pending transaction $pendingId for crash recovery.",
        )
    }

    private fun recoverInterruptedSettlement(entry: PendingTransactionStore.PendingEntry) {
        val contractId = entry.contractId()
        if (contractId == null || contractId.isBlank()) {
            plugin.log().warn("Pending ${entry.type()} ${entry.id()} has no contract id; leaving it for manual review.")
            return
        }
        val contract = storage.findById(contractId).orElse(null)
        if (contract == null) {
            plugin.log().warn(
                "Pending ${entry.type()} ${entry.id()} references missing contract $contractId; clearing stale entry.",
            )
            tryClearPending(entry.id())
            return
        }
        if (contract.status().isFinal()) {
            plugin.log().warn("Clearing stale pending ${entry.type()} ${entry.id()} for finalized contract ${contract.shortId()}")
            tryClearPending(entry.id())
            return
        }

        val now = System.currentTimeMillis()
        contract.status(ContractStatus.DISPUTED)
        contract.disputeReason(ui("dispute-settlement-interrupted", mapOf("id" to entry.id())))
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
            plugin.log().warn("Failed to persist interrupted settlement recovery for ${contract.shortId()}: ${ex.message}")
        }
    }

    @Synchronized
    fun accept(contractor: Player, contract: Contract): ServiceResult =
        when (contract.type()) {
            ContractType.WAGER -> acceptWager(contractor, contract)
            ContractType.PARTNERSHIP -> acceptPartnership(contractor, contract)
            else -> acceptService(contractor, contract)
        }

    /** Selects and accepts exactly one currently available child while holding the service lock. */
    @Synchronized
    fun acceptOneFromBatch(contractor: Player, batchId: String): ServiceResult {
        val candidate = BatchQueryService.nextAvailable(storage.all(), batchId)
            ?: return ServiceResult.fail(plugin.lang().ui("batch-empty"))
        return acceptService(contractor, candidate)
    }

    private fun acceptService(contractor: Player, contract: Contract): ServiceResult {
        val now = System.currentTimeMillis()
        if (contract.status() != ContractStatus.OPEN) {
            return ServiceResult.fail(ui("err-not-acceptable"))
        }
        if (contract.isExpired(now)) {
            return rejectExpiredAcceptance(contract)
        }
        if (contract.ownerUuid() == contractor.uniqueId) {
            return ServiceResult.fail(ui("err-accept-own"))
        }
        if (isAssignedArbiter(contractor, contract)) {
            return ServiceResult.fail(ui("err-mediator-accept"))
        }
        val repeatFailure = checkBatchRepeat(contractor, contract, now)
        if (repeatFailure != null) {
            return ServiceResult.fail(repeatFailure)
        }
        val limit = plugin.config.getInt("limits.max-active-accepted-contracts", 3)
        val activeAccepted = storage.all().stream()
            .filter { existing -> contractor.uniqueId == existing.contractorUuid() }
            .filter { existing -> existing.status().countsAsContractorActive() }
            .count()
        if (activeAccepted >= limit && !contractor.hasPermission("contract.bypass.limit")) {
            return ServiceResult.fail(ui("err-accept-limit", mapOf("limit" to limit.toString())))
        }
        contract.contractorUuid(contractor.uniqueId)
        contract.contractorName(contractor.name)
        contract.acceptedAt(now)
        contract.status(ContractStatus.IN_PROGRESS)
        val batchId = contract.metadata["batch-id"]
        val repeatPolicy = BatchRepeatPolicy.fromStored(contract.metadata["repeat-policy"])
        if (!batchId.isNullOrBlank() && repeatPolicy != BatchRepeatPolicy.UNLIMITED) {
            batchAcceptances.record(batchId, contractor.uniqueId, now, contract.id())
        }
        logEvent(contract, now, "ACCEPTED", "${contractor.name} accepted the contract")
        return dirty(contract)
    }

    private fun checkBatchRepeat(contractor: Player, contract: Contract, now: Long): String? {
        val batchId = contract.metadata["batch-id"] ?: return null
        val policy = BatchRepeatPolicy.fromStored(contract.metadata["repeat-policy"])
        if (policy == BatchRepeatPolicy.UNLIMITED || contractor.hasPermission(BATCH_REPEAT_BYPASS_PERMISSION)) {
            return null
        }
        val hasActiveContract = storage.all().any { existing ->
            existing.id() != contract.id() &&
                existing.metadata["batch-id"] == batchId &&
                existing.contractorUuid() == contractor.uniqueId &&
                existing.status().countsAsContractorActive()
        }
        val cooldownHours = contract.metadata["repeat-cooldown-hours"]?.toIntOrNull()
            ?.coerceAtLeast(1) ?: DEFAULT_REPEAT_COOLDOWN_HOURS
        val decision = BatchRepeatRules.evaluate(
            policy,
            hasActiveContract,
            batchAcceptances.lastAcceptedAt(batchId, contractor.uniqueId),
            now,
            cooldownHours * 60L * 60L * 1000L,
        )
        return when (decision.reason) {
            BatchRepeatRules.BlockReason.ACTIVE_CONTRACT -> ui("err-batch-active")
            BatchRepeatRules.BlockReason.ALREADY_ACCEPTED -> ui("err-batch-once")
            BatchRepeatRules.BlockReason.COOLDOWN -> ui("err-batch-cooldown", mapOf("time" to formatRemainingTime(decision.remainingMillis)))
            null -> null
        }
    }

    private fun formatRemainingTime(remainingMillis: Long): String {
        val totalMinutes = ((remainingMillis + 59_999L) / 60_000L).coerceAtLeast(1L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours <= 0L -> ui("duration-minutes", mapOf("minutes" to minutes.toString()))
            minutes == 0L -> ui("duration-hours", mapOf("hours" to hours.toString()))
            else -> ui("duration-hours-minutes", mapOf("hours" to hours.toString(), "minutes" to minutes.toString()))
        }
    }

    private fun acceptWager(player: Player, contract: Contract): ServiceResult {
        if (contract.status() != ContractStatus.PENDING_ACCEPT) {
            return ServiceResult.fail(ui("err-wager-not-acceptable"))
        }
        if (contract.isExpired(System.currentTimeMillis())) {
            return rejectExpiredAcceptance(contract)
        }
        val partyB = contract.participant(ParticipantRole.PARTY_B).orElse(null)
        if (partyB == null || partyB.uuid() == null || partyB.uuid() != player.uniqueId) {
            return ServiceResult.fail(ui("err-wager-wrong-player"))
        }
        val stake = partyB.moneyStake()
        if (!economy.has(player, stake)) {
            return ServiceResult.fail(ui("err-insufficient-funds", mapOf("value" to economy.format(stake))))
        }
        val pendingId = try {
            pending.beginWithdraw(player.uniqueId, stake, "wager-accept", contract.id())
        } catch (ex: IOException) {
            return ServiceResult.fail(ui("err-pending-log", mapOf("error" to (ex.message ?: ""))))
        }
        val withdrawal = economy.withdraw(player, stake)
        if (!withdrawal.success()) {
            tryClearPending(pendingId)
            return ServiceResult.fail(ui("err-withdraw-failed", mapOf("reason" to withdrawal.reason())))
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
            return ServiceResult.fail(ui("err-partner-not-acceptable"))
        }
        if (contract.isExpired(System.currentTimeMillis())) {
            return rejectExpiredAcceptance(contract)
        }
        val partyB = contract.participant(ParticipantRole.PARTY_B).orElse(null)
        if (partyB == null || partyB.uuid() == null || partyB.uuid() != player.uniqueId) {
            return ServiceResult.fail(ui("err-partner-wrong-player"))
        }
        val stake = partyB.moneyStake()
        if (!economy.has(player, stake)) {
            return ServiceResult.fail(ui("err-insufficient-funds", mapOf("value" to economy.format(stake))))
        }
        val pendingId = try {
            pending.beginWithdraw(player.uniqueId, stake, "partnership-accept", contract.id())
        } catch (ex: IOException) {
            return ServiceResult.fail(ui("err-pending-log", mapOf("error" to (ex.message ?: ""))))
        }
        val withdrawal = economy.withdraw(player, stake)
        if (!withdrawal.success()) {
            tryClearPending(pendingId)
            return ServiceResult.fail(ui("err-withdraw-failed", mapOf("reason" to withdrawal.reason())))
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

    @Synchronized
    fun createPartnership(
        creator: Player,
        partnerName: String,
        stakeA: BigDecimal,
        stakeB: BigDecimal,
        days: Int,
        title: String,
        description: String,
    ): ServiceResult = createPartnership(creator, partnerName, stakeA, stakeB, days, title, description, null)

    @Synchronized
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
        val cleanTitle = plugin.text().stripControl(title)
        if (cleanTitle.isBlank()) {
            return ServiceResult.fail(ui("err-title-empty"))
        }
        val maxTitleLength = plugin.config.getInt("limits.max-title-length", 80)
        if (cleanTitle.length > maxTitleLength) {
            return ServiceResult.fail(ui("err-title-too-long", mapOf("max" to maxTitleLength.toString())))
        }
        var cleanDescription = plugin.text().stripControl(description)
        if (cleanDescription.isBlank()) {
            cleanDescription = cleanTitle
        }
        val minStake = BigDecimal.valueOf(plugin.config.getDouble("economy.min-reward", 100.0))
        val maxStake = BigDecimal.valueOf(plugin.config.getDouble("economy.max-reward", 100000.0))
        val normA = stakeA.setScale(2, RoundingMode.HALF_UP)
        val normB = stakeB.setScale(2, RoundingMode.HALF_UP)
        if (normA < minStake || normA > maxStake || normB < minStake || normB > maxStake) {
            return ServiceResult.fail(ui("err-stakes-range", mapOf("min" to economy.format(minStake), "max" to economy.format(maxStake))))
        }
        val minDays = plugin.config.getInt("limits.min-deadline-days", 1)
        val maxDays = plugin.config.getInt("limits.max-deadline-days", 7)
        if (days < minDays || days > maxDays) {
            return ServiceResult.fail(ui("err-days-range", mapOf("min" to minDays.toString(), "max" to maxDays.toString())))
        }

        val partner = Bukkit.getOfflinePlayer(partnerName)
        if (partner.uniqueId == creator.uniqueId) {
            return ServiceResult.fail(ui("err-partner-self"))
        }
        if (!partner.isOnline && !partner.hasPlayedBefore()) {
            return ServiceResult.fail(ui("err-player-not-found", mapOf("name" to partnerName)))
        }
        val mediator = resolveOptionalMediator(mediatorName, creator.uniqueId, partner.uniqueId)
        if (!mediator.success()) {
            return ServiceResult.fail(mediator.error())
        }

        if (!economy.has(creator, normA)) {
            return ServiceResult.fail(ui("err-insufficient-funds", mapOf("value" to economy.format(normA))))
        }

        val contractId = UUID.randomUUID().toString()
        val pendingId = try {
            pending.beginWithdraw(creator.uniqueId, normA, "partnership-create", contractId)
        } catch (ex: IOException) {
            return ServiceResult.fail(ui("err-pending-log", mapOf("error" to (ex.message ?: ""))))
        }
        val withdrawal = economy.withdraw(creator, normA)
        if (!withdrawal.success()) {
            tryClearPending(pendingId)
            return ServiceResult.fail(ui("err-withdraw-failed", mapOf("reason" to withdrawal.reason())))
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
            return ServiceResult.fail(ui("err-save-refunded", mapOf("error" to (ex.message ?: ""))))
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
            return ServiceResult.fail(ui("err-partner-not-in-progress"))
        }
        val me = contract.participantByUuid(player.uniqueId).orElse(null)
        if (me == null || me.role() != ParticipantRole.PARTY_A && me.role() != ParticipantRole.PARTY_B) {
            return ServiceResult.fail(ui("err-partner-only"))
        }
        val approved = contract.metadata.getOrDefault("approved-roles", "")
        val set = LinkedHashSet<String>()
        if (approved.isNotEmpty()) {
            for (s in approved.split(",")) {
                set.add(s)
            }
        }
        if (!set.add(me.role().name)) {
            return ServiceResult.fail(ui("err-already-approved"))
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

    @Synchronized
    fun createWager(
        creator: Player,
        opponentName: String,
        stake: BigDecimal,
        days: Int,
        arbiterName: String,
        title: String,
        description: String,
    ): ServiceResult {
        val cleanTitle = plugin.text().stripControl(title)
        if (cleanTitle.isBlank()) {
            return ServiceResult.fail(ui("err-title-empty"))
        }
        val maxTitleLength = plugin.config.getInt("limits.max-title-length", 80)
        if (cleanTitle.length > maxTitleLength) {
            return ServiceResult.fail(ui("err-title-too-long", mapOf("max" to maxTitleLength.toString())))
        }
        var cleanDescription = plugin.text().stripControl(description)
        if (cleanDescription.isBlank()) {
            cleanDescription = cleanTitle
        }

        val minStake = BigDecimal.valueOf(plugin.config.getDouble("economy.min-reward", 100.0))
        val maxStake = BigDecimal.valueOf(plugin.config.getDouble("economy.max-reward", 100000.0))
        val normalizedStake = stake.setScale(2, RoundingMode.HALF_UP)
        if (normalizedStake < minStake || normalizedStake > maxStake) {
            return ServiceResult.fail(ui("err-stake-range", mapOf("min" to economy.format(minStake), "max" to economy.format(maxStake))))
        }

        val minDays = plugin.config.getInt("limits.min-deadline-days", 1)
        val maxDays = plugin.config.getInt("limits.max-deadline-days", 7)
        if (days < minDays || days > maxDays) {
            return ServiceResult.fail(ui("err-days-range", mapOf("min" to minDays.toString(), "max" to maxDays.toString())))
        }

        val opponent = Bukkit.getOfflinePlayer(opponentName)
        val arbiter = Bukkit.getOfflinePlayer(arbiterName)
        if (opponent.uniqueId == creator.uniqueId) {
            return ServiceResult.fail(ui("err-wager-self"))
        }
        if (arbiter.uniqueId == creator.uniqueId || arbiter.uniqueId == opponent.uniqueId) {
            return ServiceResult.fail(ui("err-arbiter-third-party"))
        }
        if (!opponent.isOnline && !opponent.hasPlayedBefore()) {
            return ServiceResult.fail(ui("err-player-not-found", mapOf("name" to opponentName)))
        }
        if (!arbiter.isOnline && !arbiter.hasPlayedBefore()) {
            return ServiceResult.fail(ui("err-player-not-found", mapOf("name" to arbiterName)))
        }

        if (!economy.has(creator, normalizedStake)) {
            return ServiceResult.fail(ui("err-insufficient-funds", mapOf("value" to economy.format(normalizedStake))))
        }

        val contractId = UUID.randomUUID().toString()
        val pendingId = try {
            pending.beginWithdraw(creator.uniqueId, normalizedStake, "wager-create", contractId)
        } catch (ex: IOException) {
            return ServiceResult.fail(ui("err-pending-log", mapOf("error" to (ex.message ?: ""))))
        }
        val withdrawal = economy.withdraw(creator, normalizedStake)
        if (!withdrawal.success()) {
            tryClearPending(pendingId)
            return ServiceResult.fail(ui("err-withdraw-failed", mapOf("reason" to withdrawal.reason())))
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
            return ServiceResult.fail(ui("err-save-refunded", mapOf("error" to (ex.message ?: ""))))
        }
        eventLog.append(contract.id(), "CREATED", "${creator.name} opened wager vs $opponentName, stake ${normalizedStake.toPlainString()}")
        tryClearPending(pendingId)
        return ServiceResult.ok(contract, normalizedStake)
    }

    @Synchronized
    fun resolveWager(arbiter: Player, contract: Contract, winner: String): ServiceResult {
        if (contract.type() != ContractType.WAGER) {
            return ServiceResult.fail(ui("err-not-wager"))
        }
        if (contract.status() != ContractStatus.IN_PROGRESS && contract.status() != ContractStatus.SUBMITTED) {
            return ServiceResult.fail(ui("err-not-resolvable"))
        }
        val arbiterParticipant = contract.arbiter()
        if (arbiterParticipant == null || arbiterParticipant.uuid() == null || arbiterParticipant.uuid() != arbiter.uniqueId) {
            return ServiceResult.fail(ui("err-arbiter-only"))
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
            return ServiceResult.fail(ui("err-resolve-arg"))
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

    @Synchronized
    fun acceptMediation(mediator: Player, contract: Contract): ServiceResult {
        if (!isAssignedArbiter(mediator, contract)) {
            return ServiceResult.fail(ui("err-mediator-only-accept"))
        }
        if (contract.arbiterAccepted()) {
            return ServiceResult.fail(ui("err-mediator-already-accepted"))
        }
        contract.arbiterAccepted(true)
        val now = System.currentTimeMillis()
        logEvent(contract, now, "MEDIATOR_ACCEPTED", "${mediator.name} accepted mediator duties")
        return dirty(contract)
    }

    @Synchronized
    fun mediate(mediator: Player, contract: Contract, decision: String): ServiceResult {
        if (!isAssignedArbiter(mediator, contract)) {
            return ServiceResult.fail(ui("err-mediator-only"))
        }
        if (decision.equals("accept", ignoreCase = true)) {
            return acceptMediation(mediator, contract)
        }
        if (!contract.arbiterAccepted()) {
            return ServiceResult.fail(ui("err-mediator-accept-first", mapOf("id" to contract.shortId())))
        }
        if (contract.status().isFinal()) {
            return ServiceResult.fail(ui("err-final-not-mediatable"))
        }
        if (contract.status() == ContractStatus.OPEN || contract.status() == ContractStatus.PENDING_ACCEPT) {
            return ServiceResult.fail(ui("err-not-active-mediate"))
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
            else -> ServiceResult.fail(ui("err-mediate-arg"))
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

    @Synchronized
    fun submit(player: Player, contract: Contract): ServiceResult {
        if (contract.type() != ContractType.SERVICE) {
            return ServiceResult.fail(ui("err-submit-unsupported"))
        }
        if (contract.status() != ContractStatus.IN_PROGRESS) {
            return ServiceResult.fail(ui("err-submit-not-in-progress"))
        }
        if (player.uniqueId != contract.contractorUuid()) {
            return ServiceResult.fail(ui("err-submit-contractor-only"))
        }
        if (contract.systemVerifiedService()) {
            val objective = contract.objective() ?: return ServiceResult.fail(ui("err-objective-missing"))
            if (objective.type() != ObjectiveType.DELIVER_ITEM) {
                if (objective.type() == ObjectiveType.DELIVER_MONEY) {
                    return submitMoneyObjective(player, contract, objective)
                }
                return ServiceResult.fail(ui("err-objective-auto"))
            }
            return submitDeliveryObjective(player, contract, objective)
        }
        val now = System.currentTimeMillis()
        contract.status(ContractStatus.SUBMITTED)
        contract.submittedAt(now)
        logEvent(contract, now, "SUBMITTED", "${player.name} submitted the contract")
        return dirty(contract)
    }

    private fun submitDeliveryObjective(player: Player, contract: Contract, objective: ContractObjective): ServiceResult {
        val material = Material.matchMaterial(objective.target())
            ?: return ServiceResult.fail(ui("err-deliver-target-invalid", mapOf("target" to objective.target())))
        val remaining = objective.remaining()
        if (remaining <= 0) {
            return completeObjective(contract, player.name, "delivery already satisfied")
        }
        val inventoryBefore = cloneStorageContents(player)
        val storedBefore = contract.deliveryItems()
        val progressBefore = objective.progress()
        val collection = collectDeliveryItems(player, material, remaining)
        if (collection.items.isEmpty()) {
            if (collection.ruleGemBlocked > 0) {
                return ServiceResult.fail(ui("err-rulegems-deliver"))
            }
            return ServiceResult.fail(ui("err-items-missing", mapOf("remaining" to remaining.toString(), "material" to material.name, "have" to "0")))
        }
        if (collection.amount < remaining) {
            return ServiceResult.fail(ui("err-items-missing", mapOf("remaining" to remaining.toString(), "material" to material.name, "have" to collection.amount.toString())))
        }
        contract.addDeliveryItems(collection.items)
        objective.addProgress(remaining)
        logEvent(contract, System.currentTimeMillis(), "OBJECTIVE_PROGRESS", "${player.name} delivered $remaining ${material.name} into contract storage")
        try {
            storage.save()
        } catch (ex: IOException) {
            player.inventory.storageContents = inventoryBefore
            player.updateInventory()
            contract.deliveryItems(storedBefore)
            objective.progress(progressBefore)
            return ServiceResult.fail(ui("err-deliver-save-failed", mapOf("error" to (ex.message ?: ""))))
        }
        return completeObjective(contract, player.name, "delivered ${objective.required()} ${material.name}")
    }

    private fun submitMoneyObjective(player: Player, contract: Contract, objective: ContractObjective): ServiceResult {
        val amount = BigDecimal.valueOf(objective.remaining().toLong()).setScale(2, RoundingMode.HALF_UP)
        if (amount.signum() <= 0) {
            return completeObjective(contract, player.name, "money delivery already satisfied")
        }
        if (!economy.has(player, amount)) {
            return ServiceResult.fail(ui("err-deliver-money-short", mapOf("value" to economy.format(amount))))
        }
        val contractor = contract.participant(ParticipantRole.CONTRACTOR).orElse(null)
            ?: return ServiceResult.fail(ui("err-no-contractor"))
        val stakeBefore = contractor.stake()
        val progressBefore = objective.progress()
        val pendingId = try {
            pending.beginWithdraw(player.uniqueId, amount, "contract-deliver-money", contract.id())
        } catch (ex: IOException) {
            return ServiceResult.fail(ui("err-money-pending-log", mapOf("error" to (ex.message ?: ""))))
        }
        val withdrawal = economy.withdraw(player, amount)
        if (!withdrawal.success()) {
            tryClearPending(pendingId)
            return ServiceResult.fail(ui("err-withdraw-failed", mapOf("reason" to withdrawal.reason())))
        }
        contractor.addStake(Asset.money(amount))
        objective.addProgress(objective.remaining())
        try {
            storage.save()
            tryClearPending(pendingId)
        } catch (ex: IOException) {
            contractor.stake(stakeBefore)
            objective.progress(progressBefore)
            refundOrKeepPending(player.uniqueId, amount, pendingId)
            return ServiceResult.fail(ui("err-money-save-failed", mapOf("error" to (ex.message ?: ""))))
        }
        logEvent(contract, System.currentTimeMillis(), "OBJECTIVE_PROGRESS", "${player.name} delivered ${amount.toPlainString()} money into contract escrow")
        return completeObjective(contract, player.name, "delivered ${amount.toPlainString()} money")
    }

    @Synchronized
    fun claimDeliveryItems(player: Player, contract: Contract): ServiceResult {
        if (contract.type() != ContractType.SERVICE) {
            return ServiceResult.fail(ui("err-claim-unsupported"))
        }
        if (contract.hasDeliveryItems() && player.uniqueId == contract.ownerUuid()) {
            if (contract.status() != ContractStatus.COMPLETED) {
                return ServiceResult.fail(ui("err-claim-after-completion"))
            }
            return claimStoredItems(player, contract, contract.deliveryItems(), StoredItemKind.DELIVERY) {
                contract.clearDeliveryItems()
            }
        }
        if (contract.hasRewardItems()) {
            val canClaimCompletedReward = contract.status() == ContractStatus.COMPLETED && player.uniqueId == contract.contractorUuid()
            val canReclaimClosedReward =
                (contract.status() == ContractStatus.CANCELLED || contract.status() == ContractStatus.EXPIRED) &&
                    player.uniqueId == contract.ownerUuid()
            if (canClaimCompletedReward || canReclaimClosedReward) {
                return claimStoredItems(player, contract, contract.rewardItems(), StoredItemKind.REWARD) {
                    contract.clearRewardItems()
                }
            }
        }
        return ServiceResult.fail(ui("err-claim-nothing"))
    }

    /** Which stored-item pool a claim is draining. Kept typed so restore-on-failure never keys off display text. */
    private enum class StoredItemKind(val labelKey: String) {
        DELIVERY("stored-delivery-items"),
        REWARD("stored-reward-items"),
    }

    private fun claimStoredItems(player: Player, contract: Contract, items: List<ItemStack>, kind: StoredItemKind, clear: () -> Unit): ServiceResult {
        val label = ui(kind.labelKey)
        if (items.isEmpty()) {
            return ServiceResult.fail(ui("claim-none-of-kind", mapOf("kind" to label)))
        }
        val inventoryBefore = cloneStorageContents(player)
        val leftovers = player.inventory.addItem(*items.map { it.clone() }.toTypedArray())
        if (leftovers.isNotEmpty()) {
            player.inventory.storageContents = inventoryBefore
            player.updateInventory()
            return ServiceResult.fail(ui("claim-inventory-full"))
        }
        clear()
        logEvent(contract, System.currentTimeMillis(), "ITEMS_CLAIMED", "${player.name} claimed ${items.sumOf { it.amount }} stored ${kind.name.lowercase(Locale.ROOT)} items")
        return try {
            storage.save()
            player.updateInventory()
            ServiceResult.ok(contract)
        } catch (ex: IOException) {
            player.inventory.storageContents = inventoryBefore
            player.updateInventory()
            when (kind) {
                StoredItemKind.DELIVERY -> contract.deliveryItems(items)
                StoredItemKind.REWARD -> contract.rewardItems(items)
            }
            ServiceResult.fail(ui("claim-save-failed", mapOf("error" to (ex.message ?: ""))))
        }
    }

    @Synchronized
    fun recordObjectiveProgress(
        player: Player,
        type: ObjectiveType,
        target: String,
        amount: Int,
    ): List<ObjectiveProgressUpdate> {
        if (amount <= 0) {
            return emptyList()
        }
        val updates = ArrayList<ObjectiveProgressUpdate>()
        for (contract in storage.all()) {
            if (contract.status() != ContractStatus.IN_PROGRESS || !contract.systemVerifiedService()) {
                continue
            }
            if (contract.contractorUuid() != player.uniqueId) {
                continue
            }
            val objective = contract.objective() ?: continue
            if (objective.type() != type || !objective.matches(target)) {
                continue
            }
            val added = objective.addProgress(amount)
            if (added <= 0) {
                continue
            }
            val now = System.currentTimeMillis()
            logEvent(
                contract,
                now,
                "OBJECTIVE_PROGRESS",
                "${player.name} advanced ${type.name} $target by $added (${objective.progressText()})",
            )
            if (objective.complete()) {
                val result = completeObjective(contract, player.name, "${type.name} $target reached ${objective.required()}")
                updates.add(ObjectiveProgressUpdate(contract, added, true, result))
            } else {
                storage.markDirty()
                updates.add(ObjectiveProgressUpdate(contract, added, false, ServiceResult.ok(contract)))
            }
        }
        return updates
    }

    private fun completeObjective(contract: Contract, actorName: String, detail: String): ServiceResult {
        if (contract.status() != ContractStatus.IN_PROGRESS) {
            return ServiceResult.fail(ui("err-auto-settle-blocked"))
        }
        contract.submittedAt(System.currentTimeMillis())
        return pay(contract, "SYSTEM_OBJECTIVE_COMPLETED", "$actorName completed objective: $detail")
    }

    @Synchronized
    fun approve(player: Player, contract: Contract): ServiceResult {
        if (contract.type() == ContractType.PARTNERSHIP) {
            return approvePartnership(player, contract)
        }
        if (contract.type() != ContractType.SERVICE) {
            return ServiceResult.fail(ui("err-approve-unsupported"))
        }
        // Owner may confirm payment as soon as the work is done — either after the contractor submits
        // (SUBMITTED) or proactively while still IN_PROGRESS (early acceptance). The owner only ever
        // gives away their own escrow to the contractor they chose, so this harms no third party.
        if (contract.status() != ContractStatus.SUBMITTED && contract.status() != ContractStatus.IN_PROGRESS) {
            return ServiceResult.fail(ui("err-approve-status"))
        }
        if (player.uniqueId != contract.ownerUuid()) {
            return ServiceResult.fail(ui("err-approve-owner-only"))
        }
        val early = contract.status() == ContractStatus.IN_PROGRESS
        return pay(
            contract,
            if (early) "APPROVED_EARLY" else "APPROVED",
            if (early) "${player.name} approved early before submission" else "${player.name} approved the contract",
        )
    }

    @Synchronized
    fun cancel(player: Player, contract: Contract): ServiceResult {
        val wasScheduled = contract.status() == ContractStatus.SCHEDULED
        val result = cancelInternal(player, contract)
        // Only count a real cancellation against the canceller; an escalation to dispute is not one.
        if (!wasScheduled && result.success() && result.contract()?.status() == ContractStatus.CANCELLED) {
            plugin.reputation().recordCancelled(player.uniqueId, player.name)
        }
        return result
    }

    private fun cancelInternal(player: Player, contract: Contract): ServiceResult {
        val playerUuid = player.uniqueId
        val isOwner = playerUuid == contract.ownerUuid()
        val isContractor = playerUuid == contract.contractorUuid()
        if (!isOwner && !isContractor) {
            return ServiceResult.fail(ui("err-cancel-participants-only"))
        }
        val now = System.currentTimeMillis()
        if (contract.status() == ContractStatus.SCHEDULED && isOwner) {
            return refund(contract, ContractStatus.CANCELLED, "SCHEDULE_CANCELLED", "${player.name} cancelled the scheduled contract", contract.creationFee())
        }
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
            contract.disputeReason(ui("dispute-cancel-request"))
            logEvent(contract, now, "DISPUTED", "${player.name} requested cancellation during active work")
            return dirty(contract)
        }
        return ServiceResult.fail(ui("err-cancel-status"))
    }

    @Synchronized
    fun dispute(player: Player, contract: Contract, reason: String): ServiceResult {
        val playerUuid = player.uniqueId
        val isOwner = playerUuid == contract.ownerUuid()
        val isContractor = playerUuid == contract.contractorUuid()
        if (!isOwner && !isContractor) {
            return ServiceResult.fail(ui("err-dispute-participants-only"))
        }
        if (isOwner && !plugin.config.getBoolean("disputes.allow-owner-dispute", true)) {
            return ServiceResult.fail(ui("err-dispute-owner-blocked"))
        }
        if (isContractor && !plugin.config.getBoolean("disputes.allow-contractor-dispute", true)) {
            return ServiceResult.fail(ui("err-dispute-contractor-blocked"))
        }
        if (contract.status() == ContractStatus.DISPUTED) {
            return ServiceResult.fail(ui("err-already-disputed"))
        }
        if (!canInitiatePlayerDispute(contract.status())) {
            return ServiceResult.fail(ui("err-dispute-final"))
        }
        val now = System.currentTimeMillis()
        // Remember who raised it and the state to return to, so the initiator can withdraw it later.
        contract.metadata["dispute-by"] = playerUuid.toString()
        contract.metadata["dispute-prev-status"] = contract.status().name
        contract.status(ContractStatus.DISPUTED)
        contract.disputeReason(plugin.text().stripControl(reason))
        logEvent(contract, now, "DISPUTED", "${player.name}: ${contract.disputeReason()}")
        plugin.reputation().recordDisputed(playerUuid, player.name)
        return dirty(contract)
    }

    /**
     * Lets the player who raised a dispute withdraw it, restoring the contract to its pre-dispute
     * state. Only player-initiated disputes carry the `dispute-by`/`dispute-prev-status` markers;
     * system settlement-interruption holds do not, so those still require an admin.
     */
    @Synchronized
    fun withdrawDispute(player: Player, contract: Contract): ServiceResult {
        if (contract.status() != ContractStatus.DISPUTED) {
            return ServiceResult.fail(ui("err-withdraw-not-disputed"))
        }
        val raisedBy = contract.metadata["dispute-by"]
        val previousName = contract.metadata["dispute-prev-status"]
        if (raisedBy == null || previousName == null) {
            return ServiceResult.fail(ui("err-withdraw-admin-only"))
        }
        if (raisedBy != player.uniqueId.toString()) {
            return ServiceResult.fail(ui("err-withdraw-initiator-only"))
        }
        val previous = try {
            ContractStatus.valueOf(previousName)
        } catch (ex: IllegalArgumentException) {
            return ServiceResult.fail(ui("err-withdraw-no-state"))
        }
        if (!isRestorableDisputeStatus(previous)) {
            return ServiceResult.fail(ui("err-withdraw-bad-state"))
        }
        val now = System.currentTimeMillis()
        contract.status(previous)
        contract.disputeReason(null)
        contract.metadata.remove("dispute-by")
        contract.metadata.remove("dispute-prev-status")
        plugin.reputation().recordDisputeWithdrawn(player.uniqueId, player.name)
        logEvent(contract, now, "DISPUTE_WITHDRAWN", "${player.name} withdrew the dispute")
        return dirty(contract)
    }

    @Synchronized
    fun adminPay(contract: Contract, adminName: String): ServiceResult {
        if (contract.contractorUuid() == null) {
            return ServiceResult.fail(ui("err-pay-no-contractor"))
        }
        if (contract.status().isFinal()) {
            return ServiceResult.fail(ui("err-contract-final"))
        }
        if (contract.payoutsFor(PayoutCondition.SUCCESS).isEmpty()) {
            return ServiceResult.fail(ui("err-no-success-rule"))
        }
        return pay(contract, "ADMIN_PAID", "$adminName forced payment")
    }

    @Synchronized
    fun adminRefund(contract: Contract, adminName: String): ServiceResult {
        if (contract.status().isFinal()) {
            return ServiceResult.fail(ui("err-contract-final"))
        }
        return refund(contract, ContractStatus.CANCELLED, "ADMIN_REFUNDED", "$adminName forced refund")
    }

    @Synchronized
    fun adminClose(contract: Contract, adminName: String): ServiceResult {
        if (contract.status().isFinal()) {
            return ServiceResult.fail(ui("err-contract-final"))
        }
        val now = System.currentTimeMillis()
        contract.status(ContractStatus.CANCELLED)
        contract.completedAt(now)
        logEvent(contract, now, "ADMIN_CLOSED", "$adminName closed the contract without moving funds")
        return saveSync(contract, BigDecimal.ZERO)
    }

    @Synchronized
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
        val retainedBatchIds = storage.all().mapNotNull { it.metadata["batch-id"] }.toSet()
        batchAcceptances.retainBatches(retainedBatchIds)
        return changed
    }

    /** Idempotently reveals due contracts; existing child IDs and escrow stay unchanged. */
    @Synchronized
    fun activateScheduled(now: Long = System.currentTimeMillis()): Int {
        var activated = 0
        for (contract in storage.all()) {
            val publishAt = contract.publishAt()
            if (SchedulingRules.shouldActivate(contract.status(), publishAt, now)) {
                contract.status(ContractStatus.OPEN)
                logEvent(contract, now, "PUBLISHED", "scheduled contract published")
                activated++
            }
        }
        if (activated > 0) storage.markDirty()
        return activated
    }

    private fun purgeRetiredContracts(now: Long): Int {
        val completedDays = plugin.config.getInt("retention.completed-contract-days", 90)
        val closedDays = plugin.config.getInt("retention.closed-contract-days", 30)
        var removed = 0
        for (contract in storage.all()) {
            if (contract.hasStoredItems()) {
                continue
            }
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
                plugin.log().warn("Failed to persist retired contract purge: ${ex.message}")
            }
        }
        return removed
    }

    private fun rejectExpiredAcceptance(contract: Contract): ServiceResult {
        val result = expireAwaitingAcceptance(contract)
        if (!result.success()) {
            return ServiceResult.fail(ui("err-expiry-failed", mapOf("reason" to result.reason())))
        }
        return ServiceResult.fail(ui("err-expired"))
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
            else -> ServiceResult.fail(ui("err-expiry-not-applicable"))
        }

    @Synchronized
    fun openContracts(): List<Contract> = storage.openContracts()

    @Synchronized
    fun allContracts(): List<Contract> = storage.all()

    @Synchronized
    @Throws(IOException::class)
    fun flushStores() {
        storage.flushIfDirty()
        plugin.reputation().flushIfDirty()
        batchAcceptances.flushIfDirty()
        plugin.templates().flushIfDirty()
    }

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
            return ServiceResult.fail(ui("err-refund-bonus-failed", mapOf("reason" to bonus.reason())))
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
            return ServiceResult.fail(ui("err-no-settlement-rule", mapOf("purpose" to purpose)))
        }
        val settlementId = try {
            pending.beginSettlement(contract.id(), "$purpose:$eventType")
        } catch (ex: IOException) {
            return ServiceResult.fail(ui("err-settlement-log", mapOf("error" to (ex.message ?: ""))))
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
            return ServiceResult.fail(ui("err-settlement-save", mapOf("id" to settlementId, "error" to (ex.message ?: ""))))
        }
    }

    private fun interruptSettlement(contract: Contract, settlementId: String, reason: String): ServiceResult {
        val now = System.currentTimeMillis()
        contract.status(ContractStatus.DISPUTED)
        contract.disputeReason(ui("dispute-settlement-interrupted", mapOf("id" to settlementId)))
        logEvent(contract, now, "SETTLEMENT_INTERRUPTED", "$reason; pending $settlementId")
        try {
            storage.save()
            tryClearPending(settlementId)
        } catch (ex: IOException) {
            return ServiceResult.fail(ui("err-settlement-recovery", mapOf("error" to (ex.message ?: ""))))
        }
        return ServiceResult.fail(ui("err-settlement-disputed", mapOf("reason" to reason)))
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
                        outcome.error = ui("err-recipient-role-missing", mapOf("role" to recipientRole.name))
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
                        outcome.error = ui("err-no-arbiter")
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
            return EconomyService.TransactionResult.fail(ui("err-payout-log", mapOf("error" to (ex.message ?: ""))))
        }
        val deposit = economy.deposit(playerUuid, amount)
        if (!deposit.success()) {
            tryClearPending(pendingId)
            return deposit
        }
        tryClearPending(pendingId)
        return deposit
    }

    private fun collectDeliveryItems(player: Player, material: Material, amount: Int): DeliveryCollection {
        var remaining = amount
        var ruleGemBlocked = 0
        val contents = cloneStorageContents(player)
        val collected = ArrayList<ItemStack>()
        for (index in contents.indices) {
            val item = contents[index] ?: continue
            if (item.type != material) {
                continue
            }
            if (isRuleGemItem(item)) {
                ruleGemBlocked += item.amount
                continue
            }
            val take = minOf(item.amount, remaining)
            val stored = item.clone()
            stored.amount = take
            collected.add(stored)
            item.amount = item.amount - take
            if (item.amount <= 0) {
                contents[index] = null
            }
            remaining -= take
            if (remaining <= 0) {
                break
            }
        }
        if (remaining > 0) {
            return DeliveryCollection(collected, collected.sumOf { it.amount }, ruleGemBlocked)
        }
        player.inventory.storageContents = contents
        player.updateInventory()
        return DeliveryCollection(collected, amount, ruleGemBlocked)
    }

    private fun cloneStorageContents(player: Player): Array<ItemStack?> =
        player.inventory.storageContents.map { it?.clone() }.toTypedArray()

    private fun isRuleGemItem(item: ItemStack): Boolean {
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(RULEGEMS_MARKER_KEY, PersistentDataType.BYTE)
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

    private class DeliveryCollection(
        val items: List<ItemStack>,
        val amount: Int,
        val ruleGemBlocked: Int,
    )

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
            ServiceResult.fail(ui("err-save-failed", mapOf("error" to (ex.message ?: ""))))
        }

    companion object {
        private val RULEGEMS_MARKER_KEY = NamespacedKey("rulegems", "rule_gem")

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
        fun exceedsOpenLimit(openCount: Long, contractCount: Int, openLimit: Int): Boolean =
            contractCount > 0 && openCount + contractCount.toLong() > openLimit.toLong()

        @JvmStatic
        fun perContractItemAmount(totalAmount: Int, contractCount: Int): Int? {
            if (totalAmount <= 0 || contractCount <= 0 || totalAmount % contractCount != 0) {
                return null
            }
            return totalAmount / contractCount
        }

        @JvmStatic
        fun canInitiatePlayerDispute(status: ContractStatus): Boolean =
            status != ContractStatus.DISPUTED && !status.isFinal()

        @JvmStatic
        fun isRestorableDisputeStatus(status: ContractStatus): Boolean = canInitiatePlayerDispute(status)

        @JvmStatic
        fun requiresBatchPermission(contractCount: Int): Boolean = contractCount > 1

        private const val BATCH_CREATE_PERMISSION = "contract.create.batch"
        private const val SCHEDULE_CREATE_PERMISSION = "contract.schedule.create"
        private const val BATCH_REPEAT_BYPASS_PERMISSION = "contract.bypass.batch-repeat-limit"
        private const val DEFAULT_REPEAT_COOLDOWN_HOURS = 24

        private fun defaultRepeatPolicy(contractCount: Int): BatchRepeatPolicy =
            if (contractCount > 1) BatchRepeatPolicy.ONCE else BatchRepeatPolicy.UNLIMITED

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
