package org.cubexmc.ecobalancer.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.utils.AnalysisFilters
import org.cubexmc.ecobalancer.utils.DatabaseUtils
import org.cubexmc.ecobalancer.utils.EconomicMetrics
import org.cubexmc.ecobalancer.utils.MessageUtils
import org.cubexmc.ecobalancer.utils.SchedulerUtils
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

class HealthCommand(private val plugin: EcoBalancer) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.health.calculating", null), plugin.logger, false)
        SchedulerUtils.asyncRun(
            plugin,
            Runnable {
                try {
                    val criteria = AnalysisFilters.parse(args).criteria
                    val balances = AnalysisFilters.collectFilteredBalances(criteria, plugin.config.getString("stats-world", ""))
                    if (balances.isEmpty()) {
                        SchedulerUtils.runTask(plugin, Runnable {
                            MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.health.no_data", null), plugin.logger, false)
                        })
                        return@Runnable
                    }

                    val gini = EconomicMetrics.calculateGini(balances)
                    val top1Pct = EconomicMetrics.calculateConcentration(balances, 1.0) * 100
                    val top10Pct = EconomicMetrics.calculateConcentration(balances, 10.0) * 100

                    val sortedBalances = EconomicMetrics.getSortedBalances(balances)
                    val median = EconomicMetrics.calculateMedian(sortedBalances)
                    val mean = EconomicMetrics.calculateMean(balances)
                    val stdDev = EconomicMetrics.calculateStdDev(sortedBalances, mean)

                    val healthScore = calculateHealthScore(gini, top1Pct, top10Pct, stdDev, mean)
                    val latestSnapshot = DatabaseUtils.getLatestSnapshot(plugin, plugin.logger)
                    val trend = if (latestSnapshot != null) analyzeTrend(gini, latestSnapshot.gini) else ""

                    SchedulerUtils.runTask(plugin, Runnable {
                        sendHealthReport(sender, healthScore, gini, top1Pct, top10Pct, median, mean, stdDev, balances.size, trend)
                    })
                } catch (exception: Exception) {
                    plugin.logger.severe("计算经济健康度失败: ${exception.message}")
                    exception.printStackTrace()
                    SchedulerUtils.runTask(plugin, Runnable {
                        MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.health.error", null), plugin.logger, false)
                    })
                }
            },
            0,
        )

        return true
    }

    private fun calculateHealthScore(gini: Double, top1Pct: Double, top10Pct: Double, stdDev: Double, mean: Double): HealthScore {
        val score = HealthScore()

        val giniScore = if (gini <= 0.35) {
            100.0
        } else if (gini <= 0.45) {
            100 - (gini - 0.35) * 500
        } else if (gini <= 0.6) {
            50 - (gini - 0.45) * 200
        } else {
            maxOf(0.0, 20 - (gini - 0.6) * 50)
        }
        score.giniScore = giniScore

        val concentrationScore = if (top1Pct <= 20 && top10Pct <= 50) {
            100.0
        } else if (top1Pct <= 30 && top10Pct <= 60) {
            80.0
        } else if (top1Pct <= 40 && top10Pct <= 70) {
            60.0
        } else if (top1Pct <= 50 && top10Pct <= 80) {
            40.0
        } else {
            20.0
        }
        score.concentrationScore = concentrationScore

        val cv = if (mean > 0) stdDev / mean else Double.MAX_VALUE
        val distributionScore = if (cv <= 1.0) {
            100.0
        } else if (cv <= 2.0) {
            100 - (cv - 1.0) * 50
        } else if (cv <= 3.0) {
            50 - (cv - 2.0) * 30
        } else {
            maxOf(0.0, 20 - (cv - 3.0) * 10)
        }
        score.distributionScore = distributionScore

        score.totalScore = giniScore * 0.4 + concentrationScore * 0.3 + distributionScore * 0.3
        score.level = if (score.totalScore >= 80) {
            HealthLevel.EXCELLENT
        } else if (score.totalScore >= 60) {
            HealthLevel.GOOD
        } else if (score.totalScore >= 40) {
            HealthLevel.MODERATE
        } else if (score.totalScore >= 20) {
            HealthLevel.POOR
        } else {
            HealthLevel.CRITICAL
        }

        return score
    }

    private fun analyzeTrend(currentGini: Double, previousGini: Double): String {
        val change = currentGini - previousGini
        return if (abs(change) < 0.01) "→" else if (change < 0) "↓" else "↑"
    }

    private fun msg(sender: CommandSender, message: String) {
        MessageUtils.sendMessage(sender, ChatColor.translateAlternateColorCodes('&', message), plugin.logger, false)
    }

    private fun sendHealthReport(
        sender: CommandSender,
        score: HealthScore,
        gini: Double,
        top1Pct: Double,
        top10Pct: Double,
        median: Double,
        mean: Double,
        stdDev: Double,
        playerCount: Int,
        trend: String,
    ) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())

        msg(sender, "&6&l════════════════════════════════════")
        val ph: MutableMap<String, String> = HashMap()
        msg(sender, plugin.getFormattedMessage("messages.health.title", null))
        ph["timestamp"] = timestamp
        msg(sender, plugin.getFormattedMessage("messages.health.generated_at", ph))
        msg(sender, "&6&l════════════════════════════════════")

        val level = score.level ?: HealthLevel.CRITICAL
        val levelColor = getLevelColor(level)
        val levelName = getLevelName(level)
        msg(sender, "")
        ph.clear()
        ph["score"] = String.format("%.1f", score.totalScore)
        ph["level"] = levelName
        msg(sender, levelColor + plugin.getFormattedMessage("messages.health.score_line", ph))
        msg(sender, generateProgressBar(score.totalScore, 100.0, 30, levelColor))

        if (trend.isNotEmpty()) {
            val trendKey = if (trend == "↑") {
                "messages.health.trend_up"
            } else if (trend == "↓") {
                "messages.health.trend_down"
            } else {
                "messages.health.trend_stable"
            }
            msg(sender, plugin.getFormattedMessage(trendKey, null))
        }

        msg(sender, "")
        msg(sender, plugin.getFormattedMessage("messages.health.details_header", null))

        val giniColor = if (gini <= 0.4) "&a" else if (gini <= 0.5) "&e" else "&c"
        ph.clear()
        ph["gini_colored"] = giniColor + String.format("%.3f", gini)
        ph["gini_score_40"] = String.format("%.1f", score.giniScore * 0.4)
        msg(sender, plugin.getFormattedMessage("messages.health.gini_line", ph))
        msg(sender, "  " + generateProgressBar(gini, 1.0, 20, giniColor))
        ph.clear()
        ph["gini_desc"] = getGiniDescription(gini)
        msg(sender, plugin.getFormattedMessage("messages.health.gini_desc_line", ph))

        val concColor = if (top1Pct <= 25) "&a" else if (top1Pct <= 40) "&e" else "&c"
        ph.clear()
        ph["concentration_score_30"] = String.format("%.1f", score.concentrationScore * 0.3)
        msg(sender, plugin.getFormattedMessage("messages.health.concentration_header", ph))
        ph.clear()
        ph["top1_colored"] = concColor + String.format("%.1f", top1Pct)
        msg(sender, plugin.getFormattedMessage("messages.health.top1_line", ph))
        msg(sender, "      " + generateProgressBar(top1Pct, 100.0, 18, concColor))
        ph.clear()
        ph["top10_colored"] = concColor + String.format("%.1f", top10Pct)
        msg(sender, plugin.getFormattedMessage("messages.health.top10_line", ph))
        msg(sender, "      " + generateProgressBar(top10Pct, 100.0, 18, concColor))

        val cv = if (mean > 0) stdDev / mean else 0.0
        val cvColor = if (cv <= 1.5) "&a" else if (cv <= 2.5) "&e" else "&c"
        ph.clear()
        ph["distribution_score_30"] = String.format("%.1f", score.distributionScore * 0.3)
        msg(sender, plugin.getFormattedMessage("messages.health.distribution_header", ph))
        ph.clear()
        ph["cv_colored"] = cvColor + String.format("%.2f", cv)
        msg(sender, plugin.getFormattedMessage("messages.health.cv_line", ph))
        ph.clear()
        ph["mean"] = EconomicMetrics.formatLargeNumber(mean)
        msg(sender, plugin.getFormattedMessage("messages.health.mean_line", ph))
        ph.clear()
        ph["median"] = EconomicMetrics.formatLargeNumber(median)
        msg(sender, plugin.getFormattedMessage("messages.health.median_line", ph))
        ph.clear()
        ph["stddev"] = EconomicMetrics.formatLargeNumber(stdDev)
        msg(sender, plugin.getFormattedMessage("messages.health.stddev_line", ph))

        msg(sender, "")
        msg(sender, plugin.getFormattedMessage("messages.health.reco_header", null))
        val recommendations = getRecommendationKeys(score, gini, top1Pct, cv)
        for (key in recommendations) {
            msg(sender, plugin.getFormattedMessage("messages.health.reco.$key", null))
        }

        msg(sender, "")
        ph.clear()
        ph["player_count"] = playerCount.toString()
        msg(sender, plugin.getFormattedMessage("messages.health.sample_line", ph))
        msg(sender, "&6&l════════════════════════════════════")
    }

    private fun generateProgressBar(value: Double, max: Double, length: Int, color: String): String {
        var filled = ((value / max) * length).roundToInt()
        filled = filled.coerceIn(0, length)

        val bar = StringBuilder("  &7[")
        for (i in 0 until length) {
            if (i < filled) {
                bar.append(color).append("█")
            } else {
                bar.append("&8░")
            }
        }
        bar.append("&7]")

        return ChatColor.translateAlternateColorCodes('&', bar.toString())
    }

    private fun getLevelColor(level: HealthLevel): String =
        when (level) {
            HealthLevel.EXCELLENT -> "&a"
            HealthLevel.GOOD -> "&2"
            HealthLevel.MODERATE -> "&e"
            HealthLevel.POOR -> "&6"
            HealthLevel.CRITICAL -> "&c"
        }

    private fun getLevelName(level: HealthLevel): String {
        val key = when (level) {
            HealthLevel.EXCELLENT -> "messages.health.level.excellent"
            HealthLevel.GOOD -> "messages.health.level.good"
            HealthLevel.MODERATE -> "messages.health.level.moderate"
            HealthLevel.POOR -> "messages.health.level.poor"
            HealthLevel.CRITICAL -> "messages.health.level.critical"
        }
        return ChatColor.stripColor(plugin.getFormattedMessage(key, null)) ?: ""
    }

    private fun getGiniDescription(gini: Double): String {
        val key = if (gini <= 0.3) {
            "messages.health.gini_desc.very_equal"
        } else if (gini <= 0.4) {
            "messages.health.gini_desc.relatively_equal"
        } else if (gini <= 0.5) {
            "messages.health.gini_desc.moderate_gap"
        } else if (gini <= 0.6) {
            "messages.health.gini_desc.large_gap"
        } else {
            "messages.health.gini_desc.very_unequal"
        }
        return plugin.getFormattedMessage(key, null)
    }

    private fun getRecommendationKeys(score: HealthScore, gini: Double, top1Pct: Double, cv: Double): List<String> {
        val keys: MutableList<String> = ArrayList()
        if (score.level == HealthLevel.EXCELLENT) {
            keys.add("state_excellent_keep")
            keys.add("minor_tune_tax")
            return keys
        }
        if (gini > 0.5) {
            keys.add("gini_very_high_progressive")
            keys.add("tax_rich_more")
        } else if (gini > 0.4) {
            keys.add("raise_high_income_tax_moderate")
        }
        if (top1Pct > 40) {
            keys.add("wealth_overly_concentrated")
            keys.add("special_tax_cap")
        } else if (top1Pct > 25) {
            keys.add("concentration_high_moderate_regulation")
        }
        if (cv > 2.5) {
            keys.add("cv_very_high_strong_intervention")
            keys.add("welfare_support")
        } else if (cv > 1.5) {
            keys.add("support_middle_class")
        }
        if (keys.isEmpty()) {
            keys.add("healthy_maintain")
        }
        return keys
    }

    private class HealthScore {
        var giniScore = 0.0
        var concentrationScore = 0.0
        var distributionScore = 0.0
        var totalScore = 0.0
        var level: HealthLevel? = null
    }

    private enum class HealthLevel {
        EXCELLENT,
        GOOD,
        MODERATE,
        POOR,
        CRITICAL,
    }
}
