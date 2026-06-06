package org.cubexmc.ecobalancer.commands

import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.util.StringUtil
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.utils.AnalysisFilters
import org.cubexmc.ecobalancer.utils.MessageUtils
import org.cubexmc.ecobalancer.utils.PageUtils
import org.cubexmc.ecobalancer.utils.VaultUtils
import java.util.Locale

class IntervalCommand(private val plugin: EcoBalancer) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        val pr = AnalysisFilters.parse(args)
        var sortBy = "alphabet"
        var page = 1

        if (pr.remainingArgs.isNotEmpty()) {
            val a0 = pr.remainingArgs[0]
            if ("alphabet".equals(a0, ignoreCase = true) || "balance".equals(a0, ignoreCase = true)) {
                sortBy = a0.lowercase(Locale.getDefault())
                if (pr.remainingArgs.size > 1) {
                    page = try {
                        pr.remainingArgs[1].toInt()
                    } catch (_: NumberFormatException) {
                        sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.invalid_page", null, plugin.messagePrefix))
                        return true
                    }
                }
            } else {
                try {
                    page = a0.toInt()
                } catch (_: NumberFormatException) {
                }
            }
        }

        val matchedPlayers = AnalysisFilters.collectFilteredPlayers(pr.criteria, plugin.config.getString("stats-world", "")).toMutableList()

        sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.interval_sorting", null, plugin.messagePrefix))
        if (sortBy == "balance") {
            matchedPlayers.sortWith { p1, p2 -> java.lang.Double.compare(VaultUtils.getBalance(p2), VaultUtils.getBalance(p1)) }
        } else {
            matchedPlayers.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: "" })
        }

        val pageSize = 10
        val totalPages = PageUtils.calculateTotalPages(matchedPlayers.size, pageSize)

        if (!PageUtils.isValidPage(page, totalPages)) {
            sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.invalid_page", null, plugin.messagePrefix))
            return true
        }

        val headerPlaceholders: MutableMap<String, String> = HashMap()
        val low = pr.criteria.minBalance ?: Double.NEGATIVE_INFINITY
        val up = pr.criteria.maxBalance ?: Double.POSITIVE_INFINITY
        headerPlaceholders["low"] = if (low == Double.NEGATIVE_INFINITY) "∞" else String.format("%.2f", low)
        headerPlaceholders["up"] = if (up == Double.POSITIVE_INFINITY) "∞" else String.format("%.2f", up)
        sender.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.interval_header", headerPlaceholders, plugin.messagePrefix))

        val cmdFmt = StringBuilder("/interval")
        for (token in args) {
            if (token.contains(":")) cmdFmt.append(' ').append(token)
        }
        cmdFmt.append(' ').append(sortBy).append(' ').append("%d")
        val commandFormat = cmdFmt.toString()

        PageUtils.renderPagination(
            sender,
            matchedPlayers,
            pageSize,
            page,
            PageUtils.ItemRenderer<OfflinePlayer> { s, player, _ ->
                val balance = VaultUtils.getBalance(player)
                val lastPlayed = player.lastPlayed
                val currentTime = System.currentTimeMillis()
                val daysOffline = (currentTime - lastPlayed) / (1000 * 60 * 60 * 24)

                val placeholders: MutableMap<String, String> = HashMap()
                placeholders["player"] = player.name ?: "Unknown"
                placeholders["balance"] = String.format("%.2f", balance)
                placeholders["days_offline"] = daysOffline.toString()

                s.sendMessage(MessageUtils.formatMessage(plugin.langConfig, "messages.interval_player", placeholders, plugin.messagePrefix))
            },
            "messages.interval_header",
            "messages.interval_footer",
            "messages.interval_page",
            commandFormat,
            plugin.langConfig,
            "messages.invalid_page",
            plugin.messagePrefix,
            headerPlaceholders,
        )

        return true
    }

    override fun onTabComplete(commandSender: CommandSender, command: Command, s: String, strings: Array<String>): List<String> {
        val size = 2
        val ret: MutableCollection<String> = ArrayList(size)
        if (strings.size == 1) {
            ret.add("alphabet")
            ret.add("balance")
        }
        val lowerCase = strings[0].lowercase(Locale.ROOT)
        return StringUtil.copyPartialMatches(lowerCase, ret, ArrayList(size))
    }
}
