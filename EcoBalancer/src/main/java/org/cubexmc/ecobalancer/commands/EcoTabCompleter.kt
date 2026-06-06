package org.cubexmc.ecobalancer.commands

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.util.StringUtil
import org.cubexmc.ecobalancer.EcoBalancer
import java.util.Arrays
import java.util.Locale

class EcoTabCompleter(
    private val plugin: EcoBalancer,
    private val util: UtilCommand,
) : TabCompleter {
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        val completions: MutableList<String> = ArrayList()

        if (args.size == 1) {
            val subCommands = arrayOf(
                "help", "reload", "checkall", "checkplayer", "stats",
                "perc", "checkrecords", "checkrecord", "restore", "interval",
                "gini", "concentration", "report", "health", "impact", "trends",
                "tax", "policy", "migrate", "gui",
            )
            StringUtil.copyPartialMatches(args[0], Arrays.asList(*subCommands), completions)
            return completions
        }

        if (args.size > 1) {
            val subCommand = args[0].lowercase(Locale.getDefault())
            val subArgs = Arrays.copyOfRange(args, 1, args.size)

            when (subCommand) {
                "interval" -> return util.intervalCommand.onTabComplete(sender, command, alias, subArgs)
                "policy" -> {
                    if (subArgs.size == 1) {
                        StringUtil.copyPartialMatches(subArgs[0], listOf("list", "set", "execute", "create", "delete", "clone", "rename", "info", "edit"), completions)
                        return completions
                    } else if (subArgs.size == 2 && listOf("set", "execute", "delete", "clone", "rename", "info", "edit").contains(subArgs[0].lowercase(Locale.getDefault()))) {
                        StringUtil.copyPartialMatches(subArgs[1], plugin.policyManager.getPolicyNames(), completions)
                        return completions
                    } else if (subArgs.size == 3 && subArgs[0].equals("edit", ignoreCase = true)) {
                        StringUtil.copyPartialMatches(subArgs[2], listOf("schedule", "time", "days", "dates", "inactive", "clear", "bracket", "mode", "debt"), completions)
                        return completions
                    } else if (subArgs.size > 3 && subArgs[0].equals("edit", ignoreCase = true)) {
                        val property = subArgs[2].lowercase(Locale.getDefault())
                        val editArgs = Arrays.copyOfRange(subArgs, 3, subArgs.size)
                        completePolicyEdit(property, subArgs[1], editArgs, completions)
                        return completions
                    }
                }
                "checkplayer" -> {
                    if (subArgs.size == 1) {
                        val playerNames: MutableList<String> = ArrayList()
                        Bukkit.getOnlinePlayers().forEach { player -> playerNames.add(player.name) }
                        StringUtil.copyPartialMatches(subArgs[0], playerNames, completions)
                        return completions
                    }
                }
                "stats" -> {
                    if (subArgs.size == 1) {
                        StringUtil.copyPartialMatches(subArgs[0], listOf("5", "10", "15", "20", "25", "30"), completions)
                        return completions
                    }
                }
                "checkrecord" -> {
                    if (subArgs.size == 1) {
                        return completions
                    } else if (subArgs.size == 2) {
                        StringUtil.copyPartialMatches(subArgs[1], listOf("deduction", "alphabet"), completions)
                        return completions
                    } else if (subArgs.size == 3) {
                        StringUtil.copyPartialMatches(subArgs[2], listOf("1", "2", "3", "4", "5"), completions)
                        return completions
                    }
                }
                "gini" -> {
                    if (subArgs.size == 1) {
                        StringUtil.copyPartialMatches(subArgs[0], listOf("7", "30", "60", "90"), completions)
                        return completions
                    }
                }
                "concentration" -> {
                    if (subArgs.isNotEmpty()) {
                        StringUtil.copyPartialMatches(subArgs[subArgs.size - 1], listOf("1", "5", "10", "20", "25", "50"), completions)
                        return completions
                    }
                }
                "tax" -> {
                    completeTax(subArgs, completions)
                    if (completions.isNotEmpty() || subArgs.isNotEmpty()) {
                        return completions
                    }
                }
                "migrate" -> {
                    if (subArgs.size == 1) {
                        StringUtil.copyPartialMatches(subArgs[0], listOf("check", "run", "backup"), completions)
                        return completions
                    }
                }
            }
        }

        return completions
    }

    private fun completePolicyEdit(property: String, policyName: String, editArgs: Array<String>, completions: MutableList<String>) {
        if (property == "schedule" && editArgs.size == 1) {
            StringUtil.copyPartialMatches(editArgs[0], listOf("daily", "weekly", "monthly"), completions)
        } else if (property == "mode" && editArgs.size == 1) {
            StringUtil.copyPartialMatches(editArgs[0], listOf("absolute", "percentile"), completions)
        } else if (property == "debt" && editArgs.size == 1) {
            StringUtil.copyPartialMatches(editArgs[0], listOf("inherit", "skip", "drain", "allow-negative"), completions)
        } else if (property == "bracket" && editArgs.size == 1) {
            StringUtil.copyPartialMatches(editArgs[0], listOf("add", "remove", "list", "clear"), completions)
        } else if (property == "bracket" && editArgs.size == 2 && editArgs[0].equals("remove", ignoreCase = true)) {
            val policy = plugin.policyManager.getPolicy(policyName)
            if (policy != null) {
                val brackets: MutableList<String> = ArrayList()
                for (map in policy.taxBrackets) {
                    brackets.add(map["threshold"].toString())
                }
                StringUtil.copyPartialMatches(editArgs[1], brackets, completions)
            }
        }
    }

    private fun completeTax(subArgs: Array<String>, completions: MutableList<String>) {
        if (subArgs.size == 1) {
            val taxSubs = listOf(
                "policy", "show", "schedule", "time", "days",
                "dates", "inactive", "clear", "bracket", "mode", "debt", "filter",
                "account", "status", "fund", "stats", "save", "reload",
            )
            StringUtil.copyPartialMatches(subArgs[0], taxSubs, completions)
            return
        } else if (subArgs.size == 2) {
            when (subArgs[0].lowercase(Locale.getDefault())) {
                "policy" -> StringUtil.copyPartialMatches(subArgs[1], listOf("list", "set", "execute", "create", "delete", "clone", "info", "edit"), completions)
                "schedule" -> StringUtil.copyPartialMatches(subArgs[1], listOf("daily", "weekly", "monthly"), completions)
                "time" -> StringUtil.copyPartialMatches(subArgs[1], listOf("00:00", "06:00", "12:00", "18:00"), completions)
                "days" -> StringUtil.copyPartialMatches(subArgs[1], listOf("1", "2", "3", "4", "5", "6", "7"), completions)
                "dates" -> StringUtil.copyPartialMatches(subArgs[1], listOf("1", "15", "28"), completions)
                "inactive", "clear" -> StringUtil.copyPartialMatches(subArgs[1], listOf("30", "60", "90", "180", "365"), completions)
                "mode" -> StringUtil.copyPartialMatches(subArgs[1], listOf("absolute", "percentile"), completions)
                "debt" -> StringUtil.copyPartialMatches(subArgs[1], listOf("inherit", "skip", "drain", "allow-negative"), completions)
                "bracket" -> StringUtil.copyPartialMatches(subArgs[1], listOf("add", "remove", "list", "clear"), completions)
                "account" -> StringUtil.copyPartialMatches(subArgs[1], listOf("enable", "disable", "name"), completions)
            }
            return
        } else if (subArgs.size == 3 && subArgs[0].equals("policy", ignoreCase = true)) {
            if (listOf("set", "execute", "delete", "clone", "info", "edit").contains(subArgs[1].lowercase(Locale.getDefault()))) {
                StringUtil.copyPartialMatches(subArgs[2], plugin.policyManager.getPolicyNames(), completions)
                return
            }
        } else if (subArgs.size == 4 && subArgs[0].equals("policy", ignoreCase = true) && subArgs[1].equals("edit", ignoreCase = true)) {
            StringUtil.copyPartialMatches(subArgs[3], listOf("schedule", "time", "days", "dates", "inactive", "clear", "bracket", "mode", "debt"), completions)
            return
        } else if (subArgs.size > 4 && subArgs[0].equals("policy", ignoreCase = true) && subArgs[1].equals("edit", ignoreCase = true)) {
            val property = subArgs[3].lowercase(Locale.getDefault())
            val editArgs = Arrays.copyOfRange(subArgs, 4, subArgs.size)
            completePolicyEdit(property, subArgs[2], editArgs, completions)
            return
        } else if (subArgs.size == 3) {
            if (subArgs[0].equals("bracket", ignoreCase = true) && subArgs[1].equals("remove", ignoreCase = true)) {
                val brackets: MutableList<String> = ArrayList()
                val policy = plugin.policyManager.getActivePolicy()
                if (policy != null) {
                    for (map in policy.taxBrackets) {
                        brackets.add(map["threshold"].toString())
                    }
                }
                StringUtil.copyPartialMatches(subArgs[2], brackets, completions)
                return
            } else if (subArgs[0].equals("account", ignoreCase = true) && subArgs[1].equals("name", ignoreCase = true)) {
                val names: MutableList<String> = ArrayList()
                names.add("tax")
                Bukkit.getOnlinePlayers().forEach { player -> names.add(player.name) }
                StringUtil.copyPartialMatches(subArgs[2], names, completions)
                return
            }
        }
    }
}
