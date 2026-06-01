package org.cubexmc.ecobalancer.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.cubexmc.ecobalancer.utils.MessageUtils;
import org.cubexmc.ecobalancer.utils.AnalysisFilters;
// removed unused imports

public class DescripStatsCommand implements CommandExecutor {
    private final EcoBalancer plugin;

    public DescripStatsCommand(EcoBalancer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String s, String[] args) {
		// Parse filter tokens; first remaining arg must be <bars>
		AnalysisFilters.ParseResult pr = AnalysisFilters.parse(args);
		java.util.List<String> rest = pr.remainingArgs;
		if (rest.isEmpty()) {
			sender.sendMessage(MessageUtils.formatMessage(plugin.getLangConfig(), "messages.stats_usage", null, plugin.getMessagePrefix()));
			sender.sendMessage(MessageUtils.formatMessage(plugin.getLangConfig(), "messages.stats_limits", null, plugin.getMessagePrefix()));
			return false;
		}

		int numBars;
		try {
			numBars = Integer.parseInt(rest.get(0));
			if (numBars < 1) {
				sender.sendMessage(MessageUtils.formatMessage(plugin.getLangConfig(), "messages.stats_invalid_number_of_bars", null, plugin.getMessagePrefix()));
				return false;
			}
		} catch (NumberFormatException e) {
			sender.sendMessage(MessageUtils.formatMessage(plugin.getLangConfig(), "messages.stats_usage", null, plugin.getMessagePrefix()));
			return false;
		}

		// Collect balances via filters
		String statsWorld = plugin.getConfig().getString("stats-world", "");
		java.util.List<Double> balances = org.cubexmc.ecobalancer.utils.AnalysisFilters.collectFilteredBalances(pr.criteria, statsWorld);
		if (balances == null || balances.isEmpty()) {
			sender.sendMessage(MessageUtils.formatMessage(plugin.getLangConfig(), "messages.gini.no_data", null, plugin.getMessagePrefix()));
			return true;
		}

		// Generate histogram from filtered balances, and preserve original filter tokens for clickable interval links
		plugin.generateHistogramFromBalances(sender, numBars, balances, args);
		return true;
    }
}
