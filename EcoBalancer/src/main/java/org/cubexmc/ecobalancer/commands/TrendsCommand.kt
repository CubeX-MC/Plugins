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

class TrendsCommand(private val plugin: EcoBalancer) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        var days = 30
        if (args.isNotEmpty()) {
            days = try {
                val parsed = args[0].toInt()
                if (parsed < 1 || parsed > 365) {
                    MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.trends.invalid_days", null), plugin.logger, false)
                    return true
                }
                parsed
            } catch (_: NumberFormatException) {
                MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.trends.invalid_days", null), plugin.logger, false)
                return true
            }
        }

        val queryDays = days
        MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.trends.loading", null), plugin.logger, false)

        SchedulerUtils.asyncRun(
            plugin,
            Runnable {
                try {
                    val snapshots = DatabaseUtils.getSnapshotHistory(plugin, queryDays, plugin.logger)

                    if (snapshots.isEmpty()) {
                        SchedulerUtils.runTask(plugin, Runnable {
                            MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.trends.no_data", null), plugin.logger, false)
                        })
                        return@Runnable
                    }

                    SchedulerUtils.runTask(plugin, Runnable { sendTrendsReport(sender, snapshots, queryDays) })
                } catch (exception: Exception) {
                    plugin.logger.severe("获取趋势数据失败: ${exception.message}")
                    exception.printStackTrace()
                    SchedulerUtils.runTask(plugin, Runnable {
                        MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.trends.error", null), plugin.logger, false)
                    })
                }
            },
            0,
        )

        return true
    }

    private fun msg(sender: CommandSender, message: String) {
        MessageUtils.sendMessage(sender, message, plugin.logger, false)
    }

    private fun sendTrendsReport(sender: CommandSender, snapshots: List<DatabaseUtils.EconomicSnapshot>, days: Int) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd")
        val first = snapshots[0]
        val last = snapshots[snapshots.size - 1]

        msg(sender, plugin.getFormattedMessage("messages.impact.banner", null))
        msg(sender, "&e&l         经济趋势分析报告")
        msg(sender, String.format("&7时间范围: %s ~ %s (&f%d &7天)", dateFormat.format(Date(first.timestamp)), dateFormat.format(Date(last.timestamp)), days))
        msg(sender, String.format("&7数据点数: &f%d &7个快照", snapshots.size))
        msg(sender, plugin.getFormattedMessage("messages.impact.banner", null))
        msg(sender, "")

        msg(sender, "&e&l▸ 不平等程度趋势 (基尼系数):")
        val giniChange = last.gini - first.gini
        val giniChangePercent = if (first.gini > 0) giniChange / first.gini * 100 else 0.0
        val giniTrend = getTrendIndicator(giniChange, true)
        msg(sender, String.format("  %.3f → %.3f (%s%.3f, %s%.1f%%&7)", first.gini, last.gini, if (giniChange >= 0) "&c+" else "&a", giniChange, if (giniChange >= 0) "&c+" else "&a", giniChangePercent))
        msg(sender, "  " + generateTrendChart(snapshots, "gini"))
        msg(sender, "  $giniTrend")
        msg(sender, "")

        msg(sender, "&e&l▸ 财富集中度趋势 (Top 1%):")
        val top1Change = (last.top1Pct - first.top1Pct) * 100
        val top1ChangePercent = if (first.top1Pct > 0) top1Change / (first.top1Pct * 100) * 100 else 0.0
        val top1Trend = getTrendIndicator(top1Change, true)
        msg(sender, String.format("  %.1f%% → %.1f%% (%s%.1f%%, %s%.1f%%&7)", first.top1Pct * 100, last.top1Pct * 100, if (top1Change >= 0) "&c+" else "&a", top1Change, if (top1Change >= 0) "&c+" else "&a", top1ChangePercent))
        msg(sender, "  " + generateTrendChart(snapshots, "top1"))
        msg(sender, "  $top1Trend")
        msg(sender, "")

        msg(sender, "&e&l▸ 经济规模趋势 (总货币量):")
        val moneyChange = last.totalMoney - first.totalMoney
        val moneyChangePercent = if (first.totalMoney > 0) moneyChange / first.totalMoney * 100 else 0.0
        val moneyTrend = getTrendIndicator(moneyChange, false)
        msg(sender, String.format("  %s → %s (%s%s, %s%.1f%%&7)", EconomicMetrics.formatLargeNumber(first.totalMoney), EconomicMetrics.formatLargeNumber(last.totalMoney), if (moneyChange >= 0) "&a+" else "&c", EconomicMetrics.formatLargeNumber(moneyChange), if (moneyChange >= 0) "&a+" else "&c", moneyChangePercent))
        msg(sender, "  " + generateTrendChart(snapshots, "totalMoney"))
        msg(sender, "  $moneyTrend")
        msg(sender, "")

        msg(sender, "&e&l▸ 玩家数量趋势:")
        val playerChange = last.playerCount - first.playerCount
        val playerChangePercent = if (first.playerCount > 0) playerChange.toDouble() / first.playerCount * 100 else 0.0
        msg(sender, String.format("  %d → %d (%s%d, %s%.1f%%&7)", first.playerCount, last.playerCount, if (playerChange >= 0) "&a+" else "&c", playerChange, if (playerChange >= 0) "&a+" else "&c", playerChangePercent))
        msg(sender, "  " + generateTrendChart(snapshots, "playerCount"))
        msg(sender, "")

        msg(sender, "&e&l▸ 趋势综合评价:")
        msg(sender, "  " + getOverallTrendAssessment(giniChange, top1Change, moneyChange))
        msg(sender, "")
        msg(sender, "&6&l════════════════════════════════════")
    }

    private fun generateTrendChart(snapshots: List<DatabaseUtils.EconomicSnapshot>, metric: String): String {
        val width = 40
        val height = 5
        val values = DoubleArray(snapshots.size)
        for (i in snapshots.indices) {
            val snap = snapshots[i]
            values[i] = when (metric) {
                "gini" -> snap.gini
                "top1" -> snap.top1Pct
                "totalMoney" -> snap.totalMoney
                "playerCount" -> snap.playerCount.toDouble()
                else -> 0.0
            }
        }

        var min = Double.MAX_VALUE
        var max = Double.MIN_VALUE
        for (value in values) {
            if (value < min) min = value
            if (value > max) max = value
        }

        if (max == min) {
            return ChatColor.translateAlternateColorCodes('&', "&7[" + "━".repeat(width) + "] &e→ 稳定")
        }

        val heights = IntArray(width)
        for (i in 0 until width) {
            val dataIndex = (i.toDouble() / width * values.size).toInt()
            val normalized = (values[dataIndex] - min) / (max - min)
            heights[i] = (normalized * height).toInt()
        }

        val chart = StringBuilder("&7[")
        for (i in 0 until width) {
            val h = heights[i]
            if (h == 0) {
                chart.append("&8_")
            } else if (h <= height / 3) {
                chart.append("&a▁")
            } else if (h <= height * 2 / 3) {
                chart.append("&e▄")
            } else {
                chart.append("&c▇")
            }
        }
        chart.append("&7]")

        val trendArrow = if (values[values.size - 1] > values[0] * 1.05) {
            " &c↑"
        } else if (values[values.size - 1] < values[0] * 0.95) {
            " &a↓"
        } else {
            " &e→"
        }

        return ChatColor.translateAlternateColorCodes('&', chart.toString() + trendArrow)
    }

    private fun getTrendIndicator(change: Double, lowerIsBetter: Boolean): String {
        if (abs(change) < 0.001) {
            return "&e→ 趋势平稳"
        }
        val isImproving = if (lowerIsBetter) change < 0 else change > 0
        val arrow = if (change < 0) "↓" else "↑"
        val color = if (isImproving) "&a" else "&c"
        val absChange = abs(change)
        val magnitude = if (absChange > 0.1 || absChange > 10) {
            "显著"
        } else if (absChange > 0.05 || absChange > 5) {
            "明显"
        } else {
            "轻微"
        }
        val status = if (isImproving) magnitude + "改善" else magnitude + "恶化"
        return String.format("%s%s 趋势%s", color, arrow, status)
    }

    private fun getOverallTrendAssessment(giniChange: Double, top1Change: Double, moneyChange: Double): String {
        val giniImprove = giniChange < -0.01
        val top1Improve = top1Change < -0.5
        val moneyGrow = moneyChange > 0

        val improveCount = (if (giniImprove) 1 else 0) + (if (top1Improve) 1 else 0) + (if (moneyGrow) 1 else 0)

        return if (improveCount >= 2) {
            if (giniImprove && top1Improve && moneyGrow) {
                "&a&l✓ 经济状况持续向好，平等性改善且规模增长"
            } else {
                "&a&l✓ 经济整体呈现积极趋势"
            }
        } else if (improveCount == 1) {
            "&e&l⚠ 经济趋势喜忧参半，需持续关注"
        } else if (!giniImprove && !top1Improve) {
            "&c&l✗ 不平等程度加剧，建议调整税收政策"
        } else {
            "&c&l✗ 经济状况需要改善，建议采取措施"
        }
    }
}
