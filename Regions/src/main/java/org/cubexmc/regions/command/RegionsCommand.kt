package org.cubexmc.regions.command

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.EffectScope
import org.cubexmc.regions.model.FlagConfig
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.OwnerPolicy
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.model.ValidationIssue
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class RegionsCommand(private val plugin: RegionsPlugin) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            if (sender is Player && hasAdminSilent(sender)) {
                plugin.gui().openMain(sender)
                return true
            }
            plugin.lang().send(sender, if (hasAdminSilent(sender)) "help" else "help-player")
            return true
        }

        if (args[0].equals("help", ignoreCase = true)) {
            if (hasAdminSilent(sender)) {
                plugin.lang().send(sender, "help")
            } else {
                plugin.lang().send(sender, "help-player")
            }
            return true
        }

        if (args[0].equals("gui", ignoreCase = true)) {
            val player = sender as? Player
            if (player == null) {
                plugin.lang().send(sender, "player-only")
                return true
            }
            if (!hasAdmin(player)) {
                return true
            }
            plugin.gui().openMain(player)
            return true
        }

        if (!hasAdminSilent(sender) && !args[0].equals("game", ignoreCase = true)) {
            plugin.lang().send(sender, "help-player")
            return true
        }

        return when (args[0].lowercase(Locale.ROOT)) {
            "list" -> list(sender)
            "create" -> create(sender, args)
            "remove", "delete" -> remove(sender, args)
            "enable" -> enabled(sender, args, true)
            "disable" -> enabled(sender, args, false)
            "bind" -> bind(sender, args)
            "mode" -> mode(sender, args)
            "flag" -> flag(sender, args)
            "effect" -> effect(sender, args)
            "game" -> game(sender, args)
            "reload" -> reload(sender)
            "validate" -> validate(sender, args)
            "inspect" -> inspect(sender, args)
            "cleanup" -> cleanup(sender, args)
            "doctor" -> doctor(sender)
            else -> {
                plugin.lang().send(sender, "help")
                true
            }
        }
    }

    private fun list(sender: CommandSender): Boolean {
        if (!hasAdmin(sender)) {
            return true
        }
        val regions = plugin.regions().all()
        if (regions.isEmpty()) {
            plugin.lang().send(sender, "list-empty")
            return true
        }
        plugin.lang().send(sender, "list-header", mapOf("count" to regions.size.toString()))
        for (region in regions.sortedWith(compareByDescending<RegionDefinition> { it.priority }.thenBy { it.id })) {
            plugin.lang().sendRaw(
                sender,
                plugin.lang().message(
                    "list-line",
                    mapOf(
                        "id" to region.id,
                        "name" to region.name,
                        "enabled" to plugin.lang().message(if (region.enabled) "enabled" else "disabled"),
                        "source" to region.source.describe(),
                        "mode" to (region.mode?.type ?: "none"),
                    ),
                ),
            )
        }
        return true
    }

    private fun create(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.create")) {
            return true
        }
        if (args.size < 3) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions create <id> <name>"))
            return true
        }
        val id = args[1].lowercase(Locale.ROOT)
        if (plugin.regions().find(id) != null) {
            plugin.lang().sendRaw(sender, "§c区域已存在: $id")
            return true
        }
        val region = RegionDefinition(
            id = id,
            name = join(args, 2),
            source = RegionSourceRef("cuboid", mapOf("id" to id)),
            ownerPolicy = OwnerPolicy.ADMIN,
            mode = ModeConfig("free_event"),
        )
        val result = plugin.regions().put(region)
        if (!result.success) {
            plugin.lang().sendRaw(sender, "§c创建失败: ${result.reason}")
            return true
        }
        plugin.lang().sendRaw(sender, "§a已创建区域 $id。下一步使用 /regions bind $id cuboid ... 或 /regions bind $id lands <land> [area]。")
        return true
    }

    private fun remove(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.remove")) {
            return true
        }
        val region = requireRegion(sender, args, "/regions remove <id>") ?: return true
        val result = plugin.regions().remove(region.id)
        if (!result.success) {
            plugin.lang().send(sender, "not-found", mapOf("id" to region.id))
            return true
        }
        plugin.lang().sendRaw(sender, "§a已删除区域 ${region.id}。")
        return true
    }

    private fun enabled(sender: CommandSender, args: Array<String>, enabled: Boolean): Boolean {
        if (!has(sender, "regions.region.enable")) {
            return true
        }
        val region = requireRegion(sender, args, "/regions ${args[0]} <id>") ?: return true
        val result = plugin.regions().put(region.copy(enabled = enabled))
        if (!result.success) {
            plugin.lang().sendRaw(sender, "§c操作失败: ${result.reason}")
            return true
        }
        plugin.lang().sendRaw(sender, "§a区域 ${region.id} 已${if (enabled) "启用" else "禁用"}。")
        return true
    }

    private fun bind(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.bind")) {
            return true
        }
        val region = requireRegion(sender, args, "/regions bind <id> <cuboid|lands> ...") ?: return true
        if (args.size < 4) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions bind <id> <cuboid|lands> ..."))
            return true
        }
        val source = when (args[2].lowercase(Locale.ROOT)) {
            "lands" -> RegionSourceRef("lands", mapOf("land" to args[3], "area" to (args.getOrNull(4) ?: "default")))
            "cuboid" -> {
                if (args.size < 10) {
                    plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions bind <id> cuboid <world> <minX> <minY> <minZ> <maxX> <maxY> <maxZ>"))
                    return true
                }
                val keys = listOf("world", "min-x", "min-y", "min-z", "max-x", "max-y", "max-z")
                RegionSourceRef("cuboid", keys.zip(args.drop(3).take(7)).toMap())
            }
            else -> {
                plugin.lang().sendRaw(sender, "§c未知 source: ${args[2]}")
                return true
            }
        }
        val result = plugin.regions().put(region.copy(source = source))
        if (!result.success) {
            plugin.lang().sendRaw(sender, "§c绑定失败: ${result.reason}")
            return true
        }
        plugin.lang().sendRaw(sender, "§a区域 ${region.id} 已绑定到 ${source.describe()}。")
        return true
    }

    private fun flag(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.edit")) {
            return true
        }
        if (args.size < 5 || !args[1].equals("set", ignoreCase = true)) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions flag set <id> <flag> <allow|deny|pass> [key=value...]"))
            return true
        }
        val region = plugin.regions().find(args[2])
        if (region == null) {
            plugin.lang().send(sender, "not-found", mapOf("id" to args[2]))
            return true
        }
        val key = args[3].lowercase(Locale.ROOT)
        val flags = LinkedHashMap(region.flags)
        flags[key] = FlagConfig(key, args[4].lowercase(Locale.ROOT), parsePairs(args, 5))
        val result = plugin.regions().put(region.copy(flags = flags))
        if (!result.success) {
            plugin.lang().sendRaw(sender, "§c设置失败: ${result.reason}")
            return true
        }
        plugin.lang().sendRaw(sender, "§a区域 ${region.id} 的 flag $key 已设为 ${args[4]}。")
        return true
    }

    private fun mode(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.edit")) {
            return true
        }
        if (args.size < 4 || !args[1].equals("set", ignoreCase = true)) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions mode set <id> <free_event|dual_pvp|union_war|run_race|boat_race|horse_race|hide_and_seek> [key=value...]"))
            return true
        }
        val region = plugin.regions().find(args[2])
        if (region == null) {
            plugin.lang().send(sender, "not-found", mapOf("id" to args[2]))
            return true
        }
        val type = args[3].lowercase(Locale.ROOT)
        val result = plugin.regions().put(region.copy(mode = ModeConfig(type, parsePairs(args, 4))))
        if (!result.success) {
            plugin.lang().sendRaw(sender, "§c设置失败: ${result.reason}")
            return true
        }
        plugin.lang().sendRaw(sender, "§a区域 ${region.id} 的 mode 已设为 $type。")
        return true
    }

    private fun effect(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.edit")) {
            return true
        }
        if (args.size < 4 || !args[1].equals("add", ignoreCase = true)) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions effect add <id> <effect> [key=value...]"))
            return true
        }
        val region = plugin.regions().find(args[2])
        if (region == null) {
            plugin.lang().send(sender, "not-found", mapOf("id" to args[2]))
            return true
        }
        val values = parsePairs(args, 4)
        val scope = when (values.remove("scope")?.lowercase(Locale.ROOT)) {
            "timed" -> EffectScope.TIMED
            "until_mode_end", "until-mode-end" -> EffectScope.UNTIL_MODE_END
            else -> EffectScope.WHILE_INSIDE
        }
        val effects = ArrayList(region.effects)
        effects.add(EffectConfig(args[3].lowercase(Locale.ROOT), scope, values))
        val result = plugin.regions().put(region.copy(effects = effects))
        if (!result.success) {
            plugin.lang().sendRaw(sender, "§c添加失败: ${result.reason}")
            return true
        }
        plugin.lang().sendRaw(sender, "§a区域 ${region.id} 已添加 effect ${args[3]}。")
        return true
    }

    private fun game(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size < 3) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions game <id> <ready|start|status|end>"))
            return true
        }
        val region = plugin.regions().find(args[1])
        val isRace = region != null && plugin.raceModes().isRaceMode(region)
        val isRound = region != null && plugin.roundModes().isRoundMode(region)
        val player = sender as? Player
        val action = args[2].lowercase(Locale.ROOT)
        when (action) {
            "ready" -> {
                if (player == null) {
                    plugin.lang().send(sender, "player-only")
                    return true
                }
                if (isRace) {
                    plugin.raceModes().ready(player, args[1])
                } else if (isRound) {
                    plugin.roundModes().ready(player, args[1])
                } else {
                    plugin.combatModes().ready(player, args[1])
                }
            }
            "start" -> {
                val handled = if (isRace) {
                    plugin.raceModes().startCommand(sender, args[1])
                } else if (isRound) {
                    plugin.roundModes().startCommand(sender, args[1])
                } else {
                    false
                }
                if (!handled) {
                    plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions game <id> <ready|start|status|end>"))
                }
            }
            "status" -> plugin.lang().sendRaw(sender, "§e${args[1]}: ${if (isRace) plugin.raceModes().status(args[1]) else if (isRound) plugin.roundModes().status(args[1]) else plugin.combatModes().status(args[1])}")
            "end", "stop" -> {
                val ended = if (isRace) {
                    plugin.raceModes().forceEnd(sender, args[1], "manual-command")
                } else if (isRound) {
                    plugin.roundModes().forceEnd(sender, args[1], "manual-command")
                } else {
                    if (!has(sender, "regions.region.edit")) {
                        return true
                    }
                    plugin.combatModes().forceEnd(args[1], "manual-command")
                }
                if (ended) {
                    plugin.lang().sendRaw(sender, "§a已结束 ${args[1]} 的战斗。")
                } else {
                    plugin.lang().sendRaw(sender, "§e${args[1]} 当前没有进行中的游戏。")
                }
            }
            else -> plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions game <id> <ready|start|status|end>"))
        }
        return true
    }

    private fun reload(sender: CommandSender): Boolean {
        if (!has(sender, "regions.reload")) {
            return true
        }
        plugin.reloadRegions()
        plugin.lang().send(sender, "reloaded")
        return true
    }

    private fun validate(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.validate")) {
            return true
        }
        val issues =
            if (args.size >= 2) {
                val region = plugin.regions().find(args[1])
                if (region == null) {
                    plugin.lang().send(sender, "not-found", mapOf("id" to args[1]))
                    return true
                }
                plugin.validation().validate(region)
            } else {
                plugin.validation().validateAll(plugin.regions().all())
            }
        sendIssues(sender, issues)
        return true
    }

    private fun inspect(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.inspect")) {
            return true
        }
        if (args.size < 2) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions inspect <player>"))
            return true
        }
        val player = Bukkit.getPlayerExact(args[1])
        if (player == null) {
            plugin.lang().send(sender, "player-not-found", mapOf("player" to args[1]))
            return true
        }
        val sessions = plugin.sessions().activeSessions(player.uniqueId)
        plugin.lang().send(sender, "inspect-header", mapOf("player" to player.name))
        if (sessions.isEmpty()) {
            plugin.lang().send(sender, "inspect-empty")
            return true
        }
        for (session in sessions) {
            plugin.lang().send(
                sender,
                "inspect-line",
                mapOf(
                    "region" to session.regionId,
                    "entered" to DATE_FORMAT.format(Instant.ofEpochMilli(session.enteredAtMillis)),
                ),
            )
        }
        return true
    }

    private fun cleanup(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.cleanup")) {
            return true
        }
        if (args.size < 2) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions cleanup <player>"))
            return true
        }
        val player = Bukkit.getPlayerExact(args[1])
        if (player == null) {
            plugin.lang().send(sender, "player-not-found", mapOf("player" to args[1]))
            return true
        }
        val count = plugin.sessions().cleanup(player, "manual-command")
        plugin.combatModes().restoreIfPending(player, "manual-cleanup")
        plugin.roundModes().restoreIfPending(player, "manual-cleanup")
        plugin.lang().send(sender, "cleanup-done", mapOf("player" to player.name, "count" to count.toString()))
        return true
    }

    private fun doctor(sender: CommandSender): Boolean {
        if (!has(sender, "regions.validate")) {
            return true
        }
        plugin.lang().send(sender, "doctor-header")
        val sourceSummary = plugin.sources().all().joinToString(", ") { source ->
            "${source.type}=${if (source.isAvailable()) "available" else "missing"}"
        }
        plugin.lang().send(sender, "doctor-line", mapOf("message" to "sources: $sourceSummary"))
        val unionSummary = plugin.unions().all().joinToString(", ") { provider ->
            "${provider.type}=${if (provider.isAvailable()) "available" else "missing"}"
        }
        plugin.lang().send(sender, "doctor-line", mapOf("message" to "unions: $unionSummary"))
        plugin.lang().send(sender, "doctor-line", mapOf("message" to "modes: ${plugin.modes().all().joinToString(", ")}"))
        plugin.lang().send(sender, "doctor-line", mapOf("message" to "flags: ${plugin.flags().all().joinToString(", ")}"))
        plugin.lang().send(sender, "doctor-line", mapOf("message" to "effects: ${plugin.effects().allTypes().joinToString(", ")}"))
        plugin.lang().send(sender, "doctor-line", mapOf("message" to "actions: ${plugin.actions().all().joinToString(", ")}"))
        return true
    }

    private fun sendIssues(sender: CommandSender, issues: List<ValidationIssue>) {
        if (issues.isEmpty()) {
            plugin.lang().send(sender, "validate-ok")
            return
        }
        plugin.lang().send(sender, "validate-header", mapOf("count" to issues.size.toString()))
        for (issue in issues) {
            plugin.lang().send(
                sender,
                "validate-line",
                mapOf(
                    "id" to issue.regionId,
                    "severity" to issue.severity.name,
                    "message" to issue.message,
                ),
            )
        }
    }

    private fun requireRegion(sender: CommandSender, args: Array<String>, usage: String): RegionDefinition? {
        if (args.size < 2) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to usage))
            return null
        }
        val region = plugin.regions().find(args[1])
        if (region == null) {
            plugin.lang().send(sender, "not-found", mapOf("id" to args[1]))
            return null
        }
        return region
    }

    private fun parsePairs(args: Array<String>, start: Int): MutableMap<String, String> {
        val values = LinkedHashMap<String, String>()
        for (index in start until args.size) {
            val raw = args[index]
            val split = raw.indexOf('=')
            if (split > 0) {
                values[raw.substring(0, split)] = raw.substring(split + 1)
            } else if (!values.containsKey("value")) {
                values["value"] = raw
            }
        }
        return values
    }

    private fun join(args: Array<String>, start: Int): String =
        args.drop(start).joinToString(" ")

    private fun has(sender: CommandSender, permission: String): Boolean {
        if (sender.hasPermission(permission) || sender.hasPermission("regions.admin")) {
            return true
        }
        plugin.lang().send(sender, "no-permission")
        return false
    }

    private fun hasAdmin(sender: CommandSender): Boolean {
        if (hasAdminSilent(sender)) {
            return true
        }
        plugin.lang().send(sender, "no-permission")
        return false
    }

    private fun hasAdminSilent(sender: CommandSender): Boolean =
        sender.hasPermission("regions.admin")

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        if (args.size == 1) {
            val commands = if (hasAdminSilent(sender)) {
                listOf("gui", "list", "create", "remove", "enable", "disable", "bind", "mode", "flag", "effect", "game", "reload", "validate", "inspect", "cleanup", "doctor", "help")
            } else {
                listOf("game", "help")
            }
            return startsWith(commands, args[0])
        }
        if (!hasAdminSilent(sender) && !args[0].equals("game", ignoreCase = true)) {
            return emptyList()
        }
        if (args.size == 2 && listOf("validate", "remove", "enable", "disable", "bind", "game").contains(args[0].lowercase(Locale.ROOT))) {
            return startsWith(plugin.regions().all().map { it.id }, args[1])
        }
        if (args.size == 2 && args[0].equals("mode", ignoreCase = true)) {
            return startsWith(listOf("set"), args[1])
        }
        if (args.size == 2 && args[0].equals("flag", ignoreCase = true)) {
            return startsWith(listOf("set"), args[1])
        }
        if (args.size == 2 && args[0].equals("effect", ignoreCase = true)) {
            return startsWith(listOf("add"), args[1])
        }
        if (args.size == 3 && (args[0].equals("mode", ignoreCase = true) || args[0].equals("flag", ignoreCase = true) || args[0].equals("effect", ignoreCase = true))) {
            return startsWith(plugin.regions().all().map { it.id }, args[2])
        }
        if (args.size == 3 && args[0].equals("game", ignoreCase = true)) {
            return startsWith(listOf("ready", "start", "status", "end", "stop"), args[2])
        }
        if (args.size == 3 && args[0].equals("bind", ignoreCase = true)) {
            return startsWith(listOf("cuboid", "lands"), args[2])
        }
        if (args.size == 4 && args[0].equals("flag", ignoreCase = true)) {
            return startsWith(plugin.flags().all(), args[3])
        }
        if (args.size == 4 && args[0].equals("mode", ignoreCase = true)) {
            return startsWith(plugin.modes().all(), args[3])
        }
        if (args.size == 5 && args[0].equals("flag", ignoreCase = true)) {
            return startsWith(listOf("allow", "deny", "pass", "blocklist", "allowlist"), args[4])
        }
        if (args.size == 4 && args[0].equals("effect", ignoreCase = true)) {
            return startsWith(plugin.effects().allTypes(), args[3])
        }
        if (args.size == 2 && (args[0].equals("inspect", ignoreCase = true) || args[0].equals("cleanup", ignoreCase = true))) {
            return startsWith(Bukkit.getOnlinePlayers().map { it.name }, args[1])
        }
        return emptyList()
    }

    private fun startsWith(values: Collection<String>, prefix: String): List<String> {
        val lower = prefix.lowercase(Locale.ROOT)
        return values.filter { it.lowercase(Locale.ROOT).startsWith(lower) }.sorted().take(20)
    }

    companion object {
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault())
    }
}
