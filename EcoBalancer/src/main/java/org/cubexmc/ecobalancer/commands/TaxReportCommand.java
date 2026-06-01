package org.cubexmc.ecobalancer.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.cubexmc.ecobalancer.utils.DatabaseUtils;
import org.cubexmc.ecobalancer.utils.EconomicMetrics;
import org.cubexmc.ecobalancer.utils.SchedulerUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 税收报告命令
 * 用法: /ecobal report [operation_id]
 * - 不带参数：显示最近一次 checkall 的税收报告
 * - 带operation_id：显示指定操作的税收报告
 */
public class TaxReportCommand implements CommandExecutor {
    private final EcoBalancer plugin;

    public TaxReportCommand(EcoBalancer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 解析operation_id参数
        Integer operationId = null;
        if (args.length > 0) {
            try {
                operationId = Integer.parseInt(args[0]);
                if (operationId <= 0) {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("id", args[0]);
                    sender.sendMessage(plugin.getFormattedMessage("messages.report.invalid_id", placeholders));
                    return false;
                }
            } catch (NumberFormatException e) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("input", args[0]);
                sender.sendMessage(plugin.getFormattedMessage("messages.report.invalid_number", placeholders));
                return false;
            }
        }

        sender.sendMessage(plugin.getFormattedMessage("messages.report.loading", null));

        final Integer finalOperationId = operationId;

        // 异步查询数据库
        SchedulerUtils.runTaskAsync(plugin, () -> {
            try (Connection conn = DatabaseUtils.getConnection(plugin)) {
                // 如果未指定operation_id，获取最近一次checkAll操作
                int targetOperationId = finalOperationId != null ? finalOperationId : getLatestCheckAllOperationId(conn);

                if (targetOperationId <= 0) {
                    SchedulerUtils.runTask(plugin, () -> {
                        sender.sendMessage(plugin.getFormattedMessage("messages.report.no_operations", null));
                    });
                    return;
                }

                // 查询操作详情
                TaxReportData reportData = queryReportData(conn, targetOperationId);

                if (reportData == null) {
                    SchedulerUtils.runTask(plugin, () -> {
                        Map<String, String> placeholders = new HashMap<>();
                        placeholders.put("operation_id", String.valueOf(targetOperationId));
                        sender.sendMessage(plugin.getFormattedMessage("messages.report.not_found", placeholders));
                    });
                    return;
                }

                // 主线程显示报告
                SchedulerUtils.runTask(plugin, () -> {
                    displayReport(sender, reportData);
                });

            } catch (Exception e) {
                SchedulerUtils.runTask(plugin, () -> {
                    sender.sendMessage(plugin.getFormattedMessage("messages.report.error", null));
                    plugin.getLogger().severe("Error generating tax report: " + e.getMessage());
                    e.printStackTrace();
                });
            }
        });

        return true;
    }

    /**
     * 获取最近一次 checkAll 操作的 ID
     */
    private int getLatestCheckAllOperationId(Connection conn) {
        String sql = "SELECT id FROM operations WHERE is_checkall = 1 ORDER BY timestamp DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt("id");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get latest checkAll operation: " + e.getMessage());
        }
        return -1;
    }

    /**
     * 查询税收报告数据
     */
    private TaxReportData queryReportData(Connection conn, int operationId) {
        try {
            // 查询操作信息
            String opSql = "SELECT timestamp, is_checkall FROM operations WHERE id = ?";
            long timestamp = 0L;
            boolean isCheckAll = false;

            try (PreparedStatement ps = conn.prepareStatement(opSql)) {
                ps.setInt(1, operationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        timestamp = rs.getLong("timestamp");
                        isCheckAll = rs.getBoolean("is_checkall");
                    } else {
                        return null; // 操作不存在
                    }
                }
            }

            // 查询税收统计
            String statsSql = "SELECT COUNT(*) as player_count, SUM(deduction) as total_tax, " +
                             "AVG(old_balance) as avg_old_balance, AVG(new_balance) as avg_new_balance " +
                             "FROM records WHERE operation_id = ? AND deduction > 0";
            
            int playerCount = 0;
            double totalTax = 0;
            double avgOldBalance = 0;
            double avgNewBalance = 0;

            try (PreparedStatement ps = conn.prepareStatement(statsSql)) {
                ps.setInt(1, operationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        playerCount = rs.getInt("player_count");
                        totalTax = rs.getDouble("total_tax");
                        avgOldBalance = rs.getDouble("avg_old_balance");
                        avgNewBalance = rs.getDouble("avg_new_balance");
                    }
                }
            }

            // 查询税阶分布（按扣除率分组）
            String bracketSql = "SELECT " +
                               "ROUND(deduction / old_balance * 100, 2) as tax_rate, " +
                               "COUNT(*) as count, " +
                               "SUM(deduction) as bracket_total " +
                               "FROM records " +
                               "WHERE operation_id = ? AND deduction > 0 AND old_balance > 0 " +
                               "GROUP BY tax_rate " +
                               "ORDER BY tax_rate";

            List<TaxBracketInfo> brackets = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(bracketSql)) {
                ps.setInt(1, operationId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double rate = rs.getDouble("tax_rate");
                        int count = rs.getInt("count");
                        double total = rs.getDouble("bracket_total");
                        brackets.add(new TaxBracketInfo(rate, count, total));
                    }
                }
            }

            int skippedCount = 0;
            int exemptCount = 0;
            int insufficientCount = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT "
                            + "SUM(CASE WHEN deduction <= 0 THEN 1 ELSE 0 END) AS skipped_count, "
                            + "SUM(CASE WHEN result = 'EXEMPT' THEN 1 ELSE 0 END) AS exempt_count, "
                            + "SUM(CASE WHEN result = 'INSUFFICIENT_BALANCE_SKIPPED' THEN 1 ELSE 0 END) AS insufficient_count "
                            + "FROM records WHERE operation_id = ?")) {
                ps.setInt(1, operationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        skippedCount = rs.getInt("skipped_count");
                        exemptCount = rs.getInt("exempt_count");
                        insufficientCount = rs.getInt("insufficient_count");
                    }
                }
            }

            return new TaxReportData(operationId, timestamp, isCheckAll, playerCount, 
                                    totalTax, avgOldBalance, avgNewBalance, brackets, skippedCount, exemptCount,
                                    insufficientCount);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to query report data: " + e.getMessage());
            return null;
        }
    }

    /**
     * 显示报告
     */
    private void displayReport(CommandSender sender, TaxReportData data) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("operation_id", String.valueOf(data.operationId));
        placeholders.put("operation_type", data.isCheckAll ? "CheckAll" : "CheckPlayer");
        placeholders.put("timestamp", formatTimestamp(data.timestamp));
        placeholders.put("player_count", String.valueOf(data.playerCount));
        placeholders.put("total_tax", EconomicMetrics.formatLargeNumber(data.totalTax));
        placeholders.put("avg_old_balance", EconomicMetrics.formatLargeNumber(data.avgOldBalance));
        placeholders.put("avg_new_balance", EconomicMetrics.formatLargeNumber(data.avgNewBalance));
        placeholders.put("skipped_count", String.valueOf(data.skippedCount));
        placeholders.put("exempt_count", String.valueOf(data.exemptCount));
        placeholders.put("insufficient_count", String.valueOf(data.insufficientCount));
        
        double avgTaxRate = data.avgOldBalance > 0 ? (data.totalTax / (data.avgOldBalance * data.playerCount)) * 100 : 0;
        placeholders.put("avg_tax_rate", String.format("%.2f%%", avgTaxRate));

        sender.sendMessage(plugin.getFormattedMessage("messages.report.header", placeholders));
        sender.sendMessage(plugin.getFormattedMessage("messages.report.summary", placeholders));

        if (!data.brackets.isEmpty()) {
            sender.sendMessage(plugin.getFormattedMessage("messages.report.brackets_header", null));
            for (TaxBracketInfo bracket : data.brackets) {
                Map<String, String> bracketPlaceholders = new HashMap<>();
                bracketPlaceholders.put("tax_rate", String.format("%.2f%%", bracket.rate));
                bracketPlaceholders.put("player_count", String.valueOf(bracket.count));
                bracketPlaceholders.put("total_tax", EconomicMetrics.formatLargeNumber(bracket.totalTax));
                bracketPlaceholders.put("percentage", String.format("%.1f%%", (bracket.totalTax / data.totalTax) * 100));
                sender.sendMessage(plugin.getFormattedMessage("messages.report.bracket_line", bracketPlaceholders));
            }
        }
    }

    /**
     * 格式化时间戳
     */
    private String formatTimestamp(long timestamp) {
        try {
            SimpleDateFormat outputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            return outputFormat.format(new Date(timestamp));
        } catch (Exception e) {
            return Long.toString(timestamp);
        }
    }

    /**
     * 税收报告数据类
     */
    private static class TaxReportData {
        final int operationId;
        final long timestamp;
        final boolean isCheckAll;
        final int playerCount;
        final double totalTax;
        final double avgOldBalance;
        final double avgNewBalance;
        final List<TaxBracketInfo> brackets;
        final int skippedCount;
        final int exemptCount;
        final int insufficientCount;

        TaxReportData(int operationId, long timestamp, boolean isCheckAll, int playerCount,
                     double totalTax, double avgOldBalance, double avgNewBalance,
                     List<TaxBracketInfo> brackets, int skippedCount, int exemptCount, int insufficientCount) {
            this.operationId = operationId;
            this.timestamp = timestamp;
            this.isCheckAll = isCheckAll;
            this.playerCount = playerCount;
            this.totalTax = totalTax;
            this.avgOldBalance = avgOldBalance;
            this.avgNewBalance = avgNewBalance;
            this.brackets = brackets;
            this.skippedCount = skippedCount;
            this.exemptCount = exemptCount;
            this.insufficientCount = insufficientCount;
        }
    }

    /**
     * 税阶信息类
     */
    private static class TaxBracketInfo {
        final double rate;
        final int count;
        final double totalTax;

        TaxBracketInfo(double rate, int count, double totalTax) {
            this.rate = rate;
            this.count = count;
            this.totalTax = totalTax;
        }
    }
}
