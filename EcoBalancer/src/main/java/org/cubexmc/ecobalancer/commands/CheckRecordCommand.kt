package org.cubexmc.ecobalancer.commands

import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.util.StringUtil
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.utils.DatabaseUtils
import org.cubexmc.ecobalancer.utils.SchedulerUtils
import java.sql.Connection
import java.sql.SQLException
import java.util.Locale
import kotlin.math.ceil

class CheckRecordCommand(private val plugin: EcoBalancer) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        sender.sendMessage(plugin.getFormattedMessage("messages.processing", null))
        if (args.size < 1 || args.size > 3) {
            sender.sendMessage(plugin.getFormattedMessage("messages.record_usage", null))
            return true
        }

        val operationId = try {
            args[0].toInt()
        } catch (_: NumberFormatException) {
            sender.sendMessage(plugin.getFormattedMessage("messages.record_invalid_id", null))
            return true
        }

        var page = 1
        var sortBy = "deduction"
        if (args.size >= 2) {
            if (args[1].equals("alphabet", ignoreCase = true) || args[1].equals("deduction", ignoreCase = true)) {
                sortBy = args[1].lowercase(Locale.getDefault())
                if (args.size == 3) {
                    page = try {
                        args[2].toInt()
                    } catch (_: NumberFormatException) {
                        sender.sendMessage(plugin.getFormattedMessage("messages.invalid_page", null))
                        return true
                    }
                }
            } else {
                page = try {
                    args[1].toInt()
                } catch (_: NumberFormatException) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.invalid_page", null))
                    return true
                }
            }
        }

        SchedulerUtils.runTaskAsync(plugin, Runnable { loadAndSendRecord(sender, operationId, sortBy, page) })
        return true
    }

    private class RecordDetail {
        var playerName: String? = null
        var oldBalance = 0.0
        var newBalance = 0.0
        var deduction = 0.0
        var result: String? = null
        var reason: String? = null
    }

    private fun loadAndSendRecord(sender: CommandSender, operationId: Int, sortBy: String, page: Int) {
        val pageSize = 10
        try {
            DatabaseUtils.getConnection(plugin).use { connection ->
                connection.prepareStatement("SELECT * FROM operations WHERE id = ?").use { preparedStatement ->
                    preparedStatement.setInt(1, operationId)
                    preparedStatement.executeQuery().use { resultSet ->
                        if (!resultSet.next()) {
                            SchedulerUtils.runTask(plugin, Runnable {
                                sender.sendMessage(plugin.getFormattedMessage("messages.record_invalid_id", null))
                            })
                            return
                        }

                        val isCheckAll = resultSet.getBoolean("is_checkall")
                        if (isCheckAll) {
                            val offset = maxOf(0, (page - 1) * pageSize)
                            val details: MutableList<RecordDetail> = ArrayList()
                            val orderBySql = if (sortBy == "alphabet") "player_name" else "deduction DESC"
                            connection.prepareStatement("SELECT * FROM records WHERE operation_id = ? ORDER BY $orderBySql LIMIT ? OFFSET ?").use { selectStatement ->
                                selectStatement.setInt(1, operationId)
                                selectStatement.setInt(2, pageSize)
                                selectStatement.setInt(3, offset)
                                selectStatement.executeQuery().use { allRecords ->
                                    while (allRecords.next()) {
                                        val detail = RecordDetail()
                                        detail.playerName = allRecords.getString("player_name")
                                        detail.oldBalance = allRecords.getDouble("old_balance")
                                        detail.newBalance = allRecords.getDouble("new_balance")
                                        detail.deduction = allRecords.getDouble("deduction")
                                        detail.result = allRecords.getString("result")
                                        detail.reason = allRecords.getString("reason")
                                        details.add(detail)
                                    }
                                }
                            }

                            var total = 0
                            connection.prepareStatement("SELECT COUNT(*) AS total FROM records WHERE operation_id = ?").use { countStatement ->
                                countStatement.setInt(1, operationId)
                                countStatement.executeQuery().use { countResult ->
                                    if (countResult.next()) {
                                        total = countResult.getInt("total")
                                    }
                                }
                            }
                            val totalPages = maxOf(1, ceil(total.toDouble() / pageSize).toInt())
                            SchedulerUtils.runTask(plugin, Runnable { renderCheckAllRecord(sender, operationId, sortBy, page, totalPages, details) })
                        } else {
                            val detail = loadSingleRecord(connection, operationId)
                            SchedulerUtils.runTask(plugin, Runnable { renderSinglePlayerRecord(sender, operationId, detail) })
                        }
                    }
                }
            }
        } catch (exception: SQLException) {
            val errorPlaceholders: MutableMap<String, String> = HashMap()
            errorPlaceholders["error"] = exception.message ?: "unknown"
            SchedulerUtils.runTask(plugin, Runnable {
                sender.sendMessage(plugin.getFormattedMessage("messages.record_error", errorPlaceholders))
            })
        }
    }

    @Throws(SQLException::class)
    private fun loadSingleRecord(connection: Connection, operationId: Int): RecordDetail? {
        connection.prepareStatement("SELECT * FROM records WHERE operation_id = ? AND deduction != 0.0").use { selectStatement ->
            selectStatement.setInt(1, operationId)
            selectStatement.executeQuery().use { allRecords ->
                if (allRecords.next()) {
                    val detail = RecordDetail()
                    detail.playerName = allRecords.getString("player_name")
                    detail.oldBalance = allRecords.getDouble("old_balance")
                    detail.newBalance = allRecords.getDouble("new_balance")
                    detail.deduction = allRecords.getDouble("deduction")
                    detail.result = allRecords.getString("result")
                    detail.reason = allRecords.getString("reason")
                    return detail
                }
            }
        }
        return null
    }

    private fun renderCheckAllRecord(sender: CommandSender, operationId: Int, sortBy: String, page: Int, totalPages: Int, details: List<RecordDetail>) {
        val placeholders: MutableMap<String, String> = HashMap()
        placeholders["operation_id"] = operationId.toString()
        sender.sendMessage(plugin.getFormattedMessage("messages.record_all_header", placeholders))

        for (detail in details) {
            val detailPlaceholders: MutableMap<String, String> = HashMap()
            detailPlaceholders["player"] = detail.playerName ?: ""
            detailPlaceholders["old_balance"] = String.format("%.2f", detail.oldBalance)
            detailPlaceholders["new_balance"] = String.format("%.2f", detail.newBalance)
            detailPlaceholders["deduction"] = String.format("%.2f", detail.deduction)
            detailPlaceholders["result"] = detail.result ?: "LEGACY"
            detailPlaceholders["reason"] = detail.reason ?: ""
            sender.sendMessage(plugin.getFormattedMessage("messages.record_all_detail", detailPlaceholders))
        }

        val pagePlaceholders: MutableMap<String, String> = HashMap()
        pagePlaceholders["page"] = page.toString()
        pagePlaceholders["total"] = totalPages.toString()
        val baseCmdPrefix = "/ecobal checkrecord $operationId $sortBy "

        val prevPageComp = TextComponent()
        val nextPageComp = TextComponent()
        if (page > 1) {
            prevPageComp.text = plugin.getFormattedMessage("messages.prev_page", null)
            prevPageComp.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, baseCmdPrefix + (page - 1))
        } else {
            prevPageComp.text = plugin.getFormattedMessage("messages.no_prev_page", null)
        }
        if (page < totalPages) {
            nextPageComp.text = plugin.getFormattedMessage("messages.next_page", null)
            nextPageComp.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, baseCmdPrefix + (page + 1))
        } else {
            nextPageComp.text = plugin.getFormattedMessage("messages.no_next_page", null)
        }

        val pageMessage = plugin.getFormattedMessage(
            "messages.record_page",
            pagePlaceholders,
            arrayOf("prev", "next"),
            arrayOf(prevPageComp, nextPageComp),
        )
        sender.spigot().sendMessage(pageMessage)
        sender.sendMessage(plugin.getFormattedMessage("messages.record_footer", null))
    }

    private fun renderSinglePlayerRecord(sender: CommandSender, operationId: Int, detail: RecordDetail?) {
        if (detail == null) {
            sender.sendMessage(plugin.getFormattedMessage("messages.record_not_found", null))
            return
        }
        val placeholders: MutableMap<String, String> = HashMap()
        placeholders["operation_id"] = operationId.toString()
        placeholders["player"] = detail.playerName ?: ""
        placeholders["old_balance"] = String.format("%.2f", detail.oldBalance)
        placeholders["new_balance"] = String.format("%.2f", detail.newBalance)
        placeholders["deduction"] = String.format("%.2f", detail.deduction)
        placeholders["result"] = detail.result ?: "LEGACY"
        placeholders["reason"] = detail.reason ?: ""

        sender.sendMessage(plugin.getFormattedMessage("messages.record_player_header", placeholders))
        sender.sendMessage(plugin.getFormattedMessage("messages.record_player_detail", placeholders))
    }

    override fun onTabComplete(commandSender: CommandSender, command: Command, s: String, strings: Array<String>): List<String> {
        val suggestions: MutableList<String> = ArrayList()

        if (strings.isEmpty()) return suggestions

        if (strings.size == 2) {
            suggestions.add("deduction")
            suggestions.add("alphabet")
            return StringUtil.copyPartialMatches(strings[1], suggestions, ArrayList())
        }

        if (strings.size == 3) {
            val arg2 = strings[1].lowercase(Locale.ROOT)
            if (arg2 == "deduction" || arg2 == "alphabet") {
                suggestions.add("1")
                suggestions.add("2")
                suggestions.add("3")
                return StringUtil.copyPartialMatches(strings[2], suggestions, ArrayList())
            }
        }

        return suggestions
    }
}
