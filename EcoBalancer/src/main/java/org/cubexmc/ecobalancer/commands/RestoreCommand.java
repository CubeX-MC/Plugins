package org.cubexmc.ecobalancer.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.cubexmc.ecobalancer.EcoBalancer;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.cubexmc.ecobalancer.utils.DatabaseUtils;
import org.cubexmc.ecobalancer.utils.SchedulerUtils;

public class RestoreCommand implements CommandExecutor {
    private final EcoBalancer plugin;
    private static final ReentrantLock RESTORE_LOCK = new ReentrantLock();

    public RestoreCommand(EcoBalancer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(plugin.getFormattedMessage("messages.restore_usage", null));
            return true;
        }

        int operationId;
        try {
            operationId = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getFormattedMessage("messages.restore_invalid_id", null));
            return true;
        }

        if (!RESTORE_LOCK.tryLock()) {
            sender.sendMessage(plugin.getFormattedMessage("messages.processing", null));
            return true;
        }

        // 异步读取数据库，主线程同步回放经济变更
        SchedulerUtils.runTaskAsync(plugin, () -> {
            try (Connection connection = DatabaseUtils.getConnection(plugin)) {
                long targetTs = 0L;
                try (PreparedStatement ps = connection.prepareStatement("SELECT timestamp, is_restored FROM operations WHERE id = ?")) {
                    ps.setInt(1, operationId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            SchedulerUtils.runTask(plugin, () -> sender.sendMessage(plugin.getFormattedMessage("messages.restore_operation_not_found", null)));
                            return;
                        }
                        boolean already = rs.getBoolean("is_restored");
                        if (already) {
                            Map<String, String> placeholders = new HashMap<>();
                            placeholders.put("operation_id", String.valueOf(operationId));
                            SchedulerUtils.runTask(plugin, () -> sender.sendMessage(plugin.getFormattedMessage("messages.restore_already_restored", placeholders)));
                            return;
                        }
                        targetTs = rs.getLong("timestamp");
                    }
                }

                try (PreparedStatement psOps = connection.prepareStatement(
                        "SELECT id FROM operations WHERE timestamp >= ? AND is_restored = 0 ORDER BY timestamp DESC, id DESC")) {
                    psOps.setLong(1, targetTs);
                    try (ResultSet rsOps = psOps.executeQuery()) {
                        int restoredOps = 0;
                        while (rsOps.next()) {
                            int opId = rsOps.getInt("id");
                            // Claim this operation to avoid duplicate restore in edge races.
                            try (PreparedStatement claim = connection.prepareStatement(
                                    "UPDATE operations SET is_restored = 1 WHERE id = ? AND is_restored = 0")) {
                                claim.setInt(1, opId);
                                int updated = claim.executeUpdate();
                                if (updated == 0) {
                                    continue;
                                }
                            }

                            // 收集需要回滚的条目
                            java.util.List<java.util.AbstractMap.SimpleEntry<UUID, Double>> entries = new java.util.ArrayList<>();
                            try (PreparedStatement psRec = connection.prepareStatement(
                                    "SELECT player, deduction FROM records WHERE operation_id = ? AND deduction != 0.0")) {
                                psRec.setInt(1, opId);
                                try (ResultSet rsRec = psRec.executeQuery()) {
                                    while (rsRec.next()) {
                                        String playerUUID = rsRec.getString("player");
                                        double deduction = rsRec.getDouble("deduction");
                                        entries.add(new java.util.AbstractMap.SimpleEntry<>(UUID.fromString(playerUUID), deduction));
                                    }
                                }
                            }

                            // 在主线程按批次应用经济变更
                            final int batch = 200;
                            for (int i = 0; i < entries.size(); i += batch) {
                                final int start = i;
                                final int end = Math.min(i + batch, entries.size());
                                runOnMainThreadSync(() -> {
                                    for (int j = start; j < end; j++) {
                                        UUID uid = entries.get(j).getKey();
                                        double deduction = entries.get(j).getValue();
                                        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uid);
                                        if (deduction > 0) {
                                            EcoBalancer.getEconomy().depositPlayer(offlinePlayer, deduction);
                                        } else if (deduction < 0) {
                                            EcoBalancer.getEconomy().withdrawPlayer(offlinePlayer, -deduction);
                                        }
                                    }
                                });
                            }
                            restoredOps++;
                        }

                        int finalRestoredOps = restoredOps;
                        Map<String, String> placeholders = new HashMap<>();
                        placeholders.put("operation_id", String.valueOf(operationId));
                        placeholders.put("count", String.valueOf(finalRestoredOps));
                        SchedulerUtils.runTask(plugin, () -> sender.sendMessage(plugin.getFormattedMessage("messages.restored_range", placeholders)));
                    }
                }
            } catch (SQLException e) {
                Map<String, String> errorPlaceholders = new HashMap<>();
                errorPlaceholders.put("error", e.getMessage() == null ? "unknown" : e.getMessage());
                SchedulerUtils.runTask(plugin,
                        () -> sender.sendMessage(plugin.getFormattedMessage("messages.restore_error", errorPlaceholders)));
            } finally {
                RESTORE_LOCK.unlock();
            }
        });

        return true;
    }

    private void runOnMainThreadSync(Runnable task) {
        CountDownLatch latch = new CountDownLatch(1);
        SchedulerUtils.runTask(plugin, () -> {
            try {
                task.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}