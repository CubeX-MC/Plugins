package org.cubexmc.ecobalancer.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.cubexmc.ecobalancer.EcoBalancer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dedicated TabCompleter for /ecobal command to keep UtilCommand focused on
 * execution only.
 */
public class EcoTabCompleter implements TabCompleter {
    private final EcoBalancer plugin;
    private final UtilCommand util;

    public EcoTabCompleter(EcoBalancer plugin, UtilCommand util) {
        this.plugin = plugin;
        this.util = util;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String[] subCommands = { "help", "reload", "checkall", "checkplayer", "stats",
                    "perc", "checkrecords", "checkrecord", "restore", "interval",
                    "gini", "concentration", "report", "health", "impact", "trends",
                    "tax", "policy", "migrate", "gui" };
            StringUtil.copyPartialMatches(args[0], Arrays.asList(subCommands), completions);
            return completions;
        }

        if (args.length > 1) {
            String subCommand = args[0].toLowerCase();
            String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

            switch (subCommand) {
                case "interval":
                    // Delegate to IntervalCommand's own completer if present
                    if (util.getIntervalCommand() != null) {
                        return util.getIntervalCommand().onTabComplete(sender, command, alias, subArgs);
                    }
                    break;
                case "policy":
                    if (subArgs.length == 1) {
                        StringUtil.copyPartialMatches(subArgs[0], Arrays.asList("list", "set", "execute", "create", "delete", "clone", "rename", "info", "edit"), completions);
                        return completions;
                    } else if (subArgs.length == 2
                            && Arrays.asList("set", "execute", "delete", "clone", "rename", "info", "edit").contains(subArgs[0].toLowerCase())) {
                        StringUtil.copyPartialMatches(subArgs[1], plugin.getPolicyManager().getPolicyNames(),
                                completions);
                        return completions;
                    } else if (subArgs.length == 3 && subArgs[0].equalsIgnoreCase("edit")) {
                        StringUtil.copyPartialMatches(subArgs[2], Arrays.asList("schedule", "time", "days", "dates", "inactive", "clear", "bracket", "mode", "debt"), completions);
                        return completions;
                    } else if (subArgs.length > 3 && subArgs[0].equalsIgnoreCase("edit")) {
                        String property = subArgs[2].toLowerCase();
                        String[] editArgs = Arrays.copyOfRange(subArgs, 3, subArgs.length);
                        if (property.equals("schedule") && editArgs.length == 1) {
                            StringUtil.copyPartialMatches(editArgs[0], Arrays.asList("daily", "weekly", "monthly"), completions);
                        } else if (property.equals("mode") && editArgs.length == 1) {
                            StringUtil.copyPartialMatches(editArgs[0], Arrays.asList("absolute", "percentile"), completions);
                        } else if (property.equals("debt") && editArgs.length == 1) {
                            StringUtil.copyPartialMatches(editArgs[0], Arrays.asList("inherit", "skip", "drain", "allow-negative"), completions);
                        } else if (property.equals("bracket") && editArgs.length == 1) {
                            StringUtil.copyPartialMatches(editArgs[0], Arrays.asList("add", "remove", "list", "clear"), completions);
                        } else if (property.equals("bracket") && editArgs.length == 2 && editArgs[0].equalsIgnoreCase("remove")) {
                            org.cubexmc.ecobalancer.policies.TaxPolicy p = plugin.getPolicyManager().getPolicy(subArgs[1]);
                            if (p != null) {
                                List<String> brackets = new ArrayList<>();
                                p.getTaxBrackets().forEach(m -> brackets.add(String.valueOf(m.get("threshold"))));
                                StringUtil.copyPartialMatches(editArgs[1], brackets, completions);
                            }
                        }
                        return completions;
                    }
                    break;
                case "checkplayer":
                    if (subArgs.length == 1) {
                        List<String> playerNames = new ArrayList<>();
                        Bukkit.getOnlinePlayers().forEach(player -> playerNames.add(player.getName()));
                        StringUtil.copyPartialMatches(subArgs[0], playerNames, completions);
                        return completions;
                    }
                    break;
                case "stats":
                    if (subArgs.length == 1) {
                        List<String> barOptions = Arrays.asList("5", "10", "15", "20", "25", "30");
                        StringUtil.copyPartialMatches(subArgs[0], barOptions, completions);
                        return completions;
                    }
                    break;
                case "checkrecord":
                    // Suggest sort options at the second argument; third argument suggests page
                    // numbers
                    if (subArgs.length == 1) {
                        // no suggestions for operation id
                        return completions;
                    } else if (subArgs.length == 2) {
                        List<String> sortOptions = Arrays.asList("deduction", "alphabet");
                        StringUtil.copyPartialMatches(subArgs[1], sortOptions, completions);
                        return completions;
                    } else if (subArgs.length == 3) {
                        List<String> pages = Arrays.asList("1", "2", "3", "4", "5");
                        StringUtil.copyPartialMatches(subArgs[2], pages, completions);
                        return completions;
                    }
                    break;
                case "gini":
                    // gini [days] - suggest common day ranges
                    if (subArgs.length == 1) {
                        List<String> dayOptions = Arrays.asList("7", "30", "60", "90");
                        StringUtil.copyPartialMatches(subArgs[0], dayOptions, completions);
                        return completions;
                    }
                    break;
                case "concentration":
                    // concentration [percentages...] - suggest common percentages
                    if (subArgs.length >= 1) {
                        List<String> pctOptions = Arrays.asList("1", "5", "10", "20", "25", "50");
                        StringUtil.copyPartialMatches(subArgs[subArgs.length - 1], pctOptions, completions);
                        return completions;
                    }
                    break;
                case "tax":
                    if (subArgs.length == 1) {
                        List<String> taxSubs = Arrays.asList("policy", "show", "schedule", "time", "days",
                                "dates", "inactive", "clear", "bracket", "mode", "debt", "filter",
                                "account", "status", "fund", "stats", "save", "reload");
                        StringUtil.copyPartialMatches(subArgs[0], taxSubs, completions);
                        return completions;
                    } else if (subArgs.length == 2) {
                        switch (subArgs[0].toLowerCase()) {
                            case "policy":
                                StringUtil.copyPartialMatches(subArgs[1], Arrays.asList("list", "set", "execute", "create", "delete", "clone", "info", "edit"),
                                        completions);
                                return completions;
                            case "schedule":
                                StringUtil.copyPartialMatches(subArgs[1], Arrays.asList("daily", "weekly", "monthly"),
                                        completions);
                                return completions;
                            case "time":
                                StringUtil.copyPartialMatches(subArgs[1],
                                        Arrays.asList("00:00", "06:00", "12:00", "18:00"),
                                        completions);
                                return completions;
                            case "days":
                                StringUtil.copyPartialMatches(subArgs[1],
                                        Arrays.asList("1", "2", "3", "4", "5", "6", "7"),
                                        completions);
                                return completions;
                            case "dates":
                                StringUtil.copyPartialMatches(subArgs[1], Arrays.asList("1", "15", "28"),
                                        completions);
                                return completions;
                            case "inactive":
                            case "clear":
                                StringUtil.copyPartialMatches(subArgs[1], Arrays.asList("30", "60", "90", "180", "365"),
                                        completions);
                                return completions;
                            case "mode":
                                StringUtil.copyPartialMatches(subArgs[1], Arrays.asList("absolute", "percentile"),
                                        completions);
                                return completions;
                            case "debt":
                                StringUtil.copyPartialMatches(subArgs[1],
                                        Arrays.asList("inherit", "skip", "drain", "allow-negative"), completions);
                                return completions;
                            case "bracket":
                                StringUtil.copyPartialMatches(subArgs[1],
                                        Arrays.asList("add", "remove", "list", "clear"), completions);
                                return completions;
                            case "account":
                                StringUtil.copyPartialMatches(subArgs[1], Arrays.asList("enable", "disable", "name"),
                                        completions);
                                return completions;
                        }
                    } else if (subArgs.length == 3 && subArgs[0].equalsIgnoreCase("policy")) {
                        if (Arrays.asList("set", "execute", "delete", "clone", "info", "edit").contains(subArgs[1].toLowerCase())) {
                            StringUtil.copyPartialMatches(subArgs[2], plugin.getPolicyManager().getPolicyNames(),
                                    completions);
                            return completions;
                        }
                    } else if (subArgs.length == 4 && subArgs[0].equalsIgnoreCase("policy") && subArgs[1].equalsIgnoreCase("edit")) {
                        StringUtil.copyPartialMatches(subArgs[3], Arrays.asList("schedule", "time", "days", "dates", "inactive", "clear", "bracket", "mode", "debt"), completions);
                        return completions;
                    } else if (subArgs.length > 4 && subArgs[0].equalsIgnoreCase("policy") && subArgs[1].equalsIgnoreCase("edit")) {
                        String property = subArgs[3].toLowerCase();
                        String[] editArgs = Arrays.copyOfRange(subArgs, 4, subArgs.length);
                        if (property.equals("schedule") && editArgs.length == 1) {
                            StringUtil.copyPartialMatches(editArgs[0], Arrays.asList("daily", "weekly", "monthly"), completions);
                        } else if (property.equals("mode") && editArgs.length == 1) {
                            StringUtil.copyPartialMatches(editArgs[0], Arrays.asList("absolute", "percentile"), completions);
                        } else if (property.equals("debt") && editArgs.length == 1) {
                            StringUtil.copyPartialMatches(editArgs[0], Arrays.asList("inherit", "skip", "drain", "allow-negative"), completions);
                        } else if (property.equals("bracket") && editArgs.length == 1) {
                            StringUtil.copyPartialMatches(editArgs[0], Arrays.asList("add", "remove", "list", "clear"), completions);
                        } else if (property.equals("bracket") && editArgs.length == 2 && editArgs[0].equalsIgnoreCase("remove")) {
                            org.cubexmc.ecobalancer.policies.TaxPolicy p = plugin.getPolicyManager().getPolicy(subArgs[2]);
                            if (p != null) {
                                List<String> brackets = new ArrayList<>();
                                p.getTaxBrackets().forEach(m -> brackets.add(String.valueOf(m.get("threshold"))));
                                StringUtil.copyPartialMatches(editArgs[1], brackets, completions);
                            }
                        }
                        return completions;
                    } else if (subArgs.length == 3) {
                        if (subArgs[0].equalsIgnoreCase("bracket") && subArgs[1].equalsIgnoreCase("remove")) {
                            // Suggest existing brackets
                            List<String> brackets = new ArrayList<>();
                            org.cubexmc.ecobalancer.policies.TaxPolicy p = plugin.getPolicyManager().getActivePolicy();
                            if (p != null) {
                                p.getTaxBrackets().forEach(m -> brackets.add(String.valueOf(m.get("threshold"))));
                            }
                            StringUtil.copyPartialMatches(subArgs[2], brackets, completions);
                            return completions;
                        } else if (subArgs[0].equalsIgnoreCase("account") && subArgs[1].equalsIgnoreCase("name")) {
                            List<String> names = new ArrayList<>();
                            names.add("tax"); // default
                            Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
                            StringUtil.copyPartialMatches(subArgs[2], names, completions);
                            return completions;
                        }
                    }
                    break;
                case "migrate":
                    if (subArgs.length == 1) {
                        StringUtil.copyPartialMatches(subArgs[0], Arrays.asList("check", "run", "backup"), completions);
                        return completions;
                    }
                    break;
            }
        }

        return completions;
    }
}
