package org.cubexmc.ecobalancer.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.cubexmc.ecobalancer.utils.MessageUtils;
import org.cubexmc.ecobalancer.utils.AnalysisFilters;

public class CheckAllCommand implements CommandExecutor {
    private final EcoBalancer plugin;

    public CheckAllCommand(EcoBalancer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String s, String[] args) {
        AnalysisFilters.FilterCriteria criteria = null;
        if (args.length > 0) {
            criteria = AnalysisFilters.parse(args).criteria;
        }
        sender.sendMessage(MessageUtils.formatMessage(plugin.getLangConfig(), "messages.scanning_offline_players", null, plugin.getMessagePrefix()));
        plugin.checkAll(sender, criteria);
        return true;
    }
}
