package org.cubexmc.ecobalancer.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.cubexmc.ecobalancer.utils.EconomicMetrics;
import org.cubexmc.ecobalancer.utils.AnalysisFilters;
import org.cubexmc.ecobalancer.utils.SchedulerUtils;

import java.util.*;

/**
 * 财富集中度命令
 * 用法: /ecobal concentration [percentages...]
 * - 不带参数：显示 Top 1%, 5%, 10%, 20% 的财富占比
 * - 带参数：显示指定百分比的财富占比，如 /ecobal concentration 1 10 25
 */
public class ConcentrationCommand implements CommandExecutor {
    private final EcoBalancer plugin;

    public ConcentrationCommand(EcoBalancer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 解析过滤参数
        AnalysisFilters.ParseResult pr = AnalysisFilters.parse(args);
        AnalysisFilters.FilterCriteria criteria = pr.criteria;
        List<String> rest = pr.remainingArgs;

        // 解析百分比位置参数
        List<Double> percentages = new ArrayList<>();
        if (rest.isEmpty()) {
            percentages.addAll(Arrays.asList(1.0, 5.0, 10.0, 20.0));
        } else {
            for (String arg : rest) {
                try {
                    double pct = Double.parseDouble(arg);
                    if (pct <= 0 || pct > 100) {
                        Map<String, String> placeholders = new HashMap<>();
                        placeholders.put("value", arg);
                        sender.sendMessage(plugin.getFormattedMessage("messages.concentration.invalid_percentage", placeholders));
                        return false;
                    }
                    percentages.add(pct);
                } catch (NumberFormatException e) {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("input", arg);
                    sender.sendMessage(plugin.getFormattedMessage("messages.concentration.invalid_number", placeholders));
                    return false;
                }
            }
        }

        // 去重并排序
        Set<Double> uniquePercentages = new TreeSet<>(percentages);
        final List<Double> finalPercentages = new ArrayList<>(uniquePercentages);

        sender.sendMessage(plugin.getFormattedMessage("messages.concentration.calculating", null));

        // 异步计算
        SchedulerUtils.runTaskAsync(plugin, () -> {
            try {
                // 收集余额数据（带过滤）
                List<Double> balances = AnalysisFilters.collectFilteredBalances(criteria, plugin.getConfig().getString("stats-world", ""));

                if (balances.isEmpty()) {
                    SchedulerUtils.runTask(plugin, () -> {
                        sender.sendMessage(plugin.getFormattedMessage("messages.concentration.no_data", null));
                    });
                    return;
                }

                double totalMoney = EconomicMetrics.calculateTotalMoney(balances);
                int totalPlayers = balances.size();

                // 计算每个百分比的集中度
                List<ConcentrationResult> results = new ArrayList<>();
                for (double pct : finalPercentages) {
                    double concentration = EconomicMetrics.calculateConcentration(balances, pct);
                    int topCount = Math.max(1, (int) Math.ceil(totalPlayers * pct / 100.0));
                    results.add(new ConcentrationResult(pct, concentration, topCount));
                }

                // 主线程发送结果
                SchedulerUtils.runTask(plugin, () -> {
                    Map<String, String> headerPlaceholders = new HashMap<>();
                    headerPlaceholders.put("total_players", String.valueOf(totalPlayers));
                    headerPlaceholders.put("total_money", EconomicMetrics.formatLargeNumber(totalMoney));

                    sender.sendMessage(plugin.getFormattedMessage("messages.concentration.header", headerPlaceholders));

                    for (ConcentrationResult result : results) {
                        Map<String, String> placeholders = new HashMap<>();
                        placeholders.put("percentage", String.format("%.1f%%", result.percentage));
                        placeholders.put("concentration", String.format("%.2f%%", result.concentration * 100));
                        placeholders.put("player_count", String.valueOf(result.playerCount));
                        placeholders.put("level", EconomicMetrics.getConcentrationLevel(result.concentration));

                        sender.sendMessage(plugin.getFormattedMessage("messages.concentration.result_line", placeholders));
                    }

                    // 警告信息
                    double top1 = results.isEmpty() ? 0 : results.get(0).concentration;
                    if (top1 >= 0.6) {
                        sender.sendMessage(plugin.getFormattedMessage("messages.concentration.warning_extreme", null));
                    } else if (top1 >= 0.4) {
                        sender.sendMessage(plugin.getFormattedMessage("messages.concentration.warning_high", null));
                    }
                });

            } catch (Exception e) {
                SchedulerUtils.runTask(plugin, () -> {
                    sender.sendMessage(plugin.getFormattedMessage("messages.concentration.error", null));
                    plugin.getLogger().severe("Error calculating concentration: " + e.getMessage());
                    e.printStackTrace();
                });
            }
        });

        return true;
    }

    /**
     * 集中度结果数据类
     */
    private static class ConcentrationResult {
        final double percentage;
        final double concentration;
        final int playerCount;

        ConcentrationResult(double percentage, double concentration, int playerCount) {
            this.percentage = percentage;
            this.concentration = concentration;
            this.playerCount = playerCount;
        }
    }
}
