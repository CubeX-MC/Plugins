package org.cubexmc.ecobalancer.tax

import org.bukkit.OfflinePlayer
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.utils.DatabaseUtils
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger

class TaxLedgerService(private val plugin: EcoBalancer) {
    private val logger: Logger = plugin.logger

    fun recordTax(player: OfflinePlayer?, context: TaxContext?, decision: TaxDecision?) {
        if (player == null || context == null || decision == null || decision.actualDeduction <= 0) {
            return
        }
        val playerName = player.name ?: "Unknown"
        val uuid = player.uniqueId
        val now = System.currentTimeMillis()

        try {
            DatabaseUtils.getConnection(plugin).use { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement(
                        "INSERT INTO tax_ledger (operation_id, player_uuid, player_name, policy_name, amount, balance_before, balance_after, result, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ).use { ps ->
                        ps.setInt(1, context.operationId)
                        ps.setString(2, uuid.toString())
                        ps.setString(3, playerName)
                        ps.setString(4, context.policyName)
                        ps.setDouble(5, decision.actualDeduction)
                        ps.setDouble(6, decision.oldBalance)
                        ps.setDouble(7, decision.newBalance)
                        ps.setString(8, decision.result.name)
                        ps.setLong(9, now)
                        ps.executeUpdate()
                    }

                    conn.prepareStatement(
                        "INSERT INTO player_tax_totals (player_uuid, player_name, latest_tax_paid, total_tax_paid, latest_tax_time) VALUES (?, ?, ?, ?, ?) " +
                            "ON CONFLICT(player_uuid) DO UPDATE SET player_name = excluded.player_name, latest_tax_paid = excluded.latest_tax_paid, total_tax_paid = total_tax_paid + excluded.latest_tax_paid, latest_tax_time = excluded.latest_tax_time",
                    ).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.setString(2, playerName)
                        ps.setDouble(3, decision.actualDeduction)
                        ps.setDouble(4, decision.actualDeduction)
                        ps.setLong(5, now)
                        ps.executeUpdate()
                    }

                    ensureServerStatsRow(conn)
                    conn.prepareStatement(
                        "UPDATE server_tax_stats SET total_tax_collected = total_tax_collected + ?, tax_fund_balance = tax_fund_balance + ?, latest_tax_collected = ?, latest_operation_id = ?, updated_at = ? WHERE id = 1",
                    ).use { ps ->
                        ps.setDouble(1, decision.actualDeduction)
                        ps.setDouble(2, decision.actualDeduction)
                        ps.setDouble(3, decision.actualDeduction)
                        ps.setInt(4, context.operationId)
                        ps.setLong(5, now)
                        ps.executeUpdate()
                    }
                    conn.commit()
                } catch (exception: SQLException) {
                    conn.rollback()
                    throw exception
                } finally {
                    conn.autoCommit = true
                }
            }
        } catch (exception: SQLException) {
            logger.warning("Failed to record tax ledger entry: ${exception.message}")
        }
    }

    fun getServerStats(): ServerTaxStats {
        try {
            DatabaseUtils.getConnection(plugin).use { conn ->
                ensureServerStatsRow(conn)
                conn.prepareStatement(
                    "SELECT total_tax_collected, tax_fund_balance, latest_tax_collected, latest_operation_id, updated_at FROM server_tax_stats WHERE id = 1",
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            return ServerTaxStats(
                                rs.getDouble("total_tax_collected"),
                                rs.getDouble("tax_fund_balance"),
                                rs.getDouble("latest_tax_collected"),
                                rs.getInt("latest_operation_id"),
                                rs.getLong("updated_at"),
                            )
                        }
                    }
                }
            }
        } catch (exception: SQLException) {
            logger.warning("Failed to load tax fund stats: ${exception.message}")
        }
        return ServerTaxStats(0.0, 0.0, 0.0, -1, 0L)
    }

    fun getPlayerStats(player: OfflinePlayer?): PlayerTaxStats {
        if (player == null) {
            return PlayerTaxStats("Unknown", 0.0, 0.0, 0L)
        }
        try {
            DatabaseUtils.getConnection(plugin).use { conn ->
                conn.prepareStatement(
                    "SELECT player_name, latest_tax_paid, total_tax_paid, latest_tax_time FROM player_tax_totals WHERE player_uuid = ?",
                ).use { ps ->
                    ps.setString(1, player.uniqueId.toString())
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            return PlayerTaxStats(
                                rs.getString("player_name"),
                                rs.getDouble("latest_tax_paid"),
                                rs.getDouble("total_tax_paid"),
                                rs.getLong("latest_tax_time"),
                            )
                        }
                    }
                }
            }
        } catch (exception: SQLException) {
            logger.warning("Failed to load player tax stats: ${exception.message}")
        }
        return PlayerTaxStats(player.name ?: "Unknown", 0.0, 0.0, 0L)
    }

    @Throws(SQLException::class)
    private fun ensureServerStatsRow(conn: Connection) {
        conn.prepareStatement(
            "INSERT OR IGNORE INTO server_tax_stats (id, total_tax_collected, tax_fund_balance, latest_tax_collected, latest_operation_id, updated_at) VALUES (1, 0, 0, 0, -1, 0)",
        ).use { ps ->
            ps.executeUpdate()
        }
    }

    class ServerTaxStats(
        @JvmField val totalTaxCollected: Double,
        @JvmField val taxFundBalance: Double,
        @JvmField val latestTaxCollected: Double,
        @JvmField val latestOperationId: Int,
        @JvmField val updatedAt: Long,
    )

    class PlayerTaxStats(
        @JvmField val playerName: String,
        @JvmField val latestTaxPaid: Double,
        @JvmField val totalTaxPaid: Double,
        @JvmField val latestTaxTime: Long,
    )
}
