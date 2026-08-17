package org.cubexmc.contract.integrations

import java.math.BigDecimal
import java.util.Locale
import java.util.UUID
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.model.Contract
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.model.ContractType

/**
 * Read-only PlaceholderAPI surface over the contract store.
 *
 * Placeholders are resolved from an in-memory snapshot, so nothing here touches escrow or writes
 * state. Results are cached briefly because scoreboard plugins re-request them every tick.
 */
class ContractPlaceholderExpansion(private val plugin: ContractPlugin) : PlaceholderExpansion() {
    @Volatile
    private var snapshot: CachedSnapshot? = null

    override fun getIdentifier(): String = "contracts"

    override fun getAuthor(): String = plugin.description.authors.joinToString(", ")

    override fun getVersion(): String = plugin.description.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val contracts = snapshot()
        val playerId = player?.uniqueId
        return when (params.lowercase(Locale.ROOT)) {
            "open_count" -> contracts.count { it.status() == ContractStatus.OPEN }.toString()
            "total_count" -> contracts.size.toString()
            "my_active" -> playerId?.let { id -> contracts.count { isActiveFor(it, id) } }?.toString() ?: "0"
            "my_pending_wager" -> playerId?.let { id ->
                contracts.count {
                    it.type() == ContractType.WAGER && it.status() == ContractStatus.PENDING_ACCEPT && involves(it, id)
                }
            }?.toString() ?: "0"
            "my_open_posted" -> playerId?.let { id ->
                contracts.count { it.status() == ContractStatus.OPEN && it.ownerUuid() == id }
            }?.toString() ?: "0"
            "my_disputed" -> playerId?.let { id ->
                contracts.count { it.status() == ContractStatus.DISPUTED && involves(it, id) }
            }?.toString() ?: "0"
            "open_reward_total" -> contracts
                .filter { it.status() == ContractStatus.OPEN }
                .fold(BigDecimal.ZERO) { total, contract -> total.add(contract.reward()) }
                .toPlainString()
            else -> null
        }
    }

    /** Active means the contract still needs something from this player, in either role. */
    private fun isActiveFor(contract: Contract, playerId: UUID): Boolean = when (playerId) {
        contract.ownerUuid() -> contract.status().countsAsOwnerActive()
        contract.contractorUuid() -> contract.status().countsAsContractorActive()
        else -> false
    }

    private fun involves(contract: Contract, playerId: UUID): Boolean =
        contract.participants().any { it.uuid() == playerId }

    private fun snapshot(): List<Contract> {
        val now = System.currentTimeMillis()
        snapshot?.takeIf { it.expiresAt > now }?.let { return it.contracts }
        val contracts = plugin.storage().all()
        snapshot = CachedSnapshot(contracts, now + CACHE_TTL_MILLIS)
        return contracts
    }

    private class CachedSnapshot(val contracts: List<Contract>, val expiresAt: Long)

    private companion object {
        const val CACHE_TTL_MILLIS = 2000L
    }
}
