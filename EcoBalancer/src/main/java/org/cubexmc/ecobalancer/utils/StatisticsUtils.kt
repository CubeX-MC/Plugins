package org.cubexmc.ecobalancer.utils

import org.bukkit.Bukkit
import kotlin.math.pow
import kotlin.math.sqrt

object StatisticsUtils {
    @JvmStatic
    fun calculateMedian(values: List<Double>?): Double {
        if (values.isNullOrEmpty()) {
            return 0.0
        }

        val sortedValues = ArrayList(values)
        sortedValues.sort()

        val size = sortedValues.size
        return if (size % 2 == 0) {
            (sortedValues[size / 2 - 1] + sortedValues[size / 2]) / 2
        } else {
            sortedValues[size / 2]
        }
    }

    @JvmStatic
    fun calculateStandardDeviation(values: List<Double>?, mean: Double): Double {
        if (values.isNullOrEmpty()) {
            return 0.0
        }

        var sum = 0.0
        for (value in values) {
            sum += (value - mean).pow(2.0)
        }

        return sqrt(sum / values.size)
    }

    @JvmStatic
    fun calculatePercentile(balance: Double, values: List<Double>?): Double {
        if (values.isNullOrEmpty()) {
            return 0.0
        }

        val totalPlayers = values.size
        val playersBelow = values.count { it < balance }

        return playersBelow.toDouble() / totalPlayers * 100
    }

    @JvmStatic
    fun formatNumber(number: Double): String =
        if (number >= 1000000000) {
            String.format("%.1fb", number / 1000000000)
        } else if (number >= 1000000) {
            String.format("%.1fm", number / 1000000)
        } else if (number >= 1000) {
            String.format("%.1fk", number / 1000)
        } else {
            String.format("%.1f", number)
        }

    @JvmStatic
    fun collectBalancesInRange(low: Double, high: Double): List<Double> {
        val balances: MutableList<Double> = ArrayList()
        val players = Bukkit.getOfflinePlayers()

        for (player in players) {
            try {
                if (VaultUtils.hasAccount(player)) {
                    val balance = VaultUtils.getBalance(player)
                    if (balance >= low && balance <= high) {
                        balances.add(balance)
                    }
                }
            } catch (_: Exception) {
            }
        }

        return balances
    }

    @JvmStatic
    fun createHistogram(values: List<Double>?, numBars: Int): IntArray {
        if (values.isNullOrEmpty() || numBars <= 0) {
            return IntArray(0)
        }

        val min = values.minOrNull() ?: 0.0
        val max = values.maxOrNull() ?: 0.0
        val range = max - min
        val barWidth = range / numBars

        val histogram = IntArray(numBars)
        for (value in values) {
            var barIndex = ((value - min) / barWidth).toInt()
            if (barIndex == numBars) {
                barIndex--
            }
            histogram[barIndex]++
        }

        return histogram
    }
}
