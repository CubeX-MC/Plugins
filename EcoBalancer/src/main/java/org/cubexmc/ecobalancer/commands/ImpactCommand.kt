package org.cubexmc.ecobalancer.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.utils.DatabaseUtils
import org.cubexmc.ecobalancer.utils.EconomicMetrics
import org.cubexmc.ecobalancer.utils.MessageUtils
import org.cubexmc.ecobalancer.utils.SchedulerUtils
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

class ImpactCommand(private val plugin: EcoBalancer) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        sender.sendMessage(plugin.getFormattedMessage("messages.impact.loading", null))
        SchedulerUtils.asyncRun(
            plugin,
            Runnable {
                try {
                    val impact: DatabaseUtils.OperationImpact
                    if (args.isNotEmpty()) {
                        val operationId = try {
                            args[0].toInt()
                        } catch (_: NumberFormatException) {
                            SchedulerUtils.runTask(plugin, Runnable {
                                val ph: MutableMap<String, String> = HashMap()
                                ph["id"] = args[0]
                                MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.impact.invalid_id", ph), plugin.logger, false)
                            })
                            return@Runnable
                        }
                        val found = DatabaseUtils.getOperationImpact(plugin, operationId, plugin.logger)
                        if (found == null) {
                            SchedulerUtils.runTask(plugin, Runnable {
                                val ph: MutableMap<String, String> = HashMap()
                                ph["operation_id"] = operationId.toString()
                                MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.impact.not_found", ph), plugin.logger, false)
                                MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.impact.hint_latest", null), plugin.logger, false)
                            })
                            return@Runnable
                        }
                        impact = found
                    } else {
                        val recentImpacts = DatabaseUtils.getRecentImpacts(plugin, 1, plugin.logger)
                        if (recentImpacts.isEmpty()) {
                            SchedulerUtils.runTask(plugin, Runnable {
                                MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.report.no_operations", null), plugin.logger, false)
                                MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.impact.hint_latest", null), plugin.logger, false)
                            })
                            return@Runnable
                        }
                        impact = recentImpacts[0]
                    }

                    SchedulerUtils.runTask(plugin, Runnable { sendImpactReport(sender, impact) })
                } catch (exception: Exception) {
                    plugin.logger.severe("获取税收影响数据失败: ${exception.message}")
                    exception.printStackTrace()
                    SchedulerUtils.runTask(plugin, Runnable {
                        MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.impact.error", null), plugin.logger, false)
                    })
                }
            },
            0,
        )

        return true
    }

    private fun msg(sender: CommandSender, message: String) {
        MessageUtils.sendMessage(sender, ChatColor.translateAlternateColorCodes('&', message), plugin.logger, false)
    }

    private fun sendImpactReport(sender: CommandSender, impact: DatabaseUtils.OperationImpact) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(impact.timestamp))

        msg(sender, plugin.getFormattedMessage("messages.impact.banner", null))
        val header: MutableMap<String, String> = HashMap()
        header["operation_id"] = impact.operationId.toString()
        header["timestamp"] = timestamp
        msg(sender, plugin.getFormattedMessage("messages.impact.title", null))
        msg(sender, plugin.getFormattedMessage("messages.impact.operation_line", header))
        msg(sender, plugin.getFormattedMessage("messages.impact.banner", null))
        msg(sender, "")

        msg(sender, plugin.getFormattedMessage("messages.impact.section_overview", null))
        val overview: MutableMap<String, String> = HashMap()
        overview["total_tax"] = EconomicMetrics.formatLargeNumber(impact.totalTaxCollected)
        overview["players"] = impact.playersAffected.toString()
        msg(sender, plugin.getFormattedMessage("messages.impact.collected", overview))
        msg(sender, plugin.getFormattedMessage("messages.impact.affected", overview))
        msg(sender, "")

        msg(sender, plugin.getFormattedMessage("messages.impact.section_inequality", null))
        val giniChange = impact.afterGini - impact.beforeGini
        val giniChangePercent = if (impact.beforeGini > 0) giniChange / impact.beforeGini * 100 else 0.0
        val giniTrend = getChangeIndicator(giniChange, true)
        val giniColor = if (giniChange < 0) "&a" else if (giniChange > 0) "&c" else "&e"
        val gini: MutableMap<String, String> = HashMap()
        gini["before"] = String.format("%.3f", impact.beforeGini)
        gini["after"] = String.format("%.3f", impact.afterGini)
        gini["delta_color"] = giniColor
        gini["delta"] = String.format("%.3f", giniChange)
        gini["delta_pct"] = String.format("%.1f", giniChangePercent)
        msg(sender, plugin.getFormattedMessage("messages.impact.gini_line", gini))
        msg(sender, "  " + generateComparisonBar(impact.beforeGini, impact.afterGini, 1.0, 20))
        msg(sender, String.format("    &8└─ %s %s", giniTrend, getGiniChangeDescription(giniChange)))
        msg(sender, "")

        msg(sender, plugin.getFormattedMessage("messages.impact.section_concentration", null))
        val top1Change = impact.afterTop1Pct - impact.beforeTop1Pct
        val top1ChangePercent = if (impact.beforeTop1Pct > 0) top1Change / impact.beforeTop1Pct * 100 else 0.0
        val top1Trend = getChangeIndicator(top1Change, true)
        val top1Color = if (top1Change < 0) "&a" else if (top1Change > 0) "&c" else "&e"
        val top1: MutableMap<String, String> = HashMap()
        top1["before"] = String.format("%.1f", impact.beforeTop1Pct * 100)
        top1["after"] = String.format("%.1f", impact.afterTop1Pct * 100)
        top1["delta_color"] = top1Color
        top1["delta"] = String.format("%.1f", top1Change * 100)
        top1["delta_pct"] = String.format("%.1f", top1ChangePercent)
        msg(sender, plugin.getFormattedMessage("messages.impact.top1_line", top1))
        msg(sender, "  " + generateComparisonBar(impact.beforeTop1Pct, impact.afterTop1Pct, 1.0, 20))
        msg(sender, String.format("    &8└─ %s %s", top1Trend, getConcentrationChangeDescription(top1Change)))
        msg(sender, "")

        msg(sender, plugin.getFormattedMessage("messages.impact.section_distribution", null))
        sendDistributionLine(sender, "messages.impact.median_line", impact.beforeMedian, impact.afterMedian, false)
        sendDistributionLine(sender, "messages.impact.mean_line", impact.beforeMean, impact.afterMean, false)
        sendDistributionLine(sender, "messages.impact.stddev_line", impact.beforeStdDev, impact.afterStdDev, true)
        msg(sender, "")

        msg(sender, plugin.getFormattedMessage("messages.impact.section_scale", null))
        val totalMoneyChange = impact.afterTotalMoney - impact.beforeTotalMoney
        val totalMoneyChangePercent = if (impact.beforeTotalMoney > 0) totalMoneyChange / impact.beforeTotalMoney * 100 else 0.0
        val scale: MutableMap<String, String> = HashMap()
        scale["before"] = EconomicMetrics.formatLargeNumber(impact.beforeTotalMoney)
        scale["after"] = EconomicMetrics.formatLargeNumber(impact.afterTotalMoney)
        scale["change"] = EconomicMetrics.formatLargeNumber(totalMoneyChange)
        scale["percent"] = String.format("%.2f", totalMoneyChangePercent)
        scale["removed_percent"] = String.format("%.2f", abs(totalMoneyChangePercent))
        msg(sender, plugin.getFormattedMessage("messages.impact.total_line", scale))
        msg(sender, plugin.getFormattedMessage("messages.impact.change_line", scale))
        msg(sender, plugin.getFormattedMessage("messages.impact.removed_line", scale))
        msg(sender, "")

        msg(sender, plugin.getFormattedMessage("messages.impact.section_assessment", null))
        msg(sender, "  " + getOverallAssessment(giniChange, top1Change, impact.afterMedian - impact.beforeMedian))
        msg(sender, "")
        msg(sender, plugin.getFormattedMessage("messages.impact.banner", null))
    }

    private fun sendDistributionLine(sender: CommandSender, key: String, before: Double, after: Double, lowerIsBetter: Boolean) {
        val change = after - before
        val changePercent = if (before > 0) change / before * 100 else 0.0
        val trend = getChangeIndicator(change, lowerIsBetter)
        val color = if (lowerIsBetter) {
            if (change < 0) "&a" else if (change > 0) "&c" else "&e"
        } else {
            if (change > 0) "&a" else if (change < 0) "&c" else "&e"
        }
        val placeholders: MutableMap<String, String> = HashMap()
        placeholders["before"] = EconomicMetrics.formatLargeNumber(before)
        placeholders["after"] = EconomicMetrics.formatLargeNumber(after)
        placeholders["delta_color"] = color
        placeholders["delta"] = EconomicMetrics.formatLargeNumber(change)
        placeholders["delta_pct"] = String.format("%.1f", changePercent)
        msg(sender, plugin.getFormattedMessage(key, placeholders))
        msg(sender, String.format("    &8└─ %s", trend))
    }

    private fun generateComparisonBar(before: Double, after: Double, max: Double, length: Int): String {
        var beforePos = ((before / max) * length).roundToInt()
        var afterPos = ((after / max) * length).roundToInt()
        beforePos = beforePos.coerceIn(0, length)
        afterPos = afterPos.coerceIn(0, length)

        val bar = StringBuilder("  &7[")
        for (i in 0 until length) {
            if (i == beforePos && i == afterPos) {
                bar.append("&e◆")
            } else if (i == beforePos) {
                bar.append("&c●")
            } else if (i == afterPos) {
                bar.append("&a●")
            } else if ((beforePos < afterPos && i > beforePos && i < afterPos) ||
                (beforePos > afterPos && i > afterPos && i < beforePos)
            ) {
                bar.append("&7─")
            } else {
                bar.append("&8░")
            }
        }
        bar.append("&7] &c● &7→ &a●")
        return ChatColor.translateAlternateColorCodes('&', bar.toString())
    }

    private fun getChangeIndicator(change: Double, lowerIsBetter: Boolean): String {
        if (abs(change) < 0.001) {
            return plugin.getFormattedMessage("messages.impact.trend.stable", null)
        }
        val isImprovement = if (lowerIsBetter) change < 0 else change > 0
        val arrow = if (change < 0) "↓" else "↑"
        val color = if (isImprovement) "&a" else "&c"
        val key = if (isImprovement) "messages.impact.trend.improve" else "messages.impact.trend.worsen"
        return String.format("%s%s %s", color, arrow, plugin.getFormattedMessage(key, null))
    }

    private fun getGiniChangeDescription(change: Double): String {
        val absChange = abs(change)
        return if (absChange >= 0.1) {
            plugin.getFormattedMessage("messages.impact.gini_delta.major", null)
        } else if (absChange >= 0.05) {
            plugin.getFormattedMessage("messages.impact.gini_delta.noticeable", null)
        } else if (absChange >= 0.01) {
            plugin.getFormattedMessage("messages.impact.gini_delta.slight", null)
        } else {
            plugin.getFormattedMessage("messages.impact.gini_delta.tiny", null)
        }
    }

    private fun getConcentrationChangeDescription(change: Double): String {
        val absChange = abs(change) * 100
        return if (absChange >= 5) {
            plugin.getFormattedMessage("messages.impact.concentration_delta.major", null)
        } else if (absChange >= 2) {
            plugin.getFormattedMessage("messages.impact.concentration_delta.noticeable", null)
        } else if (absChange >= 0.5) {
            plugin.getFormattedMessage("messages.impact.concentration_delta.slight", null)
        } else {
            plugin.getFormattedMessage("messages.impact.concentration_delta.tiny", null)
        }
    }

    private fun getOverallAssessment(giniChange: Double, top1Change: Double, medianChange: Double): String {
        var improveCount = 0
        var worsenCount = 0

        if (giniChange < -0.01) improveCount++ else if (giniChange > 0.01) worsenCount++
        if (top1Change < -0.005) improveCount++ else if (top1Change > 0.005) worsenCount++
        if (medianChange > 0) improveCount++ else if (medianChange < 0) worsenCount++

        return if (improveCount >= 2 && worsenCount == 0) {
            plugin.getFormattedMessage("messages.impact.assessment.strong_improve", null)
        } else if (improveCount > worsenCount) {
            plugin.getFormattedMessage("messages.impact.assessment.improve", null)
        } else if (improveCount == worsenCount) {
            plugin.getFormattedMessage("messages.impact.assessment.neutral", null)
        } else if (worsenCount > improveCount) {
            plugin.getFormattedMessage("messages.impact.assessment.worsen", null)
        } else {
            plugin.getFormattedMessage("messages.impact.assessment.very_bad", null)
        }
    }
}
