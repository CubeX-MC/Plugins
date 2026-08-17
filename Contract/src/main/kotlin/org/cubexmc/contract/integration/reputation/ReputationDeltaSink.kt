package org.cubexmc.contract.integration.reputation

import java.util.UUID

/** Receives Contract reputation deltas without owning Contract's local reputation state. */
fun interface ReputationDeltaSink {
    fun add(playerId: UUID, fieldId: String, delta: Double)

    companion object {
        @JvmField
        val NONE = ReputationDeltaSink { _, _, _ -> }
    }
}
