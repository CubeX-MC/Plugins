package org.cubexmc.contract.service;

import org.bukkit.entity.Player;
import org.cubexmc.contract.ContractPlugin;
import org.cubexmc.contract.economy.EconomyService;
import org.cubexmc.contract.model.Contract;
import org.cubexmc.contract.model.ContractStatus;
import org.cubexmc.contract.model.ContractType;
import org.cubexmc.contract.model.Participant;
import org.cubexmc.contract.model.ParticipantRole;
import org.cubexmc.contract.model.PayoutCondition;
import org.cubexmc.contract.model.PayoutRecipient;
import org.cubexmc.contract.model.PayoutRule;
import org.cubexmc.contract.storage.ContractStorage;
import org.cubexmc.contract.storage.EventLog;
import org.cubexmc.contract.storage.PendingTransactionStore;
import org.cubexmc.contract.util.Text;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class ContractService {
    private final ContractPlugin plugin;
    private final ContractStorage storage;
    private final EconomyService economy;
    private final PendingTransactionStore pending;
    private final EventLog eventLog;

    public ContractService(ContractPlugin plugin, ContractStorage storage, EconomyService economy,
                           PendingTransactionStore pending, EventLog eventLog) {
        this.plugin = plugin;
        this.storage = storage;
        this.economy = economy;
        this.pending = pending;
        this.eventLog = eventLog;
    }

    private void logEvent(Contract contract, long time, String type, String detail) {
        contract.addEvent(time, type, detail);
        eventLog.append(contract.id(), type, detail);
    }

    public ServiceResult create(Player owner, double rewardDouble, int hours, String title, String description) {
        return create(owner, rewardDouble, hours, title, description, null);
    }

    public ServiceResult create(Player owner, double rewardDouble, int hours, String title, String description,
                                String mediatorName) {
        String cleanTitle = Text.stripControl(title);
        if (cleanTitle.isBlank()) {
            return ServiceResult.fail("标题不能为空");
        }
        int maxTitleLength = plugin.getConfig().getInt("limits.max-title-length", 80);
        if (cleanTitle.length() > maxTitleLength) {
            return ServiceResult.fail("标题不能超过 " + maxTitleLength + " 个字符");
        }
        String cleanDescription = Text.stripControl(description);
        if (cleanDescription.isBlank()) {
            cleanDescription = cleanTitle;
        }
        int maxDescriptionLength = plugin.getConfig().getInt("limits.max-description-length", 500);
        if (cleanDescription.length() > maxDescriptionLength) {
            return ServiceResult.fail("描述不能超过 " + maxDescriptionLength + " 个字符");
        }
        BigDecimal reward = BigDecimal.valueOf(rewardDouble).setScale(2, RoundingMode.HALF_UP);
        BigDecimal minReward = BigDecimal.valueOf(plugin.getConfig().getDouble("economy.min-reward", 100.0));
        BigDecimal maxReward = BigDecimal.valueOf(plugin.getConfig().getDouble("economy.max-reward", 100000.0));
        if (reward.compareTo(minReward) < 0 || reward.compareTo(maxReward) > 0) {
            return ServiceResult.fail("奖金必须在 " + economy.format(minReward) + " 到 " + economy.format(maxReward) + " 之间");
        }
        int minHours = plugin.getConfig().getInt("limits.min-deadline-hours", 1);
        int maxHours = plugin.getConfig().getInt("limits.max-deadline-hours", 168);
        if (hours < minHours || hours > maxHours) {
            return ServiceResult.fail("截止时间必须在 " + minHours + " 到 " + maxHours + " 小时之间");
        }
        int openLimit = plugin.getConfig().getInt("limits.max-open-contracts", 3);
        long openCount = storage.all().stream()
            .filter(contract -> contract.ownerUuid().equals(owner.getUniqueId()))
            .filter(contract -> contract.status().countsAsOwnerActive())
            .count();
        if (openCount >= openLimit && !owner.hasPermission("contract.bypass.limit")) {
            return ServiceResult.fail("你的公开或待处理合同数量已达上限 " + openLimit);
        }
        MediatorSpec mediator = resolveOptionalMediator(mediatorName, owner.getUniqueId());
        if (!mediator.success()) {
            return ServiceResult.fail(mediator.error());
        }

        BigDecimal creationFee = owner.hasPermission("contract.bypass.fee")
            ? BigDecimal.ZERO
            : BigDecimal.valueOf(plugin.getConfig().getDouble("economy.creation-fee", 20.0)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalCost = reward.add(creationFee);
        if (!economy.has(owner, totalCost)) {
            return ServiceResult.fail("余额不足，需要 " + economy.format(totalCost));
        }

        String pendingId;
        try {
            pendingId = pending.beginWithdraw(owner.getUniqueId(), totalCost, "contract-create");
        } catch (IOException ex) {
            return ServiceResult.fail("无法写入待办事务日志: " + ex.getMessage());
        }

        EconomyService.TransactionResult withdrawal = economy.withdraw(owner, totalCost);
        if (!withdrawal.success()) {
            tryClearPending(pendingId);
            return ServiceResult.fail("扣款失败: " + withdrawal.reason());
        }

        long now = System.currentTimeMillis();
        long expiresAt = now + hours * 60L * 60L * 1000L;
        BigDecimal commissionPercent = clampCommissionPercent(
            plugin.getConfig().getDouble("economy.completion-commission-percent", 5.0));
        Contract contract = Contract.createService(
            owner.getUniqueId(),
            owner.getName(),
            cleanTitle,
            cleanDescription,
            reward,
            creationFee,
            commissionPercent,
            now,
            expiresAt
        );
        applyOptionalMediator(contract, mediator);

        try {
            storage.put(contract);
            storage.save();
        } catch (IOException ex) {
            storage.remove(contract.id());
            economy.deposit(owner.getUniqueId(), totalCost);
            tryClearPending(pendingId);
            return ServiceResult.fail("保存失败，已退回扣款: " + ex.getMessage());
        }

        eventLog.append(contract.id(), "CREATED",
            owner.getName() + " created the contract and escrowed " + reward.toPlainString());
        if (mediator.present()) {
            eventLog.append(contract.id(), "MEDIATOR_ASSIGNED",
                mediator.name() + " assigned as optional mediator");
        }
        tryClearPending(pendingId);
        return ServiceResult.ok(contract, reward);
    }

    private MediatorSpec resolveOptionalMediator(String mediatorName, UUID... excluded) {
        if (mediatorName == null || mediatorName.isBlank()) {
            return MediatorSpec.none();
        }
        org.bukkit.OfflinePlayer mediator = org.bukkit.Bukkit.getOfflinePlayer(mediatorName);
        UUID mediatorUuid = mediator.getUniqueId();
        for (UUID blocked : excluded) {
            if (mediatorUuid.equals(blocked)) {
                return MediatorSpec.fail("中间人不能是合同参与方");
            }
        }
        if (!mediator.hasPlayedBefore() && mediator.getName() == null) {
            return MediatorSpec.fail("找不到中间人 " + mediatorName);
        }
        return MediatorSpec.ok(mediatorUuid, mediator.getName() != null ? mediator.getName() : mediatorName);
    }

    private void applyOptionalMediator(Contract contract, MediatorSpec mediator) {
        if (!mediator.present()) {
            return;
        }
        contract.arbiter(new Participant(ParticipantRole.MEDIATOR, mediator.uuid(), mediator.name(), List.of()));
        contract.arbiterAccepted(false);
        contract.addEvent(System.currentTimeMillis(), "MEDIATOR_ASSIGNED",
            mediator.name() + " assigned as optional mediator");
    }

    private BigDecimal clampCommissionPercent(double percent) {
        BigDecimal value = BigDecimal.valueOf(percent).setScale(2, RoundingMode.HALF_UP);
        if (value.signum() < 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal hundred = new BigDecimal("100");
        if (value.compareTo(hundred) > 0) {
            return hundred;
        }
        return value;
    }

    private void tryClearPending(String pendingId) {
        try {
            pending.clear(pendingId);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to clear pending transaction " + pendingId + ": " + ex.getMessage());
        }
    }

    public void recoverPendingTransactions() {
        for (PendingTransactionStore.PendingEntry entry : pending.loadAll()) {
            switch (entry.type()) {
                case WITHDRAW -> recoverWithdraw(entry);
                case DEPOSIT, SETTLEMENT -> recoverInterruptedSettlement(entry);
            }
        }
    }

    private void recoverWithdraw(PendingTransactionStore.PendingEntry entry) {
        if (entry.playerUuid() == null) {
            plugin.getLogger().warning("Cannot recover pending withdraw " + entry.id() + " without player uuid.");
            return;
        }
        EconomyService.TransactionResult refund = economy.deposit(entry.playerUuid(), entry.amount());
        if (!refund.success()) {
            plugin.getLogger().warning("Failed to recover pending withdraw " + entry.id() + ": " + refund.reason());
            return;
        }
        plugin.getLogger().warning("Recovered orphan pending withdraw " + entry.id()
            + " (" + entry.purpose() + ") refunded " + entry.amount() + " to " + entry.playerUuid());
        tryClearPending(entry.id());
    }

    private void recoverInterruptedSettlement(PendingTransactionStore.PendingEntry entry) {
        if (entry.contractId() == null || entry.contractId().isBlank()) {
            plugin.getLogger().warning("Pending " + entry.type() + " " + entry.id()
                + " has no contract id; leaving it for manual review.");
            return;
        }
        Contract contract = storage.findById(entry.contractId()).orElse(null);
        if (contract == null) {
            plugin.getLogger().warning("Pending " + entry.type() + " " + entry.id()
                + " references missing contract " + entry.contractId() + "; clearing stale entry.");
            tryClearPending(entry.id());
            return;
        }
        if (contract.status().isFinal()) {
            plugin.getLogger().warning("Clearing stale pending " + entry.type() + " " + entry.id()
                + " for finalized contract " + contract.shortId());
            tryClearPending(entry.id());
            return;
        }

        long now = System.currentTimeMillis();
        contract.status(ContractStatus.DISPUTED);
        contract.disputeReason("结算中断，需要管理员核对 pending transaction " + entry.id());
        logEvent(contract, now, "SETTLEMENT_RECOVERY_REQUIRED",
            "pending " + entry.type() + " " + entry.id()
                + " purpose=" + entry.purpose()
                + " player=" + entry.playerUuid()
                + " amount=" + entry.amount()
                + " payout=" + entry.payoutKey());
        try {
            storage.save();
            tryClearPending(entry.id());
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to persist interrupted settlement recovery for "
                + contract.shortId() + ": " + ex.getMessage());
        }
    }

    public ServiceResult accept(Player contractor, Contract contract) {
        return switch (contract.type()) {
            case WAGER -> acceptWager(contractor, contract);
            case PARTNERSHIP -> acceptPartnership(contractor, contract);
            default -> acceptService(contractor, contract);
        };
    }

    private ServiceResult acceptService(Player contractor, Contract contract) {
        if (contract.status() != ContractStatus.OPEN) {
            return ServiceResult.fail("合同当前不可接取");
        }
        if (contract.ownerUuid().equals(contractor.getUniqueId())) {
            return ServiceResult.fail("不能接取自己发布的合同");
        }
        if (isAssignedArbiter(contractor, contract)) {
            return ServiceResult.fail("中间人不能接取自己负责裁决的合同");
        }
        int limit = plugin.getConfig().getInt("limits.max-active-accepted-contracts", 3);
        long activeAccepted = storage.all().stream()
            .filter(existing -> contractor.getUniqueId().equals(existing.contractorUuid()))
            .filter(existing -> existing.status().countsAsContractorActive())
            .count();
        if (activeAccepted >= limit && !contractor.hasPermission("contract.bypass.limit")) {
            return ServiceResult.fail("你的接单数量已达上限 " + limit);
        }
        long now = System.currentTimeMillis();
        contract.contractorUuid(contractor.getUniqueId());
        contract.contractorName(contractor.getName());
        contract.acceptedAt(now);
        contract.status(ContractStatus.IN_PROGRESS);
        logEvent(contract, now, "ACCEPTED", contractor.getName() + " accepted the contract");
        return dirty(contract);
    }

    private ServiceResult acceptWager(Player player, Contract contract) {
        if (contract.status() != ContractStatus.PENDING_ACCEPT) {
            return ServiceResult.fail("对赌合同当前不可接受");
        }
        Participant partyB = contract.participant(ParticipantRole.PARTY_B).orElse(null);
        if (partyB == null || partyB.uuid() == null
            || !partyB.uuid().equals(player.getUniqueId())) {
            return ServiceResult.fail("只有被指定的对方可以接受这个对赌");
        }
        BigDecimal stake = partyB.moneyStake();
        if (!economy.has(player, stake)) {
            return ServiceResult.fail("余额不足,需要 " + economy.format(stake));
        }

        String pendingId;
        try {
            pendingId = pending.beginWithdraw(player.getUniqueId(), stake, "wager-accept");
        } catch (IOException ex) {
            return ServiceResult.fail("无法写入待办事务日志: " + ex.getMessage());
        }

        EconomyService.TransactionResult withdrawal = economy.withdraw(player, stake);
        if (!withdrawal.success()) {
            tryClearPending(pendingId);
            return ServiceResult.fail("扣款失败: " + withdrawal.reason());
        }

        partyB.displayName(player.getName());
        long now = System.currentTimeMillis();
        contract.acceptedAt(now);
        contract.status(ContractStatus.IN_PROGRESS);
        logEvent(contract, now, "WAGER_ACCEPTED",
            player.getName() + " matched the wager with stake " + stake.toPlainString());

        ServiceResult result = saveSync(contract, stake);
        if (!result.success()) {
            economy.deposit(player.getUniqueId(), stake);
            tryClearPending(pendingId);
            return result;
        }
        tryClearPending(pendingId);
        return result;
    }

    private ServiceResult acceptPartnership(Player player, Contract contract) {
        if (contract.status() != ContractStatus.PENDING_ACCEPT) {
            return ServiceResult.fail("合作合同当前不可接受");
        }
        Participant partyB = contract.participant(ParticipantRole.PARTY_B).orElse(null);
        if (partyB == null || partyB.uuid() == null
            || !partyB.uuid().equals(player.getUniqueId())) {
            return ServiceResult.fail("只有被邀请的合作方可以接受");
        }
        BigDecimal stake = partyB.moneyStake();
        if (!economy.has(player, stake)) {
            return ServiceResult.fail("余额不足,需要 " + economy.format(stake));
        }

        String pendingId;
        try {
            pendingId = pending.beginWithdraw(player.getUniqueId(), stake, "partnership-accept");
        } catch (IOException ex) {
            return ServiceResult.fail("无法写入待办事务日志: " + ex.getMessage());
        }

        EconomyService.TransactionResult withdrawal = economy.withdraw(player, stake);
        if (!withdrawal.success()) {
            tryClearPending(pendingId);
            return ServiceResult.fail("扣款失败: " + withdrawal.reason());
        }

        partyB.displayName(player.getName());
        long now = System.currentTimeMillis();
        contract.acceptedAt(now);
        contract.status(ContractStatus.IN_PROGRESS);
        logEvent(contract, now, "PARTNERSHIP_ACCEPTED",
            player.getName() + " joined partnership with stake " + stake.toPlainString());

        ServiceResult result = saveSync(contract, stake);
        if (!result.success()) {
            economy.deposit(player.getUniqueId(), stake);
            tryClearPending(pendingId);
            return result;
        }
        tryClearPending(pendingId);
        return result;
    }

    public ServiceResult createPartnership(Player creator, String partnerName,
                                           BigDecimal stakeA, BigDecimal stakeB, int hours,
                                           String title, String description) {
        return createPartnership(creator, partnerName, stakeA, stakeB, hours, title, description, null);
    }

    public ServiceResult createPartnership(Player creator, String partnerName,
                                           BigDecimal stakeA, BigDecimal stakeB, int hours,
                                           String title, String description, String mediatorName) {
        String cleanTitle = Text.stripControl(title);
        if (cleanTitle.isBlank()) {
            return ServiceResult.fail("标题不能为空");
        }
        int maxTitleLength = plugin.getConfig().getInt("limits.max-title-length", 80);
        if (cleanTitle.length() > maxTitleLength) {
            return ServiceResult.fail("标题不能超过 " + maxTitleLength + " 个字符");
        }
        String cleanDescription = Text.stripControl(description);
        if (cleanDescription.isBlank()) {
            cleanDescription = cleanTitle;
        }
        BigDecimal minStake = BigDecimal.valueOf(plugin.getConfig().getDouble("economy.min-reward", 100.0));
        BigDecimal maxStake = BigDecimal.valueOf(plugin.getConfig().getDouble("economy.max-reward", 100000.0));
        BigDecimal normA = stakeA.setScale(2, RoundingMode.HALF_UP);
        BigDecimal normB = stakeB.setScale(2, RoundingMode.HALF_UP);
        if (normA.compareTo(minStake) < 0 || normA.compareTo(maxStake) > 0
            || normB.compareTo(minStake) < 0 || normB.compareTo(maxStake) > 0) {
            return ServiceResult.fail("双方押注都必须在 " + economy.format(minStake) + " 到 " + economy.format(maxStake) + " 之间");
        }
        int minHours = plugin.getConfig().getInt("limits.min-deadline-hours", 1);
        int maxHours = plugin.getConfig().getInt("limits.max-deadline-hours", 168);
        if (hours < minHours || hours > maxHours) {
            return ServiceResult.fail("截止时间必须在 " + minHours + " 到 " + maxHours + " 小时之间");
        }

        org.bukkit.OfflinePlayer partner = org.bukkit.Bukkit.getOfflinePlayer(partnerName);
        if (partner.getUniqueId().equals(creator.getUniqueId())) {
            return ServiceResult.fail("不能和自己合作");
        }
        if (!partner.hasPlayedBefore() && partner.getName() == null) {
            return ServiceResult.fail("找不到玩家 " + partnerName);
        }
        MediatorSpec mediator = resolveOptionalMediator(mediatorName, creator.getUniqueId(), partner.getUniqueId());
        if (!mediator.success()) {
            return ServiceResult.fail(mediator.error());
        }

        if (!economy.has(creator, normA)) {
            return ServiceResult.fail("余额不足,需要 " + economy.format(normA));
        }

        String pendingId;
        try {
            pendingId = pending.beginWithdraw(creator.getUniqueId(), normA, "partnership-create");
        } catch (IOException ex) {
            return ServiceResult.fail("无法写入待办事务日志: " + ex.getMessage());
        }

        EconomyService.TransactionResult withdrawal = economy.withdraw(creator, normA);
        if (!withdrawal.success()) {
            tryClearPending(pendingId);
            return ServiceResult.fail("扣款失败: " + withdrawal.reason());
        }

        long now = System.currentTimeMillis();
        long expiresAt = now + hours * 60L * 60L * 1000L;
        BigDecimal commissionPercent = clampCommissionPercent(
            plugin.getConfig().getDouble("economy.completion-commission-percent", 5.0));
        Contract contract = Contract.createPartnership(
            creator.getUniqueId(), creator.getName(),
            partner.getUniqueId(), partner.getName() != null ? partner.getName() : partnerName,
            normA, normB, commissionPercent,
            cleanTitle, cleanDescription,
            now, expiresAt
        );
        applyOptionalMediator(contract, mediator);

        try {
            storage.put(contract);
            storage.save();
        } catch (IOException ex) {
            storage.remove(contract.id());
            economy.deposit(creator.getUniqueId(), normA);
            tryClearPending(pendingId);
            return ServiceResult.fail("保存失败,已退回扣款: " + ex.getMessage());
        }

        eventLog.append(contract.id(), "CREATED",
            creator.getName() + " proposed partnership with " + partnerName
                + ", stakes " + normA.toPlainString() + "/" + normB.toPlainString());
        if (mediator.present()) {
            eventLog.append(contract.id(), "MEDIATOR_ASSIGNED",
                mediator.name() + " assigned as optional mediator");
        }
        tryClearPending(pendingId);
        return ServiceResult.ok(contract, normA);
    }

    private ServiceResult approvePartnership(Player player, Contract contract) {
        if (contract.status() != ContractStatus.IN_PROGRESS) {
            return ServiceResult.fail("只有进行中的合作合同可以确认");
        }
        Participant me = contract.participantByUuid(player.getUniqueId()).orElse(null);
        if (me == null || (me.role() != ParticipantRole.PARTY_A && me.role() != ParticipantRole.PARTY_B)) {
            return ServiceResult.fail("只有合作方可以确认");
        }
        String approved = contract.metadata.getOrDefault("approved-roles", "");
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        if (!approved.isEmpty()) {
            for (String s : approved.split(",")) {
                set.add(s);
            }
        }
        if (!set.add(me.role().name())) {
            return ServiceResult.fail("你已经确认过了,等待对方");
        }
        contract.metadata.put("approved-roles", String.join(",", set));
        long now = System.currentTimeMillis();
        logEvent(contract, now, "PARTNERSHIP_APPROVED",
            player.getName() + " (" + me.role() + ") approved partnership");

        if (set.size() >= 2) {
            return settle(contract, PayoutCondition.SUCCESS, ContractStatus.COMPLETED,
                "PARTNERSHIP_COMPLETED", "both partners approved");
        }
        return dirty(contract);
    }

    public ServiceResult createWager(Player creator, String opponentName, BigDecimal stake, int hours,
                                     String arbiterName, String title, String description) {
        String cleanTitle = Text.stripControl(title);
        if (cleanTitle.isBlank()) {
            return ServiceResult.fail("标题不能为空");
        }
        int maxTitleLength = plugin.getConfig().getInt("limits.max-title-length", 80);
        if (cleanTitle.length() > maxTitleLength) {
            return ServiceResult.fail("标题不能超过 " + maxTitleLength + " 个字符");
        }
        String cleanDescription = Text.stripControl(description);
        if (cleanDescription.isBlank()) {
            cleanDescription = cleanTitle;
        }

        BigDecimal minStake = BigDecimal.valueOf(plugin.getConfig().getDouble("economy.min-reward", 100.0));
        BigDecimal maxStake = BigDecimal.valueOf(plugin.getConfig().getDouble("economy.max-reward", 100000.0));
        BigDecimal normalizedStake = stake.setScale(2, RoundingMode.HALF_UP);
        if (normalizedStake.compareTo(minStake) < 0 || normalizedStake.compareTo(maxStake) > 0) {
            return ServiceResult.fail("押注必须在 " + economy.format(minStake) + " 到 " + economy.format(maxStake) + " 之间");
        }

        int minHours = plugin.getConfig().getInt("limits.min-deadline-hours", 1);
        int maxHours = plugin.getConfig().getInt("limits.max-deadline-hours", 168);
        if (hours < minHours || hours > maxHours) {
            return ServiceResult.fail("截止时间必须在 " + minHours + " 到 " + maxHours + " 小时之间");
        }

        org.bukkit.OfflinePlayer opponent = org.bukkit.Bukkit.getOfflinePlayer(opponentName);
        org.bukkit.OfflinePlayer arbiter = org.bukkit.Bukkit.getOfflinePlayer(arbiterName);
        if (opponent.getUniqueId().equals(creator.getUniqueId())) {
            return ServiceResult.fail("不能和自己对赌");
        }
        if (arbiter.getUniqueId().equals(creator.getUniqueId())
            || arbiter.getUniqueId().equals(opponent.getUniqueId())) {
            return ServiceResult.fail("仲裁者必须是第三方");
        }
        if (!opponent.hasPlayedBefore() && opponent.getName() == null) {
            return ServiceResult.fail("找不到玩家 " + opponentName);
        }
        if (!arbiter.hasPlayedBefore() && arbiter.getName() == null) {
            return ServiceResult.fail("找不到玩家 " + arbiterName);
        }

        if (!economy.has(creator, normalizedStake)) {
            return ServiceResult.fail("余额不足,需要 " + economy.format(normalizedStake));
        }

        String pendingId;
        try {
            pendingId = pending.beginWithdraw(creator.getUniqueId(), normalizedStake, "wager-create");
        } catch (IOException ex) {
            return ServiceResult.fail("无法写入待办事务日志: " + ex.getMessage());
        }

        EconomyService.TransactionResult withdrawal = economy.withdraw(creator, normalizedStake);
        if (!withdrawal.success()) {
            tryClearPending(pendingId);
            return ServiceResult.fail("扣款失败: " + withdrawal.reason());
        }

        long now = System.currentTimeMillis();
        long expiresAt = now + hours * 60L * 60L * 1000L;
        BigDecimal commissionPercent = clampCommissionPercent(
            plugin.getConfig().getDouble("economy.completion-commission-percent", 5.0));
        Contract contract = Contract.createWager(
            creator.getUniqueId(), creator.getName(),
            opponent.getUniqueId(), opponent.getName() != null ? opponent.getName() : opponentName,
            arbiter.getUniqueId(), arbiter.getName() != null ? arbiter.getName() : arbiterName,
            cleanTitle, cleanDescription,
            normalizedStake, commissionPercent,
            now, expiresAt
        );

        try {
            storage.put(contract);
            storage.save();
        } catch (IOException ex) {
            storage.remove(contract.id());
            economy.deposit(creator.getUniqueId(), normalizedStake);
            tryClearPending(pendingId);
            return ServiceResult.fail("保存失败,已退回扣款: " + ex.getMessage());
        }

        eventLog.append(contract.id(), "CREATED",
            creator.getName() + " opened wager vs " + opponentName + ", stake " + normalizedStake.toPlainString());
        tryClearPending(pendingId);
        return ServiceResult.ok(contract, normalizedStake);
    }

    public ServiceResult resolveWager(Player arbiter, Contract contract, String winner) {
        if (contract.type() != ContractType.WAGER) {
            return ServiceResult.fail("这不是对赌合同");
        }
        if (contract.status() != ContractStatus.IN_PROGRESS && contract.status() != ContractStatus.SUBMITTED) {
            return ServiceResult.fail("当前状态不可裁决");
        }
        Participant arbiterParticipant = contract.arbiter();
        if (arbiterParticipant == null || arbiterParticipant.uuid() == null
            || !arbiterParticipant.uuid().equals(arbiter.getUniqueId())) {
            return ServiceResult.fail("只有指定的仲裁者可以裁决");
        }

        PayoutCondition condition;
        ParticipantRole winnerRole;
        if (winner.equalsIgnoreCase("a")) {
            condition = PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER;
            winnerRole = ParticipantRole.PARTY_A;
        } else if (winner.equalsIgnoreCase("b")) {
            condition = PayoutCondition.DISPUTE_RESOLVED_FOR_CONTRACTOR;
            winnerRole = ParticipantRole.PARTY_B;
        } else {
            return ServiceResult.fail("裁决结果必须是 a 或 b");
        }
        String winnerName = contract.participant(winnerRole)
            .map(Participant::displayName).orElse(winner);
        return settle(contract, condition, ContractStatus.COMPLETED,
            "WAGER_RESOLVED",
            arbiter.getName() + " ruled in favor of " + winner.toUpperCase(Locale.ROOT) + " (" + winnerName + ")");
    }

    public ServiceResult acceptMediation(Player mediator, Contract contract) {
        if (!isAssignedArbiter(mediator, contract)) {
            return ServiceResult.fail("只有指定中间人可以接受此职责");
        }
        if (contract.arbiterAccepted()) {
            return ServiceResult.fail("中间人职责已经接受");
        }
        contract.arbiterAccepted(true);
        long now = System.currentTimeMillis();
        logEvent(contract, now, "MEDIATOR_ACCEPTED", mediator.getName() + " accepted mediator duties");
        return dirty(contract);
    }

    public ServiceResult mediate(Player mediator, Contract contract, String decision) {
        if (!isAssignedArbiter(mediator, contract)) {
            return ServiceResult.fail("只有指定中间人可以裁决这个合同");
        }
        if (decision.equalsIgnoreCase("accept")) {
            return acceptMediation(mediator, contract);
        }
        if (!contract.arbiterAccepted()) {
            return ServiceResult.fail("中间人需要先接受职责: /contract mediate " + contract.shortId() + " accept");
        }
        if (contract.status().isFinal()) {
            return ServiceResult.fail("已结束的合同不能裁决");
        }
        if (contract.status() == ContractStatus.OPEN || contract.status() == ContractStatus.PENDING_ACCEPT) {
            return ServiceResult.fail("合同尚未生效,不能由中间人裁决");
        }
        String normalized = decision.toLowerCase(Locale.ROOT);
        if (contract.type() == ContractType.WAGER && (normalized.equals("a") || normalized.equals("owner"))) {
            return resolveWager(mediator, contract, "a");
        }
        if (contract.type() == ContractType.WAGER && (normalized.equals("b") || normalized.equals("contractor"))) {
            return resolveWager(mediator, contract, "b");
        }

        return switch (normalized) {
            case "pay", "success" -> settle(contract, PayoutCondition.SUCCESS, ContractStatus.COMPLETED,
                "MEDIATOR_PAID", mediator.getName() + " mediated success");
            case "refund", "void", "failure" -> settle(contract, PayoutCondition.FAILURE, ContractStatus.CANCELLED,
                "MEDIATOR_REFUNDED", mediator.getName() + " mediated refund");
            case "owner", "a" -> mediateForSide(mediator, contract, PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER,
                "MEDIATOR_OWNER_WIN", "owner/party A");
            case "contractor", "b" -> mediateForSide(mediator, contract, PayoutCondition.DISPUTE_RESOLVED_FOR_CONTRACTOR,
                "MEDIATOR_CONTRACTOR_WIN", "contractor/party B");
            default -> ServiceResult.fail("裁决结果必须是 accept、pay、refund、owner/a 或 contractor/b");
        };
    }

    private ServiceResult mediateForSide(Player mediator, Contract contract, PayoutCondition condition,
                                         String eventType, String label) {
        if (contract.type() == ContractType.SERVICE) {
            if (condition == PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER) {
                return settle(contract, PayoutCondition.FAILURE, ContractStatus.CANCELLED,
                    "MEDIATOR_REFUNDED", mediator.getName() + " mediated for owner");
            }
            return settle(contract, PayoutCondition.SUCCESS, ContractStatus.COMPLETED,
                "MEDIATOR_PAID", mediator.getName() + " mediated for contractor");
        }
        return settle(contract, condition, ContractStatus.COMPLETED,
            eventType, mediator.getName() + " mediated for " + label);
    }

    private boolean isAssignedArbiter(Player player, Contract contract) {
        Participant arbiterParticipant = contract.arbiter();
        return arbiterParticipant != null
            && arbiterParticipant.uuid() != null
            && arbiterParticipant.uuid().equals(player.getUniqueId());
    }

    public ServiceResult submit(Player player, Contract contract) {
        if (contract.type() != ContractType.SERVICE) {
            return ServiceResult.fail("这个合同类型不使用提交完成流程");
        }
        if (contract.status() != ContractStatus.IN_PROGRESS) {
            return ServiceResult.fail("只有进行中的合同可以提交完成");
        }
        if (!player.getUniqueId().equals(contract.contractorUuid())) {
            return ServiceResult.fail("只有接单者可以提交完成");
        }
        long now = System.currentTimeMillis();
        contract.status(ContractStatus.SUBMITTED);
        contract.submittedAt(now);
        logEvent(contract, now, "SUBMITTED", player.getName() + " submitted the contract");
        return dirty(contract);
    }

    public ServiceResult approve(Player player, Contract contract) {
        if (contract.type() == ContractType.PARTNERSHIP) {
            return approvePartnership(player, contract);
        }
        if (contract.type() != ContractType.SERVICE) {
            return ServiceResult.fail("这个合同类型不能用 approve 确认");
        }
        if (contract.status() != ContractStatus.SUBMITTED) {
            return ServiceResult.fail("只有待确认的合同可以确认付款");
        }
        if (!player.getUniqueId().equals(contract.ownerUuid())) {
            return ServiceResult.fail("只有雇主可以确认这个合同");
        }
        return pay(contract, "APPROVED", player.getName() + " approved the contract");
    }

    public ServiceResult cancel(Player player, Contract contract) {
        UUID playerUuid = player.getUniqueId();
        boolean isOwner = playerUuid.equals(contract.ownerUuid());
        boolean isContractor = playerUuid.equals(contract.contractorUuid());
        if (!isOwner && !isContractor) {
            return ServiceResult.fail("只有合同相关玩家可以取消");
        }
        long now = System.currentTimeMillis();
        if (contract.status() == ContractStatus.OPEN && isOwner) {
            boolean refundFee = plugin.getConfig().getBoolean("economy.refund-creation-fee-on-cancel", false);
            BigDecimal extra = refundFee ? contract.creationFee() : BigDecimal.ZERO;
            return refund(contract, ContractStatus.CANCELLED, "CANCELLED",
                player.getName() + " cancelled the open contract", extra);
        }
        if (contract.status() == ContractStatus.PENDING_ACCEPT && isOwner) {
            return refundPendingAcceptance(contract, ContractStatus.CANCELLED, "CANCELLED_PENDING",
                player.getName() + " cancelled the pending invitation");
        }
        if (contract.status() == ContractStatus.IN_PROGRESS && isContractor) {
            return refund(contract, ContractStatus.CANCELLED, "CONTRACTOR_CANCELLED", player.getName() + " gave up the contract");
        }
        if (contract.status() == ContractStatus.IN_PROGRESS || contract.status() == ContractStatus.SUBMITTED) {
            contract.status(ContractStatus.DISPUTED);
            contract.disputeReason("取消请求需要管理员处理");
            logEvent(contract, now, "DISPUTED", player.getName() + " requested cancellation during active work");
            return dirty(contract);
        }
        return ServiceResult.fail("这个状态下不能取消合同");
    }

    public ServiceResult dispute(Player player, Contract contract, String reason) {
        UUID playerUuid = player.getUniqueId();
        boolean isOwner = playerUuid.equals(contract.ownerUuid());
        boolean isContractor = playerUuid.equals(contract.contractorUuid());
        if (!isOwner && !isContractor) {
            return ServiceResult.fail("只有合同相关玩家可以发起争议");
        }
        if (isOwner && !plugin.getConfig().getBoolean("disputes.allow-owner-dispute", true)) {
            return ServiceResult.fail("当前不允许雇主发起争议");
        }
        if (isContractor && !plugin.getConfig().getBoolean("disputes.allow-contractor-dispute", true)) {
            return ServiceResult.fail("当前不允许接单者发起争议");
        }
        if (contract.status().isFinal()) {
            return ServiceResult.fail("已结束的合同不能发起争议");
        }
        long now = System.currentTimeMillis();
        contract.status(ContractStatus.DISPUTED);
        contract.disputeReason(Text.stripControl(reason));
        logEvent(contract, now, "DISPUTED", player.getName() + ": " + contract.disputeReason());
        return dirty(contract);
    }

    public ServiceResult adminPay(Contract contract, String adminName) {
        if (contract.contractorUuid() == null) {
            return ServiceResult.fail("没有接单者，不能付款");
        }
        if (contract.status().isFinal()) {
            return ServiceResult.fail("合同已经结束");
        }
        if (contract.payoutsFor(PayoutCondition.SUCCESS).isEmpty()) {
            return ServiceResult.fail("这个合同类型没有默认成功付款规则，请使用对应裁决或退款流程");
        }
        return pay(contract, "ADMIN_PAID", adminName + " forced payment");
    }

    public ServiceResult adminRefund(Contract contract, String adminName) {
        if (contract.status().isFinal()) {
            return ServiceResult.fail("合同已经结束");
        }
        return refund(contract, ContractStatus.CANCELLED, "ADMIN_REFUNDED", adminName + " forced refund");
    }

    public ServiceResult adminClose(Contract contract, String adminName) {
        if (contract.status().isFinal()) {
            return ServiceResult.fail("合同已经结束");
        }
        long now = System.currentTimeMillis();
        contract.status(ContractStatus.CANCELLED);
        contract.completedAt(now);
        logEvent(contract, now, "ADMIN_CLOSED", adminName + " closed the contract without moving funds");
        return saveSync(contract, BigDecimal.ZERO);
    }

    public int cleanupExpired() {
        long now = System.currentTimeMillis();
        int changed = 0;
        int submittedAutoApproveHours = plugin.getConfig().getInt("expiry.submitted-auto-approve-hours", 72);
        for (Contract contract : storage.all()) {
            if (contract.status() == ContractStatus.SUBMITTED && submittedAutoApproveHours > 0 && contract.submittedAt() != null) {
                long autoApproveAt = contract.submittedAt() + submittedAutoApproveHours * 60L * 60L * 1000L;
                if (now >= autoApproveAt && pay(contract, "AUTO_APPROVED", "submitted contract auto-approved after timeout").success()) {
                    changed++;
                }
                continue;
            }
            if (contract.isExpired(now)) {
                ContractStatus current = contract.status();
                ServiceResult result;
                if (current == ContractStatus.PENDING_ACCEPT) {
                    result = refundPendingAcceptance(contract, ContractStatus.CANCELLED, "EXPIRED_PENDING",
                        "opponent did not accept in time");
                } else if (current == ContractStatus.OPEN || current == ContractStatus.IN_PROGRESS) {
                    result = refund(contract, ContractStatus.EXPIRED, "EXPIRED", "contract expired");
                } else {
                    result = markDisputed(contract, "contract expired while waiting for approval");
                }
                if (result.success()) {
                    changed++;
                }
            }
        }
        return changed;
    }

    public List<Contract> openContracts() {
        return storage.openContracts();
    }

    public List<Contract> allContracts() {
        return storage.all();
    }

    private ServiceResult pay(Contract contract, String eventType, String detail) {
        return settle(contract, PayoutCondition.SUCCESS, ContractStatus.COMPLETED, eventType, detail);
    }

    private ServiceResult refund(Contract contract, ContractStatus status, String eventType, String detail) {
        return refund(contract, status, eventType, detail, BigDecimal.ZERO);
    }

    private ServiceResult refund(Contract contract, ContractStatus status, String eventType, String detail,
                                 BigDecimal extra) {
        PayoutCondition condition = status == ContractStatus.EXPIRED
            ? PayoutCondition.TIMEOUT
            : PayoutCondition.FAILURE;
        ServiceResult base = settle(contract, condition, status, eventType, detail);
        if (!base.success() || extra == null || extra.signum() <= 0) {
            return base;
        }
        EconomyService.TransactionResult bonus = economy.deposit(contract.ownerUuid(), extra);
        if (!bonus.success()) {
            return ServiceResult.fail("退款附加失败: " + bonus.reason());
        }
        return ServiceResult.ok(base.contract(), base.amount().add(extra));
    }

    private ServiceResult refundPendingAcceptance(Contract contract, ContractStatus status, String eventType,
                                                  String detail) {
        ParticipantRole creatorRole = contract.type() == ContractType.SERVICE
            ? ParticipantRole.OWNER
            : ParticipantRole.PARTY_A;
        PayoutRule creatorRefund = new PayoutRule(PayoutCondition.FAILURE, creatorRole,
            PayoutRecipient.participant(creatorRole), new BigDecimal("100"));
        return settleWithRules(contract, List.of(creatorRefund), "PENDING_ACCEPT_REFUND",
            status, eventType, detail);
    }

    /**
     * Generic settlement entry point. Runs every PayoutRule matching `condition`,
     * updates contract status, logs event, and persists synchronously.
     */
    private ServiceResult settle(Contract contract, PayoutCondition condition, ContractStatus newStatus,
                                 String eventType, String detail) {
        return settleWithRules(contract, contract.payoutsFor(condition), condition.name(),
            newStatus, eventType, detail);
    }

    private ServiceResult settleWithRules(Contract contract, List<PayoutRule> rules, String purpose,
                                          ContractStatus newStatus, String eventType, String detail) {
        if (rules.isEmpty()) {
            return ServiceResult.fail("这个合同没有可用的结算规则: " + purpose);
        }
        String settlementId;
        try {
            settlementId = pending.beginSettlement(contract.id(), purpose + ":" + eventType);
        } catch (IOException ex) {
            return ServiceResult.fail("无法写入结算事务日志: " + ex.getMessage());
        }

        PayoutOutcome outcome = executePayouts(contract, rules, settlementId, purpose);
        if (!outcome.success) {
            if (outcome.externalEffects) {
                return interruptSettlement(contract, settlementId, outcome.error);
            }
            tryClearPending(settlementId);
            return ServiceResult.fail(outcome.error);
        }
        long now = System.currentTimeMillis();
        contract.status(newStatus);
        contract.completedAt(now);
        BigDecimal totalParticipantPayout = outcome.toRole.values().stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        logEvent(contract, now, eventType,
            detail + "; payouts " + outcome.toRole + "; sink " + outcome.toSink.toPlainString());
        try {
            storage.save();
            tryClearPending(settlementId);
            return ServiceResult.ok(contract, totalParticipantPayout);
        } catch (IOException ex) {
            return ServiceResult.fail("保存失败，结算可能已执行，已保留待恢复事务 "
                + settlementId + ": " + ex.getMessage());
        }
    }

    private ServiceResult interruptSettlement(Contract contract, String settlementId, String reason) {
        long now = System.currentTimeMillis();
        contract.status(ContractStatus.DISPUTED);
        contract.disputeReason("结算中断，需要管理员核对 pending transaction " + settlementId);
        logEvent(contract, now, "SETTLEMENT_INTERRUPTED", reason + "; pending " + settlementId);
        try {
            storage.save();
            tryClearPending(settlementId);
        } catch (IOException ex) {
            return ServiceResult.fail("结算中断且保存恢复状态失败: " + ex.getMessage());
        }
        return ServiceResult.fail("结算中断，合同已转入争议等待管理员核对: " + reason);
    }

    /**
     * Iterate all payouts rules matching the given condition and execute deposits.
     * Returns aggregated totals so callers can log and report.
     */
    private PayoutOutcome executePayouts(Contract contract, List<PayoutRule> rules,
                                         String settlementId, String purpose) {
        PayoutOutcome outcome = new PayoutOutcome();
        int index = 0;
        for (PayoutRule rule : rules) {
            index++;
            Participant source = contract.participant(rule.source()).orElse(null);
            if (source == null) {
                continue;
            }
            BigDecimal sourceAmount = source.moneyStake();
            BigDecimal share = rule.applyTo(sourceAmount);
            if (share.signum() <= 0) {
                continue;
            }
            PayoutRecipient recipient = rule.recipient();
            switch (recipient.kind()) {
                case PARTICIPANT -> {
                    Participant target = contract.participant(recipient.role()).orElse(null);
                    if (target == null || target.uuid() == null) {
                        outcome.success = false;
                        outcome.error = "找不到收款方角色 " + recipient.role();
                        return outcome;
                    }
                    EconomyService.TransactionResult deposit = depositWithPending(contract, target.uuid(), share,
                        purpose, settlementId, "rule-" + index + "-" + recipient.role());
                    if (!deposit.success()) {
                        outcome.success = false;
                        outcome.error = deposit.reason();
                        return outcome;
                    }
                    outcome.externalEffects = true;
                    outcome.toRole.merge(recipient.role(), share, BigDecimal::add);
                }
                case SYSTEM_SINK -> outcome.toSink = outcome.toSink.add(share);
                case ARBITER -> {
                    Participant arbiter = contract.arbiter();
                    if (arbiter == null || arbiter.uuid() == null) {
                        outcome.success = false;
                        outcome.error = "合同没有 arbiter";
                        return outcome;
                    }
                    EconomyService.TransactionResult deposit = depositWithPending(contract, arbiter.uuid(), share,
                        purpose, settlementId, "rule-" + index + "-ARBITER");
                    if (!deposit.success()) {
                        outcome.success = false;
                        outcome.error = deposit.reason();
                        return outcome;
                    }
                    outcome.externalEffects = true;
                    outcome.toArbiter = outcome.toArbiter.add(share);
                }
            }
        }
        return outcome;
    }

    private EconomyService.TransactionResult depositWithPending(Contract contract, UUID playerUuid, BigDecimal amount,
                                                                String purpose, String settlementId,
                                                                String payoutKey) {
        String pendingId;
        try {
            pendingId = pending.beginDeposit(playerUuid, amount, purpose, contract.id(), payoutKey, settlementId);
        } catch (IOException ex) {
            return EconomyService.TransactionResult.fail("无法写入 payout 待办事务日志: " + ex.getMessage());
        }
        EconomyService.TransactionResult deposit = economy.deposit(playerUuid, amount);
        if (!deposit.success()) {
            tryClearPending(pendingId);
            return deposit;
        }
        tryClearPending(pendingId);
        return deposit;
    }

    private record MediatorSpec(boolean success, boolean present, UUID uuid, String name, String error) {
        static MediatorSpec none() {
            return new MediatorSpec(true, false, null, null, "");
        }

        static MediatorSpec ok(UUID uuid, String name) {
            return new MediatorSpec(true, true, uuid, name, "");
        }

        static MediatorSpec fail(String error) {
            return new MediatorSpec(false, false, null, null, error);
        }
    }

    private static final class PayoutOutcome {
        boolean success = true;
        String error = "";
        java.util.Map<ParticipantRole, BigDecimal> toRole = new java.util.EnumMap<>(ParticipantRole.class);
        BigDecimal toSink = BigDecimal.ZERO;
        BigDecimal toArbiter = BigDecimal.ZERO;
        boolean externalEffects = false;
    }

    private ServiceResult markDisputed(Contract contract, String reason) {
        long now = System.currentTimeMillis();
        contract.status(ContractStatus.DISPUTED);
        contract.disputeReason(reason);
        logEvent(contract, now, "DISPUTED", reason);
        return dirty(contract);
    }

    private ServiceResult dirty(Contract contract) {
        storage.markDirty();
        return ServiceResult.ok(contract);
    }

    private ServiceResult saveSync(Contract contract, BigDecimal amount) {
        try {
            storage.save();
            return ServiceResult.ok(contract, amount);
        } catch (IOException ex) {
            return ServiceResult.fail("保存失败: " + ex.getMessage());
        }
    }
}
