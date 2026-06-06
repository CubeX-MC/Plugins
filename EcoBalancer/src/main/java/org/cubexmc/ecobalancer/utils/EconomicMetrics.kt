package org.cubexmc.ecobalancer.utils

import org.bukkit.Bukkit
import kotlin.math.ceil
import kotlin.math.sqrt

object EconomicMetrics {
    @JvmStatic
    fun calculateGini(balances: List<Double>?): Double {
        if (balances.isNullOrEmpty()) {
            return 0.0
        }

        val sorted = ArrayList(balances)
        sorted.sort()

        var sum = 0.0
        var weightedSum = 0.0
        val n = sorted.size

        for (i in 0 until n) {
            val balance = sorted[i]
            sum += balance
            weightedSum += balance * (i + 1)
        }

        if (sum == 0.0) {
            return 0.0
        }

        return (2.0 * weightedSum) / (n * sum) - (n + 1.0) / n
    }

    @JvmStatic
    fun calculateConcentration(balances: List<Double>?, topPercentage: Double): Double {
        if (balances.isNullOrEmpty() || topPercentage <= 0 || topPercentage > 100) {
            return 0.0
        }

        val sorted = ArrayList(balances)
        sorted.sort()
        sorted.reverse()

        val n = sorted.size
        val topCount = maxOf(1, ceil(n * topPercentage / 100.0).toInt())

        var topSum = 0.0
        var totalSum = 0.0

        for (i in 0 until n) {
            val balance = sorted[i]
            totalSum += balance
            if (i < topCount) {
                topSum += balance
            }
        }

        return if (totalSum == 0.0) 0.0 else topSum / totalSum
    }

    @JvmStatic
    fun collectBalances(activeDays: Int?): List<Double> {
        val balances: MutableList<Double> = ArrayList()
        val players = Bukkit.getOfflinePlayers()
        val currentTime = System.currentTimeMillis()
        val cutoffTime = if (activeDays == null) 0 else currentTime - (activeDays * 24L * 60 * 60 * 1000)

        for (player in players) {
            try {
                if (activeDays != null && player.lastPlayed < cutoffTime) {
                    continue
                }
                if (VaultUtils.hasAccount(player)) {
                    val balance = VaultUtils.getBalance(player)
                    if (balance >= 0) {
                        balances.add(balance)
                    }
                }
            } catch (_: Exception) {
            }
        }

        return balances
    }

    @JvmStatic
    fun calculateTotalMoney(balances: List<Double>?): Double {
        if (balances.isNullOrEmpty()) {
            return 0.0
        }
        var total = 0.0
        for (balance in balances) {
            total += balance
        }
        return total
    }

    @JvmStatic
    fun getConcentrationLevel(concentration: Double): String =
        if (concentration >= 0.7) {
            "极度集中"
        } else if (concentration >= 0.5) {
            "高度集中"
        } else if (concentration >= 0.3) {
            "中度集中"
        } else {
            "分散"
        }

    @JvmStatic
    fun getGiniLevel(gini: Double): String =
        if (gini >= 0.6) {
            "极度不平等"
        } else if (gini >= 0.5) {
            "高度不平等"
        } else if (gini >= 0.4) {
            "中等不平等"
        } else if (gini >= 0.3) {
            "相对平等"
        } else {
            "高度平等"
        }

    @JvmStatic
    fun formatLargeNumber(number: Double): String =
        if (number >= 1_000_000_000) {
            String.format("%.2fB", number / 1_000_000_000)
        } else if (number >= 1_000_000) {
            String.format("%.2fM", number / 1_000_000)
        } else if (number >= 1_000) {
            String.format("%.2fK", number / 1_000)
        } else {
            String.format("%.2f", number)
        }

    @JvmStatic
    fun getSortedBalances(balances: List<Double>?): List<Double> {
        if (balances.isNullOrEmpty()) {
            return ArrayList()
        }
        val sorted = ArrayList(balances)
        sorted.sort()
        return sorted
    }

    @JvmStatic
    fun calculateMedian(sortedBalances: List<Double>?): Double {
        if (sortedBalances.isNullOrEmpty()) {
            return 0.0
        }
        val n = sortedBalances.size
        return if (n % 2 == 0) {
            (sortedBalances[n / 2 - 1] + sortedBalances[n / 2]) / 2.0
        } else {
            sortedBalances[n / 2]
        }
    }

    @JvmStatic
    fun calculateStdDev(balances: List<Double>?, mean: Double): Double {
        if (balances.isNullOrEmpty()) {
            return 0.0
        }
        var sumSquaredDiff = 0.0
        for (balance in balances) {
            val diff = balance - mean
            sumSquaredDiff += diff * diff
        }
        return sqrt(sumSquaredDiff / balances.size)
    }

    @JvmStatic
    fun calculateMean(balances: List<Double>?): Double {
        if (balances.isNullOrEmpty()) {
            return 0.0
        }
        return calculateTotalMoney(balances) / balances.size
    }
}
