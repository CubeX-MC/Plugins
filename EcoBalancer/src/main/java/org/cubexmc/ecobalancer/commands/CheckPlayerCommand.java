package org.cubexmc.ecobalancer.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.cubexmc.ecobalancer.utils.MessageUtils;

public class CheckPlayerCommand implements CommandExecutor {
    private final EcoBalancer plugin;

    public CheckPlayerCommand(EcoBalancer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String s, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(MessageUtils.formatMessage(plugin.getLangConfig(), "messages.enter_player_name_or_use_checkall", null, plugin.getMessagePrefix()));
        } else {
            plugin.checkPlayer(sender, args[0]);
        }
        return true;
    }
}
