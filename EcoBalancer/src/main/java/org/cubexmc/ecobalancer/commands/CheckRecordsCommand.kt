package org.cubexmc.ecobalancer.commands

import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.utils.DatabaseUtils
import org.cubexmc.ecobalancer.utils.MessageUtils
import org.cubexmc.ecobalancer.utils.PageUtils
import org.cubexmc.ecobalancer.utils.SchedulerUtils
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.sql.Date
import java.text.SimpleDateFormat

class CheckRecordsCommand(private val plugin: EcoBalancer) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        var pageNumber = 1
        val pageSize = 10

        if (args.isNotEmpty()) {
            pageNumber = try {
                args[0].toInt()
            } catch (_: NumberFormatException) {
                sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.invalid_page", null, plugin.messagePrefix))
                return true
            }
        }

        sender.sendMessage(plugin.getFormattedMessage("messages.processing", null))
        val finalPageNumber = pageNumber
        SchedulerUtils.runTaskAsync(plugin, Runnable { loadAndSendRecords(sender, pageSize, finalPageNumber) })

        return true
    }

    private fun loadAndSendRecords(sender: CommandSender, pageSize: Int, pageNumber: Int) {
        try {
            DatabaseUtils.getConnection(plugin).use { connection ->
                val operations = fetchOperations(connection, pageSize, pageNumber)
                val totalRecords = getTotalOperationsCount(connection)
                val totalPages = PageUtils.calculateTotalPages(totalRecords, pageSize)
                SchedulerUtils.runTask(plugin, Runnable { renderOperations(sender, operations, pageNumber, totalPages) })
            }
        } catch (exception: SQLException) {
            val placeholders: MutableMap<String, String> = HashMap()
            placeholders["error"] = exception.message ?: "unknown"
            SchedulerUtils.runTask(plugin, Runnable {
                sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.records_error", placeholders, plugin.messagePrefix))
            })
        }
    }

    private fun renderOperations(sender: CommandSender, operations: List<OperationRecord>, pageNumber: Int, totalPages: Int) {
        if (operations.isEmpty()) {
            sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.no_records", null, plugin.messagePrefix))
            return
        }

        sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.records_header", null, plugin.messagePrefix))
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        for (operation in operations) {
            val placeholders: MutableMap<String, String> = HashMap()
            placeholders["time"] = dateFormat.format(Date(operation.timestamp))
            placeholders["type"] = if (operation.isCheckAll) "A" else "P"
            placeholders["deduction_amount"] = String.format("%.2f", operation.totalDeduction)
            placeholders["operation_id"] = operation.id.toString()
            placeholders["restored"] = if (operation.isRestored) "x" else " "

            val operationIdComponent = MessageUtils.createClickableComponent(
                operation.id.toString(),
                ClickEvent.Action.RUN_COMMAND,
                "/ecobal checkrecord ${operation.id}",
                MessageUtils.formatMessage(plugin.langConfig, "messages.records_click", null, plugin.messagePrefix),
            )

            val messageFormat = MessageUtils.formatComponent(
                plugin.langConfig,
                "messages.records_operation",
                placeholders,
                arrayOf("operation_id"),
                arrayOf(operationIdComponent),
                plugin.messagePrefix,
            )
            sender.spigot().sendMessage(messageFormat)
        }

        val placeholders: MutableMap<String, String> = HashMap()
        placeholders["page"] = pageNumber.toString()
        placeholders["total"] = totalPages.toString()

        val prevPage = TextComponent()
        val nextPage = TextComponent()
        if (pageNumber > 1) {
            prevPage.text = MessageUtils.formatMessage(plugin.langConfig, "messages.prev_page", null, plugin.messagePrefix)
            prevPage.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ecobal checkrecords ${pageNumber - 1}")
        } else {
            prevPage.text = MessageUtils.formatMessage(plugin.langConfig, "messages.no_prev_page", null, plugin.messagePrefix)
        }
        if (pageNumber < totalPages) {
            nextPage.text = MessageUtils.formatMessage(plugin.langConfig, "messages.next_page", null, plugin.messagePrefix)
            nextPage.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ecobal checkrecords ${pageNumber + 1}")
        } else {
            nextPage.text = MessageUtils.formatMessage(plugin.langConfig, "messages.no_next_page", null, plugin.messagePrefix)
        }
        placeholders["prev"] = prevPage.toPlainText()
        placeholders["next"] = nextPage.toPlainText()

        val message = MessageUtils.formatComponent(
            plugin.langConfig,
            "messages.records_page",
            placeholders,
            arrayOf("prev", "next"),
            arrayOf(prevPage, nextPage),
            plugin.messagePrefix,
        )

        sender.spigot().sendMessage(message)
        sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.records_footer", null, plugin.messagePrefix))
    }

    @Throws(SQLException::class)
    private fun fetchOperations(connection: Connection, pageSize: Int, pageNumber: Int): List<OperationRecord> {
        val operations: MutableList<OperationRecord> = ArrayList()
        val offset = (pageNumber - 1) * pageSize

        val sql = "SELECT o.id, o.timestamp, o.is_restored, r.is_checkall, SUM(r.deduction) AS total_deduction " +
            "FROM operations o JOIN records r ON o.id = r.operation_id " +
            "GROUP BY o.id ORDER BY o.timestamp DESC LIMIT ? OFFSET ?"

        connection.prepareStatement(sql).use { preparedStatement ->
            preparedStatement.setInt(1, pageSize)
            preparedStatement.setInt(2, offset)

            preparedStatement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    val record = OperationRecord()
                    record.id = resultSet.getInt("id")
                    record.timestamp = resultSet.getLong("timestamp")
                    record.isCheckAll = resultSet.getBoolean("is_checkall")
                    record.totalDeduction = resultSet.getDouble("total_deduction")
                    record.isRestored = resultSet.getBoolean("is_restored")
                    operations.add(record)
                }
            }
        }

        return operations
    }

    @Throws(SQLException::class)
    private fun getTotalOperationsCount(connection: Connection): Int {
        val sql = "SELECT COUNT(*) AS total FROM (SELECT o.id FROM operations o JOIN records r ON o.id = r.operation_id GROUP BY o.id)"

        connection.createStatement().use { statement: Statement ->
            statement.executeQuery(sql).use { resultSet ->
                if (resultSet.next()) {
                    return resultSet.getInt("total")
                }
            }
        }

        return 0
    }

    private class OperationRecord {
        var id = 0
        var timestamp = 0L
        var isCheckAll = false
        var isRestored = false
        var totalDeduction = 0.0
    }
}
