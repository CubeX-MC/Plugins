package org.cubexmc.ecobalancer.integrations

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.tax.TaxLedgerService
import org.cubexmc.ecobalancer.utils.EconomicMetrics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class EcoBalancerPlaceholderExpansion(private val plugin: EcoBalancer) : PlaceholderExpansion() {
    @Volatile
    private var serverStatsCache: CachedValue<TaxLedgerService.ServerTaxStats>? = null
    private val playerStatsCache: MutableMap<UUID, CachedValue<TaxLedgerService.PlayerTaxStats>> = ConcurrentHashMap()

    override fun getIdentifier(): String = "ecobal"

    override fun getAuthor(): String = plugin.description.authors.joinToString(", ")

    override fun getVersion(): String = plugin.description.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        return when (params.lowercase(Locale.ROOT)) {
            "tax_fund_balance" -> EconomicMetrics.formatLargeNumber(getServerStats().taxFundBalance)
            "tax_total_collected" -> EconomicMetrics.formatLargeNumber(getServerStats().totalTaxCollected)
            "tax_latest_collected" -> EconomicMetrics.formatLargeNumber(getServerStats().latestTaxCollected)
            "tax_latest_operation" -> getServerStats().latestOperationId.toString()
            "player_latest_tax" -> if (player == null) "0" else EconomicMetrics.formatLargeNumber(getPlayerStats(player).latestTaxPaid)
            "player_total_tax" -> if (player == null) "0" else EconomicMetrics.formatLargeNumber(getPlayerStats(player).totalTaxPaid)
            "tax_next_run" -> {
                val next = plugin.nextScheduledRunMillis
                if (next <= 0) "None" else SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(next))
            }
            "tax_active_policy" -> plugin.policyManager.getActivePolicy()?.name ?: "None"
            "tax_status" -> if (plugin.taxRunService.state.isRunning) "running" else "idle"
            "gini" -> try {
                String.format("%.4f", EconomicMetrics.calculateGini(plugin.collectAllBalances()))
            } catch (_: Throwable) {
                "0"
            }
            "top1_concentration" -> try {
                String.format("%.2f", EconomicMetrics.calculateConcentration(plugin.collectAllBalances(), 1.0))
            } catch (_: Throwable) {
                "0"
            }
            else -> null
        }
    }

    private fun getServerStats(): TaxLedgerService.ServerTaxStats {
        val now = System.currentTimeMillis()
        val cached = serverStatsCache
        if (cached != null && cached.expiresAt > now) {
            return cached.value
        }
        val stats = plugin.taxLedgerService.getServerStats()
        serverStatsCache = CachedValue(stats, now + CACHE_TTL_MILLIS)
        return stats
    }

    private fun getPlayerStats(player: OfflinePlayer): TaxLedgerService.PlayerTaxStats {
        val now = System.currentTimeMillis()
        val uuid = player.uniqueId
        val cached = playerStatsCache[uuid]
        if (cached != null && cached.expiresAt > now) {
            return cached.value
        }
        val stats = plugin.taxLedgerService.getPlayerStats(player)
        playerStatsCache[uuid] = CachedValue(stats, now + CACHE_TTL_MILLIS)
        return stats
    }

    private class CachedValue<T>(
        val value: T,
        val expiresAt: Long,
    )

    companion object {
        private const val CACHE_TTL_MILLIS = 5_000L
    }
}
