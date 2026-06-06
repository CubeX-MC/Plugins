package org.cubexmc.ecobalancer.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.utils.AnalysisFilters
import org.cubexmc.ecobalancer.utils.EconomicMetrics
import org.cubexmc.ecobalancer.utils.SchedulerUtils

class GiniCommand(private val plugin: EcoBalancer) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        val pr = AnalysisFilters.parse(args)
        val criteria = pr.criteria

        sender.sendMessage(plugin.getFormattedMessage("messages.gini.calculating", null))

        SchedulerUtils.runTaskAsync(
            plugin,
            Runnable {
                try {
                    val balances = AnalysisFilters.collectFilteredBalances(criteria, plugin.config.getString("stats-world", ""))

                    if (balances.isEmpty()) {
                        SchedulerUtils.runTask(plugin, Runnable {
                            sender.sendMessage(plugin.getFormattedMessage("messages.gini.no_data", null))
                        })
                        return@Runnable
                    }

                    val gini = EconomicMetrics.calculateGini(balances)
                    val totalMoney = EconomicMetrics.calculateTotalMoney(balances)
                    val giniLevel = EconomicMetrics.getGiniLevel(gini)

                    SchedulerUtils.runTask(
                        plugin,
                        Runnable {
                            val placeholders: MutableMap<String, String> = HashMap()
                            placeholders["gini"] = String.format("%.4f", gini)
                            placeholders["gini_percentage"] = String.format("%.2f%%", gini * 100)
                            placeholders["level"] = giniLevel
                            placeholders["player_count"] = balances.size.toString()
                            placeholders["total_money"] = EconomicMetrics.formatLargeNumber(totalMoney)
                            placeholders["days"] = criteria.activeWithinDays?.toString() ?: "∞"

                            sender.sendMessage(plugin.getFormattedMessage("messages.gini.header", null))
                            sender.sendMessage(plugin.getFormattedMessage("messages.gini.result", placeholders))

                            if (gini >= 0.6) {
                                sender.sendMessage(plugin.getFormattedMessage("messages.gini.warning_high", null))
                            } else if (gini >= 0.5) {
                                sender.sendMessage(plugin.getFormattedMessage("messages.gini.warning_moderate", null))
                            } else if (gini < 0.3) {
                                sender.sendMessage(plugin.getFormattedMessage("messages.gini.info_low", null))
                            }
                        },
                    )
                } catch (exception: Exception) {
                    SchedulerUtils.runTask(
                        plugin,
                        Runnable {
                            sender.sendMessage(plugin.getFormattedMessage("messages.gini.error", null))
                            plugin.logger.severe("Error calculating Gini coefficient: ${exception.message}")
                            exception.printStackTrace()
                        },
                    )
                }
            },
        )

        return true
    }
}
