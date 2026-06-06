package org.cubexmc.ecobalancer.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.utils.MessageUtils

class CheckPlayerCommand(private val plugin: EcoBalancer) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, s: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.enter_player_name_or_use_checkall", null, plugin.messagePrefix))
        } else {
            plugin.checkPlayer(sender, args[0])
        }
        return true
    }
}
