package org.cubexmc.ecobalancer.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.cubexmc.ecobalancer.utils.SchedulerUtils;

import java.util.Arrays;

public class UtilCommand implements CommandExecutor {
    private final EcoBalancer plugin;
    private final CheckAllCommand checkAllCommand;
    private final CheckPlayerCommand checkPlayerCommand;
    private final DescripStatsCommand statsCommand;
    private final PercentileCommand percCommand;
    private final CheckRecordsCommand checkRecordsCommand;
    private final CheckRecordCommand checkRecordCommand;
    private final RestoreCommand restoreCommand;
    private final IntervalCommand intervalCommand;
    private final GiniCommand giniCommand;
    private final ConcentrationCommand concentrationCommand;
    private final TaxReportCommand reportCommand;
    private final HealthCommand healthCommand;
    private final ImpactCommand impactCommand;
    private final TrendsCommand trendsCommand;
    private final TaxCommand taxCommand;
    private final MigrateCommand migrateCommand;

    public UtilCommand(EcoBalancer plugin) {
        this.plugin = plugin;
        this.checkAllCommand = new CheckAllCommand(plugin);
        this.checkPlayerCommand = new CheckPlayerCommand(plugin);
        this.statsCommand = new DescripStatsCommand(plugin);
        this.percCommand = new PercentileCommand(plugin);
        this.checkRecordsCommand = new CheckRecordsCommand(plugin);
        this.checkRecordCommand = new CheckRecordCommand(plugin);
        this.restoreCommand = new RestoreCommand(plugin);
        this.intervalCommand = new IntervalCommand(plugin);
        this.giniCommand = new GiniCommand(plugin);
        this.concentrationCommand = new ConcentrationCommand(plugin);
        this.reportCommand = new TaxReportCommand(plugin);
        this.healthCommand = new HealthCommand(plugin);
        this.impactCommand = new ImpactCommand(plugin);
        this.trendsCommand = new TrendsCommand(plugin);
        this.taxCommand = new TaxCommand(plugin);
        this.migrateCommand = new MigrateCommand(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // 显示帮助信息
            showHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        String permissionNode = getPermissionNode(subCommand);
        if (permissionNode != null && !sender.hasPermission(permissionNode)) {
            sender.sendMessage(plugin.getFormattedMessage("messages.no_permission", null));
            return true;
        }
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        switch (subCommand) {
            case "reload":
                SchedulerUtils.cancelAllTasks(plugin);
                plugin.reloadConfig();
                plugin.loadConfiguration();
                sender.sendMessage(plugin.getFormattedMessage("messages.reload_success", null));
                return true;
            case "help":
                showHelp(sender);
                return true;
            case "checkall":
                return checkAllCommand.onCommand(sender, command, label, subArgs);
            case "checkplayer":
                return checkPlayerCommand.onCommand(sender, command, label, subArgs);
            case "stats":
                return statsCommand.onCommand(sender, command, label, subArgs);
            case "perc":
                return percCommand.onCommand(sender, command, label, subArgs);
            case "checkrecords":
                return checkRecordsCommand.onCommand(sender, command, label, subArgs);
            case "checkrecord":
                return checkRecordCommand.onCommand(sender, command, label, subArgs);
            case "restore":
                return restoreCommand.onCommand(sender, command, label, subArgs);
            case "interval":
                return intervalCommand.onCommand(sender, command, label, subArgs);
            case "gini":
                return giniCommand.onCommand(sender, command, label, subArgs);
            case "concentration":
                return concentrationCommand.onCommand(sender, command, label, subArgs);
            case "report":
                return reportCommand.onCommand(sender, command, label, subArgs);
            case "health":
                return healthCommand.onCommand(sender, command, label, subArgs);
            case "impact":
                return impactCommand.onCommand(sender, command, label, subArgs);
            case "trends":
                return trendsCommand.onCommand(sender, command, label, subArgs);
            case "tax":
                return taxCommand.onCommand(sender, command, label, subArgs);
            case "migrate":
                return migrateCommand.onCommand(sender, command, label, subArgs);
            case "policy":
                // Route to TaxCommand treating 'policy' as the subcommand
                String[] taxArgs = new String[subArgs.length + 1];
                taxArgs[0] = "policy";
                System.arraycopy(subArgs, 0, taxArgs, 1, subArgs.length);
                return taxCommand.onCommand(sender, command, label, taxArgs);
            case "gui":
                if (!(sender instanceof org.bukkit.entity.Player)) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.command_player_only", null));
                    return true;
                }
                if (!sender.hasPermission("ecobalancer.gui.view")) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.no_permission", null));
                    return true;
                }
                plugin.getGuiManager().openMainMenu((org.bukkit.entity.Player) sender);
                return true;
            default:
                sender.sendMessage(plugin.getFormattedMessage("messages.unknown_command", null));
                return false;
        }
    }

    private String getPermissionNode(String subCommand) {
        switch (subCommand) {
            case "reload":
                return "ecobalancer.command.reload";
            case "checkall":
                return "ecobalancer.command.checkall";
            case "checkplayer":
                return "ecobalancer.command.checkplayer";
            case "stats":
                return "ecobalancer.command.stats";
            case "perc":
                return "ecobalancer.command.perc";
            case "checkrecords":
                return "ecobalancer.command.checkrecords";
            case "checkrecord":
                return "ecobalancer.command.checkrecord";
            case "restore":
                return "ecobalancer.command.restore";
            case "interval":
                return "ecobalancer.command.interval";
            case "gini":
                return "ecobalancer.command.gini";
            case "concentration":
                return "ecobalancer.command.concentration";
            case "report":
                return "ecobalancer.command.report";
            case "health":
                return "ecobalancer.command.health";
            case "impact":
                return "ecobalancer.command.impact";
            case "trends":
                return "ecobalancer.command.trends";
            case "tax":
            case "policy":
                return "ecobalancer.command.tax";
            case "migrate":
                return "ecobalancer.command.migrate";
            case "gui":
                return "ecobalancer.gui.view";
            default:
                return null;
        }
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(plugin.getFormattedMessage("messages.help_header", null));
        String[] commandMessages = {
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
                plugin.getFormattedMessage("messages.help_footer", null)
        };
        for (String str : commandMessages)
            sender.sendMessage(str);
    }

    // Expose subcommands for completer routing
    public IntervalCommand getIntervalCommand() {
        return intervalCommand;
    }

    public CheckRecordCommand getCheckRecordCommand() {
        return checkRecordCommand;
    }

    public TaxCommand getTaxCommand() {
        return taxCommand;
    }
}
