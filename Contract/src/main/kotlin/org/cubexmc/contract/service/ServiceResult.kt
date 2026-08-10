package org.cubexmc.contract.service

import org.cubexmc.contract.model.Contract
import java.math.BigDecimal

class ServiceResult(
    private val success: Boolean,
    private val reason: String,
    private val contract: Contract?,
    private val amount: BigDecimal,
) {
    fun success(): Boolean = success

    fun reason(): String = reason

    fun contract(): Contract? = contract

    fun amount(): BigDecimal = amount

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ServiceResult) {
            return false
        }
        return success == other.success &&
            reason == other.reason &&
            contract == other.contract &&
            amount == other.amount
    }

    override fun hashCode(): Int {
        var result = success.hashCode()
        result = 31 * result + reason.hashCode()
        result = 31 * result + (contract?.hashCode() ?: 0)
        result = 31 * result + amount.hashCode()
        return result
    }

    override fun toString(): String =
        "ServiceResult[success=$success, reason=$reason, contract=$contract, amount=$amount]"

    companion object {
        private val ZERO: BigDecimal = BigDecimal.ZERO

        @JvmStatic
        fun ok(contract: Contract): ServiceResult = ServiceResult(true, "", contract, ZERO)

        @JvmStatic
        fun ok(contract: Contract, amount: BigDecimal?): ServiceResult =
            ServiceResult(true, "", contract, amount ?: ZERO)

        @JvmStatic
        fun fail(reason: String): ServiceResult = ServiceResult(false, reason, null, ZERO)
    }
}
