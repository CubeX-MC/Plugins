package org.cubexmc.ecobalancer.utils;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 统计计算工具类
 */
public class StatisticsUtils {
    
    /**
     * 计算中位数
     * @param values 数值列表
     * @return 中位数
     */
    public static double calculateMedian(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        
        List<Double> sortedValues = new ArrayList<>(values);
        Collections.sort(sortedValues);
        
        int size = sortedValues.size();
        if (size % 2 == 0) {
            return (sortedValues.get(size / 2 - 1) + sortedValues.get(size / 2)) / 2;
        } else {
            return sortedValues.get(size / 2);
        }
    }
    
    /**
     * 计算标准差
     * @param values 数值列表
     * @param mean 平均值
     * @return 标准差
     */
    public static double calculateStandardDeviation(List<Double> values, double mean) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        
        double sum = 0;
        for (double value : values) {
            sum += Math.pow(value - mean, 2);
        }
        
        return Math.sqrt(sum / values.size());
    }
    
    /**
     * 计算百分位数
     * @param balance 要查询的余额
     * @param values 所有余额列表
     * @return 百分位数（0-100）
     */
    public static double calculatePercentile(double balance, List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        
        int totalPlayers = values.size();
        int playersBelow = (int) values.stream().filter(b -> b < balance).count();
        
        return (double) playersBelow / totalPlayers * 100;
    }
    
    /**
     * 格式化数字为易读格式（如1.2k, 3.5m等）
     * @param number 要格式化的数字
     * @return 格式化后的字符串
     */
    public static String formatNumber(double number) {
        if (number >= 1000000000) {
            return String.format("%.1fb", number / 1000000000);
        } else if (number >= 1000000) {
            return String.format("%.1fm", number / 1000000);
        } else if (number >= 1000) {
            return String.format("%.1fk", number / 1000);
        } else {
            return String.format("%.1f", number);
        }
    }
    
    /**
     * 收集指定范围内的玩家余额
     * @param low 最低限额
     * @param high 最高限额
     * @return 符合条件的余额列表
     */
    public static List<Double> collectBalancesInRange(double low, double high) {
        List<Double> balances = new ArrayList<>();
        OfflinePlayer[] players = Bukkit.getOfflinePlayers();
        
        for (OfflinePlayer player : players) {
            try {
                if (VaultUtils.hasAccount(player)) {
                    double balance = VaultUtils.getBalance(player);
                    if (balance >= low && balance <= high) {
                        balances.add(balance);
                    }
                }
            } catch (Exception e) {
                // 忽略可能发生的错误
            }
        }
        
        return balances;
    }
    
    /**
     * 根据数值列表创建直方图数据
     * @param values 数值列表
     * @param numBars 条形数量
     * @return 直方图数据（每个区间的频次）
     */
    public static int[] createHistogram(List<Double> values, int numBars) {
        if (values == null || values.isEmpty() || numBars <= 0) {
            return new int[0];
        }
        
        double min = values.stream().min(Double::compareTo).orElse(0.0);
        double max = values.stream().max(Double::compareTo).orElse(0.0);
        double range = max - min;
        double barWidth = range / numBars;
        
        int[] histogram = new int[numBars];
        for (double value : values) {
            int barIndex = (int) ((value - min) / barWidth);
            if (barIndex == numBars) {
                barIndex--;
            }
            histogram[barIndex]++;
        }
        
        return histogram;
    }
} 