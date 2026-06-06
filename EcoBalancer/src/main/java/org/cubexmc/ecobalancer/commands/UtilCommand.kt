package org.cubexmc.ecobalancer.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.utils.SchedulerUtils
import java.util.Arrays
import java.util.Locale

class UtilCommand(private val plugin: EcoBalancer) : CommandExecutor {
    private val checkAllCommand = CheckAllCommand(plugin)
    private val checkPlayerCommand = CheckPlayerCommand(plugin)
    private val statsCommand = DescripStatsCommand(plugin)
    private val percCommand = PercentileCommand(plugin)
    private val checkRecordsCommand = CheckRecordsCommand(plugin)
    private val checkRecordCommand = CheckRecordCommand(plugin)
    val intervalCommand = IntervalCommand(plugin)
    private val restoreCommand = RestoreCommand(plugin)
    private val giniCommand = GiniCommand(plugin)
    private val concentrationCommand = ConcentrationCommand(plugin)
    private val reportCommand = TaxReportCommand(plugin)
    private val healthCommand = HealthCommand(plugin)
    private val impactCommand = ImpactCommand(plugin)
    private val trendsCommand = TrendsCommand(plugin)
    val taxCommand = TaxCommand(plugin)
    private val migrateCommand = MigrateCommand(plugin)

    fun getCheckRecordCommand(): CheckRecordCommand = checkRecordCommand

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            showHelp(sender)
            return true
        }

        val subCommand = args[0].lowercase(Locale.getDefault())
        val permissionNode = getPermissionNode(subCommand)
        if (permissionNode != null && !sender.hasPermission(permissionNode)) {
            sender.sendMessage(plugin.getFormattedMessage("messages.no_permission", null))
            return true
        }
        val subArgs = Arrays.copyOfRange(args, 1, args.size)

        return when (subCommand) {
            "reload" -> {
                SchedulerUtils.cancelAllTasks(plugin)
                plugin.reloadConfig()
                plugin.loadConfiguration()
                sender.sendMessage(plugin.getFormattedMessage("messages.reload_success", null))
                true
            }
            "help" -> {
                showHelp(sender)
                true
            }
            "checkall" -> checkAllCommand.onCommand(sender, command, label, subArgs)
            "checkplayer" -> checkPlayerCommand.onCommand(sender, command, label, subArgs)
            "stats" -> statsCommand.onCommand(sender, command, label, subArgs)
            "perc" -> percCommand.onCommand(sender, command, label, subArgs)
            "checkrecords" -> checkRecordsCommand.onCommand(sender, command, label, subArgs)
            "checkrecord" -> checkRecordCommand.onCommand(sender, command, label, subArgs)
            "restore" -> restoreCommand.onCommand(sender, command, label, subArgs)
            "interval" -> intervalCommand.onCommand(sender, command, label, subArgs)
            "gini" -> giniCommand.onCommand(sender, command, label, subArgs)
            "concentration" -> concentrationCommand.onCommand(sender, command, label, subArgs)
            "report" -> reportCommand.onCommand(sender, command, label, subArgs)
            "health" -> healthCommand.onCommand(sender, command, label, subArgs)
            "impact" -> impactCommand.onCommand(sender, command, label, subArgs)
            "trends" -> trendsCommand.onCommand(sender, command, label, subArgs)
            "tax" -> taxCommand.onCommand(sender, command, label, subArgs)
            "migrate" -> migrateCommand.onCommand(sender, command, label, subArgs)
            "policy" -> {
                val taxArgs = Array(subArgs.size + 1) { index -> if (index == 0) "policy" else subArgs[index - 1] }
                taxCommand.onCommand(sender, command, label, taxArgs)
            }
            "gui" -> {
                if (sender !is Player) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.command_player_only", null))
                    true
                } else if (!sender.hasPermission("ecobalancer.gui.view")) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.no_permission", null))
                    true
                } else {
                    plugin.guiManager.openMainMenu(sender)
                    true
                }
            }
            else -> {
                sender.sendMessage(plugin.getFormattedMessage("messages.unknown_command", null))
                false
            }
        }
    }

    private fun getPermissionNode(subCommand: String): String? =
        when (subCommand) {
            "reload" -> "ecobalancer.command.reload"
            "checkall" -> "ecobalancer.command.checkall"
            "checkplayer" -> "ecobalancer.command.checkplayer"
            "stats" -> "ecobalancer.command.stats"
            "perc" -> "ecobalancer.command.perc"
            "checkrecords" -> "ecobalancer.command.checkrecords"
            "checkrecord" -> "ecobalancer.command.checkrecord"
            "restore" -> "ecobalancer.command.restore"
            "interval" -> "ecobalancer.command.interval"
            "gini" -> "ecobalancer.command.gini"
            "concentration" -> "ecobalancer.command.concentration"
            "report" -> "ecobalancer.command.report"
            "health" -> "ecobalancer.command.health"
            "impact" -> "ecobalancer.command.impact"
            "trends" -> "ecobalancer.command.trends"
            "tax", "policy" -> "ecobalancer.command.tax"
            "migrate" -> "ecobalancer.command.migrate"
            "gui" -> "ecobalancer.gui.view"
            else -> null
        }

    private fun showHelp(sender: CommandSender) {
        sender.sendMessage(plugin.getFormattedMessage("messages.help_header", null))
        val commandMessages = arrayOf(
            plugin.getFormattedMessage("messages.commands.help", null),
            plugin.getFormattedMessage("messages.commands.checkall", null),
            plugin.getFormattedMessage("messages.commands.checkplayer", null),
            plugin.getFormattedMessage("messages.commands.gini", null),
            plugin.getFormattedMessage("messages.commands.concentration", null),
            plugin.getFormattedMessage("messages.commands.report", null),
            plugin.getFormattedMessage("messages.commands.checkrecords", null),
            plugin.getFormattedMessage("messages.commands.checkrecord", null),
            plugin.getFormattedMessage("messages.commands.restore", null),
            plugin.getFormattedMessage("messages.commands.stats", null),
            plugin.getFormattedMessage("messages.commands.interval", null),
            plugin.getFormattedMessage("messages.commands.perc", null),
            plugin.getFormattedMessage("messages.commands.health", null),
            plugin.getFormattedMessage("messages.commands.impact", null),
            plugin.getFormattedMessage("messages.commands.trends", null),
            plugin.getFormattedMessage("messages.commands.tax", null),
            plugin.getFormattedMessage("messages.commands.policy", null),
            plugin.getFormattedMessage("messages.commands.migrate", null),
            plugin.getFormattedMessage("messages.commands.gui", null),
            plugin.getFormattedMessage("messages.commands.reload", null),
            plugin.getFormattedMessage("messages.help_footer", null),
        )
        for (str in commandMessages) {
            sender.sendMessage(str)
        }
    }
}
