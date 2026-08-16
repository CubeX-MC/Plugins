package org.cubexmc.statecharge.command

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.cubexmc.statecharge.StateChargePlugin
import org.cubexmc.statecharge.model.StateSpec
import org.cubexmc.statecharge.service.BuyResult
import org.cubexmc.statecharge.service.GiveResult

class StateChargeCommand(private val plugin: StateChargePlugin) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }
        when (args[0].lowercase()) {
            "help" -> sendHelp(sender)
            "list" -> list(sender)
            "status" -> status(sender)
            "buy" -> buy(sender, args)
            "admin" -> admin(sender, args)
            else -> sendHelp(sender)
        }
        return true
    }

    // ---- 玩家命令 ----

    private fun sendHelp(sender: CommandSender) {
        if (!requirePermission(sender, "statecharge.use")) {
            return
        }
        sender.sendMessage(plugin.lang().message("help"))
    }

    private fun list(sender: CommandSender) {
        if (!requirePermission(sender, "statecharge.use")) {
            return
        }
        val specs = plugin.definitions().purchasable()
        if (specs.isEmpty()) {
            sender.sendMessage(plugin.lang().ui("list-empty"))
            return
        }
        sender.sendMessage(plugin.lang().ui("list-header"))
        for (spec in specs) {
            val key = if (spec.maxStackSeconds() > 0) "list-line-limited" else "list-line"
            sender.sendMessage(
                plugin.lang().ui(
                    key,
                    mapOf(
                        "id" to spec.id(),
                        "state" to spec.display(),
                        "price" to plugin.economy().format(spec.price()),
                        "duration" to plugin.durationText(spec.unitSeconds()),
                        "max" to plugin.durationText(spec.maxStackSeconds()),
                    ),
                ),
            )
        }
    }

    private fun status(sender: CommandSender) {
        if (!requirePermission(sender, "statecharge.use")) {
            return
        }
        val player = requirePlayer(sender) ?: return
        val active = plugin.storage().active(player.uniqueId)
        if (active.isEmpty()) {
            sender.sendMessage(plugin.lang().ui("status-empty"))
            return
        }
        sender.sendMessage(plugin.lang().ui("status-header"))
        for ((stateId, remaining) in active) {
            val display = plugin.definitions().byId(stateId)?.display() ?: stateId
            sender.sendMessage(
                plugin.lang().ui(
                    "status-line",
                    mapOf("state" to display, "time" to plugin.durationText(remaining)),
                ),
            )
        }
    }

    private fun buy(sender: CommandSender, args: Array<String>) {
        if (!requirePermission(sender, "statecharge.use")) {
            return
        }
        val player = requirePlayer(sender) ?: return
        val stateId = args.getOrNull(1)
        if (stateId == null) {
            sender.sendMessage(plugin.lang().ui("usage-buy"))
            return
        }
        val count = args.getOrNull(2)?.toIntOrNull() ?: 1
        val result = plugin.states().buy(player, stateId, count)
        val spec = result.spec()
        if (spec != null) {
            sender.sendMessage(
                plugin.lang().message(
                    "buy-success",
                    mapOf(
                        "state" to spec.display(),
                        "time" to plugin.durationText(result.addedSeconds()),
                        "total" to plugin.durationText(result.remainingSeconds()),
                        "price" to plugin.economy().format(result.price()),
                    ),
                ),
            )
            return
        }
        val otherSpec = plugin.definitions().byId(stateId)
        when (result.failure()) {
            BuyResult.Failure.UNKNOWN_STATE ->
                sender.sendMessage(plugin.lang().message("buy-unknown-state", mapOf("state" to stateId)))
            BuyResult.Failure.DISABLED ->
                sender.sendMessage(plugin.lang().message("buy-disabled", mapOf("state" to displayOf(otherSpec, stateId))))
            BuyResult.Failure.NO_PERMISSION ->
                sender.sendMessage(plugin.lang().message("buy-no-permission"))
            BuyResult.Failure.CONFLICT -> {
                val otherId = result.conflictStateId() ?: ""
                val other = plugin.definitions().byId(otherId)?.display() ?: otherId
                sender.sendMessage(plugin.lang().message("buy-conflict", mapOf("other" to other)))
            }
            BuyResult.Failure.MAX_STACK -> {
                val maxText = otherSpec?.let { plugin.durationText(it.maxStackSeconds()) } ?: "?"
                sender.sendMessage(plugin.lang().message("buy-max-stack", mapOf("max" to maxText)))
            }
            BuyResult.Failure.INVALID_COUNT ->
                sender.sendMessage(plugin.lang().message("buy-invalid-count"))
            BuyResult.Failure.INSUFFICIENT_FUNDS ->
                sender.sendMessage(
                    plugin.lang().message("buy-insufficient", mapOf("price" to plugin.economy().format(result.price()))),
                )
            BuyResult.Failure.ECONOMY_FAILED ->
                sender.sendMessage(plugin.lang().message("buy-economy-failed", mapOf("reason" to (result.reason() ?: ""))))
            null -> Unit
        }
    }

    // ---- 管理命令 ----

    private fun admin(sender: CommandSender, args: Array<String>) {
        val sub = args.getOrNull(1)
        if (sub == null) {
            sender.sendMessage(plugin.lang().ui("usage-admin-give"))
            sender.sendMessage(plugin.lang().ui("usage-admin-clear"))
            sender.sendMessage(plugin.lang().ui("usage-admin-reload"))
            return
        }
        when (sub.lowercase()) {
            "give" -> adminGive(sender, args)
            "clear" -> adminClear(sender, args)
            "reload" -> adminReload(sender)
            else -> {
                sender.sendMessage(plugin.lang().ui("usage-admin-give"))
                sender.sendMessage(plugin.lang().ui("usage-admin-clear"))
                sender.sendMessage(plugin.lang().ui("usage-admin-reload"))
            }
        }
    }

    private fun adminGive(sender: CommandSender, args: Array<String>) {
        if (!requirePermission(sender, "statecharge.admin.give")) {
            return
        }
        val playerName = args.getOrNull(2)
        val stateId = args.getOrNull(3)
        val seconds = args.getOrNull(4)?.toLongOrNull()
        if (playerName == null || stateId == null || seconds == null) {
            sender.sendMessage(plugin.lang().ui("usage-admin-give"))
            return
        }
        val target = Bukkit.getOfflinePlayerIfCached(playerName)
        if (target == null) {
            sender.sendMessage(plugin.lang().message("admin-player-unknown", mapOf("player" to playerName)))
            return
        }
        val result = plugin.states().give(target, stateId, seconds)
        val spec = result.spec()
        if (spec != null) {
            sender.sendMessage(
                plugin.lang().message(
                    "admin-give-success",
                    mapOf(
                        "player" to playerName,
                        "state" to spec.display(),
                        "time" to plugin.durationText(seconds),
                    ),
                ),
            )
            return
        }
        when (result.failure()) {
            GiveResult.Failure.UNKNOWN_STATE ->
                sender.sendMessage(plugin.lang().message("admin-give-unknown-state", mapOf("state" to stateId)))
            GiveResult.Failure.INVALID_SECONDS ->
                sender.sendMessage(plugin.lang().message("admin-give-invalid-seconds"))
            null -> Unit
        }
    }

    private fun adminClear(sender: CommandSender, args: Array<String>) {
        if (!requirePermission(sender, "statecharge.admin.clear")) {
            return
        }
        val playerName = args.getOrNull(2)
        if (playerName == null) {
            sender.sendMessage(plugin.lang().ui("usage-admin-clear"))
            return
        }
        val target = Bukkit.getOfflinePlayerIfCached(playerName)
        if (target == null) {
            sender.sendMessage(plugin.lang().message("admin-player-unknown", mapOf("player" to playerName)))
            return
        }
        val stateId = args.getOrNull(3)?.takeUnless { it.equals("all", ignoreCase = true) }
        val cleared = plugin.states().clear(target, stateId)
        if (cleared > 0) {
            sender.sendMessage(
                plugin.lang().message("admin-clear-success", mapOf("player" to playerName, "count" to cleared.toString())),
            )
        } else {
            sender.sendMessage(plugin.lang().message("admin-clear-none", mapOf("player" to playerName)))
        }
    }

    private fun adminReload(sender: CommandSender) {
        if (!requirePermission(sender, "statecharge.admin.reload")) {
            return
        }
        val report = plugin.reloadStates()
        if (report.ok()) {
            sender.sendMessage(plugin.lang().message("admin-reload-success"))
        } else {
            val stage = report.failureSummaries().firstOrNull() ?: "?"
            sender.sendMessage(plugin.lang().message("admin-reload-failed", mapOf("stage" to stage)))
        }
    }

    // ---- Tab 补全 ----

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<String>,
    ): List<String> {
        if (args.size == 1) {
            return listOf("list", "status", "buy", "help", "admin")
                .filter { sender.hasPermission(permissionFor(it)) }
                .filter { it.startsWith(args[0].lowercase()) }
        }
        if (args.size == 2 && args[0].equals("buy", ignoreCase = true)) {
            return plugin.definitions().purchasable().map { it.id() }.filter { it.startsWith(args[1].lowercase()) }
        }
        if (args[0].equals("admin", ignoreCase = true)) {
            return when {
                args.size == 2 -> listOf("give", "clear", "reload").filter { it.startsWith(args[1].lowercase()) }
                args.size == 3 && (args[1].equals("give", ignoreCase = true) || args[1].equals("clear", ignoreCase = true)) ->
                    Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[2]) }
                args.size == 4 && args[1].equals("give", ignoreCase = true) ->
                    plugin.definitions().all().map { it.id() }.filter { it.startsWith(args[3].lowercase()) }
                args.size == 4 && args[1].equals("clear", ignoreCase = true) ->
                    (plugin.definitions().all().map { it.id() } + "all").filter { it.startsWith(args[3].lowercase()) }
                else -> emptyList()
            }
        }
        return emptyList()
    }

    // ---- 辅助 ----

    private fun requirePermission(sender: CommandSender, permission: String): Boolean {
        if (sender.hasPermission(permission)) {
            return true
        }
        sender.sendMessage(plugin.lang().message("no-permission"))
        return false
    }

    private fun requirePlayer(sender: CommandSender): Player? {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(plugin.lang().message("player-only"))
        }
        return player
    }

    private fun displayOf(spec: StateSpec?, fallback: String): String = spec?.display() ?: fallback

    private fun permissionFor(sub: String): String = if (sub == "admin") "statecharge.admin" else "statecharge.use"
}
