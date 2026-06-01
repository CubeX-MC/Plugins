package org.cubexmc.ecobalancer.utils;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.*;
import java.util.logging.Logger;

/**
 * 数据库操作工具类
 */
public class DatabaseUtils {
    private static void applyPragmas(Connection connection) {
        try (Statement s = connection.createStatement()) {
            // Safer defaults for server-side SQLite usage
            // WAL persists at DB-level after first set; others are per-connection
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA temp_store=MEMORY");
            // Negative value sets cache size in KB for newer SQLite
            s.execute("PRAGMA cache_size=-8192");
            // Wait up to 10s for locked database to become available
            s.execute("PRAGMA busy_timeout=10000");
        } catch (SQLException e) {
            // Pragmas are best-effort; keep connection usable.
        }
    }
    private static File getDatabaseFile(Plugin plugin) {
        File dataFolder = plugin.getDataFolder();
        return new File(dataFolder, "records.db");
    }

    /**
     * 获取数据库连接
     * @param plugin 插件实例
     * @return 数据库连接
     * @throws SQLException 连接异常
     */
    public static Connection getConnection(Plugin plugin) throws SQLException {
        File databaseFile = getDatabaseFile(plugin);
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath() + "?busy_timeout=10000");
        applyPragmas(c);
        return c;
    }

    /**
     * 初始化数据库表
     * @param plugin 插件实例
     * @param logger 日志器
     */
    public static void initializeTables(Plugin plugin, Logger logger) {
        try (Connection connection = getConnection(plugin)) {
            try (Statement statement = connection.createStatement()) {
                // 创建operations表
                statement.execute("CREATE TABLE IF NOT EXISTS operations (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, is_checkall BOOLEAN NOT NULL, is_restored BOOLEAN NOT NULL DEFAULT 0)");
                
                // 创建records表（保持兼容：若已存在则不变；新安装包含外键约束）
                statement.execute("CREATE TABLE IF NOT EXISTS records (id INTEGER PRIMARY KEY AUTOINCREMENT, player_name TEXT NOT NULL, player TEXT NOT NULL, old_balance REAL NOT NULL, new_balance REAL NOT NULL, deduction REAL NOT NULL, timestamp INTEGER NOT NULL, is_checkall BOOLEAN NOT NULL, operation_id INTEGER NOT NULL, FOREIGN KEY(operation_id) REFERENCES operations(id) ON DELETE CASCADE)");
                addColumnIfMissing(connection, "records", "policy_name", "TEXT");
                addColumnIfMissing(connection, "records", "operation_type", "TEXT");
                addColumnIfMissing(connection, "records", "result", "TEXT");
                addColumnIfMissing(connection, "records", "reason", "TEXT");
                addColumnIfMissing(connection, "records", "requested_deduction", "REAL");
                addColumnIfMissing(connection, "records", "actual_deduction", "REAL");

                // 索引优化
                statement.execute("CREATE INDEX IF NOT EXISTS idx_records_operation_id ON records(operation_id)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_records_timestamp ON records(timestamp)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_records_player ON records(player)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_records_result ON records(result)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_operations_timestamp ON operations(timestamp)");

                // 创建经济快照表（用于趋势分析）
                statement.execute("CREATE TABLE IF NOT EXISTS economic_snapshots (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "snapshot_date TEXT NOT NULL, " +
                        "timestamp INTEGER NOT NULL, " +
                        "total_money REAL NOT NULL, " +
                        "player_count INTEGER NOT NULL, " +
                        "active_players_7d INTEGER, " +
                        "active_players_30d INTEGER, " +
                        "gini_coefficient REAL, " +
                        "median_balance REAL, " +
                        "mean_balance REAL, " +
                        "std_dev REAL, " +
                        "top1_percentage REAL, " +
                        "top5_percentage REAL, " +
                        "top10_percentage REAL" +
                        ")");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_date ON economic_snapshots(snapshot_date)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_timestamp ON economic_snapshots(timestamp)");

                // 创建操作影响对比表（用于税收前后对比）
                statement.execute("CREATE TABLE IF NOT EXISTS operation_impact (" +
                        "operation_id INTEGER PRIMARY KEY, " +
                        "before_gini REAL, " +
                        "after_gini REAL, " +
                        "before_median REAL, " +
                        "after_median REAL, " +
                        "before_mean REAL, " +
                        "after_mean REAL, " +
                        "before_std_dev REAL, " +
                        "after_std_dev REAL, " +
                        "before_top1_pct REAL, " +
                        "after_top1_pct REAL, " +
                        "before_total_money REAL, " +
                        "after_total_money REAL, " +
                        "total_tax_collected REAL, " +
                        "players_affected INTEGER, " +
                        "timestamp INTEGER NOT NULL" +
                        ")");

                statement.execute("CREATE TABLE IF NOT EXISTS tax_ledger (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "operation_id INTEGER, " +
                        "player_uuid TEXT, " +
                        "player_name TEXT, " +
                        "policy_name TEXT, " +
                        "amount REAL NOT NULL, " +
                        "balance_before REAL, " +
                        "balance_after REAL, " +
                        "result TEXT, " +
                        "timestamp INTEGER NOT NULL" +
                        ")");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tax_ledger_operation ON tax_ledger(operation_id)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tax_ledger_player ON tax_ledger(player_uuid)");

                statement.execute("CREATE TABLE IF NOT EXISTS player_tax_totals (" +
                        "player_uuid TEXT PRIMARY KEY, " +
                        "player_name TEXT, " +
                        "latest_tax_paid REAL NOT NULL DEFAULT 0, " +
                        "total_tax_paid REAL NOT NULL DEFAULT 0, " +
                        "latest_tax_time INTEGER" +
                        ")");

                statement.execute("CREATE TABLE IF NOT EXISTS server_tax_stats (" +
                        "id INTEGER PRIMARY KEY CHECK (id = 1), " +
                        "total_tax_collected REAL NOT NULL DEFAULT 0, " +
                        "tax_fund_balance REAL NOT NULL DEFAULT 0, " +
                        "latest_tax_collected REAL NOT NULL DEFAULT 0, " +
                        "latest_operation_id INTEGER, " +
                        "updated_at INTEGER" +
                        ")");
                statement.execute("INSERT OR IGNORE INTO server_tax_stats (id, total_tax_collected, tax_fund_balance, latest_tax_collected, latest_operation_id, updated_at) VALUES (1, 0, 0, 0, -1, 0)");
            }
        } catch (SQLException e) {
            logger.severe("初始化数据库表失败: " + e.getMessage());
        }
    }

    private static void addColumnIfMissing(Connection connection, String table, String column, String definition) {
        try (Statement s = connection.createStatement()) {
            s.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (!msg.contains("duplicate column")) {
                // Best effort for compatibility with old SQLite files; table creation still
                // leaves new installs with the full schema through subsequent statements.
            }
        }
    }

    /**
     * 保存玩家余额变更记录
     * @param plugin 插件实例
     * @param player 玩家
     * @param oldBalance 旧余额
     * @param newBalance 新余额
     * @param deduction 扣除金额
     * @param isCheckAll 是否检查所有玩家
     * @param operationId 操作ID
     * @param logger 日志器
     */
    public static void saveRecord(Plugin plugin, OfflinePlayer player, double oldBalance, double newBalance, double deduction, boolean isCheckAll, int operationId, Logger logger) {
        saveRecord(plugin, player, oldBalance, newBalance, deduction, isCheckAll, operationId, null, null, null, null,
                deduction, deduction, logger);
    }

    public static void saveRecord(Plugin plugin, OfflinePlayer player, double oldBalance, double newBalance,
            double deduction, boolean isCheckAll, int operationId, String policyName, String operationType,
            String result, String reason, double requestedDeduction, double actualDeduction, Logger logger) {
        runSqlWithRetry(logger, () -> {
            try (Connection connection = getConnection(plugin);
                 PreparedStatement preparedStatement = connection.prepareStatement(
                        "INSERT INTO records (player_name, player, old_balance, new_balance, deduction, timestamp, is_checkall, operation_id, policy_name, operation_type, result, reason, requested_deduction, actual_deduction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                preparedStatement.setString(1, player.getName());
                preparedStatement.setString(2, player.getUniqueId().toString());
                preparedStatement.setDouble(3, oldBalance);
                preparedStatement.setDouble(4, newBalance);
                preparedStatement.setDouble(5, deduction);
                preparedStatement.setLong(6, System.currentTimeMillis());
                preparedStatement.setBoolean(7, isCheckAll);
                preparedStatement.setInt(8, operationId);
                preparedStatement.setString(9, policyName);
                preparedStatement.setString(10, operationType);
                preparedStatement.setString(11, result);
                preparedStatement.setString(12, reason);
                preparedStatement.setDouble(13, requestedDeduction);
                preparedStatement.setDouble(14, actualDeduction);
                preparedStatement.executeUpdate();
            }
        });
    }

    @FunctionalInterface
    private interface SqlRunnable { void run() throws SQLException; }

    private static void runSqlWithRetry(Logger logger, SqlRunnable op) {
        int attempts = 6; // ~ up to ~ (5 * 100ms) + busy_timeout allowance
        long sleepMs = 100L;
        SQLException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                op.run();
                return;
            } catch (SQLException e) {
                last = e;
                String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
                if (msg.contains("database is locked") || msg.contains("sqlite_busy")) {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warning("SQL retry sleep interrupted: " + ie.getMessage());
                    }
                    sleepMs = Math.min(1000L, sleepMs * 2);
                    continue;
                }
                break;
            }
        }
        if (last != null) {
            logger.severe("保存记录失败: " + last.getMessage());
        }
    }

    /**
     * 获取下一个操作ID
     * @param plugin 插件实例
     * @param isCheckAll 是否检查所有玩家
     * @param logger 日志器
     * @return 操作ID
     */
    public static int getNextOperationId(Plugin plugin, boolean isCheckAll, Logger logger) {
        try (Connection connection = getConnection(plugin)) {
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "INSERT INTO operations (timestamp, is_checkall, is_restored) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                preparedStatement.setLong(1, System.currentTimeMillis());
                preparedStatement.setBoolean(2, isCheckAll);
                preparedStatement.setBoolean(3, false);
                preparedStatement.executeUpdate();

                try (ResultSet generatedKeys = preparedStatement.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        return generatedKeys.getInt(1);
                    } else {
                        throw new SQLException("创建操作失败，未获取到ID");
                    }
                }
            }
        } catch (SQLException e) {
            logger.severe("获取下一个操作ID失败: " + e.getMessage());
            return -1;
        }
    }

    /**
     * 计算操作的总扣除金额
     * @param plugin 插件实例
     * @param operationId 操作ID
     * @param logger 日志器
     * @return 总扣除金额
     */
    public static double calculateTotalDeduction(Plugin plugin, int operationId, Logger logger) {
        try (Connection connection = getConnection(plugin)) {
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "SELECT SUM(deduction) AS total_deduction FROM records WHERE operation_id = ?")) {
                preparedStatement.setInt(1, operationId);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getDouble("total_deduction");
                    }
                }
            }
        } catch (SQLException e) {
            logger.severe("计算总扣除金额失败: " + e.getMessage());
        }
        return 0;
    }

    /**
     * 统计某次操作中受到影响（扣款>0）的玩家数量
     */
    public static int getAffectedPlayersCount(Plugin plugin, int operationId, Logger logger) {
        try (Connection connection = getConnection(plugin)) {
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "SELECT COUNT(*) AS cnt FROM records WHERE operation_id = ? AND deduction > 0")) {
                preparedStatement.setInt(1, operationId);
                try (ResultSet rs = preparedStatement.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("cnt");
                    }
                }
            }
        } catch (SQLException e) {
            logger.severe("统计受影响玩家数量失败: " + e.getMessage());
        }
        return 0;
    }

    /**
     * 清理过期记录
     * @param plugin 插件实例
     * @param retentionDays 保留天数
     * @param logger 日志器
     */
    public static void cleanupRecords(Plugin plugin, int retentionDays, Logger logger) {
        long cutoffTime = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);
        
        try (Connection connection = getConnection(plugin)) {
            // 删除过期的记录
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "DELETE FROM records WHERE timestamp < ?")) {
                preparedStatement.setLong(1, cutoffTime);
                int recordsDeleted = preparedStatement.executeUpdate();
                logger.info("已清理 " + recordsDeleted + " 条过期记录");
            }
            
            // 删除没有关联记录的操作
            try (Statement statement = connection.createStatement()) {
                int operationsDeleted = statement.executeUpdate(
                        "DELETE FROM operations WHERE id NOT IN (SELECT DISTINCT operation_id FROM records)");
                logger.info("已清理 " + operationsDeleted + " 条无关联记录的操作");
            }
        } catch (SQLException e) {
            logger.severe("清理过期记录失败: " + e.getMessage());
        }
    }

    /**
     * 保存经济快照
     * @param plugin 插件实例
     * @param snapshotData 快照数据
     * @param logger 日志器
     */
    public static void saveSnapshot(Plugin plugin, EconomicSnapshot snapshotData, Logger logger) {
        String sql = "INSERT INTO economic_snapshots (snapshot_date, timestamp, total_money, player_count, " +
                "active_players_7d, active_players_30d, gini_coefficient, median_balance, mean_balance, " +
                "std_dev, top1_percentage, top5_percentage, top10_percentage) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        runSqlWithRetry(logger, () -> {
            try (Connection conn = getConnection(plugin);
                 PreparedStatement statement = conn.prepareStatement(sql)) {
                statement.setString(1, snapshotData.date);
                statement.setLong(2, snapshotData.timestamp);
                statement.setDouble(3, snapshotData.totalMoney);
                statement.setInt(4, snapshotData.playerCount);
                statement.setInt(5, snapshotData.activePlayers7d);
                statement.setInt(6, snapshotData.activePlayers30d);
                statement.setDouble(7, snapshotData.gini);
                statement.setDouble(8, snapshotData.median);
                statement.setDouble(9, snapshotData.mean);
                statement.setDouble(10, snapshotData.stdDev);
                statement.setDouble(11, snapshotData.top1Pct);
                statement.setDouble(12, snapshotData.top5Pct);
                statement.setDouble(13, snapshotData.top10Pct);
                statement.executeUpdate();
            }
        });
    }

    /**
     * 保存操作影响对比数据
     * @param plugin 插件实例
     * @param operationId 操作ID
     * @param impactData 影响数据
     * @param logger 日志器
     */
    public static void saveOperationImpact(Plugin plugin, int operationId, OperationImpact impactData, Logger logger) {
        String sql = "INSERT OR REPLACE INTO operation_impact (operation_id, before_gini, after_gini, " +
                "before_median, after_median, before_mean, after_mean, before_std_dev, after_std_dev, " +
                "before_top1_pct, after_top1_pct, before_total_money, after_total_money, " +
                "total_tax_collected, players_affected, timestamp) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        runSqlWithRetry(logger, () -> {
            try (Connection conn = getConnection(plugin);
                 PreparedStatement statement = conn.prepareStatement(sql)) {
                statement.setInt(1, operationId);
                statement.setDouble(2, impactData.beforeGini);
                statement.setDouble(3, impactData.afterGini);
                statement.setDouble(4, impactData.beforeMedian);
                statement.setDouble(5, impactData.afterMedian);
                statement.setDouble(6, impactData.beforeMean);
                statement.setDouble(7, impactData.afterMean);
                statement.setDouble(8, impactData.beforeStdDev);
                statement.setDouble(9, impactData.afterStdDev);
                statement.setDouble(10, impactData.beforeTop1Pct);
                statement.setDouble(11, impactData.afterTop1Pct);
                statement.setDouble(12, impactData.beforeTotalMoney);
                statement.setDouble(13, impactData.afterTotalMoney);
                statement.setDouble(14, impactData.totalTaxCollected);
                statement.setInt(15, impactData.playersAffected);
                statement.setLong(16, impactData.timestamp);
                statement.executeUpdate();
            }
        });
    }

    /**
     * 获取最新的经济快照
     * @param plugin 插件实例
     * @param logger 日志器
     * @return 最新快照，如果不存在则返回null
     */
    public static EconomicSnapshot getLatestSnapshot(Plugin plugin, Logger logger) {
        String sql = "SELECT * FROM economic_snapshots ORDER BY timestamp DESC LIMIT 1";
        
        try (Connection conn = getConnection(plugin);
             PreparedStatement statement = conn.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (rs.next()) {
                return extractSnapshotFromResultSet(rs);
            }
        } catch (SQLException e) {
            logger.severe("获取最新快照失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 获取历史快照（按时间范围）
     * @param plugin 插件实例
     * @param days 查询多少天内的快照
     * @param logger 日志器
     * @return 快照列表
     */
    public static java.util.List<EconomicSnapshot> getSnapshotHistory(Plugin plugin, int days, Logger logger) {
        long cutoffTime = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L);
        String sql = "SELECT * FROM economic_snapshots WHERE timestamp >= ? ORDER BY timestamp ASC";
        java.util.List<EconomicSnapshot> snapshots = new java.util.ArrayList<>();
        
        try (Connection conn = getConnection(plugin);
             PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setLong(1, cutoffTime);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    snapshots.add(extractSnapshotFromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            logger.severe("获取历史快照失败: " + e.getMessage());
        }
        return snapshots;
    }

    /**
     * 获取操作影响数据
     * @param plugin 插件实例
     * @param operationId 操作ID
     * @param logger 日志器
     * @return 影响数据，如果不存在则返回null
     */
    public static OperationImpact getOperationImpact(Plugin plugin, int operationId, Logger logger) {
        String sql = "SELECT * FROM operation_impact WHERE operation_id = ?";
        
        try (Connection conn = getConnection(plugin);
             PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setInt(1, operationId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return extractImpactFromResultSet(rs);
                }
            }
        } catch (SQLException e) {
            logger.severe("获取操作影响数据失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 获取最近的操作影响列表
     * @param plugin 插件实例
     * @param limit 最多返回多少条
     * @param logger 日志器
     * @return 影响数据列表
     */
    public static java.util.List<OperationImpact> getRecentImpacts(Plugin plugin, int limit, Logger logger) {
        String sql = "SELECT * FROM operation_impact ORDER BY timestamp DESC LIMIT ?";
        java.util.List<OperationImpact> impacts = new java.util.ArrayList<>();
        
        try (Connection conn = getConnection(plugin);
             PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    impacts.add(extractImpactFromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            logger.severe("获取最近操作影响失败: " + e.getMessage());
        }
        return impacts;
    }

    // 辅助方法：从ResultSet提取快照数据
    private static EconomicSnapshot extractSnapshotFromResultSet(ResultSet rs) throws SQLException {
        EconomicSnapshot snapshot = new EconomicSnapshot();
        snapshot.id = rs.getInt("id");
        snapshot.date = rs.getString("snapshot_date");
        snapshot.timestamp = rs.getLong("timestamp");
        snapshot.totalMoney = rs.getDouble("total_money");
        snapshot.playerCount = rs.getInt("player_count");
        snapshot.activePlayers7d = rs.getInt("active_players_7d");
        snapshot.activePlayers30d = rs.getInt("active_players_30d");
        snapshot.gini = rs.getDouble("gini_coefficient");
        snapshot.median = rs.getDouble("median_balance");
        snapshot.mean = rs.getDouble("mean_balance");
        snapshot.stdDev = rs.getDouble("std_dev");
        snapshot.top1Pct = rs.getDouble("top1_percentage");
        snapshot.top5Pct = rs.getDouble("top5_percentage");
        snapshot.top10Pct = rs.getDouble("top10_percentage");
        return snapshot;
    }

    // 辅助方法：从ResultSet提取影响数据
    private static OperationImpact extractImpactFromResultSet(ResultSet rs) throws SQLException {
        OperationImpact impact = new OperationImpact();
        impact.operationId = rs.getInt("operation_id");
        impact.beforeGini = rs.getDouble("before_gini");
        impact.afterGini = rs.getDouble("after_gini");
        impact.beforeMedian = rs.getDouble("before_median");
        impact.afterMedian = rs.getDouble("after_median");
        impact.beforeMean = rs.getDouble("before_mean");
        impact.afterMean = rs.getDouble("after_mean");
        impact.beforeStdDev = rs.getDouble("before_std_dev");
        impact.afterStdDev = rs.getDouble("after_std_dev");
        impact.beforeTop1Pct = rs.getDouble("before_top1_pct");
        impact.afterTop1Pct = rs.getDouble("after_top1_pct");
        impact.beforeTotalMoney = rs.getDouble("before_total_money");
        impact.afterTotalMoney = rs.getDouble("after_total_money");
        impact.totalTaxCollected = rs.getDouble("total_tax_collected");
        impact.playersAffected = rs.getInt("players_affected");
        impact.timestamp = rs.getLong("timestamp");
        return impact;
    }

    /**
     * 经济快照数据类
     */
    public static class EconomicSnapshot {
        public int id;
        public String date;
        public long timestamp;
        public double totalMoney;
        public int playerCount;
        public int activePlayers7d;
        public int activePlayers30d;
        public double gini;
        public double median;
        public double mean;
        public double stdDev;
        public double top1Pct;
        public double top5Pct;
        public double top10Pct;
    }

    /**
     * 操作影响数据类
     */
    public static class OperationImpact {
        public int operationId;
        public double beforeGini;
        public double afterGini;
        public double beforeMedian;
        public double afterMedian;
        public double beforeMean;
        public double afterMean;
        public double beforeStdDev;
        public double afterStdDev;
        public double beforeTop1Pct;
        public double afterTop1Pct;
        public double beforeTotalMoney;
        public double afterTotalMoney;
        public double totalTaxCollected;
        public int playersAffected;
        public long timestamp;
    }
} 
