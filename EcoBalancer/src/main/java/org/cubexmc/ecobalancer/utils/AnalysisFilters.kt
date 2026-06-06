package org.cubexmc.ecobalancer.utils

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import java.util.Locale
import kotlin.math.ceil

object AnalysisFilters {
    class FilterCriteria {
        @JvmField var activeWithinDays: Int? = null
        @JvmField var minPlaytimeHours: Int? = null
        @JvmField var minBalance: Double? = null
        @JvmField var maxBalance: Double? = null
        @JvmField var minPercentile: Double? = null
        @JvmField var maxPercentile: Double? = null
    }

    class ParseResult(
        @JvmField val criteria: FilterCriteria,
        @JvmField val remainingArgs: List<String>,
    )

    @JvmStatic
    fun parse(args: Array<String>?): ParseResult {
        val criteria = FilterCriteria()
        val rest: MutableList<String> = ArrayList()
        if (args != null) {
            for (arg in args) {
                val idx = arg.indexOf(':')
                if (idx <= 0 || idx == arg.length - 1) {
                    rest.add(arg)
                    continue
                }
                val key = arg.substring(0, idx).lowercase(Locale.ROOT)
                val value = arg.substring(idx + 1)
                try {
                    when (key) {
                        "d" -> {
                            val n = value.toInt()
                            if (n > 0) criteria.activeWithinDays = n
                        }
                        "p" -> {
                            val n = value.toInt()
                            if (n >= 0) criteria.minPlaytimeHours = n
                        }
                        "l" -> criteria.minBalance = value.toDouble()
                        "u" -> criteria.maxBalance = value.toDouble()
                        "lr" -> {
                            var p = value.toDouble()
                            if (p < 0) p = 0.0
                            if (p > 100) p = 100.0
                            criteria.minPercentile = p
                        }
                        "ur" -> {
                            var p = value.toDouble()
                            if (p < 0) p = 0.0
                            if (p > 100) p = 100.0
                            criteria.maxPercentile = p
                        }
                        else -> rest.add(arg)
                    }
                } catch (_: NumberFormatException) {
                    rest.add(arg)
                }
            }
        }
        return ParseResult(criteria, rest)
    }

    @JvmStatic
    fun collectFilteredBalances(criteria: FilterCriteria?, statsWorldName: String?): List<Double> {
        val activeCriteria = criteria ?: FilterCriteria()

        val candidates: MutableList<PlayerEntry> = ArrayList()
        val players = Bukkit.getOfflinePlayers()
        val now = System.currentTimeMillis()
        val activeWithinDays = activeCriteria.activeWithinDays
        val cutoff = if (activeWithinDays == null) {
            Long.MIN_VALUE
        } else {
            now - activeWithinDays * 24L * 60 * 60 * 1000
        }

        for (player in players) {
            try {
                if (activeWithinDays != null && player.lastPlayed < cutoff) continue
                if (!VaultUtils.hasAccount(player)) continue

                val minPlaytimeHours = activeCriteria.minPlaytimeHours
                if (minPlaytimeHours != null) {
                    PlaytimeUtils.ensureLoadedFor(player.uniqueId, statsWorldName)
                    val hours = PlaytimeUtils.getPlaytimeHours(player.uniqueId)
                    if (hours < minPlaytimeHours) continue
                }

                val balance = VaultUtils.getBalance(player)
                if (balance < 0) continue
                candidates.add(PlayerEntry(player, balance))
            } catch (throwable: Throwable) {
                Bukkit.getLogger().fine("[EcoBalancer] Failed to process filtered balance candidate: ${throwable.message}")
            }
        }

        var minBound = activeCriteria.minBalance ?: Double.NEGATIVE_INFINITY
        var maxBound = activeCriteria.maxBalance ?: Double.POSITIVE_INFINITY

        val minPercentile = activeCriteria.minPercentile
        val maxPercentile = activeCriteria.maxPercentile
        if (minPercentile != null || maxPercentile != null) {
            val sorted: MutableList<Double> = ArrayList()
            for (entry in candidates) sorted.add(entry.balance)
            sorted.sort()
            if (minPercentile != null) {
                val lb = percentileValue(sorted, minPercentile)
                if (lb > minBound) minBound = lb
            }
            if (maxPercentile != null) {
                val ub = percentileValue(sorted, maxPercentile)
                if (ub < maxBound) maxBound = ub
            }
        }

        if (minBound > maxBound) return emptyList()

        val result: MutableList<Double> = ArrayList()
        for (entry in candidates) {
            if (entry.balance >= minBound && entry.balance <= maxBound) result.add(entry.balance)
        }
        return result
    }

    @JvmStatic
    fun collectFilteredPlayers(criteria: FilterCriteria?, statsWorldName: String?): List<OfflinePlayer> {
        val activeCriteria = criteria ?: FilterCriteria()

        val candidates: MutableList<OfflinePlayer> = ArrayList()
        val candidateBalances: MutableList<Double> = ArrayList()
        val players = Bukkit.getOfflinePlayers()
        val now = System.currentTimeMillis()
        val activeWithinDays = activeCriteria.activeWithinDays
        val cutoff = if (activeWithinDays == null) {
            Long.MIN_VALUE
        } else {
            now - activeWithinDays * 24L * 60 * 60 * 1000
        }

        for (player in players) {
            try {
                if (activeWithinDays != null && player.lastPlayed < cutoff) continue
                if (!VaultUtils.hasAccount(player)) continue
                val minPlaytimeHours = activeCriteria.minPlaytimeHours
                if (minPlaytimeHours != null) {
                    PlaytimeUtils.ensureLoadedFor(player.uniqueId, statsWorldName)
                    val hours = PlaytimeUtils.getPlaytimeHours(player.uniqueId)
                    if (hours < minPlaytimeHours) continue
                }
                val balance = VaultUtils.getBalance(player)
                if (balance < 0) continue
                candidates.add(player)
                candidateBalances.add(balance)
            } catch (throwable: Throwable) {
                Bukkit.getLogger().fine("[EcoBalancer] Failed to process filtered player candidate: ${throwable.message}")
            }
        }

        var minBound = activeCriteria.minBalance ?: Double.NEGATIVE_INFINITY
        var maxBound = activeCriteria.maxBalance ?: Double.POSITIVE_INFINITY

        val minPercentile = activeCriteria.minPercentile
        val maxPercentile = activeCriteria.maxPercentile
        if (minPercentile != null || maxPercentile != null) {
            val sorted = ArrayList(candidateBalances)
            sorted.sort()
            if (minPercentile != null) {
                val lb = percentileValue(sorted, minPercentile)
                if (lb > minBound) minBound = lb
            }
            if (maxPercentile != null) {
                val ub = percentileValue(sorted, maxPercentile)
                if (ub < maxBound) maxBound = ub
            }
        }

        if (minBound > maxBound) return emptyList()

        val result: MutableList<OfflinePlayer> = ArrayList()
        for (i in candidates.indices) {
            val balance = candidateBalances[i]
            if (balance >= minBound && balance <= maxBound) result.add(candidates[i])
        }
        return result
    }

    private fun percentileValue(sorted: List<Double>?, p: Double): Double {
        if (sorted.isNullOrEmpty()) return 0.0
        if (p <= 0) return sorted[0]
        if (p >= 100) return sorted[sorted.size - 1]
        val n = sorted.size
        var rank = ceil((p / 100.0) * n).toInt()
        rank = maxOf(1, minOf(rank, n))
        return sorted[rank - 1]
    }

    private class PlayerEntry(
        @Suppress("unused") val player: OfflinePlayer,
        val balance: Double,
    )
}
