package org.cubexmc.contract.model

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Collections
import java.util.UUID

/**
 * Principal-only, UUID-addressed allocations computed from a funded signature snapshot (PLAN §5.1,
 * option B). No generic source selector or role-based payout is added: several members share ALLY.
 * This is a calculation, not a payment/recovery journal; the service must execute and persist it.
 */
class AlliancePayoutPlan private constructor(transfers: List<Transfer>) {
    class Transfer internal constructor(
        private val sourceUuid: UUID,
        private val recipientUuid: UUID,
        private val amount: BigDecimal,
    ) {
        fun sourceUuid(): UUID = sourceUuid
        fun recipientUuid(): UUID = recipientUuid
        fun amount(): BigDecimal = amount
    }

    private val transfers = Collections.unmodifiableList(ArrayList(transfers))

    fun transfers(): List<Transfer> = transfers

    fun payments(): Map<UUID, BigDecimal> {
        val result = LinkedHashMap<UUID, BigDecimal>()
        for (transfer in transfers) {
            result.merge(transfer.recipientUuid(), transfer.amount()) { left, right -> left.add(right) }
        }
        return Collections.unmodifiableMap(result)
    }

    fun total(): BigDecimal = transfers.fold(BigDecimal("0.00")) { sum, transfer -> sum.add(transfer.amount()) }

    companion object {
        /** Pending cancellation/timeout returns only signatures whose principal has landed. */
        @JvmStatic
        fun refund(contract: Contract): AlliancePayoutPlan {
            require(contract.status() in setOf(ContractStatus.PENDING_ACCEPT_MULTI, ContractStatus.IN_PROGRESS, ContractStatus.DISPUTED)) {
                "Alliance refund requires a live alliance"
            }
            val stakes = fundedStakes(contract)
            return AlliancePayoutPlan(stakes.map { (id, amount) -> Transfer(id, id, amount) })
        }

        @JvmStatic
        fun success(contract: Contract): AlliancePayoutPlan {
            require(contract.status() == ContractStatus.IN_PROGRESS && agreement(contract).allApproved()) {
                "Alliance success requires every signature and approval on an active alliance"
            }
            return refund(contract)
        }

        /** Caller owns the disputed outcome/authority; this method only allocates principal. */
        @JvmStatic
        fun breach(contract: Contract, defaulterUuid: UUID): AlliancePayoutPlan {
            require(contract.status() == ContractStatus.DISPUTED && agreement(contract).allAccepted()) {
                "Alliance breach requires a fully signed disputed alliance"
            }
            val stakes = fundedStakes(contract)
            val forfeited = requireNotNull(stakes[defaulterUuid]) { "Alliance defaulter must be a funded member" }
            val beneficiaries = stakes.keys.filter { it != defaulterUuid }.sortedBy { it.toString() }
            val transfers = ArrayList<Transfer>()
            for (id in beneficiaries) transfers.add(Transfer(id, id, stakes.getValue(id)))

            // Work in integer cents. Independent percent rounding would create or lose escrow.
            val cents = forfeited.movePointRight(2).toBigIntegerExact()
            val division = cents.divideAndRemainder(BigInteger.valueOf(beneficiaries.size.toLong()))
            for ((index, recipient) in beneficiaries.withIndex()) {
                val share = division[0].add(if (index < division[1].toInt()) BigInteger.ONE else BigInteger.ZERO)
                if (share.signum() > 0) transfers.add(Transfer(defaulterUuid, recipient, BigDecimal(share, 2)))
            }
            return AlliancePayoutPlan(transfers)
        }

        internal fun principalByUuid(contract: Contract): Map<UUID, BigDecimal> {
            require(contract.type() == ContractType.ALLIANCE) { "Expected an alliance" }
            require(contract.resolutionRule() == ResolutionRule.ALL_APPROVE) { "Alliance requires unanimous approval" }
            val members = contract.participants()
            require(members.size >= 3 && members.count { it.role() == ParticipantRole.OWNER } == 1) {
                "Alliance requires one creator and at least two allies"
            }
            require(members.all { it.role() == ParticipantRole.OWNER || it.role() == ParticipantRole.ALLY }) {
                "Invalid alliance member role"
            }
            require(contract.payouts().isEmpty() && !contract.hasStoredItems()) {
                "Alliance principal plans cannot contain role-based payouts or stored items"
            }
            val stakes = LinkedHashMap<UUID, BigDecimal>()
            for (member in members) {
                val id = requireNotNull(member.uuid()) { "Alliance members must be named" }
                require(!stakes.containsKey(id)) { "Alliance member UUIDs must be unique" }
                val assets = member.stake()
                require(assets.isNotEmpty() && assets.all { it.isMoney() && it.amount().signum() > 0 }) {
                    "Alliance principal must consist of positive money assets"
                }
                val amount = try {
                    assets.fold(BigDecimal("0.00")) { sum, asset ->
                        sum.add(asset.amount().setScale(2, RoundingMode.UNNECESSARY))
                    }
                } catch (ex: ArithmeticException) {
                    throw IllegalArgumentException("Alliance principal must use whole cents", ex)
                }
                stakes[id] = amount
            }
            return stakes
        }

        private fun agreement(contract: Contract): AllianceAgreement = contract.checkedAllianceAgreement()

        private fun fundedStakes(contract: Contract): Map<UUID, BigDecimal> {
            val agreement = agreement(contract)
            require(contract.status() == ContractStatus.PENDING_ACCEPT_MULTI || agreement.allAccepted()) {
                "Active alliance is missing funded signatures"
            }
            return principalByUuid(contract).filterKeys { agreement.hasAccepted(it) }
        }
    }
}
