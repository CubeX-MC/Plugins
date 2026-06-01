package org.cubexmc.ecobalancer.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.cubexmc.ecobalancer.utils.DatabaseUtils;
import org.cubexmc.ecobalancer.utils.EconomicMetrics;
import org.cubexmc.ecobalancer.utils.AnalysisFilters;
import org.cubexmc.ecobalancer.utils.MessageUtils;
import org.cubexmc.ecobalancer.utils.SchedulerUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 经济健康度评估命令
 * 综合多个经济指标评估服务器经济健康状态
 */
public class HealthCommand implements CommandExecutor {
    private final EcoBalancer plugin;

    public HealthCommand(EcoBalancer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 进度提示
        MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.health.calculating", null), plugin.getLogger(), false);
        // 异步计算健康度
        SchedulerUtils.asyncRun(plugin, () -> {
            try {
                // 收集余额（带过滤参数）
                AnalysisFilters.FilterCriteria criteria = AnalysisFilters.parse(args).criteria;
                List<Double> balances = AnalysisFilters.collectFilteredBalances(criteria, plugin.getConfig().getString("stats-world", ""));
                if (balances.isEmpty()) {
                    SchedulerUtils.runTask(plugin, () -> {
                        MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.health.no_data", null), plugin.getLogger(), false);
                    });
                    return;
                }

                // 计算当前指标
                double gini = EconomicMetrics.calculateGini(balances);
                double top1Pct = EconomicMetrics.calculateConcentration(balances, 1.0) * 100;
                double top10Pct = EconomicMetrics.calculateConcentration(balances, 10.0) * 100;
                
                List<Double> sortedBalances = EconomicMetrics.getSortedBalances(balances);
                double median = EconomicMetrics.calculateMedian(sortedBalances);
                double mean = EconomicMetrics.calculateMean(balances);
                double stdDev = EconomicMetrics.calculateStdDev(sortedBalances, mean);

                // 计算健康度评分
                HealthScore healthScore = calculateHealthScore(gini, top1Pct, top10Pct, stdDev, mean);

                // 获取历史数据进行趋势分析
                DatabaseUtils.EconomicSnapshot latestSnapshot = DatabaseUtils.getLatestSnapshot(plugin, plugin.getLogger());
                String trend = "";
                if (latestSnapshot != null) {
                    trend = analyzeTrend(gini, latestSnapshot.gini);
                }

                // 回到主线程发送消息
                final HealthScore finalScore = healthScore;
                final String finalTrend = trend;
                final int playerCount = balances.size();
                SchedulerUtils.runTask(plugin, () -> {
                    sendHealthReport(sender, finalScore, gini, top1Pct, top10Pct, median, mean, 
                                   stdDev, playerCount, finalTrend);
                });

            } catch (Exception e) {
                plugin.getLogger().severe("计算经济健康度失败: " + e.getMessage());
                e.printStackTrace();
                SchedulerUtils.runTask(plugin, () -> {
                    MessageUtils.sendMessage(sender, plugin.getFormattedMessage("messages.health.error", null), plugin.getLogger(), false);
                });
            }
        }, 0);

        return true;
    }

    /**
     * 计算综合健康度评分 (0-100)
     */
    private HealthScore calculateHealthScore(double gini, double top1Pct, double top10Pct, 
                                           double stdDev, double mean) {
        HealthScore score = new HealthScore();

        // 1. 基尼系数评分 (权重: 40%)
        // 理想值: 0.3-0.4, 0.5+为警戒
        double giniScore;
        if (gini <= 0.35) {
            giniScore = 100;
        } else if (gini <= 0.45) {
            giniScore = 100 - (gini - 0.35) * 500; // 0.35-0.45区间: 100-50分
        } else if (gini <= 0.6) {
            giniScore = 50 - (gini - 0.45) * 200; // 0.45-0.6区间: 50-20分
        } else {
            giniScore = Math.max(0, 20 - (gini - 0.6) * 50); // 0.6+: 20-0分
        }
        score.giniScore = giniScore;

        // 2. 财富集中度评分 (权重: 30%)
        // top1%理想值<20%, top10%理想值<50%
        double concentrationScore;
        if (top1Pct <= 20 && top10Pct <= 50) {
            concentrationScore = 100;
        } else if (top1Pct <= 30 && top10Pct <= 60) {
            concentrationScore = 80;
        } else if (top1Pct <= 40 && top10Pct <= 70) {
            concentrationScore = 60;
        } else if (top1Pct <= 50 && top10Pct <= 80) {
            concentrationScore = 40;
        } else {
            concentrationScore = 20;
        }
        score.concentrationScore = concentrationScore;

        // 3. 分布均匀度评分 (权重: 30%)
        // 使用变异系数 (CV = stdDev / mean)，理想值<1.0
        double cv = (mean > 0) ? (stdDev / mean) : Double.MAX_VALUE;
        double distributionScore;
        if (cv <= 1.0) {
            distributionScore = 100;
        } else if (cv <= 2.0) {
            distributionScore = 100 - (cv - 1.0) * 50; // 1.0-2.0: 100-50分
        } else if (cv <= 3.0) {
            distributionScore = 50 - (cv - 2.0) * 30; // 2.0-3.0: 50-20分
        } else {
            distributionScore = Math.max(0, 20 - (cv - 3.0) * 10); // 3.0+: 20-0分
        }
        score.distributionScore = distributionScore;

        // 4. 综合评分
        score.totalScore = giniScore * 0.4 + concentrationScore * 0.3 + distributionScore * 0.3;

        // 5. 健康等级判定
        if (score.totalScore >= 80) {
            score.level = HealthLevel.EXCELLENT;
        } else if (score.totalScore >= 60) {
            score.level = HealthLevel.GOOD;
        } else if (score.totalScore >= 40) {
            score.level = HealthLevel.MODERATE;
        } else if (score.totalScore >= 20) {
            score.level = HealthLevel.POOR;
        } else {
            score.level = HealthLevel.CRITICAL;
        }

        return score;
    }

    /**
     * 分析趋势
     */
    private String analyzeTrend(double currentGini, double previousGini) {
        double change = currentGini - previousGini;
        if (Math.abs(change) < 0.01) {
            return "→"; // 稳定
        } else if (change < 0) {
            return "↓"; // 改善（基尼系数下降）
        } else {
            return "↑"; // 恶化（基尼系数上升）
        }
    }

    /**
     * 简化的发送消息方法
     */
    private void msg(CommandSender sender, String message) {
        MessageUtils.sendMessage(sender, ChatColor.translateAlternateColorCodes('&', message), plugin.getLogger(), false);
    }

    /**
     * 发送健康度报告
     */
    private void sendHealthReport(CommandSender sender, HealthScore score, double gini, 
                                 double top1Pct, double top10Pct, double median, 
                                 double mean, double stdDev, int playerCount, String trend) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String timestamp = dateFormat.format(new Date());

        msg(sender, "&6&l════════════════════════════════════");
        java.util.HashMap<String, String> ph = new java.util.HashMap<>();
        msg(sender, plugin.getFormattedMessage("messages.health.title", null));
        ph.clear(); ph.put("timestamp", timestamp);
        msg(sender, plugin.getFormattedMessage("messages.health.generated_at", ph));
        msg(sender, "&6&l════════════════════════════════════");
        
        // 综合评分和等级
        String levelColor = getLevelColor(score.level);
        String levelName = getLevelName(score.level);
        msg(sender, "");
        ph.clear();
        ph.put("score", String.format("%.1f", score.totalScore));
        ph.put("level", levelName);
        msg(sender, levelColor + plugin.getFormattedMessage("messages.health.score_line", ph));
        msg(sender, generateProgressBar(score.totalScore, 100, 30, levelColor));
        
        if (!trend.isEmpty()) {
            String trendKey = trend.equals("↑") ? "messages.health.trend_up" : trend.equals("↓") ? "messages.health.trend_down" : "messages.health.trend_stable";
            msg(sender, plugin.getFormattedMessage(trendKey, null));
        }

        // 详细指标
        msg(sender, "");
        msg(sender, plugin.getFormattedMessage("messages.health.details_header", null));
        
        // 1. 基尼系数
        String giniColor = gini <= 0.4 ? "&a" : gini <= 0.5 ? "&e" : "&c";
        ph.clear();
        ph.put("gini_colored", giniColor + String.format("%.3f", gini));
        ph.put("gini_score_40", String.format("%.1f", score.giniScore * 0.4));
        msg(sender, plugin.getFormattedMessage("messages.health.gini_line", ph));
        msg(sender, "  " + generateProgressBar(gini, 1.0, 20, giniColor));
        ph.clear(); ph.put("gini_desc", getGiniDescription(gini));
        msg(sender, plugin.getFormattedMessage("messages.health.gini_desc_line", ph));

        // 2. 财富集中度
        String concColor = top1Pct <= 25 ? "&a" : top1Pct <= 40 ? "&e" : "&c";
        ph.clear(); ph.put("concentration_score_30", String.format("%.1f", score.concentrationScore * 0.3));
        msg(sender, plugin.getFormattedMessage("messages.health.concentration_header", ph));
        ph.clear(); ph.put("top1_colored", concColor + String.format("%.1f", top1Pct));
        msg(sender, plugin.getFormattedMessage("messages.health.top1_line", ph));
        msg(sender, "      " + generateProgressBar(top1Pct, 100, 18, concColor));
        ph.clear(); ph.put("top10_colored", concColor + String.format("%.1f", top10Pct));
        msg(sender, plugin.getFormattedMessage("messages.health.top10_line", ph));
        msg(sender, "      " + generateProgressBar(top10Pct, 100, 18, concColor));

        // 3. 分布均匀度
        double cv = (mean > 0) ? (stdDev / mean) : 0;
        String cvColor = cv <= 1.5 ? "&a" : cv <= 2.5 ? "&e" : "&c";
        ph.clear(); ph.put("distribution_score_30", String.format("%.1f", score.distributionScore * 0.3));
        msg(sender, plugin.getFormattedMessage("messages.health.distribution_header", ph));
        ph.clear(); ph.put("cv_colored", cvColor + String.format("%.2f", cv));
        msg(sender, plugin.getFormattedMessage("messages.health.cv_line", ph));
        ph.clear(); ph.put("mean", EconomicMetrics.formatLargeNumber(mean));
        msg(sender, plugin.getFormattedMessage("messages.health.mean_line", ph));
        ph.clear(); ph.put("median", EconomicMetrics.formatLargeNumber(median));
        msg(sender, plugin.getFormattedMessage("messages.health.median_line", ph));
        ph.clear(); ph.put("stddev", EconomicMetrics.formatLargeNumber(stdDev));
        msg(sender, plugin.getFormattedMessage("messages.health.stddev_line", ph));

        // 建议
        msg(sender, "");
        msg(sender, plugin.getFormattedMessage("messages.health.reco_header", null));
        List<String> recommendations = getRecommendationKeys(score, gini, top1Pct, cv);
        for (String key : recommendations) {
            msg(sender, plugin.getFormattedMessage("messages.health.reco." + key, null));
        }

        msg(sender, "");
        ph.clear(); ph.put("player_count", String.valueOf(playerCount));
        msg(sender, plugin.getFormattedMessage("messages.health.sample_line", ph));
        msg(sender, "&6&l════════════════════════════════════");
    }

    /**
     * 生成进度条
     */
    private String generateProgressBar(double value, double max, int length, String color) {
        int filled = (int) Math.round((value / max) * length);
        filled = Math.max(0, Math.min(length, filled));
        
        StringBuilder bar = new StringBuilder("  &7[");
        for (int i = 0; i < length; i++) {
            if (i < filled) {
                bar.append(color).append("█");
            } else {
                bar.append("&8░");
            }
        }
        bar.append("&7]");
        
        return ChatColor.translateAlternateColorCodes('&', bar.toString());
    }

    /**
     * 获取等级颜色
     */
    private String getLevelColor(HealthLevel level) {
        switch (level) {
            case EXCELLENT: return "&a";
            case GOOD: return "&2";
            case MODERATE: return "&e";
            case POOR: return "&6";
            case CRITICAL: return "&c";
            default: return "&7";
        }
    }

    /**
     * 获取等级名称
     */
    private String getLevelName(HealthLevel level) {
        String key;
        switch (level) {
            case EXCELLENT: key = "messages.health.level.excellent"; break;
            case GOOD: key = "messages.health.level.good"; break;
            case MODERATE: key = "messages.health.level.moderate"; break;
            case POOR: key = "messages.health.level.poor"; break;
            case CRITICAL: key = "messages.health.level.critical"; break;
            default: key = "messages.health.level.unknown"; break;
        }
        return ChatColor.stripColor(plugin.getFormattedMessage(key, null));
    }

    /**
     * 获取基尼系数描述
     */
    private String getGiniDescription(double gini) {
        String key;
        if (gini <= 0.3) key = "messages.health.gini_desc.very_equal";
        else if (gini <= 0.4) key = "messages.health.gini_desc.relatively_equal";
        else if (gini <= 0.5) key = "messages.health.gini_desc.moderate_gap";
        else if (gini <= 0.6) key = "messages.health.gini_desc.large_gap";
        else key = "messages.health.gini_desc.very_unequal";
        return plugin.getFormattedMessage(key, null);
    }

    /**
     * 获取改善建议
     */
    private java.util.List<String> getRecommendationKeys(HealthScore score, double gini, double top1Pct, double cv) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        if (score.level == HealthLevel.EXCELLENT) {
            keys.add("state_excellent_keep");
            keys.add("minor_tune_tax");
            return keys;
        }
        if (gini > 0.5) {
            keys.add("gini_very_high_progressive");
            keys.add("tax_rich_more");
        } else if (gini > 0.4) {
            keys.add("raise_high_income_tax_moderate");
        }
        if (top1Pct > 40) {
            keys.add("wealth_overly_concentrated");
            keys.add("special_tax_cap");
        } else if (top1Pct > 25) {
            keys.add("concentration_high_moderate_regulation");
        }
        if (cv > 2.5) {
            keys.add("cv_very_high_strong_intervention");
            keys.add("welfare_support");
        } else if (cv > 1.5) {
            keys.add("support_middle_class");
        }
        if (keys.isEmpty()) {
            keys.add("healthy_maintain");
        }
        return keys;
    }

    /**
     * 健康度评分数据类
     */
    private static class HealthScore {
        double giniScore;
        double concentrationScore;
        double distributionScore;
        double totalScore;
        HealthLevel level;
    }

    /**
     * 健康等级枚举
     */
    private enum HealthLevel {
        EXCELLENT,  // 优秀: 80+
        GOOD,       // 良好: 60-79
        MODERATE,   // 中等: 40-59
        POOR,       // 较差: 20-39
        CRITICAL    // 危急: 0-19
    }
}
