package org.cubexmc.ecobalancer.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.utils.AnalysisFilters
import org.cubexmc.ecobalancer.utils.EconomicMetrics
import org.cubexmc.ecobalancer.utils.SchedulerUtils
import kotlin.math.ceil

class ConcentrationCommand(private val plugin: EcoBalancer) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        val pr = AnalysisFilters.parse(args)
        val criteria = pr.criteria
        val rest = pr.remainingArgs

        val percentages: MutableList<Double> = ArrayList()
        if (rest.isEmpty()) {
            percentages.addAll(listOf(1.0, 5.0, 10.0, 20.0))
        } else {
            for (arg in rest) {
                try {
                    val pct = arg.toDouble()
                    if (pct <= 0 || pct > 100) {
                        val placeholders: MutableMap<String, String> = HashMap()
                        placeholders["value"] = arg
                        sender.sendMessage(plugin.getFormattedMessage("messages.concentration.invalid_percentage", placeholders))
                        return false
                    }
                    percentages.add(pct)
                } catch (_: NumberFormatException) {
                    val placeholders: MutableMap<String, String> = HashMap()
                    placeholders["input"] = arg
                    sender.sendMessage(plugin.getFormattedMessage("messages.concentration.invalid_number", placeholders))
                    return false
                }
            }
        }

        val finalPercentages = ArrayList(percentages.toSortedSet())
        sender.sendMessage(plugin.getFormattedMessage("messages.concentration.calculating", null))

        SchedulerUtils.runTaskAsync(
            plugin,
            Runnable {
                try {
                    val balances = AnalysisFilters.collectFilteredBalances(criteria, plugin.config.getString("stats-world", ""))

                    if (balances.isEmpty()) {
                        SchedulerUtils.runTask(plugin, Runnable {
                            sender.sendMessage(plugin.getFormattedMessage("messages.concentration.no_data", null))
                        })
                        return@Runnable
                    }

                    val totalMoney = EconomicMetrics.calculateTotalMoney(balances)
                    val totalPlayers = balances.size

                    val results: MutableList<ConcentrationResult> = ArrayList()
                    for (pct in finalPercentages) {
                        val concentration = EconomicMetrics.calculateConcentration(balances, pct)
                        val topCount = maxOf(1, ceil(totalPlayers * pct / 100.0).toInt())
                        results.add(ConcentrationResult(pct, concentration, topCount))
                    }

                    SchedulerUtils.runTask(
                        plugin,
                        Runnable {
                            val headerPlaceholders: MutableMap<String, String> = HashMap()
                            headerPlaceholders["total_players"] = totalPlayers.toString()
                            headerPlaceholders["total_money"] = EconomicMetrics.formatLargeNumber(totalMoney)

                            sender.sendMessage(plugin.getFormattedMessage("messages.concentration.header", headerPlaceholders))

                            for (result in results) {
                                val placeholders: MutableMap<String, String> = HashMap()
                                placeholders["percentage"] = String.format("%.1f%%", result.percentage)
                                placeholders["concentration"] = String.format("%.2f%%", result.concentration * 100)
                                placeholders["player_count"] = result.playerCount.toString()
                                placeholders["level"] = EconomicMetrics.getConcentrationLevel(result.concentration)

                                sender.sendMessage(plugin.getFormattedMessage("messages.concentration.result_line", placeholders))
                            }

                            val top1 = if (results.isEmpty()) 0.0 else results[0].concentration
                            if (top1 >= 0.6) {
                                sender.sendMessage(plugin.getFormattedMessage("messages.concentration.warning_extreme", null))
                            } else if (top1 >= 0.4) {
                                sender.sendMessage(plugin.getFormattedMessage("messages.concentration.warning_high", null))
                            }
                        },
                    )
                } catch (exception: Exception) {
                    SchedulerUtils.runTask(
                        plugin,
                        Runnable {
                            sender.sendMessage(plugin.getFormattedMessage("messages.concentration.error", null))
                            plugin.logger.severe("Error calculating concentration: ${exception.message}")
                            exception.printStackTrace()
                        },
                    )
                }
            },
        )

        return true
    }

    private class ConcentrationResult(
        val percentage: Double,
        val concentration: Double,
        val playerCount: Int,
    )
}
