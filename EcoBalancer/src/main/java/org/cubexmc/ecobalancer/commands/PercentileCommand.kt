package org.cubexmc.ecobalancer.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.utils.AnalysisFilters
import org.cubexmc.ecobalancer.utils.MessageUtils
import org.cubexmc.ecobalancer.utils.StatisticsUtils
import java.util.Arrays

class PercentileCommand(private val plugin: EcoBalancer) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.perc_usage", null, plugin.messagePrefix))
            return false
        }

        val balance = try {
            args[0].toDouble()
        } catch (_: NumberFormatException) {
            sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.perc_invalid_args", null, plugin.messagePrefix))
            return false
        }

        val pr = AnalysisFilters.parse(Arrays.copyOfRange(args, 1, args.size))
        val statsWorld = plugin.config.getString("stats-world", "")
        val balances = AnalysisFilters.collectFilteredBalances(pr.criteria, statsWorld)

        val percentile = StatisticsUtils.calculatePercentile(balance, balances)

        val placeholders: MutableMap<String, String> = HashMap()
        placeholders["balance"] = String.format("%.2f", balance)
        placeholders["percentile"] = String.format("%.2f", percentile)
        val low = pr.criteria.minBalance ?: Double.NEGATIVE_INFINITY
        val up = pr.criteria.maxBalance ?: Double.POSITIVE_INFINITY
        placeholders["low"] = if (low == Double.NEGATIVE_INFINITY) "∞" else String.format("%.2f", low)
        placeholders["up"] = if (up == Double.POSITIVE_INFINITY) "∞" else String.format("%.2f", up)

        sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.perc_success", placeholders, plugin.messagePrefix))
        return true
    }
}
