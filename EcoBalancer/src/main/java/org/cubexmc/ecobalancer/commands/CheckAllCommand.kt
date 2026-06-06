package org.cubexmc.ecobalancer.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.utils.AnalysisFilters
import org.cubexmc.ecobalancer.utils.MessageUtils

class CheckAllCommand(private val plugin: EcoBalancer) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, s: String, args: Array<String>): Boolean {
        val criteria = if (args.isNotEmpty()) AnalysisFilters.parse(args).criteria else null
        sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.scanning_offline_players", null, plugin.messagePrefix))
        plugin.checkAll(sender, criteria)
        return true
    }
}
