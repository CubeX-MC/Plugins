package org.cubexmc.contract.service

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.economy.EconomyService
import org.cubexmc.contract.model.AllianceAgreement
import org.cubexmc.contract.model.Asset
import org.cubexmc.contract.model.Contract
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.model.ContractType
import org.cubexmc.contract.model.Participant
import org.cubexmc.contract.model.ParticipantRole
import org.cubexmc.contract.storage.ContractStorage
import org.cubexmc.contract.storage.EventLog
import org.cubexmc.contract.storage.PendingTransactionStore
import org.cubexmc.contract.storage.PendingTransactionStore.FundingPhase
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** Called only under ContractService's monitor. This slice funds signatures; it never settles. */
internal class AllianceFundingService(
    private val plugin: ContractPlugin,
    private val storage: ContractStorage,
    private val economy: EconomyService,
    private val pending: PendingTransactionStore,
    private val eventLog: EventLog,
) {
    fun create(creator: Player, creatorStake: BigDecimal, allyStakes: Map<String, BigDecimal>, days: Int,
               title: String, description: String): ServiceResult {
        if (!creator.hasPermission("contract.create")) return fail("err-alliance-create-permission")
        if (blocked(creator.uniqueId, null)) return fail("err-alliance-funding-blocked")
        val config = plugin.config
        val cleanTitle = plugin.text().stripControl(title)
        if (cleanTitle.isBlank()) return fail("err-title-empty")
        val maxTitle = config.getInt("limits.max-title-length", 80)
        if (cleanTitle.length > maxTitle) return fail("err-title-too-long", mapOf("max" to maxTitle.toString()))
        val cleanDescription = plugin.text().stripControl(description).ifBlank { cleanTitle }
        val maxDescription = config.getInt("limits.max-description-length", 500)
        if (cleanDescription.length > maxDescription) return fail("err-alliance-description-length", mapOf("max" to maxDescription.toString()))
        val minDays = config.getInt("limits.min-deadline-days", 1)
        val maxDays = config.getInt("limits.max-deadline-days", 7)
        if (days < minDays || days > maxDays || days <= 0) return fail("err-days-range", mapOf("min" to minDays.toString(), "max" to maxDays.toString()))
        if (allyStakes.size < 2) return fail("err-alliance-roster")
        val ownerAmount = validStake(creatorStake) ?: return fail("err-alliance-stake")
        val ids = hashSetOf(creator.uniqueId)
        val allies = ArrayList<Participant>()
        for ((name, rawAmount) in allyStakes) {
            val amount = validStake(rawAmount) ?: return fail("err-alliance-stake")
            if (name.isBlank()) return fail("err-alliance-roster")
            val ally = Bukkit.getOfflinePlayer(name)
            if (!ally.isOnline && !ally.hasPlayedBefore()) return fail("err-player-not-found", mapOf("name" to name))
            if (!ids.add(ally.uniqueId)) return fail("err-alliance-roster")
            allies.add(Participant(ParticipantRole.ALLY, ally.uniqueId, ally.name ?: name, listOf(Asset.money(amount))))
        }
        val openCount = storage.all().count { it.ownerUuid() == creator.uniqueId && it.status().countsAsOwnerActive() }
        if (openCount >= config.getInt("limits.max-open-contracts", 3)) return fail("err-alliance-open-limit")
        val now = System.currentTimeMillis()
        val contract = Contract.createAlliance(UUID.randomUUID().toString(),
            Participant(ParticipantRole.OWNER, creator.uniqueId, creator.name, listOf(Asset.money(ownerAmount))),
            allies, cleanTitle, cleanDescription, now, now + days * 86_400_000L)
        return fund(creator, contract, ownerAmount, contract.checkedAllianceAgreement(), true)
    }

    fun accept(player: Player, contract: Contract): ServiceResult {
        if (!player.hasPermission("contract.accept")) return fail("err-alliance-accept-permission")
        if (storage.findById(contract.id()).orElse(null) !== contract) return fail("err-alliance-stale")
        if (contract.type() != ContractType.ALLIANCE || contract.status() != ContractStatus.PENDING_ACCEPT_MULTI) return fail("err-alliance-not-pending")
        val agreement = try { contract.checkedAllianceAgreement() } catch (ex: IllegalArgumentException) { return fail("err-alliance-invalid") }
        val member = contract.participantByUuid(player.uniqueId).orElse(null)
        if (member == null || member.role() != ParticipantRole.ALLY) return fail("err-alliance-member-only")
        if (agreement.hasAccepted(player.uniqueId)) return fail("err-alliance-already-signed")
        val now = System.currentTimeMillis()
        if (now >= contract.expiresAt()) return fail("err-expired")
        if (blocked(player.uniqueId, contract.id())) return fail("err-alliance-funding-blocked")
        val activeCount = storage.all().count {
            if (it.status().isFinal()) false
            else if (it.type() == ContractType.ALLIANCE) it.ownerUuid() != player.uniqueId && it.allianceAgreement()?.hasAccepted(player.uniqueId) == true
            else it.contractorUuid() == player.uniqueId && it.status().countsAsContractorActive()
        }
        if (activeCount >= plugin.config.getInt("limits.max-active-accepted-contracts", 3)) return fail("err-alliance-active-limit")
        val signed = try { agreement.accept(player.uniqueId, now) } catch (ex: IllegalArgumentException) { return fail("err-alliance-invalid") }
        return fund(player, contract, member.moneyStake(), signed, false)
    }

    private fun fund(player: Player, contract: Contract, amount: BigDecimal, signed: AllianceAgreement, creating: Boolean): ServiceResult {
        if (!economy.has(player, amount)) return fail("err-insufficient-funds", mapOf("value" to economy.format(amount)))
        val purpose = if (creating) "alliance-create" else "alliance-accept"
        val operation = try { pending.beginAllianceWithdraw(player.uniqueId, amount, purpose, contract.id()) }
            catch (ex: Exception) {
                plugin.log().severe("Could not prepare alliance funding: ${ex.message}")
                return fail("err-alliance-journal")
            }
        val withdrawal = try { economy.withdraw(player, amount) } catch (ex: Exception) {
            review(operation, "Vault withdraw outcome unknown: ${ex.message}")
            return reviewFailure(operation)
        }
        if (!withdrawal.success()) {
            if (!advance(operation, FundingPhase.PREPARED, FundingPhase.REJECTED)) return reviewFailure(operation)
            clear(operation)
            return fail("err-withdraw-failed", mapOf("reason" to withdrawal.reason()))
        }
        if (!advance(operation, FundingPhase.PREPARED, FundingPhase.WITHDRAWN)) return reviewFailure(operation)

        val previous = contract.checkedAllianceAgreement()
        val previousStatus = contract.status()
        val previousAcceptedAt = contract.acceptedAt()
        val key = operationKey(player.uniqueId)
        val previousOperation = contract.metadata[key]
        try {
            contract.allianceAgreement(signed)
            contract.metadata[key] = operation
            if (signed.allAccepted()) {
                contract.status(ContractStatus.IN_PROGRESS)
                contract.acceptedAt(signed.signatures().values.maxOrNull())
            }
            if (creating) storage.put(contract)
            storage.save()
        } catch (ex: Exception) {
            if (creating) storage.remove(contract.id())
            contract.allianceAgreement(previous)
            contract.status(previousStatus)
            contract.acceptedAt(previousAcceptedAt)
            if (previousOperation == null) contract.metadata.remove(key) else contract.metadata[key] = previousOperation
            review(operation, "Signature save failed: ${ex.message}")
            if (!refund(operation, player.uniqueId, amount)) return reviewFailure(operation)
            return fail("err-alliance-save-refunded")
        }
        clear(operation)
        val detail = "${player.uniqueId} funded ${amount.toPlainString()}; operation $operation"
        contract.addEvent(System.currentTimeMillis(), "ALLIANCE_FUNDED", detail)
        storage.markDirty()
        eventLog.append(contract.id(), "ALLIANCE_FUNDED", detail)
        return ServiceResult.ok(contract, amount)
    }

    /** A persisted matching signature is the commit record, regardless of the global status. */
    fun recover(entry: PendingTransactionStore.PendingEntry) {
        val id = entry.id()
        val player = entry.playerUuid()
        val contractId = entry.contractId()
        val phase = entry.fundingPhase()
        if (player == null || contractId.isNullOrBlank() || phase == null) {
            review(id, "Incomplete funding entry")
            return
        }
        val contract = storage.findById(contractId).orElse(null)
        if (contract != null) {
            val agreement = try { contract.checkedAllianceAgreement() } catch (ex: IllegalArgumentException) {
                review(id, "Contract does not have valid alliance terms")
                return
            }
            val member = contract.participantByUuid(player).orElse(null)
            val expectedRole = if (entry.purpose() == "alliance-create") ParticipantRole.OWNER else ParticipantRole.ALLY
            if (member == null || member.role() != expectedRole || member.moneyStake().compareTo(entry.amount()) != 0) {
                review(id, "Funding identity or amount does not match terms")
                return
            }
            if (agreement.hasAccepted(player)) {
                if (contract.metadata[operationKey(player)] == id && phase == FundingPhase.WITHDRAWN) clear(id)
                else review(id, "Signature/operation/phase conflict; not refunding committed escrow")
                return
            }
            if (contract.metadata.containsKey(operationKey(player)) || contract.status().isFinal()) {
                review(id, "Unsigned funding record conflicts with contract state")
                return
            }
        } else if (entry.purpose() != "alliance-create") {
            review(id, "Acceptance references a missing contract")
            return
        }
        when (phase) {
            FundingPhase.WITHDRAWN -> refund(id, player, entry.amount())
            FundingPhase.REFUNDED, FundingPhase.REJECTED -> clear(id)
            FundingPhase.PREPARED, FundingPhase.REFUNDING -> review(id, "Uncertain Vault outcome in $phase; automatic replay blocked")
        }
    }

    private fun refund(operation: String, player: UUID, amount: BigDecimal): Boolean {
        if (!advance(operation, FundingPhase.WITHDRAWN, FundingPhase.REFUNDING)) return false
        val result = try { economy.deposit(player, amount) } catch (ex: Exception) {
            review(operation, "Refund outcome unknown: ${ex.message}")
            return false
        }
        if (!result.success()) {
            // A definite rejection has no external effect, so a future recovery may retry.
            advance(operation, FundingPhase.REFUNDING, FundingPhase.WITHDRAWN)
            review(operation, "Refund rejected: ${result.reason()}")
            return false
        }
        if (!advance(operation, FundingPhase.REFUNDING, FundingPhase.REFUNDED)) return false
        clear(operation)
        return true
    }

    private fun advance(operation: String, from: FundingPhase, to: FundingPhase): Boolean = try {
        pending.advanceFunding(operation, from, to)
        true
    } catch (ex: Exception) {
        review(operation, "Could not persist $from -> $to: ${ex.message}")
        false
    }

    private fun clear(operation: String) {
        try { pending.clear(operation) } catch (ex: Exception) { review(operation, "Could not clear resolved entry: ${ex.message}") }
    }

    private fun blocked(player: UUID, contractId: String?): Boolean = try {
        pending.loadAll().any { PendingTransactionStore.isAllianceFunding(it.purpose()) &&
            (it.playerUuid() == player || contractId != null && it.contractId() == contractId) }
    } catch (ex: Exception) {
        plugin.log().severe("Cannot read funding journal; alliance actions blocked: ${ex.message}")
        true
    }

    private fun validStake(amount: BigDecimal): BigDecimal? {
        val normalized = try { amount.setScale(2, RoundingMode.UNNECESSARY) } catch (ex: ArithmeticException) { return null }
        val minimum = BigDecimal.valueOf(plugin.config.getDouble("economy.min-reward", 100.0))
        val maximum = BigDecimal.valueOf(plugin.config.getDouble("economy.max-reward", 100000.0))
        return normalized.takeIf { it.signum() > 0 && it >= minimum && it <= maximum }
    }

    private fun operationKey(player: UUID): String = "alliance-funding-op-$player"
    private fun review(id: String, reason: String) { plugin.log().severe("Alliance funding $id requires review: $reason") }
    private fun reviewFailure(id: String): ServiceResult = fail("err-alliance-funding-review", mapOf("id" to id))
    private fun fail(key: String, values: Map<String, String> = emptyMap()): ServiceResult = ServiceResult.fail(plugin.lang().ui(key, values))
}
