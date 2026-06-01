package org.cubexmc.ecobalancer.tax;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.OfflinePlayer;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.cubexmc.ecobalancer.utils.DatabaseUtils;

public class TaxLedgerService {
    private final EcoBalancer plugin;
    private final Logger logger;

    public TaxLedgerService(EcoBalancer plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void recordTax(OfflinePlayer player, TaxContext context, TaxDecision decision) {
        if (player == null || context == null || decision == null || decision.getActualDeduction() <= 0) {
            return;
        }
        String playerName = player.getName() == null ? "Unknown" : player.getName();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        try (Connection conn = DatabaseUtils.getConnection(plugin)) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO tax_ledger (operation_id, player_uuid, player_name, policy_name, amount, balance_before, balance_after, result, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setInt(1, context.getOperationId());
                    ps.setString(2, uuid.toString());
                    ps.setString(3, playerName);
                    ps.setString(4, context.getPolicyName());
                    ps.setDouble(5, decision.getActualDeduction());
                    ps.setDouble(6, decision.getOldBalance());
                    ps.setDouble(7, decision.getNewBalance());
                    ps.setString(8, decision.getResult().name());
                    ps.setLong(9, now);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO player_tax_totals (player_uuid, player_name, latest_tax_paid, total_tax_paid, latest_tax_time) VALUES (?, ?, ?, ?, ?) "
                                + "ON CONFLICT(player_uuid) DO UPDATE SET player_name = excluded.player_name, latest_tax_paid = excluded.latest_tax_paid, total_tax_paid = total_tax_paid + excluded.latest_tax_paid, latest_tax_time = excluded.latest_tax_time")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, playerName);
                    ps.setDouble(3, decision.getActualDeduction());
                    ps.setDouble(4, decision.getActualDeduction());
                    ps.setLong(5, now);
                    ps.executeUpdate();
                }

                ensureServerStatsRow(conn);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE server_tax_stats SET total_tax_collected = total_tax_collected + ?, tax_fund_balance = tax_fund_balance + ?, latest_tax_collected = ?, latest_operation_id = ?, updated_at = ? WHERE id = 1")) {
                    ps.setDouble(1, decision.getActualDeduction());
                    ps.setDouble(2, decision.getActualDeduction());
                    ps.setDouble(3, decision.getActualDeduction());
                    ps.setInt(4, context.getOperationId());
                    ps.setLong(5, now);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.warning("Failed to record tax ledger entry: " + e.getMessage());
        }
    }

    public ServerTaxStats getServerStats() {
        try (Connection conn = DatabaseUtils.getConnection(plugin)) {
            ensureServerStatsRow(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT total_tax_collected, tax_fund_balance, latest_tax_collected, latest_operation_id, updated_at FROM server_tax_stats WHERE id = 1");
                    ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ServerTaxStats(rs.getDouble("total_tax_collected"), rs.getDouble("tax_fund_balance"),
                            rs.getDouble("latest_tax_collected"), rs.getInt("latest_operation_id"),
                            rs.getLong("updated_at"));
                }
            }
        } catch (SQLException e) {
            logger.warning("Failed to load tax fund stats: " + e.getMessage());
        }
        return new ServerTaxStats(0.0, 0.0, 0.0, -1, 0L);
    }

    public PlayerTaxStats getPlayerStats(OfflinePlayer player) {
        if (player == null) {
            return new PlayerTaxStats("Unknown", 0.0, 0.0, 0L);
        }
        try (Connection conn = DatabaseUtils.getConnection(plugin);
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT player_name, latest_tax_paid, total_tax_paid, latest_tax_time FROM player_tax_totals WHERE player_uuid = ?")) {
            ps.setString(1, player.getUniqueId().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PlayerTaxStats(rs.getString("player_name"), rs.getDouble("latest_tax_paid"),
                            rs.getDouble("total_tax_paid"), rs.getLong("latest_tax_time"));
                }
            }
        } catch (SQLException e) {
            logger.warning("Failed to load player tax stats: " + e.getMessage());
        }
        return new PlayerTaxStats(player.getName() == null ? "Unknown" : player.getName(), 0.0, 0.0, 0L);
    }

    private void ensureServerStatsRow(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO server_tax_stats (id, total_tax_collected, tax_fund_balance, latest_tax_collected, latest_operation_id, updated_at) VALUES (1, 0, 0, 0, -1, 0)")) {
            ps.executeUpdate();
        }
    }

    public static class ServerTaxStats {
        public final double totalTaxCollected;
        public final double taxFundBalance;
        public final double latestTaxCollected;
        public final int latestOperationId;
        public final long updatedAt;

        public ServerTaxStats(double totalTaxCollected, double taxFundBalance, double latestTaxCollected,
                int latestOperationId, long updatedAt) {
            this.totalTaxCollected = totalTaxCollected;
            this.taxFundBalance = taxFundBalance;
            this.latestTaxCollected = latestTaxCollected;
            this.latestOperationId = latestOperationId;
            this.updatedAt = updatedAt;
        }
    }

    public static class PlayerTaxStats {
        public final String playerName;
        public final double latestTaxPaid;
        public final double totalTaxPaid;
        public final long latestTaxTime;

        public PlayerTaxStats(String playerName, double latestTaxPaid, double totalTaxPaid, long latestTaxTime) {
            this.playerName = playerName;
            this.latestTaxPaid = latestTaxPaid;
            this.totalTaxPaid = totalTaxPaid;
            this.latestTaxTime = latestTaxTime;
        }
    }
}
