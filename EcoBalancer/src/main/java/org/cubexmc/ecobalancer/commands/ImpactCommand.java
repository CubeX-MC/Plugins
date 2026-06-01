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
import java.util.Map;
import java.util.Date;
import java.util.List;

/**
 * 税收影响分析命令
 * 对比税收操作前后的经济指标变化
 */
public class ImpactCommand implements CommandExecutor {
    private final EcoBalancer plugin;

    public ImpactCommand(EcoBalancer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 进度提示
        sender.sendMessage(plugin.getFormattedMessage("messages.impact.loading", null));
        // 异步加载数据
        SchedulerUtils.asyncRun(plugin, () -> {
            try {
                DatabaseUtils.OperationImpact impact;
                
                // 如果提供了操作ID参数，获取指定操作的影响数据
                if (args.length > 0) {
                    try {
                        int operationId = Integer.parseInt(args[0]);
                        impact = DatabaseUtils.getOperationImpact(plugin, operationId, plugin.getLogger());
                        
                        if (impact == null) {
                            SchedulerUtils.runTask(plugin, () -> {
                                Map<String, String> ph = new java.util.HashMap<>();
                                ph.put("operation_id", Integer.toString(operationId));
                                MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.impact.not_found", ph), plugin.getLogger(), false);
                                MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.impact.hint_latest", null), plugin.getLogger(), false);
                            });
                            return;
                        }
                    } catch (NumberFormatException e) {
                        SchedulerUtils.runTask(plugin, () -> {
                            Map<String, String> ph = new java.util.HashMap<>();
                            ph.put("id", args[0]);
                            MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.impact.invalid_id", ph), plugin.getLogger(), false);
                        });
                        return;
                    }
                } else {
                    // 默认获取最近一次操作的影响数据
                    List<DatabaseUtils.OperationImpact> recentImpacts = 
                        DatabaseUtils.getRecentImpacts(plugin, 1, plugin.getLogger());
                    
                    if (recentImpacts.isEmpty()) {
                        SchedulerUtils.runTask(plugin, () -> {
                            MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.report.no_operations", null), plugin.getLogger(), false);
                            MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.impact.hint_latest", null), plugin.getLogger(), false);
                        });
                        return;
                    }
                    
                    impact = recentImpacts.get(0);
                }

                // 回到主线程发送报告
                final DatabaseUtils.OperationImpact finalImpact = impact;
                SchedulerUtils.runTask(plugin, () -> {
                    sendImpactReport(sender, finalImpact);
                });

            } catch (Exception e) {
                plugin.getLogger().severe("获取税收影响数据失败: " + e.getMessage());
                e.printStackTrace();
                SchedulerUtils.runTask(plugin, () -> {
                    MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.impact.error", null), plugin.getLogger(), false);
                });
            }
        }, 0);

        return true;
    }

    /**
     * 简化的发送消息方法
     */
    private void msg(CommandSender sender, String message) {
        MessageUtils.sendMessage(sender, ChatColor.translateAlternateColorCodes('&', message), plugin.getLogger(), false);
    }

    /**
     * 发送税收影响报告
     */
    private void sendImpactReport(CommandSender sender, DatabaseUtils.OperationImpact impact) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String timestamp = dateFormat.format(new Date(impact.timestamp));

        // Header
        msg(sender, plugin.getFormattedMessage("messages.impact.banner", null));
        Map<String, String> header = new java.util.HashMap<>();
        header.put("operation_id", Integer.toString(impact.operationId));
        header.put("timestamp", timestamp);
        msg(sender, plugin.getFormattedMessage("messages.impact.title", null));
        msg(sender, plugin.getFormattedMessage("messages.impact.operation_line", header));
        msg(sender, plugin.getFormattedMessage("messages.impact.banner", null));
        msg(sender, "");

        // Overview
        msg(sender, plugin.getFormattedMessage("messages.impact.section_overview", null));
        Map<String, String> overview = new java.util.HashMap<>();
        overview.put("total_tax", EconomicMetrics.formatLargeNumber(impact.totalTaxCollected));
        overview.put("players", Integer.toString(impact.playersAffected));
        msg(sender, plugin.getFormattedMessage("messages.impact.collected", overview));
        msg(sender, plugin.getFormattedMessage("messages.impact.affected", overview));
        msg(sender, "");

        // Gini change
        msg(sender, plugin.getFormattedMessage("messages.impact.section_inequality", null));
        double giniChange = impact.afterGini - impact.beforeGini;
        double giniChangePercent = (impact.beforeGini > 0) ? (giniChange / impact.beforeGini * 100) : 0;
        String giniTrend = getChangeIndicator(giniChange, true); // lower is better
        String giniColor = (giniChange < 0) ? "&a" : (giniChange > 0) ? "&c" : "&e";

        Map<String, String> gini = new java.util.HashMap<>();
        gini.put("before", String.format("%.3f", impact.beforeGini));
        gini.put("after", String.format("%.3f", impact.afterGini));
        gini.put("delta_color", giniColor);
        gini.put("delta", String.format("%.3f", giniChange));
        gini.put("delta_pct", String.format("%.1f", giniChangePercent));
        msg(sender, plugin.getFormattedMessage("messages.impact.gini_line", gini));
        msg(sender, "  " + generateComparisonBar(impact.beforeGini, impact.afterGini, 1.0, 20));
        msg(sender, String.format("    &8└─ %s %s", giniTrend, getGiniChangeDescription(giniChange)));
        msg(sender, "");

        // Concentration change (Top 1%)
        msg(sender, plugin.getFormattedMessage("messages.impact.section_concentration", null));
        double top1Change = impact.afterTop1Pct - impact.beforeTop1Pct;
        double top1ChangePercent = (impact.beforeTop1Pct > 0) ? (top1Change / impact.beforeTop1Pct * 100) : 0;
        String top1Trend = getChangeIndicator(top1Change, true);
        String top1Color = (top1Change < 0) ? "&a" : (top1Change > 0) ? "&c" : "&e";

        Map<String, String> top1 = new java.util.HashMap<>();
        top1.put("before", String.format("%.1f", impact.beforeTop1Pct * 100));
        top1.put("after", String.format("%.1f", impact.afterTop1Pct * 100));
        top1.put("delta_color", top1Color);
        top1.put("delta", String.format("%.1f", top1Change * 100));
        top1.put("delta_pct", String.format("%.1f", top1ChangePercent));
        msg(sender, plugin.getFormattedMessage("messages.impact.top1_line", top1));
        msg(sender, "  " + generateComparisonBar(impact.beforeTop1Pct, impact.afterTop1Pct, 1.0, 20));
        msg(sender, String.format("    &8└─ %s %s", top1Trend, getConcentrationChangeDescription(top1Change)));
        msg(sender, "");

        // Median and mean changes
        msg(sender, plugin.getFormattedMessage("messages.impact.section_distribution", null));
        
        // 中位数
        double medianChange = impact.afterMedian - impact.beforeMedian;
        double medianChangePercent = (impact.beforeMedian > 0) ? (medianChange / impact.beforeMedian * 100) : 0;
        String medianTrend = getChangeIndicator(medianChange, false); // false表示值升高是改善
        String medianColor = (medianChange > 0) ? "&a" : (medianChange < 0) ? "&c" : "&e";
        
        Map<String, String> med = new java.util.HashMap<>();
        med.put("before", EconomicMetrics.formatLargeNumber(impact.beforeMedian));
        med.put("after", EconomicMetrics.formatLargeNumber(impact.afterMedian));
        med.put("delta_color", medianColor);
        med.put("delta", EconomicMetrics.formatLargeNumber(medianChange));
        med.put("delta_pct", String.format("%.1f", medianChangePercent));
        msg(sender, plugin.getFormattedMessage("messages.impact.median_line", med));
        msg(sender, String.format("    &8└─ %s", medianTrend));
        
        // 均值
        double meanChange = impact.afterMean - impact.beforeMean;
        double meanChangePercent = (impact.beforeMean > 0) ? (meanChange / impact.beforeMean * 100) : 0;
        String meanTrend = getChangeIndicator(meanChange, false);
        String meanColor = (meanChange > 0) ? "&a" : (meanChange < 0) ? "&c" : "&e";
        
        Map<String, String> mean = new java.util.HashMap<>();
        mean.put("before", EconomicMetrics.formatLargeNumber(impact.beforeMean));
        mean.put("after", EconomicMetrics.formatLargeNumber(impact.afterMean));
        mean.put("delta_color", meanColor);
        mean.put("delta", EconomicMetrics.formatLargeNumber(meanChange));
        mean.put("delta_pct", String.format("%.1f", meanChangePercent));
        msg(sender, plugin.getFormattedMessage("messages.impact.mean_line", mean));
        msg(sender, String.format("    &8└─ %s", meanTrend));
        
        // 标准差
        double stdDevChange = impact.afterStdDev - impact.beforeStdDev;
        double stdDevChangePercent = (impact.beforeStdDev > 0) ? (stdDevChange / impact.beforeStdDev * 100) : 0;
        String stdDevTrend = getChangeIndicator(stdDevChange, true); // 标准差降低是改善
        String stdDevColor = (stdDevChange < 0) ? "&a" : (stdDevChange > 0) ? "&c" : "&e";
        
        Map<String, String> sd = new java.util.HashMap<>();
        sd.put("before", EconomicMetrics.formatLargeNumber(impact.beforeStdDev));
        sd.put("after", EconomicMetrics.formatLargeNumber(impact.afterStdDev));
        sd.put("delta_color", stdDevColor);
        sd.put("delta", EconomicMetrics.formatLargeNumber(stdDevChange));
        sd.put("delta_pct", String.format("%.1f", stdDevChangePercent));
        msg(sender, plugin.getFormattedMessage("messages.impact.stddev_line", sd));
        msg(sender, String.format("    &8└─ %s", stdDevTrend));
        msg(sender, "");

        // Total money change
        msg(sender, plugin.getFormattedMessage("messages.impact.section_scale", null));
        double totalMoneyChange = impact.afterTotalMoney - impact.beforeTotalMoney;
        double totalMoneyChangePercent = (impact.beforeTotalMoney > 0) ? 
            (totalMoneyChange / impact.beforeTotalMoney * 100) : 0;

        Map<String, String> scale = new java.util.HashMap<>();
        scale.put("before", EconomicMetrics.formatLargeNumber(impact.beforeTotalMoney));
        scale.put("after", EconomicMetrics.formatLargeNumber(impact.afterTotalMoney));
        scale.put("change", EconomicMetrics.formatLargeNumber(totalMoneyChange));
        scale.put("percent", String.format("%.2f", totalMoneyChangePercent));
        scale.put("removed_percent", String.format("%.2f", Math.abs(totalMoneyChangePercent)));
        msg(sender, plugin.getFormattedMessage("messages.impact.total_line", scale));
        msg(sender, plugin.getFormattedMessage("messages.impact.change_line", scale));
        msg(sender, plugin.getFormattedMessage("messages.impact.removed_line", scale));
        msg(sender, "");

        // Overall assessment
        msg(sender, plugin.getFormattedMessage("messages.impact.section_assessment", null));
        String overallAssessment = getOverallAssessment(giniChange, top1Change, medianChange);
        msg(sender, "  " + overallAssessment);
        
        msg(sender, "");
        msg(sender, plugin.getFormattedMessage("messages.impact.banner", null));
    }

    /**
     * 生成对比进度条
     */
    private String generateComparisonBar(double before, double after, double max, int length) {
        int beforePos = (int) Math.round((before / max) * length);
        int afterPos = (int) Math.round((after / max) * length);
        beforePos = Math.max(0, Math.min(length, beforePos));
        afterPos = Math.max(0, Math.min(length, afterPos));
        
        StringBuilder bar = new StringBuilder("  &7[");
        
        for (int i = 0; i < length; i++) {
            if (i == beforePos && i == afterPos) {
                bar.append("&e◆"); // 没变化
            } else if (i == beforePos) {
                bar.append("&c●"); // 之前的位置
            } else if (i == afterPos) {
                bar.append("&a●"); // 之后的位置
            } else if ((beforePos < afterPos && i > beforePos && i < afterPos) ||
                      (beforePos > afterPos && i > afterPos && i < beforePos)) {
                bar.append("&7─"); // 变化区间
            } else {
                bar.append("&8░"); // 空白区域
            }
        }
        
        bar.append("&7] &c● &7→ &a●");
        return ChatColor.translateAlternateColorCodes('&', bar.toString());
    }

    /**
     * 获取变化指示器
     * @param change 变化值
     * @param lowerIsBetter 是否值降低代表改善
     */
    private String getChangeIndicator(double change, boolean lowerIsBetter) {
        if (Math.abs(change) < 0.001) {
            return plugin.getFormattedMessage("messages.impact.trend.stable", null);
        }

        boolean isImprovement = lowerIsBetter ? (change < 0) : (change > 0);
        String arrow = (change < 0) ? "↓" : "↑";
        String color = isImprovement ? "&a" : "&c";
        String key = isImprovement ? "messages.impact.trend.improve" : "messages.impact.trend.worsen";
        return String.format("%s%s %s", color, arrow, plugin.getFormattedMessage(key, null));
    }

    /**
     * 获取基尼系数变化描述
     */
    private String getGiniChangeDescription(double change) {
        double absChange = Math.abs(change);
        if (absChange >= 0.1) {
            return plugin.getFormattedMessage("messages.impact.gini_delta.major", null);
        } else if (absChange >= 0.05) {
            return plugin.getFormattedMessage("messages.impact.gini_delta.noticeable", null);
        } else if (absChange >= 0.01) {
            return plugin.getFormattedMessage("messages.impact.gini_delta.slight", null);
        } else {
            return plugin.getFormattedMessage("messages.impact.gini_delta.tiny", null);
        }
    }

    /**
     * 获取集中度变化描述
     */
    private String getConcentrationChangeDescription(double change) {
        double absChange = Math.abs(change) * 100;
        if (absChange >= 5) {
            return plugin.getFormattedMessage("messages.impact.concentration_delta.major", null);
        } else if (absChange >= 2) {
            return plugin.getFormattedMessage("messages.impact.concentration_delta.noticeable", null);
        } else if (absChange >= 0.5) {
            return plugin.getFormattedMessage("messages.impact.concentration_delta.slight", null);
        } else {
            return plugin.getFormattedMessage("messages.impact.concentration_delta.tiny", null);
        }
    }

    /**
     * 获取综合评价
     */
    private String getOverallAssessment(double giniChange, double top1Change, double medianChange) {
        int improveCount = 0;
        int worsenCount = 0;
        
        // 基尼系数：降低是改善
        if (giniChange < -0.01) improveCount++;
        else if (giniChange > 0.01) worsenCount++;
        
        // 集中度：降低是改善
        if (top1Change < -0.005) improveCount++;
        else if (top1Change > 0.005) worsenCount++;
        
        // 中位数：升高是改善
        if (medianChange > 0) improveCount++;
        else if (medianChange < 0) worsenCount++;
        
        if (improveCount >= 2 && worsenCount == 0) {
            return plugin.getFormattedMessage("messages.impact.assessment.strong_improve", null);
        } else if (improveCount > worsenCount) {
            return plugin.getFormattedMessage("messages.impact.assessment.improve", null);
        } else if (improveCount == worsenCount) {
            return plugin.getFormattedMessage("messages.impact.assessment.neutral", null);
        } else if (worsenCount > improveCount) {
            return plugin.getFormattedMessage("messages.impact.assessment.worsen", null);
        } else {
            return plugin.getFormattedMessage("messages.impact.assessment.very_bad", null);
        }
    }
}
