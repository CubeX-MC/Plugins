package org.cubexmc.contract.api.escrow

import java.util.UUID

/**
 * Optional funding boundary for a Regions match backed by an accepted Contract WAGER.
 *
 * The caller generates one stable operation id for the complete lock/settle-or-refund transaction.
 * Only that lock owner may terminate the funding, and replaying its terminal call returns the
 * recorded result rather than moving money twice.
 */
interface ContractEscrowService {
    fun check(contractId: String, regionId: String): ContractEscrowResult

    fun lock(operationId: String, contractId: String, regionId: String): ContractEscrowResult

    fun settle(operationId: String, contractId: String, regionId: String, winnerId: UUID): ContractEscrowResult

    fun refund(operationId: String, contractId: String, regionId: String, reason: String): ContractEscrowResult
}
