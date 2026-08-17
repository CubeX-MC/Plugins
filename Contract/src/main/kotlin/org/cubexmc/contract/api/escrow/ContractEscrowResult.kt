package org.cubexmc.contract.api.escrow

import java.math.BigDecimal
import java.util.UUID

/** Provider-owned response using only JDK types so consumers can call it through reflection. */
class ContractEscrowResult(
    private val code: ContractEscrowCode,
    private val successful: Boolean,
    private val replayed: Boolean,
    private val contractId: String?,
    private val regionId: String?,
    private val operationId: String?,
    private val partyA: UUID?,
    private val partyB: UUID?,
    private val amount: BigDecimal,
    private val detail: String,
) {
    fun code(): ContractEscrowCode = code
    fun successful(): Boolean = successful
    fun replayed(): Boolean = replayed
    fun contractId(): String? = contractId
    fun regionId(): String? = regionId
    fun operationId(): String? = operationId
    fun partyA(): UUID? = partyA
    fun partyB(): UUID? = partyB
    fun amount(): BigDecimal = amount
    fun detail(): String = detail
}
