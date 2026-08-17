package org.cubexmc.regions.reward

import java.util.UUID

data class FundingResult(
    val successful: Boolean,
    val code: String,
    val detail: String = "",
    val contractId: String? = null,
    val partyA: UUID? = null,
    val partyB: UUID? = null,
) {
    companion object {
        fun ok(contractId: String? = null, partyA: UUID? = null, partyB: UUID? = null): FundingResult =
            FundingResult(true, "OK", contractId = contractId, partyA = partyA, partyB = partyB)

        fun fail(code: String, detail: String = ""): FundingResult = FundingResult(false, code, detail)
    }
}
