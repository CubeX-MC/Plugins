package org.cubexmc.regions.reward

import org.cubexmc.core.CubexLogger
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.storage.RewardFundingStore
import org.cubexmc.regions.storage.RewardFundingStore.Lease
import org.cubexmc.regions.storage.RewardFundingStore.LeaseState
import java.util.UUID

/** Crash-recoverable Regions-side coordinator; Contract remains the sole owner of money and payout state. */
class RewardFundingService(
    private val provider: RewardFundingProvider,
    private val store: RewardFundingStore,
    private val unionId: (UUID) -> String?,
    private val logger: CubexLogger,
) : RewardFundingRuntime {
    override fun check(region: RegionDefinition): FundingResult {
        val values = region.mode?.values ?: return FundingResult.ok()
        val source = values[REWARD_SOURCE]?.trim().orEmpty()
        val contractId = values[REWARD_CONTRACT]?.trim().orEmpty()
        if (source.isBlank() && contractId.isBlank()) return FundingResult.ok()
        if (!source.equals(CONTRACT_SOURCE, ignoreCase = true)) {
            return FundingResult.fail("UNSUPPORTED_SOURCE", "Only reward-source=contract is currently supported.")
        }
        if (contractId.isBlank()) return FundingResult.fail("CONTRACT_REQUIRED", "reward-contract is required.")
        if (region.mode?.type?.lowercase() !in SUPPORTED_MODES) {
            return FundingResult.fail("MODE_UNSUPPORTED", "Contract rewards currently support dual_pvp and union_war only.")
        }
        return provider.check(contractId, region.id)
    }

    @Synchronized
    override fun reserve(region: RegionDefinition): FundingResult {
        val configured = configuredContract(region) ?: return FundingResult.ok()
        store.get(region.id)?.let {
            return FundingResult.fail("OUTSTANDING_LEASE", "Region ${region.id} already has funding operation ${it.operationId}.")
        }
        val checked = check(region)
        if (!checked.successful) return checked

        val lease = Lease(region.id, configured, UUID.randomUUID().toString(), LeaseState.PREPARING)
        store.put(lease)
        if (!store.save()) return FundingResult.fail("LEASE_PERSISTENCE_FAILED")

        val locked = provider.lock(lease.operationId, configured, region.id)
        if (!locked.successful) {
            store.remove(region.id)
            store.save()
            return locked
        }
        lease.state = LeaseState.LOCKED
        store.put(lease)
        return if (store.save()) locked else FundingResult.fail("LEASE_PERSISTENCE_FAILED", "Contract is locked; restart reconciliation will refund it.")
    }

    @Synchronized
    override fun settle(region: RegionDefinition, winnerCandidates: Set<UUID>): FundingResult {
        if (configuredContract(region) == null) return FundingResult.ok()
        val lease = store.get(region.id) ?: return FundingResult.fail("LEASE_MISSING", "No reward funding lease exists.")
        val winner = if (lease.state == LeaseState.SETTLING) lease.winnerId else selectWinner(region, lease, winnerCandidates)
        if (winner == null) return FundingResult.fail("WINNER_NOT_FUNDED", "The match winner does not map to exactly one WAGER party.")

        lease.state = LeaseState.SETTLING
        lease.winnerId = winner
        store.put(lease)
        if (!store.save()) return FundingResult.fail("LEASE_PERSISTENCE_FAILED")
        val result = provider.settle(lease.operationId, lease.contractId, lease.regionId, winner)
        return if (result.successful) finalize(lease, result) else result
    }

    @Synchronized
    override fun refund(region: RegionDefinition, reason: String): FundingResult {
        if (configuredContract(region) == null) return FundingResult.ok()
        val lease = store.get(region.id) ?: return FundingResult.ok()
        return refundLease(lease, reason)
    }

    /** On startup/reload every unfinished match is aborted safely: terminal retries replay, locks refund. */
    @Synchronized
    override fun reconcile(): List<FundingResult> = store.all().map { lease ->
        when (lease.state) {
            LeaseState.PREPARING -> {
                val locked = provider.lock(lease.operationId, lease.contractId, lease.regionId)
                if (!locked.successful) locked else refundLease(lease, "restart-recovery")
            }
            LeaseState.LOCKED -> refundLease(lease, "restart-recovery")
            LeaseState.SETTLING -> {
                val winner = lease.winnerId
                if (winner == null) {
                    FundingResult.fail("LEASE_MALFORMED", "Settling lease has no winner.")
                } else {
                    val result = provider.settle(lease.operationId, lease.contractId, lease.regionId, winner)
                    if (result.successful) finalize(lease, result) else result
                }
            }
            LeaseState.REFUNDING -> refundLease(lease, lease.reason.ifBlank { "restart-recovery" })
            LeaseState.TERMINAL -> {
                store.remove(lease.regionId)
                if (store.save()) FundingResult.ok(lease.contractId) else FundingResult.fail("LEASE_PERSISTENCE_FAILED")
            }
        }
    }

    private fun selectWinner(region: RegionDefinition, lease: Lease, candidates: Set<UUID>): UUID? {
        val checked = provider.check(lease.contractId, region.id)
        if (!checked.successful) return null
        val fundedParties = listOfNotNull(checked.partyA, checked.partyB)
        val matches = if (region.mode?.type.equals("union_war", ignoreCase = true)) {
            val winningUnions = candidates.mapNotNull(unionId).toSet()
            fundedParties.filter { unionId(it) in winningUnions }
        } else {
            fundedParties.filter(candidates::contains)
        }
        return matches.distinct().singleOrNull()
    }

    private fun refundLease(lease: Lease, reason: String): FundingResult {
        lease.state = LeaseState.REFUNDING
        lease.reason = reason
        store.put(lease)
        if (!store.save()) return FundingResult.fail("LEASE_PERSISTENCE_FAILED")
        val result = provider.refund(lease.operationId, lease.contractId, lease.regionId, reason)
        return if (result.successful) finalize(lease, result) else result
    }

    private fun finalize(lease: Lease, result: FundingResult): FundingResult {
        lease.state = LeaseState.TERMINAL
        store.put(lease)
        if (!store.save()) {
            logger.warn("Funding operation ${lease.operationId} completed but its terminal lease marker could not be saved.")
            return FundingResult.fail("LEASE_PERSISTENCE_FAILED", "Contract operation completed; retry/review is required.")
        }
        store.remove(lease.regionId)
        if (!store.save()) {
            logger.warn("Terminal funding lease ${lease.operationId} remains on disk and will be cleared on restart.")
        }
        return result
    }

    private fun configuredContract(region: RegionDefinition): String? {
        val values = region.mode?.values ?: return null
        if (!values[REWARD_SOURCE].equals(CONTRACT_SOURCE, ignoreCase = true)) return null
        return values[REWARD_CONTRACT]?.trim()?.takeIf(String::isNotBlank)
    }

    private companion object {
        const val REWARD_SOURCE = "reward-source"
        const val REWARD_CONTRACT = "reward-contract"
        const val CONTRACT_SOURCE = "contract"
        val SUPPORTED_MODES = setOf("dual_pvp", "union_war")
    }
}
