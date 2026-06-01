package org.cubexmc.contract.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class Contract {
    private final String id;
    private final ContractType type;
    private final String title;
    private final String description;
    private final List<Participant> participants;
    private Participant arbiter;
    private final ResolutionRule resolutionRule;
    private final List<PayoutRule> payouts;
    private ContractStatus status;
    private final long createdAt;
    private Long acceptedAt;
    private Long submittedAt;
    private Long completedAt;
    private final long expiresAt;
    private String disputeReason;
    private final List<ContractEvent> events;

    public Contract(
        String id,
        ContractType type,
        String title,
        String description,
        List<Participant> participants,
        Participant arbiter,
        ResolutionRule resolutionRule,
        List<PayoutRule> payouts,
        ContractStatus status,
        long createdAt,
        Long acceptedAt,
        Long submittedAt,
        Long completedAt,
        long expiresAt,
        String disputeReason,
        List<ContractEvent> events
    ) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.description = description;
        this.participants = new ArrayList<>(participants);
        this.arbiter = arbiter;
        this.resolutionRule = resolutionRule;
        this.payouts = new ArrayList<>(payouts);
        this.status = status;
        this.createdAt = createdAt;
        this.acceptedAt = acceptedAt;
        this.submittedAt = submittedAt;
        this.completedAt = completedAt;
        this.expiresAt = expiresAt;
        this.disputeReason = disputeReason;
        this.events = new ArrayList<>(events);
    }

    /**
     * Factory for legacy single-employer SERVICE contracts. Keeps backward compatibility
     * with the /contract service flow while the model is now generic.
     */
    public static Contract createService(UUID ownerUuid, String ownerName, String title, String description,
                                         BigDecimal reward, BigDecimal creationFee, BigDecimal commissionPercent,
                                         long now, long expiresAt) {
        Participant owner = new Participant(ParticipantRole.OWNER, ownerUuid, ownerName,
            List.of(Asset.money(reward)));
        Participant contractor = new Participant(ParticipantRole.CONTRACTOR, null, null,
            List.of());
        List<PayoutRule> rules = new ArrayList<>();
        BigDecimal payoutShare = new BigDecimal("100").subtract(commissionPercent);
        rules.add(new PayoutRule(PayoutCondition.SUCCESS, ParticipantRole.OWNER,
            PayoutRecipient.participant(ParticipantRole.CONTRACTOR), payoutShare));
        rules.add(new PayoutRule(PayoutCondition.SUCCESS, ParticipantRole.OWNER,
            PayoutRecipient.systemSink(), commissionPercent));
        rules.add(new PayoutRule(PayoutCondition.FAILURE, ParticipantRole.OWNER,
            PayoutRecipient.participant(ParticipantRole.OWNER), new BigDecimal("100")));
        rules.add(new PayoutRule(PayoutCondition.TIMEOUT, ParticipantRole.OWNER,
            PayoutRecipient.participant(ParticipantRole.OWNER), new BigDecimal("100")));

        Contract contract = new Contract(
            UUID.randomUUID().toString(),
            ContractType.SERVICE,
            title,
            description,
            List.of(owner, contractor),
            null,
            ResolutionRule.OWNER_APPROVE,
            rules,
            ContractStatus.OPEN,
            now,
            null,
            null,
            null,
            expiresAt,
            null,
            List.of()
        );
        contract.metadata.put("creation-fee", creationFee.toPlainString());
        contract.metadata.put("commission-percent", commissionPercent.toPlainString());
        contract.addEvent(now, "CREATED", ownerName + " created the contract and escrowed " + reward.toPlainString());
        return contract;
    }

    /**
     * Wager: two parties each stake an equal amount, an arbiter rules the outcome,
     * winner takes both stakes minus commission.
     */
    public static Contract createWager(UUID partyAUuid, String partyAName,
                                       UUID partyBUuid, String partyBName,
                                       UUID arbiterUuid, String arbiterName,
                                       String title, String description,
                                       BigDecimal stake, BigDecimal commissionPercent,
                                       long now, long expiresAt) {
        Participant partyA = new Participant(ParticipantRole.PARTY_A, partyAUuid, partyAName,
            List.of(Asset.money(stake)));
        Participant partyB = new Participant(ParticipantRole.PARTY_B, partyBUuid, partyBName,
            List.of(Asset.money(stake)));
        Participant arbiter = new Participant(ParticipantRole.MEDIATOR, arbiterUuid, arbiterName, List.of());

        BigDecimal payoutShare = new BigDecimal("100").subtract(commissionPercent);
        List<PayoutRule> rules = new ArrayList<>();

        // A wins: 从 A 和 B 各拿 payoutShare% 给 A,剩余 commissionPercent% 给 sink
        rules.add(new PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER,
            ParticipantRole.PARTY_A, PayoutRecipient.participant(ParticipantRole.PARTY_A), payoutShare));
        rules.add(new PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER,
            ParticipantRole.PARTY_A, PayoutRecipient.systemSink(), commissionPercent));
        rules.add(new PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER,
            ParticipantRole.PARTY_B, PayoutRecipient.participant(ParticipantRole.PARTY_A), payoutShare));
        rules.add(new PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER,
            ParticipantRole.PARTY_B, PayoutRecipient.systemSink(), commissionPercent));

        // B wins: 镜像
        rules.add(new PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_CONTRACTOR,
            ParticipantRole.PARTY_A, PayoutRecipient.participant(ParticipantRole.PARTY_B), payoutShare));
        rules.add(new PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_CONTRACTOR,
            ParticipantRole.PARTY_A, PayoutRecipient.systemSink(), commissionPercent));
        rules.add(new PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_CONTRACTOR,
            ParticipantRole.PARTY_B, PayoutRecipient.participant(ParticipantRole.PARTY_B), payoutShare));
        rules.add(new PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_CONTRACTOR,
            ParticipantRole.PARTY_B, PayoutRecipient.systemSink(), commissionPercent));

        // TIMEOUT (双方都没动): 各退各的,不收 commission
        rules.add(new PayoutRule(PayoutCondition.TIMEOUT,
            ParticipantRole.PARTY_A, PayoutRecipient.participant(ParticipantRole.PARTY_A), new BigDecimal("100")));
        rules.add(new PayoutRule(PayoutCondition.TIMEOUT,
            ParticipantRole.PARTY_B, PayoutRecipient.participant(ParticipantRole.PARTY_B), new BigDecimal("100")));

        // FAILURE (opponent 拒绝/超时未接受): 退 A 100%; B 还没押,不动
        rules.add(new PayoutRule(PayoutCondition.FAILURE,
            ParticipantRole.PARTY_A, PayoutRecipient.participant(ParticipantRole.PARTY_A), new BigDecimal("100")));

        Contract contract = new Contract(
            UUID.randomUUID().toString(),
            ContractType.WAGER,
            title,
            description,
            List.of(partyA, partyB),
            arbiter,
            ResolutionRule.ARBITER,
            rules,
            ContractStatus.PENDING_ACCEPT,
            now,
            null,
            null,
            null,
            expiresAt,
            null,
            List.of()
        );
        contract.metadata.put("commission-percent", commissionPercent.toPlainString());
        contract.addEvent(now, "CREATED",
            partyAName + " opened wager vs " + partyBName + ", arbiter " + arbiterName
                + ", stake " + stake.toPlainString());
        return contract;
    }

    /**
     * Partnership: two partners each stake, BOTH_APPROVE for success, each gets back own stake
     * minus commission. No violator judgement in v1 — disputed cancels go to admin arbitration.
     */
    public static Contract createPartnership(UUID creatorUuid, String creatorName,
                                             UUID partnerUuid, String partnerName,
                                             BigDecimal stakeA, BigDecimal stakeB,
                                             BigDecimal commissionPercent,
                                             String title, String description,
                                             long now, long expiresAt) {
        // Use PARTY_A / PARTY_B roles so existing PayoutRecipient infrastructure addresses them by role.
        Participant first = new Participant(ParticipantRole.PARTY_A, creatorUuid, creatorName,
            List.of(Asset.money(stakeA)));
        Participant second = new Participant(ParticipantRole.PARTY_B, partnerUuid, partnerName,
            List.of(Asset.money(stakeB)));

        BigDecimal payoutShare = new BigDecimal("100").subtract(commissionPercent);
        List<PayoutRule> rules = new ArrayList<>();
        // SUCCESS: each gets back own stake minus commission
        rules.add(new PayoutRule(PayoutCondition.SUCCESS,
            ParticipantRole.PARTY_A, PayoutRecipient.participant(ParticipantRole.PARTY_A), payoutShare));
        rules.add(new PayoutRule(PayoutCondition.SUCCESS,
            ParticipantRole.PARTY_A, PayoutRecipient.systemSink(), commissionPercent));
        rules.add(new PayoutRule(PayoutCondition.SUCCESS,
            ParticipantRole.PARTY_B, PayoutRecipient.participant(ParticipantRole.PARTY_B), payoutShare));
        rules.add(new PayoutRule(PayoutCondition.SUCCESS,
            ParticipantRole.PARTY_B, PayoutRecipient.systemSink(), commissionPercent));

        // FAILURE / TIMEOUT: each gets back own stake fully, no commission
        rules.add(new PayoutRule(PayoutCondition.FAILURE,
            ParticipantRole.PARTY_A, PayoutRecipient.participant(ParticipantRole.PARTY_A), new BigDecimal("100")));
        rules.add(new PayoutRule(PayoutCondition.FAILURE,
            ParticipantRole.PARTY_B, PayoutRecipient.participant(ParticipantRole.PARTY_B), new BigDecimal("100")));
        rules.add(new PayoutRule(PayoutCondition.TIMEOUT,
            ParticipantRole.PARTY_A, PayoutRecipient.participant(ParticipantRole.PARTY_A), new BigDecimal("100")));
        rules.add(new PayoutRule(PayoutCondition.TIMEOUT,
            ParticipantRole.PARTY_B, PayoutRecipient.participant(ParticipantRole.PARTY_B), new BigDecimal("100")));

        // DISPUTE_RESOLVED_FOR_OWNER:  A 守约,B 违约 → A 拿走 B 的押注 (admin 决断后)
        rules.add(new PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER,
            ParticipantRole.PARTY_A, PayoutRecipient.participant(ParticipantRole.PARTY_A), new BigDecimal("100")));
        rules.add(new PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER,
            ParticipantRole.PARTY_B, PayoutRecipient.participant(ParticipantRole.PARTY_A), new BigDecimal("100")));
        // DISPUTE_RESOLVED_FOR_CONTRACTOR: 镜像
        rules.add(new PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_CONTRACTOR,
            ParticipantRole.PARTY_A, PayoutRecipient.participant(ParticipantRole.PARTY_B), new BigDecimal("100")));
        rules.add(new PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_CONTRACTOR,
            ParticipantRole.PARTY_B, PayoutRecipient.participant(ParticipantRole.PARTY_B), new BigDecimal("100")));

        Contract contract = new Contract(
            UUID.randomUUID().toString(),
            ContractType.PARTNERSHIP,
            title,
            description,
            List.of(first, second),
            null,
            ResolutionRule.BOTH_APPROVE,
            rules,
            ContractStatus.PENDING_ACCEPT,
            now,
            null,
            null,
            null,
            expiresAt,
            null,
            List.of()
        );
        contract.metadata.put("commission-percent", commissionPercent.toPlainString());
        contract.addEvent(now, "CREATED",
            creatorName + " proposed partnership with " + partnerName
                + ", stakes " + stakeA.toPlainString() + " / " + stakeB.toPlainString());
        return contract;
    }

    public String shortId() {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    public boolean isExpired(long now) {
        return now >= expiresAt && !status.isFinal() && status != ContractStatus.DISPUTED;
    }

    public void addEvent(long time, String type, String detail) {
        events.add(new ContractEvent(time, type, detail));
    }

    public Optional<Participant> participant(ParticipantRole role) {
        return participants.stream().filter(p -> p.role() == role).findFirst();
    }

    public Optional<Participant> participantByUuid(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        return participants.stream().filter(p -> uuid.equals(p.uuid())).findFirst();
    }

    public boolean relatedTo(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        if (participants.stream().anyMatch(p -> uuid.equals(p.uuid()))) {
            return true;
        }
        return arbiter != null && uuid.equals(arbiter.uuid());
    }

    public boolean hasArbiter() {
        return arbiter != null && arbiter.uuid() != null;
    }

    public boolean arbiterAccepted() {
        return !"false".equalsIgnoreCase(metadata.getOrDefault("mediator-accepted", "true"));
    }

    public void arbiterAccepted(boolean accepted) {
        metadata.put("mediator-accepted", Boolean.toString(accepted));
    }

    public List<PayoutRule> payoutsFor(PayoutCondition condition) {
        List<PayoutRule> matches = new ArrayList<>();
        for (PayoutRule rule : payouts) {
            if (rule.condition() == condition) {
                matches.add(rule);
            }
        }
        return matches;
    }

    // === Legacy SERVICE-flavored accessors (preserved for command/GUI compatibility) ===

    public UUID ownerUuid() {
        return primaryParticipant().map(Participant::uuid).orElse(null);
    }

    public String ownerName() {
        return primaryParticipant().map(Participant::displayName).orElse(null);
    }

    public UUID contractorUuid() {
        return secondaryParticipant().map(Participant::uuid).orElse(null);
    }

    public void contractorUuid(UUID uuid) {
        participant(ParticipantRole.CONTRACTOR).ifPresent(p -> p.uuid(uuid));
    }

    public String contractorName() {
        return secondaryParticipant().map(Participant::displayName).orElse(null);
    }

    public void contractorName(String name) {
        participant(ParticipantRole.CONTRACTOR).ifPresent(p -> p.displayName(name));
    }

    public BigDecimal reward() {
        return primaryParticipant().map(Participant::moneyStake).orElse(BigDecimal.ZERO);
    }

    public BigDecimal creationFee() {
        String stored = metadata.get("creation-fee");
        return stored == null ? BigDecimal.ZERO : new BigDecimal(stored);
    }

    public BigDecimal commissionPercent() {
        String stored = metadata.get("commission-percent");
        return stored == null ? BigDecimal.ZERO : new BigDecimal(stored);
    }

    public BigDecimal payoutAmount() {
        // For SERVICE compatibility: derived as reward - commission
        BigDecimal reward = reward();
        BigDecimal commission = commissionAmount();
        return reward.subtract(commission).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public BigDecimal commissionAmount() {
        BigDecimal reward = reward();
        BigDecimal percent = commissionPercent();
        if (percent.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return reward.multiply(percent).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
    }

    private Optional<Participant> primaryParticipant() {
        return firstParticipant(
            ParticipantRole.OWNER,
            ParticipantRole.PARTY_A,
            ParticipantRole.POSTER,
            ParticipantRole.CREDITOR
        );
    }

    private Optional<Participant> secondaryParticipant() {
        return firstParticipant(
            ParticipantRole.CONTRACTOR,
            ParticipantRole.PARTY_B,
            ParticipantRole.PARTNER,
            ParticipantRole.CLAIMER,
            ParticipantRole.DEBTOR
        );
    }

    private Optional<Participant> firstParticipant(ParticipantRole... roles) {
        for (ParticipantRole role : roles) {
            Optional<Participant> match = participant(role);
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    // === Generic accessors ===

    public String id() {
        return id;
    }

    public ContractType type() {
        return type;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public List<Participant> participants() {
        return Collections.unmodifiableList(participants);
    }

    public Participant arbiter() {
        return arbiter;
    }

    public void arbiter(Participant arbiter) {
        this.arbiter = arbiter;
    }

    public ResolutionRule resolutionRule() {
        return resolutionRule;
    }

    public List<PayoutRule> payouts() {
        return Collections.unmodifiableList(payouts);
    }

    public ContractStatus status() {
        return status;
    }

    public void status(ContractStatus status) {
        this.status = status;
    }

    public long createdAt() {
        return createdAt;
    }

    public Long acceptedAt() {
        return acceptedAt;
    }

    public void acceptedAt(Long acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public Long submittedAt() {
        return submittedAt;
    }

    public void submittedAt(Long submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Long completedAt() {
        return completedAt;
    }

    public void completedAt(Long completedAt) {
        this.completedAt = completedAt;
    }

    public long expiresAt() {
        return expiresAt;
    }

    public String disputeReason() {
        return disputeReason;
    }

    public void disputeReason(String disputeReason) {
        this.disputeReason = disputeReason;
    }

    public List<ContractEvent> events() {
        return List.copyOf(events);
    }

    // Free-form metadata bag for type-specific data (creation-fee, commission-percent, etc).
    // Not part of the generic model; preserved across save/load.
    public final java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
}
