package org.cubexmc.ecobalancer.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.utils.AnalysisFilters
import org.cubexmc.ecobalancer.utils.MessageUtils

class DescripStatsCommand(private val plugin: EcoBalancer) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, s: String, args: Array<String>): Boolean {
        val pr = AnalysisFilters.parse(args)
        val rest = pr.remainingArgs
        if (rest.isEmpty()) {
            sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.stats_usage", null, plugin.messagePrefix))
            sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.stats_limits", null, plugin.messagePrefix))
            return false
        }

        val numBars: Int = try {
            val parsed = rest[0].toInt()
            if (parsed < 1) {
                sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.stats_invalid_number_of_bars", null, plugin.messagePrefix))
                return false
            }
            parsed
        } catch (_: NumberFormatException) {
            sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.stats_usage", null, plugin.messagePrefix))
            return false
        }

        val statsWorld = plugin.config.getString("stats-world", "")
        val balances = AnalysisFilters.collectFilteredBalances(pr.criteria, statsWorld)
        if (balances.isEmpty()) {
            sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.gini.no_data", null, plugin.messagePrefix))
            return true
        }

        plugin.generateHistogramFromBalances(sender, numBars, balances, args)
        return true
    }
}
