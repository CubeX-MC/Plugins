package org.cubexmc.ecobalancer.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.cubexmc.ecobalancer.utils.DatabaseUtils;
import org.cubexmc.ecobalancer.utils.EconomicMetrics;
import org.cubexmc.ecobalancer.utils.MessageUtils;
import org.cubexmc.ecobalancer.utils.SchedulerUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 经济趋势分析命令
 * 分析历史快照数据，展示经济指标的趋势变化
 */
public class TrendsCommand implements CommandExecutor {
    private final EcoBalancer plugin;

    public TrendsCommand(EcoBalancer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 默认查询30天的数据
        int days = 30;
        if (args.length > 0) {
            try {
                days = Integer.parseInt(args[0]);
                if (days < 1 || days > 365) {
                    MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.trends.invalid_days", null), plugin.getLogger(), false);
                    return true;
                }
            } catch (NumberFormatException e) {
                MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.trends.invalid_days", null), plugin.getLogger(), false);
                return true;
            }
        }

        final int queryDays = days;
        // 进度提示
        MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.trends.loading", null), plugin.getLogger(), false);
        
        // 异步加载数据
        SchedulerUtils.asyncRun(plugin, () -> {
            try {
                List<DatabaseUtils.EconomicSnapshot> snapshots = 
                    DatabaseUtils.getSnapshotHistory(plugin, queryDays, plugin.getLogger());
                
                if (snapshots.isEmpty()) {
                    SchedulerUtils.runTask(plugin, () -> {
                        MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.trends.no_data", null), plugin.getLogger(), false);
                    });
                    return;
                }

                // 回到主线程发送报告
                SchedulerUtils.runTask(plugin, () -> {
                    sendTrendsReport(sender, snapshots, queryDays);
                });

            } catch (Exception e) {
                plugin.getLogger().severe("获取趋势数据失败: " + e.getMessage());
                e.printStackTrace();
                SchedulerUtils.runTask(plugin, () -> {
                    MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.trends.error", null), plugin.getLogger(), false);
                });
            }
        }, 0);

        return true;
    }

    /**
     * 简化的发送消息方法
     */
    private void msg(CommandSender sender, String message) {
        MessageUtils.sendMessage(sender, message, plugin.getLogger(), false);
    }

    /**
     * 发送趋势报告
     */
    private void sendTrendsReport(CommandSender sender, List<DatabaseUtils.EconomicSnapshot> snapshots, int days) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        
        DatabaseUtils.EconomicSnapshot first = snapshots.get(0);
        DatabaseUtils.EconomicSnapshot last = snapshots.get(snapshots.size() - 1);

        msg(sender, plugin.getFormattedMessage("messages.impact.banner", null));
        msg(sender, "&e&l         经济趋势分析报告");
        msg(sender, String.format("&7时间范围: %s ~ %s (&f%d &7天)", 
            dateFormat.format(new Date(first.timestamp)), 
            dateFormat.format(new Date(last.timestamp)), days));
        msg(sender, String.format("&7数据点数: &f%d &7个快照", snapshots.size()));
        msg(sender, plugin.getFormattedMessage("messages.impact.banner", null));
        msg(sender, "");

        // 基尼系数趋势
        msg(sender, "&e&l▸ 不平等程度趋势 (基尼系数):");
        double giniChange = last.gini - first.gini;
        double giniChangePercent = (first.gini > 0) ? (giniChange / first.gini * 100) : 0;
        String giniTrend = getTrendIndicator(giniChange, true);
        
        msg(sender, String.format("  %.3f → %.3f (%s%.3f, %s%.1f%%&7)", 
            first.gini, last.gini, 
            (giniChange >= 0 ? "&c+" : "&a"), giniChange,
            (giniChange >= 0 ? "&c+" : "&a"), giniChangePercent));
        msg(sender, "  " + generateTrendChart(snapshots, "gini"));
        msg(sender, "  " + giniTrend);
        msg(sender, "");

        // 财富集中度趋势
        msg(sender, "&e&l▸ 财富集中度趋势 (Top 1%):");
        double top1Change = (last.top1Pct - first.top1Pct) * 100;
        double top1ChangePercent = (first.top1Pct > 0) ? (top1Change / (first.top1Pct * 100) * 100) : 0;
        String top1Trend = getTrendIndicator(top1Change, true);
        
        msg(sender, String.format("  %.1f%% → %.1f%% (%s%.1f%%, %s%.1f%%&7)", 
            first.top1Pct * 100, last.top1Pct * 100,
            (top1Change >= 0 ? "&c+" : "&a"), top1Change,
            (top1Change >= 0 ? "&c+" : "&a"), top1ChangePercent));
        msg(sender, "  " + generateTrendChart(snapshots, "top1"));
        msg(sender, "  " + top1Trend);
        msg(sender, "");

        // 总货币量趋势
        msg(sender, "&e&l▸ 经济规模趋势 (总货币量):");
        double moneyChange = last.totalMoney - first.totalMoney;
        double moneyChangePercent = (first.totalMoney > 0) ? (moneyChange / first.totalMoney * 100) : 0;
        String moneyTrend = getTrendIndicator(moneyChange, false);
        
        msg(sender, String.format("  %s → %s (%s%s, %s%.1f%%&7)", 
            EconomicMetrics.formatLargeNumber(first.totalMoney),
            EconomicMetrics.formatLargeNumber(last.totalMoney),
            (moneyChange >= 0 ? "&a+" : "&c"), EconomicMetrics.formatLargeNumber(moneyChange),
            (moneyChange >= 0 ? "&a+" : "&c"), moneyChangePercent));
        msg(sender, "  " + generateTrendChart(snapshots, "totalMoney"));
        msg(sender, "  " + moneyTrend);
        msg(sender, "");

        // 玩家数量趋势
        msg(sender, "&e&l▸ 玩家数量趋势:");
        int playerChange = last.playerCount - first.playerCount;
        double playerChangePercent = (first.playerCount > 0) ? ((double)playerChange / first.playerCount * 100) : 0;
        
        msg(sender, String.format("  %d → %d (%s%d, %s%.1f%%&7)", 
            first.playerCount, last.playerCount,
            (playerChange >= 0 ? "&a+" : "&c"), playerChange,
            (playerChange >= 0 ? "&a+" : "&c"), playerChangePercent));
        msg(sender, "  " + generateTrendChart(snapshots, "playerCount"));
        msg(sender, "");

        // 综合评价
        msg(sender, "&e&l▸ 趋势综合评价:");
        String assessment = getOverallTrendAssessment(giniChange, top1Change, moneyChange);
        msg(sender, "  " + assessment);
        
        msg(sender, "");
        msg(sender, "&6&l════════════════════════════════════");
    }

    /**
     * 生成 ASCII 趋势图
     */
    private String generateTrendChart(List<DatabaseUtils.EconomicSnapshot> snapshots, String metric) {
        final int width = 40;
        final int height = 5;
        
        // 提取指标数据
        double[] values = new double[snapshots.size()];
        for (int i = 0; i < snapshots.size(); i++) {
            DatabaseUtils.EconomicSnapshot snap = snapshots.get(i);
            switch (metric) {
                case "gini":
                    values[i] = snap.gini;
                    break;
                case "top1":
                    values[i] = snap.top1Pct;
                    break;
                case "totalMoney":
                    values[i] = snap.totalMoney;
                    break;
                case "playerCount":
                    values[i] = snap.playerCount;
                    break;
                default:
                    values[i] = 0;
            }
        }

        // 找到最大最小值
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        for (double v : values) {
            if (v < min) min = v;
            if (v > max) max = v;
        }

        // 避免除以零
        if (max == min) {
            return ChatColor.translateAlternateColorCodes('&', 
                "&7[" + "━".repeat(width) + "] &e→ 稳定");
        }

        // 归一化到图表高度
        int[] heights = new int[width];
        for (int i = 0; i < width; i++) {
            int dataIndex = (int) ((double) i / width * values.length);
            double normalized = (values[dataIndex] - min) / (max - min);
            heights[i] = (int) (normalized * height);
        }

        // 绘制图表
        StringBuilder chart = new StringBuilder("&7[");
        for (int i = 0; i < width; i++) {
            int h = heights[i];
            if (h == 0) {
                chart.append("&8_");
            } else if (h <= height / 3) {
                chart.append("&a▁");
            } else if (h <= height * 2 / 3) {
                chart.append("&e▄");
            } else {
                chart.append("&c▇");
            }
        }
        chart.append("&7]");

        // 判断整体趋势
        String trendArrow;
        if (values[values.length - 1] > values[0] * 1.05) {
            trendArrow = " &c↑";
        } else if (values[values.length - 1] < values[0] * 0.95) {
            trendArrow = " &a↓";
        } else {
            trendArrow = " &e→";
        }

        return ChatColor.translateAlternateColorCodes('&', chart.toString() + trendArrow);
    }

    /**
     * 获取趋势指示器
     */
    private String getTrendIndicator(double change, boolean lowerIsBetter) {
        if (Math.abs(change) < 0.001) {
            return "&e→ 趋势平稳";
        }
        
        boolean isImproving = lowerIsBetter ? (change < 0) : (change > 0);
        String arrow = (change < 0) ? "↓" : "↑";
        String color = isImproving ? "&a" : "&c";
        String status;
        
        double absChange = Math.abs(change);
        String magnitude;
        if (absChange > 0.1 || absChange > 10) {
            magnitude = "显著";
        } else if (absChange > 0.05 || absChange > 5) {
            magnitude = "明显";
        } else {
            magnitude = "轻微";
        }
        
        if (isImproving) {
            status = magnitude + "改善";
        } else {
            status = magnitude + "恶化";
        }
        
        return String.format("%s%s 趋势%s", color, arrow, status);
    }

    /**
     * 获取整体趋势评价
     */
    private String getOverallTrendAssessment(double giniChange, double top1Change, double moneyChange) {
        boolean giniImprove = giniChange < -0.01;
        boolean top1Improve = top1Change < -0.5;
        boolean moneyGrow = moneyChange > 0;
        
        int improveCount = (giniImprove ? 1 : 0) + (top1Improve ? 1 : 0) + (moneyGrow ? 1 : 0);
        
        if (improveCount >= 2) {
            if (giniImprove && top1Improve && moneyGrow) {
                return "&a&l✓ 经济状况持续向好，平等性改善且规模增长";
            } else {
                return "&a&l✓ 经济整体呈现积极趋势";
            }
        } else if (improveCount == 1) {
            return "&e&l⚠ 经济趋势喜忧参半，需持续关注";
        } else {
            if (!giniImprove && !top1Improve) {
                return "&c&l✗ 不平等程度加剧，建议调整税收政策";
            } else {
                return "&c&l✗ 经济状况需要改善，建议采取措施";
            }
        }
    }
}
