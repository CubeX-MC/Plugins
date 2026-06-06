package org.cubexmc.ecobalancer.commands

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.utils.DatabaseUtils
import org.cubexmc.ecobalancer.utils.SchedulerUtils
import java.sql.SQLException
import java.util.AbstractMap
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class RestoreCommand(private val plugin: EcoBalancer) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.size != 1) {
            sender.sendMessage(plugin.getFormattedMessage("messages.restore_usage", null))
            return true
        }

        val operationId = try {
            args[0].toInt()
        } catch (_: NumberFormatException) {
            sender.sendMessage(plugin.getFormattedMessage("messages.restore_invalid_id", null))
            return true
        }

        if (!RESTORE_LOCK.tryLock()) {
            sender.sendMessage(plugin.getFormattedMessage("messages.processing", null))
            return true
        }

        SchedulerUtils.runTaskAsync(
            plugin,
            Runnable {
                try {
                    DatabaseUtils.getConnection(plugin).use { connection ->
                        var targetTs = 0L
                        connection.prepareStatement("SELECT timestamp, is_restored FROM operations WHERE id = ?").use { ps ->
                            ps.setInt(1, operationId)
                            ps.executeQuery().use { rs ->
                                if (!rs.next()) {
                                    SchedulerUtils.runTask(plugin, Runnable {
                                        sender.sendMessage(plugin.getFormattedMessage("messages.restore_operation_not_found", null))
                                    })
                                    return@Runnable
                                }
                                val already = rs.getBoolean("is_restored")
                                if (already) {
                                    val placeholders: MutableMap<String, String> = HashMap()
                                    placeholders["operation_id"] = operationId.toString()
                                    SchedulerUtils.runTask(plugin, Runnable {
                                        sender.sendMessage(plugin.getFormattedMessage("messages.restore_already_restored", placeholders))
                                    })
                                    return@Runnable
                                }
                                targetTs = rs.getLong("timestamp")
                            }
                        }

                        connection.prepareStatement(
                            "SELECT id FROM operations WHERE timestamp >= ? AND is_restored = 0 ORDER BY timestamp DESC, id DESC",
                        ).use { psOps ->
                            psOps.setLong(1, targetTs)
                            psOps.executeQuery().use { rsOps ->
                                var restoredOps = 0
                                while (rsOps.next()) {
                                    val opId = rsOps.getInt("id")
                                    connection.prepareStatement("UPDATE operations SET is_restored = 1 WHERE id = ? AND is_restored = 0").use { claim ->
                                        claim.setInt(1, opId)
                                        val updated = claim.executeUpdate()
                                        if (updated == 0) {
                                            continue
                                        }
                                    }

                                    val entries: MutableList<AbstractMap.SimpleEntry<UUID, Double>> = ArrayList()
                                    connection.prepareStatement("SELECT player, deduction FROM records WHERE operation_id = ? AND deduction != 0.0").use { psRec ->
                                        psRec.setInt(1, opId)
                                        psRec.executeQuery().use { rsRec ->
                                            while (rsRec.next()) {
                                                val playerUUID = rsRec.getString("player")
                                                val deduction = rsRec.getDouble("deduction")
                                                entries.add(AbstractMap.SimpleEntry(UUID.fromString(playerUUID), deduction))
                                            }
                                        }
                                    }

                                    val batch = 200
                                    var i = 0
                                    while (i < entries.size) {
                                        val start = i
                                        val end = minOf(i + batch, entries.size)
                                        runOnMainThreadSync(
                                            Runnable {
                                                for (j in start until end) {
                                                    val uid = entries[j].key
                                                    val deduction = entries[j].value
                                                    val offlinePlayer = Bukkit.getOfflinePlayer(uid)
                                                    if (deduction > 0) {
                                                        EcoBalancer.getEconomy().depositPlayer(offlinePlayer, deduction)
                                                    } else if (deduction < 0) {
                                                        EcoBalancer.getEconomy().withdrawPlayer(offlinePlayer, -deduction)
                                                    }
                                                }
                                            },
                                        )
                                        i += batch
                                    }
                                    restoredOps++
                                }

                                val finalRestoredOps = restoredOps
                                val placeholders: MutableMap<String, String> = HashMap()
                                placeholders["operation_id"] = operationId.toString()
                                placeholders["count"] = finalRestoredOps.toString()
                                SchedulerUtils.runTask(plugin, Runnable {
                                    sender.sendMessage(plugin.getFormattedMessage("messages.restored_range", placeholders))
                                })
                            }
                        }
                    }
                } catch (exception: SQLException) {
                    val errorPlaceholders: MutableMap<String, String> = HashMap()
                    errorPlaceholders["error"] = exception.message ?: "unknown"
                    SchedulerUtils.runTask(plugin, Runnable {
                        sender.sendMessage(plugin.getFormattedMessage("messages.restore_error", errorPlaceholders))
                    })
                } finally {
                    RESTORE_LOCK.unlock()
                }
            },
        )

        return true
    }

    private fun runOnMainThreadSync(task: Runnable) {
        val latch = CountDownLatch(1)
        SchedulerUtils.runTask(
            plugin,
            Runnable {
                try {
                    task.run()
                } finally {
                    latch.countDown()
                }
            },
        )
        try {
            latch.await(30, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private val RESTORE_LOCK = ReentrantLock()
    }
}
