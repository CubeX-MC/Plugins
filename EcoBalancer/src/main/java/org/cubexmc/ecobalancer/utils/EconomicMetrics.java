package org.cubexmc.ecobalancer.utils;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 经济指标计算工具类
 * 提供基尼系数、财富集中度等核心经济指标的计算方法
 */
public class EconomicMetrics {

    /**
     * 计算基尼系数 (Gini Coefficient)
     * 
     * @param balances 余额列表（已排序或未排序均可）
     * @return 基尼系数 (0-1)，0表示完全平等，1表示完全不平等
     */
    public static double calculateGini(List<Double> balances) {
        if (balances == null || balances.isEmpty()) {
            return 0.0;
        }

        List<Double> sorted = new ArrayList<>(balances);
        Collections.sort(sorted);

        double sum = 0.0;
        double weightedSum = 0.0;
        int n = sorted.size();

        for (int i = 0; i < n; i++) {
            double balance = sorted.get(i);
            sum += balance;
            weightedSum += balance * (i + 1);
        }

        if (sum == 0) {
            return 0.0;
        }

        // Gini = (2 * weightedSum) / (n * sum) - (n + 1) / n
        return (2.0 * weightedSum) / (n * sum) - (n + 1.0) / n;
    }

    /**
     * 计算财富集中度（Top N% 持有的财富占比）
     * 
     * @param balances 余额列表
     * @param topPercentage 顶部百分比 (0-100)
     * @return 财富占比 (0-1)
     */
    public static double calculateConcentration(List<Double> balances, double topPercentage) {
        if (balances == null || balances.isEmpty() || topPercentage <= 0 || topPercentage > 100) {
            return 0.0;
        }

        List<Double> sorted = new ArrayList<>(balances);
        Collections.sort(sorted);
        Collections.reverse(sorted); // 降序排列

        int n = sorted.size();
        int topCount = Math.max(1, (int) Math.ceil(n * topPercentage / 100.0));

        double topSum = 0.0;
        double totalSum = 0.0;

        for (int i = 0; i < n; i++) {
            double balance = sorted.get(i);
            totalSum += balance;
            if (i < topCount) {
                topSum += balance;
            }
        }

        return totalSum == 0 ? 0.0 : topSum / totalSum;
    }

    /**
     * 收集所有玩家的余额
     * 
     * @param activeDays 可选：仅统计N天内活跃的玩家，null表示统计所有玩家
     * @return 余额列表
     */
    public static List<Double> collectBalances(Integer activeDays) {
        List<Double> balances = new ArrayList<>();
        OfflinePlayer[] players = Bukkit.getOfflinePlayers();
        long currentTime = System.currentTimeMillis();
        long cutoffTime = activeDays == null ? 0 : currentTime - (activeDays * 24L * 60 * 60 * 1000);

        for (OfflinePlayer player : players) {
            try {
                // 如果设置了活跃天数过滤
                if (activeDays != null && player.getLastPlayed() < cutoffTime) {
                    continue;
                }

                if (VaultUtils.hasAccount(player)) {
                    double balance = VaultUtils.getBalance(player);
                    if (balance >= 0) { // 仅统计非负余额
                        balances.add(balance);
                    }
                }
            } catch (Exception e) {
                // 忽略单个玩家的错误
            }
        }

        return balances;
    }

    /**
     * 计算总货币量
     * 
     * @param balances 余额列表
     * @return 总货币量
     */
    public static double calculateTotalMoney(List<Double> balances) {
        if (balances == null || balances.isEmpty()) {
            return 0.0;
        }
        return balances.stream().mapToDouble(Double::doubleValue).sum();
    }

    /**
     * 获取集中度等级描述
     * 
     * @param concentration 集中度 (0-1)
     * @return 描述文本
     */
    public static String getConcentrationLevel(double concentration) {
        if (concentration >= 0.7) {
            return "极度集中";
        } else if (concentration >= 0.5) {
            return "高度集中";
        } else if (concentration >= 0.3) {
            return "中度集中";
        } else {
            return "分散";
        }
    }

    /**
     * 获取基尼系数等级描述
     * 
     * @param gini 基尼系数 (0-1)
     * @return 描述文本
     */
    public static String getGiniLevel(double gini) {
        if (gini >= 0.6) {
            return "极度不平等";
        } else if (gini >= 0.5) {
            return "高度不平等";
        } else if (gini >= 0.4) {
            return "中等不平等";
        } else if (gini >= 0.3) {
            return "相对平等";
        } else {
            return "高度平等";
        }
    }

    /**
     * 格式化大数字为易读格式
     * 
     * @param number 数字
     * @return 格式化后的字符串
     */
    public static String formatLargeNumber(double number) {
        if (number >= 1_000_000_000) {
            return String.format("%.2fB", number / 1_000_000_000);
        } else if (number >= 1_000_000) {
            return String.format("%.2fM", number / 1_000_000);
        } else if (number >= 1_000) {
            return String.format("%.2fK", number / 1_000);
        } else {
            return String.format("%.2f", number);
        }
    }

    /**
     * 获取排序后的余额列表（用于中位数等计算）
     * 
     * @param balances 原始余额列表
     * @return 排序后的余额列表
     */
    public static List<Double> getSortedBalances(List<Double> balances) {
        if (balances == null || balances.isEmpty()) {
            return new ArrayList<>();
        }
        List<Double> sorted = new ArrayList<>(balances);
        Collections.sort(sorted);
        return sorted;
    }

    /**
     * 计算中位数
     * 
     * @param sortedBalances 已排序的余额列表
     * @return 中位数
     */
    public static double calculateMedian(List<Double> sortedBalances) {
        if (sortedBalances == null || sortedBalances.isEmpty()) {
            return 0.0;
        }
        
        int n = sortedBalances.size();
        if (n % 2 == 0) {
            // 偶数个元素，取中间两个的平均值
            return (sortedBalances.get(n / 2 - 1) + sortedBalances.get(n / 2)) / 2.0;
        } else {
            // 奇数个元素，取中间的
            return sortedBalances.get(n / 2);
        }
    }

    /**
     * 计算标准差
     * 
     * @param balances 余额列表
     * @param mean 均值（可预先计算好传入以提高效率）
     * @return 标准差
     */
    public static double calculateStdDev(List<Double> balances, double mean) {
        if (balances == null || balances.isEmpty()) {
            return 0.0;
        }
        
        double sumSquaredDiff = 0.0;
        for (double balance : balances) {
            double diff = balance - mean;
            sumSquaredDiff += diff * diff;
        }
        
        return Math.sqrt(sumSquaredDiff / balances.size());
    }

    /**
     * 计算均值
     * 
     * @param balances 余额列表
     * @return 均值
     */
    public static double calculateMean(List<Double> balances) {
        if (balances == null || balances.isEmpty()) {
            return 0.0;
        }
        return calculateTotalMoney(balances) / balances.size();
    }
}
