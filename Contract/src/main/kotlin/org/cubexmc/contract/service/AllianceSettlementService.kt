package org.cubexmc.contract.service

import org.bukkit.entity.Player
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.economy.EconomyService
import org.cubexmc.contract.model.AllianceSettlement
import org.cubexmc.contract.model.AllianceSettlement.Outcome
import org.cubexmc.contract.model.AllianceSettlement.Phase
import org.cubexmc.contract.model.Contract
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.model.ContractType
import org.cubexmc.contract.storage.ContractStorage
import org.cubexmc.contract.storage.EventLog
import org.cubexmc.contract.storage.PendingTransactionStore
import java.math.BigDecimal
import java.util.UUID

/** All calls share ContractService's monitor with funding; no role-based payout is used. */
internal class AllianceSettlementService(
    private val plugin: ContractPlugin,
    private val storage: ContractStorage,
    private val economy: EconomyService,
    private val pending: PendingTransactionStore,
    private val events: EventLog,
) {
    fun approve(player: Player, contract: Contract): ServiceResult {
        gate(contract)?.let { return it }
        if (!player.hasPermission("contract.approve")) return fail("err-alliance-action-permission")
        if (contract.status() != ContractStatus.IN_PROGRESS) return fail("err-approve-status")
        val previous = contract.checkedAllianceAgreement()
        if (player.uniqueId !in previous.members()) return fail("err-alliance-action-member")
        if (!previous.allAccepted()) return fail("err-alliance-invalid")
        if (player.uniqueId in previous.approvals()) {
            // If preparing the journal failed after the final approval save, retry that preparation.
            return if (previous.allApproved()) settle(contract, Outcome.SUCCESS, null, player.uniqueId.toString()) else fail("err-already-approved")
        }
        val approved = previous.approve(player.uniqueId)
        try {
            events.appendRequired(contract.id(), "ALLIANCE_APPROVED", player.uniqueId.toString())
            contract.allianceAgreement(approved)
            storage.save()
        } catch (ex: Exception) {
            contract.allianceAgreement(previous)
            return review(contract.id(), ex)
        }
        return if (approved.allApproved()) settle(contract, Outcome.SUCCESS, null, player.uniqueId.toString())
        else ServiceResult.ok(contract, BigDecimal.ZERO)
    }

    fun cancel(player: Player, contract: Contract): ServiceResult {
        gate(contract)?.let { return it }
        if (!player.hasPermission("contract.cancel")) return fail("err-alliance-action-permission")
        if (!contract.checkedAllianceAgreement().hasAccepted(player.uniqueId)) return fail("err-alliance-action-member")
        return when (contract.status()) {
            ContractStatus.PENDING_ACCEPT_MULTI -> settle(contract, Outcome.REFUND, null, player.uniqueId.toString())
            ContractStatus.IN_PROGRESS -> raiseDispute(contract, player, plugin.lang().ui("dispute-cancel-request"), false)
            else -> fail("err-cancel-status")
        }
    }

    fun dispute(player: Player, contract: Contract, reason: String): ServiceResult {
        gate(contract)?.let { return it }
        if (!player.hasPermission("contract.dispute")) return fail("err-alliance-action-permission")
        if (!contract.checkedAllianceAgreement().hasAccepted(player.uniqueId)) return fail("err-alliance-action-member")
        val owner = player.uniqueId == contract.ownerUuid()
        if (!plugin.config.getBoolean(if (owner) "disputes.allow-owner-dispute" else "disputes.allow-contractor-dispute", true))
            return fail("err-alliance-action-permission")
        if (contract.status() != ContractStatus.IN_PROGRESS) return fail("err-dispute-final")
        return raiseDispute(contract, player, plugin.text().stripControl(reason), true)
    }

    private fun raiseDispute(contract: Contract, player: Player, reason: String, withdrawable: Boolean): ServiceResult {
        val previous = contract.status()
        val oldReason = contract.disputeReason()
        val oldMetadata = HashMap(contract.metadata)
        try {
            events.appendRequired(contract.id(), "ALLIANCE_DISPUTED", "${player.uniqueId}: $reason")
            if (withdrawable) {
                contract.metadata["dispute-by"] = player.uniqueId.toString()
                contract.metadata["dispute-prev-status"] = previous.name
            }
            contract.status(ContractStatus.DISPUTED)
            contract.disputeReason(reason)
            storage.save()
            return ServiceResult.ok(contract, BigDecimal.ZERO)
        } catch (ex: Exception) {
            contract.status(previous)
            contract.disputeReason(oldReason)
            contract.metadata.clear()
            contract.metadata.putAll(oldMetadata)
            return review(contract.id(), ex)
        }
    }

    fun resolve(arbiter: Player, contract: Contract, defaulter: UUID): ServiceResult {
        gate(contract)?.let { return it }
        if (!arbiter.hasPermission("contract.admin.settle")) return fail("err-alliance-action-permission")
        if (contract.status() != ContractStatus.DISPUTED) return fail("err-alliance-breach-status")
        return settle(contract, Outcome.BREACH, defaulter, arbiter.uniqueId.toString())
    }

    fun refund(contract: Contract, adminName: String): ServiceResult {
        gate(contract)?.let { return it }
        return settle(contract, Outcome.REFUND, null, adminName)
    }

    fun expire(contract: Contract): ServiceResult {
        gate(contract)?.let { return it }
        if (!contract.isExpired(System.currentTimeMillis())) return fail("err-expiry-not-applicable")
        return settle(contract, Outcome.TIMEOUT, null, "system")
    }

    private fun gate(contract: Contract): ServiceResult? {
        if (contract.type() != ContractType.ALLIANCE || storage.findById(contract.id()).orElse(null) !== contract)
            return fail("err-alliance-stale")
        if (contract.status().isFinal()) return fail("err-contract-final")
        if (held(contract)) return fail("err-alliance-settlement-blocked")
        return try { contract.checkedAllianceAgreement(); null } catch (ex: IllegalArgumentException) { fail("err-alliance-invalid") }
    }

    /** A persisted operation anchors the first plan; pending funding blocks even final retention. */
    fun held(contract: Contract): Boolean = try {
        (!contract.status().isFinal() && contract.metadata.containsKey(OPERATION)) ||
            pending.loadAll().any { it.contractId() == contract.id() }
    } catch (ex: Exception) {
        plugin.log().severe("Alliance journal unreadable; actions and retention blocked: ${ex.message}")
        true
    }

    private fun settle(contract: Contract, outcome: Outcome, defaulter: UUID?, actor: String): ServiceResult {
        val settlement = try { AllianceSettlement.create(contract, outcome, defaulter, actor) }
            catch (ex: IllegalArgumentException) { return fail("err-alliance-invalid") }
        val id = try { pending.beginAllianceSettlement(contract.id(), settlement) }
            catch (ex: Exception) { return review(contract.id(), ex) }
        return execute(contract, id, settlement)
    }

    fun recover(entry: PendingTransactionStore.PendingEntry) {
        val contract = entry.contractId()?.let { storage.findById(it).orElse(null) }
        val settlement = entry.allianceSettlement()
        if (contract == null || contract.type() != ContractType.ALLIANCE || settlement == null) {
            review(entry.id(), IllegalStateException("Missing alliance contract or allocation"))
            return
        }
        // Do not resume while funding/another intent for the same contract is still unresolved.
        if (pending.loadAll().any { it.contractId() == contract.id() && it.id() != entry.id() }) {
            review(entry.id(), IllegalStateException("Unresolved funding or conflicting settlement"))
            return
        }
        execute(contract, entry.id(), settlement)
    }

    private fun execute(contract: Contract, id: String, initial: AllianceSettlement): ServiceResult {
        var settlement = initial
        try {
            settlement.validate(contract)
            val operation = contract.metadata[OPERATION]
            require(operation == null || operation == id) { "Conflicting settlement operation" }
            if (contract.status().isFinal()) {
                require(operation == id && settlement.allPaid() && contract.status() == settlement.outcome.status &&
                    contract.completedAt() != null) { "Terminal contract does not match confirmed payments" }
                clear(id)
                return ServiceResult.ok(contract, BigDecimal.ZERO)
            }
            require(contract.status() in setOf(ContractStatus.PENDING_ACCEPT_MULTI, ContractStatus.IN_PROGRESS, ContractStatus.DISPUTED)) {
                "Invalid live alliance status"
            }
            require(contract.status() == ContractStatus.PENDING_ACCEPT_MULTI || contract.checkedAllianceAgreement().allAccepted()) {
                "Active alliance lacks funded signatures"
            }
            if (settlement.outcome == Outcome.SUCCESS) require(contract.status() == ContractStatus.IN_PROGRESS)
            if (settlement.outcome == Outcome.BREACH) require(contract.status() == ContractStatus.DISPUTED)
            if (operation == null) {
                require(settlement.payments().keys.all { settlement.phase(it) == Phase.READY }) { "Payments lack contract anchor" }
                events.appendRequired(contract.id(), "ALLIANCE_SETTLEMENT_PREPARED", "operation $id; ${settlement.toMap()}")
                contract.metadata[OPERATION] = id
                try { storage.save() } catch (ex: Exception) { contract.metadata.remove(OPERATION); throw ex }
            }
            require(!settlement.hasUncertainPayment()) { "Vault payout outcome uncertain; reconcile PAYING record" }
            for ((recipient, amount) in settlement.payments()) {
                if (settlement.phase(recipient) == Phase.PAID) continue
                // Both audit and journal are durable before calling the external provider.
                events.appendRequired(contract.id(), "ALLIANCE_PAYOUT_INTENT", "operation $id; $recipient; $amount")
                pending.advanceAlliancePayment(id, recipient, Phase.READY, Phase.PAYING)
                settlement = settlement.advance(recipient, Phase.READY, Phase.PAYING)
                val result = economy.deposit(recipient, amount)
                require(result.success()) { "Vault payout was not confirmed: ${result.reason()}" }
                pending.advanceAlliancePayment(id, recipient, Phase.PAYING, Phase.PAID)
                settlement = settlement.advance(recipient, Phase.PAYING, Phase.PAID)
            }
            val now = System.currentTimeMillis()
            events.appendRequired(contract.id(), "ALLIANCE_SETTLED", "operation $id; ${settlement.outcome}; ${settlement.payments()}")
            val oldStatus = contract.status()
            val oldCompleted = contract.completedAt()
            val oldReason = contract.disputeReason()
            contract.status(settlement.outcome.status)
            contract.completedAt(now)
            contract.disputeReason(null)
            try { storage.save() } catch (ex: Exception) {
                contract.status(oldStatus)
                contract.completedAt(oldCompleted)
                contract.disputeReason(oldReason)
                throw ex
            }
            clear(id)
            return ServiceResult.ok(contract, settlement.payments().values.fold(BigDecimal.ZERO) { sum, amount -> sum.add(amount) })
        } catch (ex: Exception) {
            // Leave the original plan, anchor and payment phases intact. No second plan can pay again.
            return review(id, ex)
        }
    }

    private fun clear(id: String) {
        try { pending.clear(id) } catch (ex: Exception) { review(id, ex) }
    }

    private fun review(id: String, ex: Exception): ServiceResult {
        plugin.log().severe("Alliance settlement $id requires review: ${ex.message}")
        return fail("err-alliance-settlement-review", mapOf("id" to id))
    }

    private fun fail(key: String, values: Map<String, String> = emptyMap()): ServiceResult = ServiceResult.fail(plugin.lang().ui(key, values))

    companion object { const val OPERATION = "alliance-settlement-op" }
}
