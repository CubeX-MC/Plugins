package org.cubexmc.contract.api.escrow

import java.util.UUID

enum class ContractEscrowCode { OK }

class ContractEscrowResult(
    private val successful: Boolean,
    private val contractId: String?,
    private val partyA: UUID?,
    private val partyB: UUID?,
) {
    fun successful(): Boolean = successful
    fun code(): ContractEscrowCode = ContractEscrowCode.OK
    fun detail(): String = ""
    fun contractId(): String? = contractId
    fun partyA(): UUID? = partyA
    fun partyB(): UUID? = partyB
}

interface ContractEscrowService {
    fun check(contractId: String, regionId: String): ContractEscrowResult
    fun lock(operationId: String, contractId: String, regionId: String): ContractEscrowResult
    fun settle(operationId: String, contractId: String, regionId: String, winnerId: UUID): ContractEscrowResult
    fun refund(operationId: String, contractId: String, regionId: String, reason: String): ContractEscrowResult
}
