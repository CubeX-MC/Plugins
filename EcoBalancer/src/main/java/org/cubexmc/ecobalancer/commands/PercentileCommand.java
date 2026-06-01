package org.cubexmc.ecobalancer.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.cubexmc.ecobalancer.utils.MessageUtils;
import org.cubexmc.ecobalancer.utils.StatisticsUtils;
import org.cubexmc.ecobalancer.utils.AnalysisFilters;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PercentileCommand implements CommandExecutor {
    private final EcoBalancer plugin;

    public PercentileCommand(EcoBalancer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // New syntax: <balance> then optional filter tokens (d:, p:, l:, u:, lr:, ur:)
        if (args.length < 1) {
            sender.sendMessage(MessageUtils.formatMessage(plugin.getLangConfig(), "messages.perc_usage", null, plugin.getMessagePrefix()));
            return false;
        }

        double balance;
        try {
            balance = Double.parseDouble(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(MessageUtils.formatMessage(plugin.getLangConfig(), "messages.perc_invalid_args", null, plugin.getMessagePrefix()));
            return false;
        }

        AnalysisFilters.ParseResult pr = AnalysisFilters.parse(java.util.Arrays.copyOfRange(args, 1, args.length));
        String statsWorld = plugin.getConfig().getString("stats-world", "");
        List<Double> balances = org.cubexmc.ecobalancer.utils.AnalysisFilters.collectFilteredBalances(pr.criteria, statsWorld);
        
        // 计算百分位数
        double percentile = StatisticsUtils.calculatePercentile(balance, balances);
        
        // 准备消息占位符
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("balance", String.format("%.2f", balance));
        placeholders.put("percentile", String.format("%.2f", percentile));
        double low = pr.criteria.minBalance == null ? Double.NEGATIVE_INFINITY : pr.criteria.minBalance;
        double up = pr.criteria.maxBalance == null ? Double.POSITIVE_INFINITY : pr.criteria.maxBalance;
        placeholders.put("low", low == Double.NEGATIVE_INFINITY ? "∞" : String.format("%.2f", low));
        placeholders.put("up", up == Double.POSITIVE_INFINITY ? "∞" : String.format("%.2f", up));
        
        // 发送结果消息
        sender.sendMessage(MessageUtils.formatMessage(plugin.getLangConfig(), "messages.perc_success", placeholders, plugin.getMessagePrefix()));
        return true;
    }
}
