package org.cubexmc.clarity

import java.util.Locale
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.cubexmc.core.CubexCommandSuggestions

/** /clarity 命令:player / item / reload。目标支持玩家名或原版选择器(@a/@s/@p/@r)。 */
class ClarityCommand(
    private val plugin: ClarityPlugin,
    private val service: ClarityService,
) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            usage(sender)
            return true
        }
        when (args[0].lowercase(Locale.ROOT)) {
            "player" -> handlePlayer(sender, args)
            "item" -> handleItem(sender, args)
            "reload" -> {
                plugin.reloadClarityConfig()
                sender.sendMessage(
                    "§aClarity config reloaded. auto-clean-on-join=${plugin.config().autoCleanOnJoin()} " +
                        "dry-run=${plugin.config().dryRun()}",
                )
            }
            "scan", "sweep", "purge" -> handleLegacyPlayer(sender, args)
            else -> usage(sender)
        }
        return true
    }

    private fun handlePlayer(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            playerUsage(sender)
            return
        }
        when (args[1].lowercase(Locale.ROOT)) {
            "scan" -> resolveTargets(sender, args, 2).forEach { service.scan(sender, it) }
            "sweep" -> resolveTargets(sender, args, 2).forEach { service.sweep(sender, it, 0L) }
            "purge" -> {
                if (args.size < 5) {
                    sender.sendMessage("§cUsage: /clarity player purge <player|@selector> attr <namespace|id>  |  effect <type|all-infinite>")
                    return
                }
                val kind = args[3].lowercase(Locale.ROOT)
                if (kind != "attr" && kind != "effect") {
                    sender.sendMessage("§cUnknown kind '$kind'. Use 'attr' or 'effect'.")
                    return
                }
                for (target in resolveTargets(sender, args, 2)) {
                    if (kind == "attr") service.purgeAttr(sender, target, args[4])
                    else service.purgeEffect(sender, target, args[4])
                }
            }
            else -> playerUsage(sender)
        }
    }

    private fun handleItem(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            itemUsage(sender)
            return
        }
        when (args[1].lowercase(Locale.ROOT)) {
            "scan" -> {
                val targetScope = resolveTargetsAndScope(sender, args, 2, ItemScope.ALL)
                targetScope.targets().forEach { service.scanItems(sender, it, targetScope.scope()) }
            }
            "sweep" -> {
                val targetScope = resolveTargetsAndScope(sender, args, 2, ItemScope.INVENTORY)
                targetScope.targets().forEach { service.sweepItems(sender, it, targetScope.scope()) }
            }
            "purge" -> {
                val purge = parseItemPurge(sender, args, 2)
                if (purge.targets().isEmpty()) return
                if (purge.rule() != "leveltools") {
                    sender.sendMessage("§cUnknown item purge rule '${purge.rule()}'. Use 'leveltools'.")
                    return
                }
                purge.targets().forEach { service.purgeLevelToolsItems(sender, it, purge.scope()) }
            }
            else -> itemUsage(sender)
        }
    }

    private fun handleLegacyPlayer(sender: CommandSender, args: Array<String>) {
        sender.sendMessage("§eLegacy syntax is still supported. Prefer /clarity player ${args[0].lowercase(Locale.ROOT)} ...")
        when (args[0].lowercase(Locale.ROOT)) {
            "scan" -> resolveTargets(sender, args, 1).forEach { service.scan(sender, it) }
            "sweep" -> resolveTargets(sender, args, 1).forEach { service.sweep(sender, it, 0L) }
            "purge" -> {
                if (args.size < 4) {
                    sender.sendMessage("§cUsage: /clarity player purge <player|@selector> attr <namespace|id>  |  effect <type|all-infinite>")
                    return
                }
                val kind = args[2].lowercase(Locale.ROOT)
                if (kind != "attr" && kind != "effect") {
                    sender.sendMessage("§cUnknown kind '$kind'. Use 'attr' or 'effect'.")
                    return
                }
                for (target in resolveTargets(sender, args, 1)) {
                    if (kind == "attr") service.purgeAttr(sender, target, args[3])
                    else service.purgeEffect(sender, target, args[3])
                }
            }
            else -> usage(sender)
        }
    }

    private fun resolveTargetsAndScope(sender: CommandSender, args: Array<String>, index: Int, defaultScope: ItemScope): TargetScope {
        if (args.size <= index) {
            if (sender is Player) return TargetScope(listOf(sender), defaultScope)
            sender.sendMessage("§cMissing target (player name or selector).")
            return TargetScope.empty(defaultScope)
        }
        if (args.size > index + 2) {
            sender.sendMessage("§cToo many arguments.")
            itemUsage(sender)
            return TargetScope.empty(defaultScope)
        }
        val firstAsScope = ItemScope.parse(args[index])
        if (firstAsScope != null) {
            if (sender is Player) return TargetScope(listOf(sender), firstAsScope)
            sender.sendMessage("§cConsole must provide a target before the item scope.")
            return TargetScope.empty(firstAsScope)
        }
        val targets = resolveTargets(sender, args, index)
        var scope = defaultScope
        if (args.size > index + 1) {
            scope = ItemScope.parse(args[index + 1]) ?: run {
                sender.sendMessage("§cUnknown item scope '${args[index + 1]}'. Use hand, inventory, equipment, ender, or all.")
                return TargetScope.empty(defaultScope)
            }
        }
        return TargetScope(targets, scope)
    }

    private fun parseItemPurge(sender: CommandSender, args: Array<String>, index: Int): ItemPurgeArgs {
        if (args.size <= index) {
            itemPurgeUsage(sender)
            return ItemPurgeArgs.empty()
        }
        val rule = args.last().lowercase(Locale.ROOT)
        val beforeRule = args.size - index - 1
        if (beforeRule > 2) {
            itemPurgeUsage(sender)
            return ItemPurgeArgs.empty()
        }
        var scope = ItemScope.HAND
        val targets: List<Player>
        when (beforeRule) {
            0 -> {
                if (sender !is Player) {
                    sender.sendMessage("§cConsole must provide a target.")
                    return ItemPurgeArgs.empty()
                }
                targets = listOf(sender)
            }
            1 -> {
                val onlyScope = ItemScope.parse(args[index])
                if (onlyScope != null) {
                    if (sender !is Player) {
                        sender.sendMessage("§cConsole must provide a target before the item scope.")
                        return ItemPurgeArgs.empty()
                    }
                    targets = listOf(sender)
                    scope = onlyScope
                } else {
                    targets = resolveTargets(sender, args, index)
                }
            }
            else -> {
                targets = resolveTargets(sender, args, index)
                scope = ItemScope.parse(args[index + 1]) ?: run {
                    sender.sendMessage("§cUnknown item scope '${args[index + 1]}'. Use hand, inventory, equipment, ender, or all.")
                    return ItemPurgeArgs.empty()
                }
            }
        }
        return ItemPurgeArgs(targets, scope, rule)
    }

    private fun resolveTargets(sender: CommandSender, args: Array<String>, index: Int): List<Player> {
        if (args.size <= index) {
            if (sender is Player) return listOf(sender)
            sender.sendMessage("§cMissing target (player name or selector).")
            return emptyList()
        }
        val token = args[index]
        if (token.startsWith("@")) {
            val players = ArrayList<Player>()
            try {
                for (entity in Bukkit.selectEntities(sender, token)) if (entity is Player) players.add(entity)
            } catch (_: IllegalArgumentException) {
                sender.sendMessage("§cInvalid selector: $token")
                return emptyList()
            }
            if (players.isEmpty()) sender.sendMessage("§eSelector matched no online players: $token")
            return players
        }
        val player = Bukkit.getPlayerExact(token)
        if (player == null) {
            sender.sendMessage("§cPlayer not online: $token §7(works on online players only)")
            return emptyList()
        }
        return listOf(player)
    }

    private fun usage(sender: CommandSender) {
        sender.sendMessage("§6Clarity §7— clean orphaned player state and item metadata")
        playerUsage(sender)
        itemUsage(sender)
        sender.sendMessage("§e  /clarity reload §7— reload config.yml")
    }

    private fun playerUsage(sender: CommandSender) {
        sender.sendMessage("§e  /clarity player scan <player|@selector> §7— list attribute modifiers + potion effects")
        sender.sendMessage("§e  /clarity player sweep <player|@selector> §7— apply configured player blacklist (honours dry-run)")
        sender.sendMessage("§e  /clarity player purge <player|@selector> attr <namespace|id> §7— remove matching modifiers")
        sender.sendMessage("§e  /clarity player purge <player|@selector> effect <type|all-infinite> §7— remove potion effect(s)")
    }

    private fun itemUsage(sender: CommandSender) {
        sender.sendMessage("§e  /clarity item scan <player|@selector> [hand|inventory|equipment|ender|all] §7— list suspicious item metadata")
        sender.sendMessage("§e  /clarity item sweep <player|@selector> [hand|inventory|equipment|ender|all] §7— apply configured item rules (honours dry-run)")
        itemPurgeUsage(sender)
    }

    private fun itemPurgeUsage(sender: CommandSender) {
        sender.sendMessage("§e  /clarity item purge <player|@selector> [hand|inventory|equipment|ender|all] leveltools §7— remove LevelTools item residue")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        CubexCommandSuggestions.root(args, ROOT_SUBS)?.let { return it }
        val root = args[0].lowercase(Locale.ROOT)
        if (root == "player") return completePlayer(args)
        if (root == "item") return completeItem(args)
        if (args.size == 2 && root in listOf("scan", "purge", "sweep")) return filter(targetSuggestions(), args[1])
        if (args.size == 3 && root == "purge") return filter(listOf("attr", "effect"), args[2])
        if (args.size == 4 && root == "purge" && args[2].equals("effect", ignoreCase = true)) {
            return filter(listOf("all-infinite"), args[3])
        }
        return emptyList()
    }

    private fun completePlayer(args: Array<String>): List<String> {
        if (args.size == 2) return filter(PLAYER_SUBS, args[1])
        val sub = args[1].lowercase(Locale.ROOT)
        if (args.size == 3 && sub in PLAYER_SUBS) return filter(targetSuggestions(), args[2])
        if (args.size == 4 && sub == "purge") return filter(listOf("attr", "effect"), args[3])
        if (args.size == 5 && sub == "purge" && args[3].equals("effect", ignoreCase = true)) {
            return filter(listOf("all-infinite"), args[4])
        }
        return emptyList()
    }

    private fun completeItem(args: Array<String>): List<String> {
        if (args.size == 2) return filter(ITEM_SUBS, args[1])
        val sub = args[1].lowercase(Locale.ROOT)
        if (args.size == 3 && sub in ITEM_SUBS) {
            val suggestions = ArrayList(targetSuggestions())
            suggestions.addAll(ITEM_SCOPES)
            if (sub == "purge") suggestions.add("leveltools")
            return filter(suggestions, args[2])
        }
        if (args.size == 4 && sub in ITEM_SUBS) {
            val suggestions = ArrayList(ITEM_SCOPES)
            if (sub == "purge") suggestions.add("leveltools")
            return filter(suggestions, args[3])
        }
        if (args.size == 5 && sub == "purge") return filter(listOf("leveltools"), args[4])
        return emptyList()
    }

    private fun targetSuggestions(): List<String> {
        val names = ArrayList(SELECTORS)
        Bukkit.getOnlinePlayers().forEach { names.add(it.name) }
        return names
    }

    private fun filter(options: List<String>, prefix: String): List<String> {
        val lowerPrefix = prefix.lowercase(Locale.ROOT)
        return options.filter { it.lowercase(Locale.ROOT).startsWith(lowerPrefix) }
    }

    private class TargetScope(private val targets: List<Player>, private val scope: ItemScope) {
        fun targets(): List<Player> = targets
        fun scope(): ItemScope = scope
        companion object {
            fun empty(scope: ItemScope): TargetScope = TargetScope(emptyList(), scope)
        }
    }

    private class ItemPurgeArgs(
        private val targets: List<Player>,
        private val scope: ItemScope,
        private val rule: String,
    ) {
        fun targets(): List<Player> = targets
        fun scope(): ItemScope = scope
        fun rule(): String = rule
        companion object {
            fun empty(): ItemPurgeArgs = ItemPurgeArgs(emptyList(), ItemScope.HAND, "")
        }
    }

    private companion object {
        val ROOT_SUBS = listOf("player", "item", "reload", "scan", "purge", "sweep")
        val PLAYER_SUBS = listOf("scan", "purge", "sweep")
        val ITEM_SUBS = listOf("scan", "sweep", "purge")
        val ITEM_SCOPES = listOf("hand", "inventory", "equipment", "ender", "all")
        val SELECTORS = listOf("@a", "@s", "@p", "@r")
    }
}
