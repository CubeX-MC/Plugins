package org.cubexmc.contract.service

import org.cubexmc.contract.model.BatchSummary
import org.cubexmc.contract.model.Contract
import org.cubexmc.contract.model.ContractStatus

/** Pure batch projections shared by the hall, details view and tests. */
object BatchQueryService {
    @JvmStatic
    fun nextAvailable(contracts: Collection<Contract>, batchId: String): Contract? =
        contracts.asSequence()
            .filter { it.metadata["batch-id"] == batchId && it.status() == ContractStatus.OPEN }
            .sortedWith(compareBy<Contract> { it.expiresAt() }.thenBy { it.metadata["batch-index"]?.toIntOrNull() ?: Int.MAX_VALUE })
            .firstOrNull()

    @JvmStatic
    fun summaries(contracts: Collection<Contract>): Map<String, BatchSummary> =
        contracts.asSequence()
            .mapNotNull { contract ->
                contract.metadata["batch-id"]?.takeIf(String::isNotBlank)?.let { it to contract }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (batchId, children) -> summarize(batchId, children) }

    @JvmStatic
    fun summarize(batchId: String, children: Collection<Contract>): BatchSummary {
        require(batchId.isNotBlank()) { "batchId must not be blank" }
        require(children.isNotEmpty()) { "a batch needs at least one child contract" }
        require(children.all { it.metadata["batch-id"] == batchId }) { "all children must belong to the batch" }
        val ordered = children.sortedWith(compareBy<Contract> { it.metadata["batch-index"]?.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.createdAt() })
        val representative = ordered.first()
        val declaredSize = representative.metadata["batch-size"]?.toIntOrNull()?.takeIf { it > 0 }
        return BatchSummary(
            batchId = batchId,
            representative = representative,
            children = ordered,
            total = declaredSize ?: ordered.size,
            available = ordered.count { it.status() == ContractStatus.OPEN },
            accepted = ordered.count { it.acceptedAt() != null },
            submitted = ordered.count { it.submittedAt() != null },
            completed = ordered.count { it.status() == ContractStatus.COMPLETED },
        )
    }
}
