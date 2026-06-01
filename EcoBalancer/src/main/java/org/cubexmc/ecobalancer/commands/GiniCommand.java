package org.cubexmc.ecobalancer.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.cubexmc.ecobalancer.utils.EconomicMetrics;
import org.cubexmc.ecobalancer.utils.AnalysisFilters;
import org.cubexmc.ecobalancer.utils.SchedulerUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基尼系数命令
 * 用法: /ecobal gini [days]
 * - 不带参数：计算所有玩家的基尼系数
 * - 带days参数：仅计算N天内活跃玩家的基尼系数
 */
public class GiniCommand implements CommandExecutor {
    private final EcoBalancer plugin;

    public GiniCommand(EcoBalancer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 解析过滤参数（WorldEdit风格 key:value），其余保留给位置参数（兼容旧用法）
        AnalysisFilters.ParseResult pr = AnalysisFilters.parse(args);
        AnalysisFilters.FilterCriteria criteria = pr.criteria;

        // 提示开始计算
        sender.sendMessage(plugin.getFormattedMessage("messages.gini.calculating", null));

        final AnalysisFilters.FilterCriteria finalCriteria = criteria;

        // 异步计算（避免阻塞主线程）
        SchedulerUtils.runTaskAsync(plugin, () -> {
            try {
                // 收集余额数据（带过滤）
                List<Double> balances = AnalysisFilters.collectFilteredBalances(finalCriteria, plugin.getConfig().getString("stats-world", ""));

                if (balances.isEmpty()) {
                    SchedulerUtils.runTask(plugin, () -> {
                        sender.sendMessage(plugin.getFormattedMessage("messages.gini.no_data", null));
                    });
                    return;
                }

                // 计算基尼系数
                double gini = EconomicMetrics.calculateGini(balances);
                double totalMoney = EconomicMetrics.calculateTotalMoney(balances);
                String giniLevel = EconomicMetrics.getGiniLevel(gini);

                // 主线程发送结果
                SchedulerUtils.runTask(plugin, () -> {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("gini", String.format("%.4f", gini));
                    placeholders.put("gini_percentage", String.format("%.2f%%", gini * 100));
                    placeholders.put("level", giniLevel);
                    placeholders.put("player_count", String.valueOf(balances.size()));
                    placeholders.put("total_money", EconomicMetrics.formatLargeNumber(totalMoney));
                    placeholders.put("days", finalCriteria.activeWithinDays == null ? "∞" : String.valueOf(finalCriteria.activeWithinDays));

                    sender.sendMessage(plugin.getFormattedMessage("messages.gini.header", null));
                    sender.sendMessage(plugin.getFormattedMessage("messages.gini.result", placeholders));
                    
                    // 额外的解释信息
                    if (gini >= 0.6) {
                        sender.sendMessage(plugin.getFormattedMessage("messages.gini.warning_high", null));
                    } else if (gini >= 0.5) {
                        sender.sendMessage(plugin.getFormattedMessage("messages.gini.warning_moderate", null));
                    } else if (gini < 0.3) {
                        sender.sendMessage(plugin.getFormattedMessage("messages.gini.info_low", null));
                    }
                });

            } catch (Exception e) {
                SchedulerUtils.runTask(plugin, () -> {
                    sender.sendMessage(plugin.getFormattedMessage("messages.gini.error", null));
                    plugin.getLogger().severe("Error calculating Gini coefficient: " + e.getMessage());
                    e.printStackTrace();
                });
            }
        });

        return true;
    }
}
