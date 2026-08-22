package org.cubexmc.statecharge.command

import java.math.BigDecimal
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.cubexmc.core.CubexCommandSuggestions
import org.cubexmc.statecharge.StateChargePlugin
import org.cubexmc.statecharge.model.StateSpec
import org.cubexmc.statecharge.service.ToggleResult

class StateChargeCommand(private val plugin: StateChargePlugin) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            // 空参直接开交易页:玩家最常做的就是开关状态。
            if (sender is Player && sender.hasPermission(USE_PERMISSION)) {
                plugin.shop().open(sender)
            } else {
                sendHelp(sender)
            }
            return true
        }
        when (args[0].lowercase()) {
            "help" -> sendHelp(sender)
            "gui", "shop" -> gui(sender)
            "list" -> list(sender)
            "status" -> status(sender)
            "toggle" -> toggle(sender, args)
            "on" -> switch(sender, args, on = true)
            "off" -> switch(sender, args, on = false)
            "guard" -> guard(sender, args)
            "admin" -> admin(sender, args)
            else -> sendHelp(sender)
        }
        return true
    }

    // ---- 玩家命令 ----

    private fun sendHelp(sender: CommandSender) {
        if (!requirePermission(sender, USE_PERMISSION)) {
            return
        }
        sender.sendMessage(plugin.lang().message("help"))
    }

    private fun gui(sender: CommandSender) {
        if (!requirePermission(sender, USE_PERMISSION)) {
            return
        }
        val player = requirePlayer(sender) ?: return
        plugin.shop().open(player)
    }

    private fun list(sender: CommandSender) {
        if (!requirePermission(sender, USE_PERMISSION)) {
            return
        }
        val specs = plugin.definitions().purchasable()
        if (specs.isEmpty()) {
            sender.sendMessage(plugin.lang().ui("list-empty"))
            return
        }
        sender.sendMessage(plugin.lang().ui("list-header"))
        for (spec in specs) {
            sender.sendMessage(
                plugin.lang().ui(
                    "list-line",
                    mapOf(
                        "id" to spec.id(),
                        "state" to spec.display(),
                        "price" to plugin.economy().format(spec.price()),
                        "duration" to plugin.durationText(spec.unitSeconds()),
                    ),
                ),
            )
        }
    }

    private fun status(sender: CommandSender) {
        if (!requirePermission(sender, USE_PERMISSION)) {
            return
        }
        val player = requirePlayer(sender) ?: return
        val active = plugin.storage().activeStates(player.uniqueId)
        if (active.isEmpty()) {
            sender.sendMessage(plugin.lang().ui("status-empty"))
        } else {
            sender.sendMessage(plugin.lang().ui("status-header"))
            for (stateId in active) {
                val spec = plugin.definitions().byId(stateId)
                sender.sendMessage(
                    plugin.lang().ui(
                        "status-line",
                        mapOf(
                            "state" to displayOf(spec, stateId),
                            "price" to plugin.economy().format(spec?.price() ?: BigDecimal.ZERO),
                            "duration" to plugin.durationText(spec?.unitSeconds() ?: 0L),
                        ),
                    ),
                )
            }
        }
        sender.sendMessage(
            plugin.lang().ui(
                "status-guard",
                mapOf("amount" to plugin.economy().format(plugin.states().guardOf(player.uniqueId))),
            ),
        )
    }

    private fun toggle(sender: CommandSender, args: Array<String>) {
        if (!requirePermission(sender, USE_PERMISSION)) {
            return
        }
        val player = requirePlayer(sender) ?: return
        if (args.size < 2) {
            sender.sendMessage(plugin.lang().ui("usage-toggle"))
            return
        }
        report(player, plugin.states().toggle(player, args[1].lowercase()), args[1])
    }

    private fun switch(sender: CommandSender, args: Array<String>, on: Boolean) {
        if (!requirePermission(sender, USE_PERMISSION)) {
            return
        }
        val player = requirePlayer(sender) ?: return
        if (args.size < 2) {
            sender.sendMessage(plugin.lang().ui(if (on) "usage-on" else "usage-off"))
            return
        }
        val stateId = args[1].lowercase()
        val spec = plugin.definitions().byId(stateId)
        if (spec == null) {
            sender.sendMessage(plugin.lang().message("toggle-unknown-state", mapOf("state" to stateId)))
            return
        }
        // 已经是目标状态就什么都不做,避免 on 一个开着的状态反而把它关掉。
        if (plugin.storage().isActive(player.uniqueId, stateId) == on) {
            sender.sendMessage(
                plugin.lang().message(
                    if (on) "toggle-already-on" else "toggle-already-off",
                    mapOf("state" to spec.display()),
                ),
            )
            return
        }
        val result = if (on) plugin.states().turnOn(player, spec) else plugin.states().turnOff(player, spec)
        report(player, result, stateId)
    }

    private fun guard(sender: CommandSender, args: Array<String>) {
        if (!requirePermission(sender, USE_PERMISSION)) {
            return
        }
        val player = requirePlayer(sender) ?: return
        if (args.size < 2) {
            sender.sendMessage(
                plugin.lang().ui(
                    "status-guard",
                    mapOf("amount" to plugin.economy().format(plugin.states().guardOf(player.uniqueId))),
                ),
            )
            sender.sendMessage(plugin.lang().ui("usage-guard"))
            return
        }
        if (args[1].equals("off", ignoreCase = true) || args[1].equals("reset", ignoreCase = true)) {
            plugin.states().setGuard(player.uniqueId, null)
            sender.sendMessage(
                plugin.lang().message(
                    "guard-reset",
                    mapOf("amount" to plugin.economy().format(plugin.defaultGuard())),
                ),
            )
            return
        }
        val amount = args[1].toBigDecimalOrNull()
        if (amount == null || amount.signum() < 0) {
            sender.sendMessage(plugin.lang().message("guard-invalid"))
            return
        }
        plugin.states().setGuard(player.uniqueId, amount)
        sender.sendMessage(
            plugin.lang().message("guard-set", mapOf("amount" to plugin.economy().format(amount))),
        )
    }

    private fun report(player: Player, result: ToggleResult, fallbackId: String) {
        val display = displayOf(result.spec(), fallbackId)
        if (result.success()) {
            val message = if (result.nowActive()) {
                plugin.lang().message("toggle-on", mapOf("state" to display))
            } else {
                plugin.lang().message(
                    "toggle-off",
                    mapOf("state" to display, "price" to plugin.economy().format(result.charged())),
                )
            }
            player.sendMessage(message)
            return
        }
        val key = when (result.failure()) {
            ToggleResult.Failure.UNKNOWN_STATE -> "toggle-unknown-state"
            ToggleResult.Failure.DISABLED -> "toggle-disabled"
            ToggleResult.Failure.NO_PERMISSION -> "toggle-no-permission"
            ToggleResult.Failure.GUARD_REACHED -> "toggle-guard-reached"
            null -> "toggle-unknown-state"
        }
        player.sendMessage(plugin.lang().message(key, mapOf("state" to display)))
    }

    // ---- 管理员 ----

    private fun admin(sender: CommandSender, args: Array<String>) {
        if (!requirePermission(sender, ADMIN_PERMISSION)) {
            return
        }
        if (args.size < 2) {
            sender.sendMessage(plugin.lang().ui("usage-admin-off"))
            sender.sendMessage(plugin.lang().ui("usage-admin-reload"))
            return
        }
        when (args[1].lowercase()) {
            "off", "clear" -> adminOff(sender, args)
            "reload" -> adminReload(sender)
            else -> {
                sender.sendMessage(plugin.lang().ui("usage-admin-off"))
                sender.sendMessage(plugin.lang().ui("usage-admin-reload"))
            }
        }
    }

    private fun adminOff(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            sender.sendMessage(plugin.lang().ui("usage-admin-off"))
            return
        }
        // 关闭状态要移除实体效果,只能对在线玩家做。
        val target = Bukkit.getPlayerExact(args[2])
        if (target == null) {
            sender.sendMessage(plugin.lang().message("admin-player-offline", mapOf("player" to args[2])))
            return
        }
        val stateId = args.getOrNull(3)?.lowercase()
        val count = plugin.states().clear(target, stateId)
        if (count == 0) {
            sender.sendMessage(plugin.lang().message("admin-clear-none", mapOf("player" to target.name)))
            return
        }
        sender.sendMessage(
            plugin.lang().message(
                "admin-clear-success",
                mapOf("player" to target.name, "count" to count.toString()),
            ),
        )
    }

    private fun adminReload(sender: CommandSender) {
        val report = plugin.reloadStates()
        if (report.ok()) {
            sender.sendMessage(plugin.lang().message("admin-reload-success"))
            return
        }
        sender.sendMessage(
            plugin.lang().message(
                "admin-reload-failed",
                mapOf("stage" to (report.failureSummaries().firstOrNull() ?: "?")),
            ),
        )
    }

    // ---- 补全 ----

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<String>,
    ): List<String>? {
        if (args.size <= 1) {
            val subs = SUBCOMMANDS.filter { sender.hasPermission(permissionFor(it)) }
            return CubexCommandSuggestions.root(args, subs)
        }
        val sub = args[0].lowercase()
        if (args.size == 2 && sub in STATE_SUBCOMMANDS) {
            return CubexCommandSuggestions.matching(plugin.definitions().purchasable().map { it.id() }, args[1])
        }
        if (args.size == 2 && sub == "guard") {
            return CubexCommandSuggestions.matching(listOf("off"), args[1])
        }
        if (sub == "admin") {
            if (args.size == 2) {
                return CubexCommandSuggestions.matching(listOf("off", "reload"), args[1])
            }
            if (args.size == 3 && args[1].lowercase() in setOf("off", "clear")) {
                return CubexCommandSuggestions.matching(Bukkit.getOnlinePlayers().map { it.name }, args[2])
            }
            if (args.size == 4 && args[1].lowercase() in setOf("off", "clear")) {
                return CubexCommandSuggestions.matching(plugin.definitions().all().map { it.id() }, args[3])
            }
        }
        return emptyList()
    }

    // ---- 内部 ----

    private fun requirePermission(sender: CommandSender, permission: String): Boolean {
        if (sender.hasPermission(permission)) {
            return true
        }
        sender.sendMessage(plugin.lang().message("no-permission"))
        return false
    }

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender is Player) {
            return sender
        }
        sender.sendMessage(plugin.lang().message("player-only"))
        return null
    }

    private fun displayOf(spec: StateSpec?, fallback: String): String = spec?.display() ?: fallback

    private fun permissionFor(sub: String): String = if (sub == "admin") ADMIN_PERMISSION else USE_PERMISSION

    private fun String.toBigDecimalOrNull(): BigDecimal? =
        try {
            BigDecimal(this)
        } catch (ex: NumberFormatException) {
            null
        }

    private companion object {
        const val USE_PERMISSION = "statecharge.use"
        const val ADMIN_PERMISSION = "statecharge.admin"

        val SUBCOMMANDS = listOf("help", "gui", "list", "status", "toggle", "on", "off", "guard", "admin")
        val STATE_SUBCOMMANDS = setOf("toggle", "on", "off")
    }
}
