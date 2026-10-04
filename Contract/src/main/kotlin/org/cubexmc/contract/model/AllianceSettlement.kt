package org.cubexmc.contract.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.Collections

/** Immutable executable journal snapshot. A PAYING entry is deliberately never replayed. */
class AllianceSettlement private constructor(
    val outcome: Outcome,
    val defaulter: UUID?,
    val actor: String,
    principals: Map<UUID, BigDecimal>,
    agreement: Map<*, *>,
    transfers: List<Map<String, String>>,
    phases: Map<UUID, Phase>,
) {
    private val principals = Collections.unmodifiableMap(LinkedHashMap(principals))
    private val agreement = frozenMap(agreement)
    private val transfers = Collections.unmodifiableList(transfers.map { Collections.unmodifiableMap(LinkedHashMap(it)) })
    private val phases = Collections.unmodifiableMap(LinkedHashMap(phases))

    enum class Outcome(val status: ContractStatus) {
        SUCCESS(ContractStatus.COMPLETED), REFUND(ContractStatus.CANCELLED),
        TIMEOUT(ContractStatus.EXPIRED), BREACH(ContractStatus.COMPLETED)
    }
    enum class Phase { READY, PAYING, PAID }

    fun payments(): Map<UUID, BigDecimal> = transfers.groupBy { UUID.fromString(it.getValue("recipient")) }
        .mapValues { (_, rows) -> rows.fold(BigDecimal("0.00")) { sum, row -> sum.add(money(row["amount"])) } }

    fun phase(recipient: UUID): Phase = phases.getValue(recipient)
    fun allPaid(): Boolean = phases.values.all { it == Phase.PAID }
    fun hasUncertainPayment(): Boolean = phases.values.any { it == Phase.PAYING }

    fun advance(recipient: UUID, expected: Phase, next: Phase): AllianceSettlement {
        require(phase(recipient) == expected &&
            (expected == Phase.READY && next == Phase.PAYING || expected == Phase.PAYING && next == Phase.PAID)) {
            "Invalid alliance payout phase transition"
        }
        return AllianceSettlement(outcome, defaulter, actor, principals, agreement, transfers, phases + (recipient to next))
    }

    fun validate(contract: Contract) {
        val current = contract.checkedAllianceAgreement()
        val savedAgreement = AllianceAgreement.fromMap(current.members(), current.creatorUuid(), agreement)
        require(AlliancePayoutPlan.principalByUuid(contract) == principals && current.toMap() == savedAgreement.toMap()) {
            "Alliance settlement terms or signatures changed"
        }
        if (outcome == Outcome.SUCCESS) require(current.allApproved()) { "Missing unanimous approval" }
        if (outcome == Outcome.TIMEOUT) require(!current.allAccepted()) { "Timeout is only for unsigned invitations" }
        val expected = if (outcome == Outcome.BREACH)
            AlliancePayoutPlan.principalBreach(contract, requireNotNull(defaulter))
        else AlliancePayoutPlan.principalRefund(contract)
        require(rows(expected) == transfers) { "Alliance settlement allocation does not match principal plan" }
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "version" to 1, "outcome" to outcome.name, "defaulter" to defaulter?.toString(), "actor" to actor,
        "principals" to principals.map { (id, amount) -> mapOf("uuid" to id.toString(), "amount" to amount.toPlainString()) },
        "agreement" to agreement, "transfers" to transfers,
        "payments" to phases.map { (id, phase) -> mapOf("uuid" to id.toString(), "phase" to phase.name) },
    )

    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(contract: Contract, outcome: Outcome, defaulter: UUID?, actor: String = "system"): AllianceSettlement {
            val plan = when (outcome) {
                Outcome.SUCCESS -> AlliancePayoutPlan.success(contract)
                Outcome.BREACH -> AlliancePayoutPlan.breach(contract, requireNotNull(defaulter))
                else -> AlliancePayoutPlan.refund(contract)
            }
            require((outcome == Outcome.BREACH) == (defaulter != null)) { "Unexpected defaulter" }
            require(actor.isNotBlank()) { "Settlement actor is missing" }
            val snapshot = AllianceSettlement(outcome, defaulter, actor, AlliancePayoutPlan.principalByUuid(contract),
                contract.checkedAllianceAgreement().toMap(), rows(plan), plan.payments().mapValues { Phase.READY })
            snapshot.validate(contract)
            return snapshot
        }

        @JvmStatic
        fun fromMap(map: Map<*, *>): AllianceSettlement {
            require(map["version"].toString() == "1") { "Unsupported alliance settlement format" }
            val outcome = Outcome.valueOf(map["outcome"].toString())
            val defaulter = map["defaulter"]?.let { UUID.fromString(it.toString()) }
            val actor = requireNotNull(map["actor"] as? String).also { require(it.isNotBlank()) }
            require((outcome == Outcome.BREACH) == (defaulter != null))
            val principals = LinkedHashMap<UUID, BigDecimal>()
            for (entry in entries(map, "principals")) {
                require(principals.putIfAbsent(UUID.fromString(entry["uuid"].toString()), money(entry["amount"])) == null)
            }
            require(principals.size >= 3)
            val agreement = requireNotNull(map["agreement"] as? Map<*, *>) { "Missing signature snapshot" }
            val transfers = entries(map, "transfers").map { entry ->
                mapOf("source" to UUID.fromString(entry["source"].toString()).toString(),
                    "recipient" to UUID.fromString(entry["recipient"].toString()).toString(),
                    "amount" to money(entry["amount"]).toPlainString())
            }
            require(transfers.isNotEmpty() && transfers.map { it["source"] to it["recipient"] }.distinct().size == transfers.size)
            val phases = LinkedHashMap<UUID, Phase>()
            for (entry in entries(map, "payments")) {
                require(phases.putIfAbsent(UUID.fromString(entry["uuid"].toString()), Phase.valueOf(entry["phase"].toString())) == null)
            }
            require(phases.keys == transfers.map { UUID.fromString(it.getValue("recipient")) }.toSet())
            return AllianceSettlement(outcome, defaulter, actor, principals, agreement, transfers, phases)
        }

        private fun entries(map: Map<*, *>, key: String): List<Map<*, *>> =
            requireNotNull(map[key] as? List<*>) { "Missing $key" }.map { requireNotNull(it as? Map<*, *>) }

        private fun money(raw: Any?): BigDecimal = BigDecimal(requireNotNull(raw).toString())
            .setScale(2, RoundingMode.UNNECESSARY).also { require(it.signum() > 0) }

        private fun rows(plan: AlliancePayoutPlan): List<Map<String, String>> = plan.transfers().map {
            mapOf("source" to it.sourceUuid().toString(), "recipient" to it.recipientUuid().toString(),
                "amount" to it.amount().toPlainString())
        }

        private fun frozenMap(map: Map<*, *>): Map<*, *> = Collections.unmodifiableMap(map.mapValues { (_, value) -> freeze(value) })
        private fun freeze(value: Any?): Any? = when (value) {
            is Map<*, *> -> frozenMap(value)
            is List<*> -> Collections.unmodifiableList(value.map { freeze(it) })
            else -> value
        }
    }
}
