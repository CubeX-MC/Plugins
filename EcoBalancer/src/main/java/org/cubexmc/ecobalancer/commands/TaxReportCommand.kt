package org.cubexmc.ecobalancer.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.utils.DatabaseUtils
import org.cubexmc.ecobalancer.utils.EconomicMetrics
import org.cubexmc.ecobalancer.utils.SchedulerUtils
import java.sql.Connection
import java.text.SimpleDateFormat
import java.util.Date

class TaxReportCommand(private val plugin: EcoBalancer) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        var operationId: Int? = null
        if (args.isNotEmpty()) {
            operationId = try {
                val parsed = args[0].toInt()
                if (parsed <= 0) {
                    val placeholders: MutableMap<String, String> = HashMap()
                    placeholders["id"] = args[0]
                    sender.sendMessage(plugin.getFormattedMessage("messages.report.invalid_id", placeholders))
                    return false
                }
                parsed
            } catch (_: NumberFormatException) {
                val placeholders: MutableMap<String, String> = HashMap()
                placeholders["input"] = args[0]
                sender.sendMessage(plugin.getFormattedMessage("messages.report.invalid_number", placeholders))
                return false
            }
        }

        sender.sendMessage(plugin.getFormattedMessage("messages.report.loading", null))

        val finalOperationId = operationId
        SchedulerUtils.runTaskAsync(
            plugin,
            Runnable {
                try {
                    DatabaseUtils.getConnection(plugin).use { conn ->
                        val targetOperationId = finalOperationId ?: getLatestCheckAllOperationId(conn)

                        if (targetOperationId <= 0) {
                            SchedulerUtils.runTask(plugin, Runnable {
                                sender.sendMessage(plugin.getFormattedMessage("messages.report.no_operations", null))
                            })
                            return@Runnable
                        }

                        val reportData = queryReportData(conn, targetOperationId)

                        if (reportData == null) {
                            SchedulerUtils.runTask(plugin, Runnable {
                                val placeholders: MutableMap<String, String> = HashMap()
                                placeholders["operation_id"] = targetOperationId.toString()
                                sender.sendMessage(plugin.getFormattedMessage("messages.report.not_found", placeholders))
                            })
                            return@Runnable
                        }

                        SchedulerUtils.runTask(plugin, Runnable { displayReport(sender, reportData) })
                    }
                } catch (exception: Exception) {
                    SchedulerUtils.runTask(
                        plugin,
                        Runnable {
                            sender.sendMessage(plugin.getFormattedMessage("messages.report.error", null))
                            plugin.logger.severe("Error generating tax report: ${exception.message}")
                            exception.printStackTrace()
                        },
                    )
                }
            },
        )

        return true
    }

    private fun getLatestCheckAllOperationId(conn: Connection): Int {
        val sql = "SELECT id FROM operations WHERE is_checkall = 1 ORDER BY timestamp DESC LIMIT 1"
        try {
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getInt("id")
                    }
                }
            }
        } catch (exception: Exception) {
            plugin.logger.warning("Failed to get latest checkAll operation: ${exception.message}")
        }
        return -1
    }

    private fun queryReportData(conn: Connection, operationId: Int): TaxReportData? {
        try {
            val opSql = "SELECT timestamp, is_checkall FROM operations WHERE id = ?"
            var timestamp = 0L
            var isCheckAll = false

            conn.prepareStatement(opSql).use { ps ->
                ps.setInt(1, operationId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        timestamp = rs.getLong("timestamp")
                        isCheckAll = rs.getBoolean("is_checkall")
                    } else {
                        return null
                    }
                }
            }

            val statsSql = "SELECT COUNT(*) as player_count, SUM(deduction) as total_tax, " +
                "AVG(old_balance) as avg_old_balance, AVG(new_balance) as avg_new_balance " +
                "FROM records WHERE operation_id = ? AND deduction > 0"

            var playerCount = 0
            var totalTax = 0.0
            var avgOldBalance = 0.0
            var avgNewBalance = 0.0

            conn.prepareStatement(statsSql).use { ps ->
                ps.setInt(1, operationId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        playerCount = rs.getInt("player_count")
                        totalTax = rs.getDouble("total_tax")
                        avgOldBalance = rs.getDouble("avg_old_balance")
                        avgNewBalance = rs.getDouble("avg_new_balance")
                    }
                }
            }

            val bracketSql = "SELECT " +
                "ROUND(deduction / old_balance * 100, 2) as tax_rate, " +
                "COUNT(*) as count, " +
                "SUM(deduction) as bracket_total " +
                "FROM records " +
                "WHERE operation_id = ? AND deduction > 0 AND old_balance > 0 " +
                "GROUP BY tax_rate " +
                "ORDER BY tax_rate"

            val brackets: MutableList<TaxBracketInfo> = ArrayList()
            conn.prepareStatement(bracketSql).use { ps ->
                ps.setInt(1, operationId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val rate = rs.getDouble("tax_rate")
                        val count = rs.getInt("count")
                        val total = rs.getDouble("bracket_total")
                        brackets.add(TaxBracketInfo(rate, count, total))
                    }
                }
            }

            var skippedCount = 0
            var exemptCount = 0
            var insufficientCount = 0
            conn.prepareStatement(
                "SELECT " +
                    "SUM(CASE WHEN deduction <= 0 THEN 1 ELSE 0 END) AS skipped_count, " +
                    "SUM(CASE WHEN result = 'EXEMPT' THEN 1 ELSE 0 END) AS exempt_count, " +
                    "SUM(CASE WHEN result = 'INSUFFICIENT_BALANCE_SKIPPED' THEN 1 ELSE 0 END) AS insufficient_count " +
                    "FROM records WHERE operation_id = ?",
            ).use { ps ->
                ps.setInt(1, operationId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        skippedCount = rs.getInt("skipped_count")
                        exemptCount = rs.getInt("exempt_count")
                        insufficientCount = rs.getInt("insufficient_count")
                    }
                }
            }

            return TaxReportData(
                operationId,
                timestamp,
                isCheckAll,
                playerCount,
                totalTax,
                avgOldBalance,
                avgNewBalance,
                brackets,
                skippedCount,
                exemptCount,
                insufficientCount,
            )
        } catch (exception: Exception) {
            plugin.logger.warning("Failed to query report data: ${exception.message}")
            return null
        }
    }

    private fun displayReport(sender: CommandSender, data: TaxReportData) {
        val placeholders: MutableMap<String, String> = HashMap()
        placeholders["operation_id"] = data.operationId.toString()
        placeholders["operation_type"] = if (data.isCheckAll) "CheckAll" else "CheckPlayer"
        placeholders["timestamp"] = formatTimestamp(data.timestamp)
        placeholders["player_count"] = data.playerCount.toString()
        placeholders["total_tax"] = EconomicMetrics.formatLargeNumber(data.totalTax)
        placeholders["avg_old_balance"] = EconomicMetrics.formatLargeNumber(data.avgOldBalance)
        placeholders["avg_new_balance"] = EconomicMetrics.formatLargeNumber(data.avgNewBalance)
        placeholders["skipped_count"] = data.skippedCount.toString()
        placeholders["exempt_count"] = data.exemptCount.toString()
        placeholders["insufficient_count"] = data.insufficientCount.toString()

        val avgTaxRate = if (data.avgOldBalance > 0) (data.totalTax / (data.avgOldBalance * data.playerCount)) * 100 else 0.0
        placeholders["avg_tax_rate"] = String.format("%.2f%%", avgTaxRate)

        sender.sendMessage(plugin.getFormattedMessage("messages.report.header", placeholders))
        sender.sendMessage(plugin.getFormattedMessage("messages.report.summary", placeholders))

        if (data.brackets.isNotEmpty()) {
            sender.sendMessage(plugin.getFormattedMessage("messages.report.brackets_header", null))
            for (bracket in data.brackets) {
                val bracketPlaceholders: MutableMap<String, String> = HashMap()
                bracketPlaceholders["tax_rate"] = String.format("%.2f%%", bracket.rate)
                bracketPlaceholders["player_count"] = bracket.count.toString()
                bracketPlaceholders["total_tax"] = EconomicMetrics.formatLargeNumber(bracket.totalTax)
                bracketPlaceholders["percentage"] = String.format("%.1f%%", (bracket.totalTax / data.totalTax) * 100)
                sender.sendMessage(plugin.getFormattedMessage("messages.report.bracket_line", bracketPlaceholders))
            }
        }
    }

    private fun formatTimestamp(timestamp: Long): String =
        try {
            SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(timestamp))
        } catch (_: Exception) {
            timestamp.toString()
        }

    private class TaxReportData(
        val operationId: Int,
        val timestamp: Long,
        val isCheckAll: Boolean,
        val playerCount: Int,
        val totalTax: Double,
        val avgOldBalance: Double,
        val avgNewBalance: Double,
        val brackets: List<TaxBracketInfo>,
        val skippedCount: Int,
        val exemptCount: Int,
        val insufficientCount: Int,
    )

    private class TaxBracketInfo(
        val rate: Double,
        val count: Int,
        val totalTax: Double,
    )
}
