package org.cubexmc.contract.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Optional
import java.util.UUID

class Contract(
    private val id: String,
    private val type: ContractType,
    private val title: String,
    private val description: String,
    participants: List<Participant>,
    private var arbiter: Participant?,
    private val resolutionRule: ResolutionRule,
    payouts: List<PayoutRule>,
    private var status: ContractStatus,
    private val createdAt: Long,
    private var acceptedAt: Long?,
    private var submittedAt: Long?,
    private var completedAt: Long?,
    private val expiresAt: Long,
    private var disputeReason: String?,
    events: List<ContractEvent>,
) {
    private val participants: MutableList<Participant> = ArrayList(participants)
    private val payouts: MutableList<PayoutRule> = ArrayList(payouts)
    private val events: MutableList<ContractEvent> = ArrayList(events)

    // Free-form metadata bag for type-specific data (creation-fee, commission-percent, etc).
    // Not part of the generic model; preserved across save/load.
    @JvmField
    val metadata: MutableMap<String, String> = LinkedHashMap()

    fun shortId(): String = if (id.length <= 8) id else id.substring(0, 8)

    fun isExpired(now: Long): Boolean = now >= expiresAt && !status.isFinal() && status != ContractStatus.DISPUTED

    fun addEvent(time: Long, type: String, detail: String) {
        events.add(ContractEvent(time, type, detail))
    }

    fun participant(role: ParticipantRole): Optional<Participant> =
        participants.stream().filter { p -> p.role() == role }.findFirst()

    fun participantByUuid(uuid: UUID?): Optional<Participant> {
        if (uuid == null) {
            return Optional.empty()
        }
        return participants.stream().filter { p -> uuid == p.uuid() }.findFirst()
    }

    fun relatedTo(uuid: UUID?): Boolean {
        if (uuid == null) {
            return false
        }
        if (participants.stream().anyMatch { p -> uuid == p.uuid() }) {
            return true
        }
        return arbiter != null && uuid == arbiter?.uuid()
    }

    fun hasArbiter(): Boolean = arbiter != null && arbiter?.uuid() != null

    fun arbiterAccepted(): Boolean = !"false".equals(metadata.getOrDefault("mediator-accepted", "true"), ignoreCase = true)

    fun arbiterAccepted(accepted: Boolean) {
        metadata["mediator-accepted"] = accepted.toString()
    }

    fun payoutsFor(condition: PayoutCondition): List<PayoutRule> {
        val matches = ArrayList<PayoutRule>()
        for (rule in payouts) {
            if (rule.condition() == condition) {
                matches.add(rule)
            }
        }
        return matches
    }

    // === Legacy SERVICE-flavored accessors (preserved for command/GUI compatibility) ===

    fun ownerUuid(): UUID? = primaryParticipant().map { it.uuid() }.orElse(null)

    fun ownerName(): String? = primaryParticipant().map { it.displayName() }.orElse(null)

    fun contractorUuid(): UUID? = secondaryParticipant().map { it.uuid() }.orElse(null)

    fun contractorUuid(uuid: UUID?) {
        participant(ParticipantRole.CONTRACTOR).ifPresent { p -> p.uuid(uuid) }
    }

    fun contractorName(): String? = secondaryParticipant().map { it.displayName() }.orElse(null)

    fun contractorName(name: String?) {
        participant(ParticipantRole.CONTRACTOR).ifPresent { p -> p.displayName(name) }
    }

    fun reward(): BigDecimal = primaryParticipant().map { it.moneyStake() }.orElse(BigDecimal.ZERO)

    fun creationFee(): BigDecimal {
        val stored = metadata["creation-fee"]
        return if (stored == null) BigDecimal.ZERO else BigDecimal(stored)
    }

    fun commissionPercent(): BigDecimal {
        val stored = metadata["commission-percent"]
        return if (stored == null) BigDecimal.ZERO else BigDecimal(stored)
    }

    fun payoutAmount(): BigDecimal {
        val reward = reward()
        val commission = commissionAmount()
        return reward.subtract(commission).setScale(2, RoundingMode.HALF_UP)
    }

    fun commissionAmount(): BigDecimal {
        val reward = reward()
        val percent = commissionPercent()
        if (percent.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2)
        }
        return reward.multiply(percent).divide(BigDecimal("100"), 2, RoundingMode.HALF_UP)
    }

    private fun primaryParticipant(): Optional<Participant> =
        firstParticipant(
            ParticipantRole.OWNER,
            ParticipantRole.PARTY_A,
            ParticipantRole.POSTER,
            ParticipantRole.CREDITOR,
        )

    private fun secondaryParticipant(): Optional<Participant> =
        firstParticipant(
            ParticipantRole.CONTRACTOR,
            ParticipantRole.PARTY_B,
            ParticipantRole.PARTNER,
            ParticipantRole.CLAIMER,
            ParticipantRole.DEBTOR,
        )

    private fun firstParticipant(vararg roles: ParticipantRole): Optional<Participant> {
        for (role in roles) {
            val match = participant(role)
            if (match.isPresent) {
                return match
            }
        }
        return Optional.empty()
    }

    // === Generic accessors ===

    fun id(): String = id

    fun type(): ContractType = type

    fun title(): String = title

    fun description(): String = description

    fun participants(): List<Participant> = Collections.unmodifiableList(participants)

    fun arbiter(): Participant? = arbiter

    fun arbiter(arbiter: Participant?) {
        this.arbiter = arbiter
    }

    fun resolutionRule(): ResolutionRule = resolutionRule

    fun payouts(): List<PayoutRule> = Collections.unmodifiableList(payouts)

    fun status(): ContractStatus = status

    fun status(status: ContractStatus) {
        this.status = status
    }

    fun createdAt(): Long = createdAt

    fun acceptedAt(): Long? = acceptedAt

    fun acceptedAt(acceptedAt: Long?) {
        this.acceptedAt = acceptedAt
    }

    fun submittedAt(): Long? = submittedAt

    fun submittedAt(submittedAt: Long?) {
        this.submittedAt = submittedAt
    }

    fun completedAt(): Long? = completedAt

    fun completedAt(completedAt: Long?) {
        this.completedAt = completedAt
    }

    fun expiresAt(): Long = expiresAt

    fun disputeReason(): String? = disputeReason

    fun disputeReason(disputeReason: String?) {
        this.disputeReason = disputeReason
    }

    fun events(): List<ContractEvent> = Collections.unmodifiableList(ArrayList(events))

    companion object {
        /**
         * Factory for legacy single-employer SERVICE contracts. Keeps backward compatibility
         * with the /contract service flow while the model is now generic.
         */
        @JvmStatic
        fun createService(
            ownerUuid: UUID,
            ownerName: String,
            title: String,
            description: String,
            reward: BigDecimal,
            creationFee: BigDecimal,
            commissionPercent: BigDecimal,
            now: Long,
            expiresAt: Long,
        ): Contract = createService(
            UUID.randomUUID().toString(),
            ownerUuid,
            ownerName,
            title,
            description,
            reward,
            creationFee,
            commissionPercent,
            now,
            expiresAt,
        )

        @JvmStatic
        fun createService(
            id: String,
            ownerUuid: UUID,
            ownerName: String,
            title: String,
            description: String,
            reward: BigDecimal,
            creationFee: BigDecimal,
            commissionPercent: BigDecimal,
            now: Long,
            expiresAt: Long,
        ): Contract {
            val owner = Participant(ParticipantRole.OWNER, ownerUuid, ownerName, listOf(Asset.money(reward)))
            val contractor = Participant(ParticipantRole.CONTRACTOR, null, null, emptyList())
            val rules = ArrayList<PayoutRule>()
            val payoutShare = BigDecimal("100").subtract(commissionPercent)
            rules.add(
                PayoutRule(
                    PayoutCondition.SUCCESS,
                    ParticipantRole.OWNER,
                    PayoutRecipient.participant(ParticipantRole.CONTRACTOR),
                    payoutShare,
                ),
            )
            rules.add(
                PayoutRule(PayoutCondition.SUCCESS, ParticipantRole.OWNER, PayoutRecipient.systemSink(), commissionPercent),
            )
            rules.add(
                PayoutRule(
                    PayoutCondition.FAILURE,
                    ParticipantRole.OWNER,
                    PayoutRecipient.participant(ParticipantRole.OWNER),
                    BigDecimal("100"),
                ),
            )
            rules.add(
                PayoutRule(
                    PayoutCondition.TIMEOUT,
                    ParticipantRole.OWNER,
                    PayoutRecipient.participant(ParticipantRole.OWNER),
                    BigDecimal("100"),
                ),
            )

            val contract = Contract(
                id,
                ContractType.SERVICE,
                title,
                description,
                listOf(owner, contractor),
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
                emptyList(),
            )
            contract.metadata["creation-fee"] = creationFee.toPlainString()
            contract.metadata["commission-percent"] = commissionPercent.toPlainString()
            contract.addEvent(now, "CREATED", "$ownerName created the contract and escrowed ${reward.toPlainString()}")
            return contract
        }

        @JvmStatic
        fun createWager(
            partyAUuid: UUID,
            partyAName: String,
            partyBUuid: UUID,
            partyBName: String,
            arbiterUuid: UUID,
            arbiterName: String,
            title: String,
            description: String,
            stake: BigDecimal,
            commissionPercent: BigDecimal,
            now: Long,
            expiresAt: Long,
        ): Contract = createWager(
            UUID.randomUUID().toString(),
            partyAUuid,
            partyAName,
            partyBUuid,
            partyBName,
            arbiterUuid,
            arbiterName,
            title,
            description,
            stake,
            commissionPercent,
            now,
            expiresAt,
        )

        @JvmStatic
        fun createWager(
            id: String,
            partyAUuid: UUID,
            partyAName: String,
            partyBUuid: UUID,
            partyBName: String,
            arbiterUuid: UUID,
            arbiterName: String,
            title: String,
            description: String,
            stake: BigDecimal,
            commissionPercent: BigDecimal,
            now: Long,
            expiresAt: Long,
        ): Contract {
            val partyA = Participant(ParticipantRole.PARTY_A, partyAUuid, partyAName, listOf(Asset.money(stake)))
            val partyB = Participant(ParticipantRole.PARTY_B, partyBUuid, partyBName, listOf(Asset.money(stake)))
            val arbiter = Participant(ParticipantRole.MEDIATOR, arbiterUuid, arbiterName, emptyList())

            val payoutShare = BigDecimal("100").subtract(commissionPercent)
            val rules = ArrayList<PayoutRule>()

            rules.add(PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER, ParticipantRole.PARTY_A, PayoutRecipient.participant(ParticipantRole.PARTY_A), payoutShare))
            rules.add(PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER, ParticipantRole.PARTY_A, PayoutRecipient.systemSink(), commissionPercent))
            rules.add(PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER, ParticipantRole.PARTY_B, PayoutRecipient.participant(ParticipantRole.PARTY_A), payoutShare))
            rules.add(PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER, ParticipantRole.PARTY_B, PayoutRecipient.systemSink(), commissionPercent))

            rules.add(PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_CONTRACTOR, ParticipantRole.PARTY_A, PayoutRecipient.participant(ParticipantRole.PARTY_B), payoutShare))
            rules.add(PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_CONTRACTOR, ParticipantRole.PARTY_A, PayoutRecipient.systemSink(), commissionPercent))
            rules.add(PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_CONTRACTOR, ParticipantRole.PARTY_B, PayoutRecipient.participant(ParticipantRole.PARTY_B), payoutShare))
            rules.add(PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_CONTRACTOR, ParticipantRole.PARTY_B, PayoutRecipient.systemSink(), commissionPercent))

            rules.add(PayoutRule(PayoutCondition.TIMEOUT, ParticipantRole.PARTY_A, PayoutRecipient.participant(ParticipantRole.PARTY_A), BigDecimal("100")))
            rules.add(PayoutRule(PayoutCondition.TIMEOUT, ParticipantRole.PARTY_B, PayoutRecipient.participant(ParticipantRole.PARTY_B), BigDecimal("100")))
            rules.add(PayoutRule(PayoutCondition.FAILURE, ParticipantRole.PARTY_A, PayoutRecipient.participant(ParticipantRole.PARTY_A), BigDecimal("100")))

            val contract = Contract(
                id,
                ContractType.WAGER,
                title,
                description,
                listOf(partyA, partyB),
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
                emptyList(),
            )
            contract.metadata["commission-percent"] = commissionPercent.toPlainString()
            contract.addEvent(
                now,
                "CREATED",
                "$partyAName opened wager vs $partyBName, arbiter $arbiterName, stake ${stake.toPlainString()}",
            )
            return contract
        }

        @JvmStatic
        fun createPartnership(
            creatorUuid: UUID,
            creatorName: String,
            partnerUuid: UUID,
            partnerName: String,
            stakeA: BigDecimal,
            stakeB: BigDecimal,
            commissionPercent: BigDecimal,
            title: String,
            description: String,
            now: Long,
            expiresAt: Long,
        ): Contract = createPartnership(
            UUID.randomUUID().toString(),
            creatorUuid,
            creatorName,
            partnerUuid,
            partnerName,
            stakeA,
            stakeB,
            commissionPercent,
            title,
            description,
            now,
            expiresAt,
        )

        @JvmStatic
        fun createPartnership(
            id: String,
            creatorUuid: UUID,
            creatorName: String,
            partnerUuid: UUID,
            partnerName: String,
            stakeA: BigDecimal,
            stakeB: BigDecimal,
            commissionPercent: BigDecimal,
            title: String,
            description: String,
            now: Long,
            expiresAt: Long,
        ): Contract {
            val first = Participant(ParticipantRole.PARTY_A, creatorUuid, creatorName, listOf(Asset.money(stakeA)))
            val second = Participant(ParticipantRole.PARTY_B, partnerUuid, partnerName, listOf(Asset.money(stakeB)))

            val payoutShare = BigDecimal("100").subtract(commissionPercent)
            val rules = ArrayList<PayoutRule>()
            rules.add(PayoutRule(PayoutCondition.SUCCESS, ParticipantRole.PARTY_A, PayoutRecipient.participant(ParticipantRole.PARTY_A), payoutShare))
            rules.add(PayoutRule(PayoutCondition.SUCCESS, ParticipantRole.PARTY_A, PayoutRecipient.systemSink(), commissionPercent))
            rules.add(PayoutRule(PayoutCondition.SUCCESS, ParticipantRole.PARTY_B, PayoutRecipient.participant(ParticipantRole.PARTY_B), payoutShare))
            rules.add(PayoutRule(PayoutCondition.SUCCESS, ParticipantRole.PARTY_B, PayoutRecipient.systemSink(), commissionPercent))

            rules.add(PayoutRule(PayoutCondition.FAILURE, ParticipantRole.PARTY_A, PayoutRecipient.participant(ParticipantRole.PARTY_A), BigDecimal("100")))
            rules.add(PayoutRule(PayoutCondition.FAILURE, ParticipantRole.PARTY_B, PayoutRecipient.participant(ParticipantRole.PARTY_B), BigDecimal("100")))
            rules.add(PayoutRule(PayoutCondition.TIMEOUT, ParticipantRole.PARTY_A, PayoutRecipient.participant(ParticipantRole.PARTY_A), BigDecimal("100")))
            rules.add(PayoutRule(PayoutCondition.TIMEOUT, ParticipantRole.PARTY_B, PayoutRecipient.participant(ParticipantRole.PARTY_B), BigDecimal("100")))

            rules.add(PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER, ParticipantRole.PARTY_A, PayoutRecipient.participant(ParticipantRole.PARTY_A), BigDecimal("100")))
            rules.add(PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER, ParticipantRole.PARTY_B, PayoutRecipient.participant(ParticipantRole.PARTY_A), BigDecimal("100")))
            rules.add(PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_CONTRACTOR, ParticipantRole.PARTY_A, PayoutRecipient.participant(ParticipantRole.PARTY_B), BigDecimal("100")))
            rules.add(PayoutRule(PayoutCondition.DISPUTE_RESOLVED_FOR_CONTRACTOR, ParticipantRole.PARTY_B, PayoutRecipient.participant(ParticipantRole.PARTY_B), BigDecimal("100")))

            val contract = Contract(
                id,
                ContractType.PARTNERSHIP,
                title,
                description,
                listOf(first, second),
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
                emptyList(),
            )
            contract.metadata["commission-percent"] = commissionPercent.toPlainString()
            contract.addEvent(
                now,
                "CREATED",
                "$creatorName proposed partnership with $partnerName, stakes ${stakeA.toPlainString()} / ${stakeB.toPlainString()}",
            )
            return contract
        }
    }
}
