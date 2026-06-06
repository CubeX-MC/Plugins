package org.cubexmc.ecobalancer.utils

import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.Locale
import java.util.logging.Logger

object DatabaseUtils {
    private fun applyPragmas(connection: Connection) {
        try {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA synchronous=NORMAL")
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute("PRAGMA temp_store=MEMORY")
                statement.execute("PRAGMA cache_size=-8192")
                statement.execute("PRAGMA busy_timeout=10000")
            }
        } catch (_: SQLException) {
        }
    }

    private fun getDatabaseFile(plugin: Plugin): File = File(plugin.dataFolder, "records.db")

    @JvmStatic
    @Throws(SQLException::class)
    fun getConnection(plugin: Plugin): Connection {
        val databaseFile = getDatabaseFile(plugin)
        val connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.absolutePath + "?busy_timeout=10000")
        applyPragmas(connection)
        return connection
    }

    @JvmStatic
    fun initializeTables(plugin: Plugin, logger: Logger) {
        try {
            getConnection(plugin).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE IF NOT EXISTS operations (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, is_checkall BOOLEAN NOT NULL, is_restored BOOLEAN NOT NULL DEFAULT 0)")
                    statement.execute("CREATE TABLE IF NOT EXISTS records (id INTEGER PRIMARY KEY AUTOINCREMENT, player_name TEXT NOT NULL, player TEXT NOT NULL, old_balance REAL NOT NULL, new_balance REAL NOT NULL, deduction REAL NOT NULL, timestamp INTEGER NOT NULL, is_checkall BOOLEAN NOT NULL, operation_id INTEGER NOT NULL, FOREIGN KEY(operation_id) REFERENCES operations(id) ON DELETE CASCADE)")
                    addColumnIfMissing(connection, "records", "policy_name", "TEXT")
                    addColumnIfMissing(connection, "records", "operation_type", "TEXT")
                    addColumnIfMissing(connection, "records", "result", "TEXT")
                    addColumnIfMissing(connection, "records", "reason", "TEXT")
                    addColumnIfMissing(connection, "records", "requested_deduction", "REAL")
                    addColumnIfMissing(connection, "records", "actual_deduction", "REAL")

                    statement.execute("CREATE INDEX IF NOT EXISTS idx_records_operation_id ON records(operation_id)")
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_records_timestamp ON records(timestamp)")
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_records_player ON records(player)")
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_records_result ON records(result)")
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_operations_timestamp ON operations(timestamp)")

                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS economic_snapshots (" +
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
                            ")",
                    )
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_date ON economic_snapshots(snapshot_date)")
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_timestamp ON economic_snapshots(timestamp)")

                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS operation_impact (" +
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
                            ")",
                    )

                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS tax_ledger (" +
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
                            ")",
                    )
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_tax_ledger_operation ON tax_ledger(operation_id)")
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_tax_ledger_player ON tax_ledger(player_uuid)")

                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS player_tax_totals (" +
                            "player_uuid TEXT PRIMARY KEY, " +
                            "player_name TEXT, " +
                            "latest_tax_paid REAL NOT NULL DEFAULT 0, " +
                            "total_tax_paid REAL NOT NULL DEFAULT 0, " +
                            "latest_tax_time INTEGER" +
                            ")",
                    )

                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS server_tax_stats (" +
                            "id INTEGER PRIMARY KEY CHECK (id = 1), " +
                            "total_tax_collected REAL NOT NULL DEFAULT 0, " +
                            "tax_fund_balance REAL NOT NULL DEFAULT 0, " +
                            "latest_tax_collected REAL NOT NULL DEFAULT 0, " +
                            "latest_operation_id INTEGER, " +
                            "updated_at INTEGER" +
                            ")",
                    )
                    statement.execute("INSERT OR IGNORE INTO server_tax_stats (id, total_tax_collected, tax_fund_balance, latest_tax_collected, latest_operation_id, updated_at) VALUES (1, 0, 0, 0, -1, 0)")
                }
            }
        } catch (exception: SQLException) {
            logger.severe("初始化数据库表失败: ${exception.message}")
        }
    }

    private fun addColumnIfMissing(connection: Connection, table: String, column: String, definition: String) {
        try {
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE $table ADD COLUMN $column $definition")
            }
        } catch (exception: SQLException) {
            val msg = (exception.message ?: "").lowercase(Locale.ROOT)
            if (!msg.contains("duplicate column")) {
                // Best effort for compatibility with old SQLite files.
            }
        }
    }

    @JvmStatic
    fun saveRecord(
        plugin: Plugin,
        player: OfflinePlayer,
        oldBalance: Double,
        newBalance: Double,
        deduction: Double,
        isCheckAll: Boolean,
        operationId: Int,
        logger: Logger,
    ) {
        saveRecord(plugin, player, oldBalance, newBalance, deduction, isCheckAll, operationId, null, null, null, null, deduction, deduction, logger)
    }

    @JvmStatic
    fun saveRecord(
        plugin: Plugin,
        player: OfflinePlayer,
        oldBalance: Double,
        newBalance: Double,
        deduction: Double,
        isCheckAll: Boolean,
        operationId: Int,
        policyName: String?,
        operationType: String?,
        result: String?,
        reason: String?,
        requestedDeduction: Double,
        actualDeduction: Double,
        logger: Logger,
    ) {
        runSqlWithRetry(logger) {
            getConnection(plugin).use { connection ->
                connection.prepareStatement(
                    "INSERT INTO records (player_name, player, old_balance, new_balance, deduction, timestamp, is_checkall, operation_id, policy_name, operation_type, result, reason, requested_deduction, actual_deduction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { preparedStatement ->
                    preparedStatement.setString(1, player.name)
                    preparedStatement.setString(2, player.uniqueId.toString())
                    preparedStatement.setDouble(3, oldBalance)
                    preparedStatement.setDouble(4, newBalance)
                    preparedStatement.setDouble(5, deduction)
                    preparedStatement.setLong(6, System.currentTimeMillis())
                    preparedStatement.setBoolean(7, isCheckAll)
                    preparedStatement.setInt(8, operationId)
                    preparedStatement.setString(9, policyName)
                    preparedStatement.setString(10, operationType)
                    preparedStatement.setString(11, result)
                    preparedStatement.setString(12, reason)
                    preparedStatement.setDouble(13, requestedDeduction)
                    preparedStatement.setDouble(14, actualDeduction)
                    preparedStatement.executeUpdate()
                }
            }
        }
    }

    private fun interface SqlRunnable {
        @Throws(SQLException::class)
        fun run()
    }

    private fun runSqlWithRetry(logger: Logger, op: SqlRunnable) {
        val attempts = 6
        var sleepMs = 100L
        var last: SQLException? = null
        for (i in 0 until attempts) {
            try {
                op.run()
                return
            } catch (exception: SQLException) {
                last = exception
                val msg = (exception.message ?: "").lowercase(Locale.ROOT)
                if (msg.contains("database is locked") || msg.contains("sqlite_busy")) {
                    try {
                        Thread.sleep(sleepMs)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        logger.warning("SQL retry sleep interrupted: ${interrupted.message}")
                    }
                    sleepMs = minOf(1000L, sleepMs * 2)
                    continue
                }
                break
            }
        }
        if (last != null) {
            logger.severe("保存记录失败: ${last.message}")
        }
    }

    @JvmStatic
    fun getNextOperationId(plugin: Plugin, isCheckAll: Boolean, logger: Logger): Int {
        try {
            getConnection(plugin).use { connection ->
                connection.prepareStatement(
                    "INSERT INTO operations (timestamp, is_checkall, is_restored) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS,
                ).use { preparedStatement ->
                    preparedStatement.setLong(1, System.currentTimeMillis())
                    preparedStatement.setBoolean(2, isCheckAll)
                    preparedStatement.setBoolean(3, false)
                    preparedStatement.executeUpdate()

                    preparedStatement.generatedKeys.use { generatedKeys ->
                        if (generatedKeys.next()) {
                            return generatedKeys.getInt(1)
                        }
                        throw SQLException("创建操作失败，未获取到ID")
                    }
                }
            }
        } catch (exception: SQLException) {
            logger.severe("获取下一个操作ID失败: ${exception.message}")
            return -1
        }
    }

    @JvmStatic
    fun calculateTotalDeduction(plugin: Plugin, operationId: Int, logger: Logger): Double {
        try {
            getConnection(plugin).use { connection ->
                connection.prepareStatement("SELECT SUM(deduction) AS total_deduction FROM records WHERE operation_id = ?").use { preparedStatement ->
                    preparedStatement.setInt(1, operationId)
                    preparedStatement.executeQuery().use { resultSet ->
                        if (resultSet.next()) {
                            return resultSet.getDouble("total_deduction")
                        }
                    }
                }
            }
        } catch (exception: SQLException) {
            logger.severe("计算总扣除金额失败: ${exception.message}")
        }
        return 0.0
    }

    @JvmStatic
    fun getAffectedPlayersCount(plugin: Plugin, operationId: Int, logger: Logger): Int {
        try {
            getConnection(plugin).use { connection ->
                connection.prepareStatement("SELECT COUNT(*) AS cnt FROM records WHERE operation_id = ? AND deduction > 0").use { preparedStatement ->
                    preparedStatement.setInt(1, operationId)
                    preparedStatement.executeQuery().use { rs ->
                        if (rs.next()) {
                            return rs.getInt("cnt")
                        }
                    }
                }
            }
        } catch (exception: SQLException) {
            logger.severe("统计受影响玩家数量失败: ${exception.message}")
        }
        return 0
    }

    @JvmStatic
    fun cleanupRecords(plugin: Plugin, retentionDays: Int, logger: Logger) {
        val cutoffTime = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L)

        try {
            getConnection(plugin).use { connection ->
                connection.prepareStatement("DELETE FROM records WHERE timestamp < ?").use { preparedStatement ->
                    preparedStatement.setLong(1, cutoffTime)
                    val recordsDeleted = preparedStatement.executeUpdate()
                    logger.info("已清理 $recordsDeleted 条过期记录")
                }

                connection.createStatement().use { statement ->
                    val operationsDeleted = statement.executeUpdate("DELETE FROM operations WHERE id NOT IN (SELECT DISTINCT operation_id FROM records)")
                    logger.info("已清理 $operationsDeleted 条无关联记录的操作")
                }
            }
        } catch (exception: SQLException) {
            logger.severe("清理过期记录失败: ${exception.message}")
        }
    }

    @JvmStatic
    fun saveSnapshot(plugin: Plugin, snapshotData: EconomicSnapshot, logger: Logger) {
        val sql = "INSERT INTO economic_snapshots (snapshot_date, timestamp, total_money, player_count, " +
            "active_players_7d, active_players_30d, gini_coefficient, median_balance, mean_balance, " +
            "std_dev, top1_percentage, top5_percentage, top10_percentage) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        runSqlWithRetry(logger) {
            getConnection(plugin).use { conn ->
                conn.prepareStatement(sql).use { statement ->
                    statement.setString(1, snapshotData.date)
                    statement.setLong(2, snapshotData.timestamp)
                    statement.setDouble(3, snapshotData.totalMoney)
                    statement.setInt(4, snapshotData.playerCount)
                    statement.setInt(5, snapshotData.activePlayers7d)
                    statement.setInt(6, snapshotData.activePlayers30d)
                    statement.setDouble(7, snapshotData.gini)
                    statement.setDouble(8, snapshotData.median)
                    statement.setDouble(9, snapshotData.mean)
                    statement.setDouble(10, snapshotData.stdDev)
                    statement.setDouble(11, snapshotData.top1Pct)
                    statement.setDouble(12, snapshotData.top5Pct)
                    statement.setDouble(13, snapshotData.top10Pct)
                    statement.executeUpdate()
                }
            }
        }
    }

    @JvmStatic
    fun saveOperationImpact(plugin: Plugin, operationId: Int, impactData: OperationImpact, logger: Logger) {
        val sql = "INSERT OR REPLACE INTO operation_impact (operation_id, before_gini, after_gini, " +
            "before_median, after_median, before_mean, after_mean, before_std_dev, after_std_dev, " +
            "before_top1_pct, after_top1_pct, before_total_money, after_total_money, " +
            "total_tax_collected, players_affected, timestamp) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        runSqlWithRetry(logger) {
            getConnection(plugin).use { conn ->
                conn.prepareStatement(sql).use { statement ->
                    statement.setInt(1, operationId)
                    statement.setDouble(2, impactData.beforeGini)
                    statement.setDouble(3, impactData.afterGini)
                    statement.setDouble(4, impactData.beforeMedian)
                    statement.setDouble(5, impactData.afterMedian)
                    statement.setDouble(6, impactData.beforeMean)
                    statement.setDouble(7, impactData.afterMean)
                    statement.setDouble(8, impactData.beforeStdDev)
                    statement.setDouble(9, impactData.afterStdDev)
                    statement.setDouble(10, impactData.beforeTop1Pct)
                    statement.setDouble(11, impactData.afterTop1Pct)
                    statement.setDouble(12, impactData.beforeTotalMoney)
                    statement.setDouble(13, impactData.afterTotalMoney)
                    statement.setDouble(14, impactData.totalTaxCollected)
                    statement.setInt(15, impactData.playersAffected)
                    statement.setLong(16, impactData.timestamp)
                    statement.executeUpdate()
                }
            }
        }
    }

    @JvmStatic
    fun getLatestSnapshot(plugin: Plugin, logger: Logger): EconomicSnapshot? {
        val sql = "SELECT * FROM economic_snapshots ORDER BY timestamp DESC LIMIT 1"

        try {
            getConnection(plugin).use { conn ->
                conn.prepareStatement(sql).use { statement ->
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            return extractSnapshotFromResultSet(rs)
                        }
                    }
                }
            }
        } catch (exception: SQLException) {
            logger.severe("获取最新快照失败: ${exception.message}")
        }
        return null
    }

    @JvmStatic
    fun getSnapshotHistory(plugin: Plugin, days: Int, logger: Logger): List<EconomicSnapshot> {
        val cutoffTime = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L)
        val sql = "SELECT * FROM economic_snapshots WHERE timestamp >= ? ORDER BY timestamp ASC"
        val snapshots: MutableList<EconomicSnapshot> = ArrayList()

        try {
            getConnection(plugin).use { conn ->
                conn.prepareStatement(sql).use { statement ->
                    statement.setLong(1, cutoffTime)
                    statement.executeQuery().use { rs ->
                        while (rs.next()) {
                            snapshots.add(extractSnapshotFromResultSet(rs))
                        }
                    }
                }
            }
        } catch (exception: SQLException) {
            logger.severe("获取历史快照失败: ${exception.message}")
        }
        return snapshots
    }

    @JvmStatic
    fun getOperationImpact(plugin: Plugin, operationId: Int, logger: Logger): OperationImpact? {
        val sql = "SELECT * FROM operation_impact WHERE operation_id = ?"

        try {
            getConnection(plugin).use { conn ->
                conn.prepareStatement(sql).use { statement ->
                    statement.setInt(1, operationId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            return extractImpactFromResultSet(rs)
                        }
                    }
                }
            }
        } catch (exception: SQLException) {
            logger.severe("获取操作影响数据失败: ${exception.message}")
        }
        return null
    }

    @JvmStatic
    fun getRecentImpacts(plugin: Plugin, limit: Int, logger: Logger): List<OperationImpact> {
        val sql = "SELECT * FROM operation_impact ORDER BY timestamp DESC LIMIT ?"
        val impacts: MutableList<OperationImpact> = ArrayList()

        try {
            getConnection(plugin).use { conn ->
                conn.prepareStatement(sql).use { statement ->
                    statement.setInt(1, limit)
                    statement.executeQuery().use { rs ->
                        while (rs.next()) {
                            impacts.add(extractImpactFromResultSet(rs))
                        }
                    }
                }
            }
        } catch (exception: SQLException) {
            logger.severe("获取最近操作影响失败: ${exception.message}")
        }
        return impacts
    }

    @Throws(SQLException::class)
    private fun extractSnapshotFromResultSet(rs: ResultSet): EconomicSnapshot {
        val snapshot = EconomicSnapshot()
        snapshot.id = rs.getInt("id")
        snapshot.date = rs.getString("snapshot_date")
        snapshot.timestamp = rs.getLong("timestamp")
        snapshot.totalMoney = rs.getDouble("total_money")
        snapshot.playerCount = rs.getInt("player_count")
        snapshot.activePlayers7d = rs.getInt("active_players_7d")
        snapshot.activePlayers30d = rs.getInt("active_players_30d")
        snapshot.gini = rs.getDouble("gini_coefficient")
        snapshot.median = rs.getDouble("median_balance")
        snapshot.mean = rs.getDouble("mean_balance")
        snapshot.stdDev = rs.getDouble("std_dev")
        snapshot.top1Pct = rs.getDouble("top1_percentage")
        snapshot.top5Pct = rs.getDouble("top5_percentage")
        snapshot.top10Pct = rs.getDouble("top10_percentage")
        return snapshot
    }

    @Throws(SQLException::class)
    private fun extractImpactFromResultSet(rs: ResultSet): OperationImpact {
        val impact = OperationImpact()
        impact.operationId = rs.getInt("operation_id")
        impact.beforeGini = rs.getDouble("before_gini")
        impact.afterGini = rs.getDouble("after_gini")
        impact.beforeMedian = rs.getDouble("before_median")
        impact.afterMedian = rs.getDouble("after_median")
        impact.beforeMean = rs.getDouble("before_mean")
        impact.afterMean = rs.getDouble("after_mean")
        impact.beforeStdDev = rs.getDouble("before_std_dev")
        impact.afterStdDev = rs.getDouble("after_std_dev")
        impact.beforeTop1Pct = rs.getDouble("before_top1_pct")
        impact.afterTop1Pct = rs.getDouble("after_top1_pct")
        impact.beforeTotalMoney = rs.getDouble("before_total_money")
        impact.afterTotalMoney = rs.getDouble("after_total_money")
        impact.totalTaxCollected = rs.getDouble("total_tax_collected")
        impact.playersAffected = rs.getInt("players_affected")
        impact.timestamp = rs.getLong("timestamp")
        return impact
    }

    class EconomicSnapshot {
        @JvmField var id: Int = 0
        @JvmField var date: String? = null
        @JvmField var timestamp: Long = 0
        @JvmField var totalMoney: Double = 0.0
        @JvmField var playerCount: Int = 0
        @JvmField var activePlayers7d: Int = 0
        @JvmField var activePlayers30d: Int = 0
        @JvmField var gini: Double = 0.0
        @JvmField var median: Double = 0.0
        @JvmField var mean: Double = 0.0
        @JvmField var stdDev: Double = 0.0
        @JvmField var top1Pct: Double = 0.0
        @JvmField var top5Pct: Double = 0.0
        @JvmField var top10Pct: Double = 0.0
    }

    class OperationImpact {
        @JvmField var operationId: Int = 0
        @JvmField var beforeGini: Double = 0.0
        @JvmField var afterGini: Double = 0.0
        @JvmField var beforeMedian: Double = 0.0
        @JvmField var afterMedian: Double = 0.0
        @JvmField var beforeMean: Double = 0.0
        @JvmField var afterMean: Double = 0.0
        @JvmField var beforeStdDev: Double = 0.0
        @JvmField var afterStdDev: Double = 0.0
        @JvmField var beforeTop1Pct: Double = 0.0
        @JvmField var afterTop1Pct: Double = 0.0
        @JvmField var beforeTotalMoney: Double = 0.0
        @JvmField var afterTotalMoney: Double = 0.0
        @JvmField var totalTaxCollected: Double = 0.0
        @JvmField var playersAffected: Int = 0
        @JvmField var timestamp: Long = 0
    }
}
