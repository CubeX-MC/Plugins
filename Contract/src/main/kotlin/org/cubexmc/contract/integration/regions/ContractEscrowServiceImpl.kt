package org.cubexmc.contract.integration.regions

import org.cubexmc.contract.api.escrow.ContractEscrowCode
import org.cubexmc.contract.api.escrow.ContractEscrowResult
import org.cubexmc.contract.api.escrow.ContractEscrowService
import org.cubexmc.contract.model.Contract
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.model.ContractType
import org.cubexmc.contract.model.ParticipantRole
import org.cubexmc.contract.service.ServiceResult
import org.cubexmc.contract.storage.ContractStorage
import org.cubexmc.core.CubexLogger
import java.io.IOException
import java.math.BigDecimal
import java.util.UUID

internal class ContractEscrowServiceImpl(
    private val storage: ContractStorage,
    private val executor: RegionFundingExecutor,
    private val logger: CubexLogger,
) : ContractEscrowService {
    override fun check(contractId: String, regionId: String): ContractEscrowResult =
        synchronized(executor) { checkInternal(contractId, regionId) }

    private fun checkInternal(contractId: String, regionId: String): ContractEscrowResult {
        val invalid = validateInput(contractId, regionId)
        if (invalid != null) return invalid
        val contract = resolve(contractId) ?: return result(ContractEscrowCode.CONTRACT_NOT_FOUND, detail = "Contract not found.")
        return eligibility(contract, regionId)
    }

    override fun lock(operationId: String, contractId: String, regionId: String): ContractEscrowResult =
        synchronized(executor) { lockInternal(operationId, contractId, regionId) }

    private fun lockInternal(operationId: String, contractId: String, regionId: String): ContractEscrowResult {
        if (operationId.isBlank()) return result(ContractEscrowCode.INVALID_REQUEST, detail = "operationId must not be blank.")
        val checked = checkInternal(contractId, regionId)
        if (!checked.successful()) return checked
        val contract = resolve(contractId) ?: return result(ContractEscrowCode.CONTRACT_NOT_FOUND)
        val existingOperation = contract.metadata[RegionFundingMetadata.LOCK_OPERATION]
        if (existingOperation != null) {
            return if (existingOperation == operationId) response(
                ContractEscrowCode.REPLAYED,
                contract,
                regionId,
                operationId,
                successful = true,
                replayed = true,
                detail = "Funding lock already exists.",
            ) else response(
                ContractEscrowCode.LOCK_CONFLICT,
                contract,
                regionId,
                existingOperation,
                detail = "The contract is already locked by another operation.",
            )
        }

        contract.metadata[RegionFundingMetadata.REGION_ID] = regionId
        contract.metadata[RegionFundingMetadata.LOCK_OPERATION] = operationId
        try {
            storage.save()
        } catch (ex: IOException) {
            contract.metadata.remove(RegionFundingMetadata.REGION_ID)
            contract.metadata.remove(RegionFundingMetadata.LOCK_OPERATION)
            return response(ContractEscrowCode.PERSISTENCE_FAILED, contract, regionId, operationId, detail = ex.message.orEmpty())
        }
        runCatching {
            executor.recordLock(contract, regionId, operationId)
            storage.save()
        }.onFailure { logger.warn("Region funding lock was persisted but its audit event could not be saved.", it) }
        return response(ContractEscrowCode.OK, contract, regionId, operationId, successful = true)
    }

    override fun settle(
        operationId: String,
        contractId: String,
        regionId: String,
        winnerId: UUID,
    ): ContractEscrowResult = synchronized(executor) {
        val contract = resolve(contractId) ?: return result(ContractEscrowCode.CONTRACT_NOT_FOUND)
        val partyA = contract.participant(ParticipantRole.PARTY_A).map { it.uuid() }.orElse(null)
        val partyB = contract.participant(ParticipantRole.PARTY_B).map { it.uuid() }.orElse(null)
        val action = when (winnerId) {
            partyA -> "PARTY_A_WINS"
            partyB -> "PARTY_B_WINS"
            else -> return response(
                ContractEscrowCode.NOT_ELIGIBLE,
                contract,
                regionId,
                operationId,
                detail = "Winner is not a funded WAGER party.",
            )
        }
        return terminal(operationId, contract, regionId, action) {
            executor.settle(contract, winnerId, operationId, regionId)
        }
    }

    override fun refund(
        operationId: String,
        contractId: String,
        regionId: String,
        reason: String,
    ): ContractEscrowResult = synchronized(executor) {
        val contract = resolve(contractId) ?: return result(ContractEscrowCode.CONTRACT_NOT_FOUND)
        return terminal(operationId, contract, regionId, "REFUND") {
            executor.refund(contract, operationId, regionId, reason)
        }
    }

    private fun terminal(
        operationId: String,
        contract: Contract,
        regionId: String,
        action: String,
        execute: () -> ServiceResult,
    ): ContractEscrowResult {
        if (operationId.isBlank() || regionId.isBlank()) {
            return response(ContractEscrowCode.INVALID_REQUEST, contract, regionId, operationId)
        }
        val lockedRegion = contract.metadata[RegionFundingMetadata.REGION_ID]
        if (lockedRegion != regionId) {
            return response(ContractEscrowCode.LOCK_CONFLICT, contract, regionId, operationId, detail = "Contract is not locked to this region.")
        }
        val lockOperation = contract.metadata[RegionFundingMetadata.LOCK_OPERATION]
        if (lockOperation != operationId) {
            return response(
                ContractEscrowCode.OPERATION_CONFLICT,
                contract,
                regionId,
                lockOperation,
                detail = "Terminal operation does not own the funding lock.",
            )
        }
        val previousOperation = contract.metadata[RegionFundingMetadata.TERMINAL_OPERATION]
        if (previousOperation != null) {
            if (previousOperation != operationId || contract.metadata[RegionFundingMetadata.TERMINAL_ACTION] != action) {
                return response(ContractEscrowCode.OPERATION_CONFLICT, contract, regionId, previousOperation)
            }
            val state = contract.metadata[RegionFundingMetadata.TERMINAL_STATE]
            return if (state == RegionFundingMetadata.COMPLETE) {
                response(
                    ContractEscrowCode.REPLAYED,
                    contract,
                    regionId,
                    operationId,
                    successful = true,
                    replayed = true,
                    amount = storedAmount(contract),
                )
            } else {
                response(ContractEscrowCode.REVIEW_REQUIRED, contract, regionId, operationId, detail = "A previous terminal attempt requires review.")
            }
        }
        if (contract.status() != ContractStatus.IN_PROGRESS) {
            val code = if (contract.status().isFinal() || contract.status() == ContractStatus.DISPUTED) {
                ContractEscrowCode.REVIEW_REQUIRED
            } else {
                ContractEscrowCode.NOT_ELIGIBLE
            }
            return response(code, contract, regionId, operationId, detail = "WAGER is not in progress; inspect its current settlement state.")
        }

        setTerminalMetadata(contract, operationId, action, RegionFundingMetadata.PROCESSING)
        try {
            storage.save()
        } catch (ex: IOException) {
            clearTerminalMetadata(contract)
            return response(ContractEscrowCode.PERSISTENCE_FAILED, contract, regionId, operationId, detail = ex.message.orEmpty())
        }

        val settlement = execute()
        if (!settlement.success()) {
            return if (contract.status() == ContractStatus.IN_PROGRESS) {
                clearTerminalMetadata(contract)
                runCatching { storage.save() }
                    .onFailure { logger.warn("Could not clear failed region funding operation $operationId.", it) }
                response(ContractEscrowCode.SETTLEMENT_FAILED, contract, regionId, operationId, detail = settlement.reason())
            } else {
                contract.metadata[RegionFundingMetadata.TERMINAL_STATE] = RegionFundingMetadata.REVIEW
                runCatching { storage.save() }
                    .onFailure { logger.warn("Could not persist review state for region funding operation $operationId.", it) }
                response(ContractEscrowCode.REVIEW_REQUIRED, contract, regionId, operationId, detail = settlement.reason())
            }
        }

        contract.metadata[RegionFundingMetadata.TERMINAL_STATE] = RegionFundingMetadata.COMPLETE
        contract.metadata[RegionFundingMetadata.TERMINAL_AMOUNT] = settlement.amount().toPlainString()
        return try {
            storage.save()
            response(
                ContractEscrowCode.OK,
                contract,
                regionId,
                operationId,
                successful = true,
                amount = settlement.amount(),
            )
        } catch (ex: IOException) {
            contract.metadata[RegionFundingMetadata.TERMINAL_STATE] = RegionFundingMetadata.REVIEW
            response(
                ContractEscrowCode.REVIEW_REQUIRED,
                contract,
                regionId,
                operationId,
                detail = "Payout completed but its idempotency result could not be persisted: ${ex.message.orEmpty()}",
            )
        }
    }

    private fun eligibility(contract: Contract, regionId: String): ContractEscrowResult {
        if (contract.type() != ContractType.WAGER || contract.status() != ContractStatus.IN_PROGRESS) {
            return response(ContractEscrowCode.NOT_ELIGIBLE, contract, regionId, detail = "Only accepted, in-progress WAGER contracts are eligible.")
        }
        val partyA = contract.participant(ParticipantRole.PARTY_A).map { it.uuid() }.orElse(null)
        val partyB = contract.participant(ParticipantRole.PARTY_B).map { it.uuid() }.orElse(null)
        if (partyA == null || partyB == null) {
            return response(ContractEscrowCode.NOT_ELIGIBLE, contract, regionId, detail = "Both WAGER parties must be present.")
        }
        val lockedRegion = contract.metadata[RegionFundingMetadata.REGION_ID]
        if (lockedRegion != null && lockedRegion != regionId) {
            return response(ContractEscrowCode.LOCK_CONFLICT, contract, regionId, detail = "Contract is locked to region $lockedRegion.")
        }
        return response(
            ContractEscrowCode.OK,
            contract,
            regionId,
            contract.metadata[RegionFundingMetadata.LOCK_OPERATION],
            successful = true,
        )
    }

    private fun validateInput(contractId: String, regionId: String): ContractEscrowResult? =
        if (contractId.isBlank() || regionId.isBlank()) {
            result(ContractEscrowCode.INVALID_REQUEST, detail = "contractId and regionId must not be blank.")
        } else null

    private fun resolve(contractId: String): Contract? =
        storage.findById(contractId).orElseGet { storage.findByPrefix(contractId).orElse(null) }

    private fun setTerminalMetadata(contract: Contract, operationId: String, action: String, state: String) {
        contract.metadata[RegionFundingMetadata.TERMINAL_OPERATION] = operationId
        contract.metadata[RegionFundingMetadata.TERMINAL_ACTION] = action
        contract.metadata[RegionFundingMetadata.TERMINAL_STATE] = state
    }

    private fun clearTerminalMetadata(contract: Contract) {
        contract.metadata.remove(RegionFundingMetadata.TERMINAL_OPERATION)
        contract.metadata.remove(RegionFundingMetadata.TERMINAL_ACTION)
        contract.metadata.remove(RegionFundingMetadata.TERMINAL_STATE)
        contract.metadata.remove(RegionFundingMetadata.TERMINAL_AMOUNT)
    }

    private fun storedAmount(contract: Contract): BigDecimal =
        contract.metadata[RegionFundingMetadata.TERMINAL_AMOUNT]?.toBigDecimalOrNull() ?: BigDecimal.ZERO

    private fun response(
        code: ContractEscrowCode,
        contract: Contract,
        regionId: String,
        operationId: String? = null,
        successful: Boolean = false,
        replayed: Boolean = false,
        amount: BigDecimal = BigDecimal.ZERO,
        detail: String = "",
    ): ContractEscrowResult = ContractEscrowResult(
        code,
        successful,
        replayed,
        contract.id(),
        regionId,
        operationId,
        contract.participant(ParticipantRole.PARTY_A).map { it.uuid() }.orElse(null),
        contract.participant(ParticipantRole.PARTY_B).map { it.uuid() }.orElse(null),
        amount,
        detail,
    )

    private fun result(code: ContractEscrowCode, detail: String = ""): ContractEscrowResult =
        ContractEscrowResult(code, false, false, null, null, null, null, null, BigDecimal.ZERO, detail)
}

internal interface RegionFundingExecutor {
    fun recordLock(contract: Contract, regionId: String, operationId: String)

    fun settle(contract: Contract, winnerId: UUID, operationId: String, regionId: String): ServiceResult

    fun refund(contract: Contract, operationId: String, regionId: String, reason: String): ServiceResult
}
