package org.cubexmc.regions.command

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.capability.CapabilityKind
import org.cubexmc.regions.capability.CapabilityRisk
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.EffectCombination
import org.cubexmc.regions.model.EffectScope
import org.cubexmc.regions.model.FlagConfig
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.OwnerPolicy
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.model.ValidationIssue
import org.cubexmc.regions.service.AuthorityDecision
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class RegionsCommand(private val plugin: RegionsPlugin) : BasicCommand {
    override fun execute(commandSourceStack: CommandSourceStack, args: Array<String>) {
        execute(commandSourceStack.sender, args)
    }

    private fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            if (sender is Player && canEnterManagementSilent(sender)) {
                plugin.gui().openMain(sender)
                return true
            }
            sendHelp(sender)
            return true
        }

        if (args[0].equals("help", ignoreCase = true)) {
            sendHelp(sender)
            return true
        }

        if (args[0].equals("gui", ignoreCase = true)) {
            val player = sender as? Player
            if (player == null) {
                plugin.lang().send(sender, "player-only")
                return true
            }
            if (!canEnterManagement(player)) {
                return true
            }
            plugin.gui().openMain(player)
            return true
        }

        if (!canEnterManagementSilent(sender) && !args[0].equals("game", ignoreCase = true)) {
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
            "trial" -> trial(sender, args)
            "preview" -> preview(sender, args)
            "publish" -> publish(sender, args)
            "withdraw", "unpublish" -> withdraw(sender, args)
            "history" -> history(sender, args)
            "rollback" -> rollback(sender, args)
            "archive" -> archive(sender, args)
            "freeze" -> freeze(sender, args)
            "unfreeze" -> unfreeze(sender, args)
            "audit" -> audit(sender, args)
            "game" -> game(sender, args)
            "reload" -> reload(sender)
            "validate" -> validate(sender, args)
            "inspect" -> inspect(sender, args)
            "cleanup" -> cleanup(sender, args)
            "doctor" -> doctor(sender)
            else -> {
                sendHelp(sender)
                true
            }
        }
    }

    private fun list(sender: CommandSender): Boolean {
        if (!canEnterManagement(sender)) {
            return true
        }
        val regions = plugin.authority().visibleRegions(sender, plugin.regions().all())
            .map { plugin.publishing().editable(it.id) ?: it }
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
                        "lifecycle" to region.lifecycle.name.lowercase(Locale.ROOT),
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
        val landMarker = args.indexOfFirst { it.equals("--land", ignoreCase = true) }
        val source = if (landMarker >= 0) {
            val land = args.getOrNull(landMarker + 1)
            if (land.isNullOrBlank()) {
                plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions create <id> <name> --land <land> [area]"))
                return true
            }
            RegionSourceRef("lands", mapOf("land" to land, "area" to (args.getOrNull(landMarker + 2) ?: "default")))
        } else {
            RegionSourceRef("cuboid", mapOf("id" to id))
        }
        if (!allow(sender, plugin.authority().canCreate(sender, source))) {
            return true
        }
        val nameEnd = if (landMarker >= 0) landMarker else args.size
        val name = args.slice(2 until nameEnd).joinToString(" ").ifBlank { id }
        val region = withOwnerSnapshot(RegionDefinition(
            id = id,
            name = name,
            source = source,
            ownerPolicy = if (source.type == "lands") OwnerPolicy.LANDS_OWNER else OwnerPolicy.ADMIN,
            mode = ModeConfig("free_event"),
        ))
        val result = plugin.publishing().createDraft(sender, region)
        if (!result.success) {
            plugin.lang().sendRaw(sender, "§c创建失败: ${result.reason}")
            return true
        }
        plugin.lang().sendRaw(sender, "§a已创建区域草稿 $id，并绑定到 ${source.describe()}。校验后使用 /regions publish $id 发布。")
        return true
    }

    private fun remove(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.remove")) {
            return true
        }
        val region = requireRegion(sender, args, "/regions remove <id>") ?: return true
        if (!canManage(sender, region)) return true
        val result = plugin.regions().remove(region.id)
        if (!result.success) {
            plugin.lang().send(sender, "not-found", mapOf("id" to region.id))
            return true
        }
        plugin.audit().record(sender, region.id, "region.remove")
        plugin.lang().sendRaw(sender, "§a已删除区域 ${region.id}。")
        return true
    }

    private fun enabled(sender: CommandSender, args: Array<String>, enabled: Boolean): Boolean {
        if (!has(sender, "regions.region.enable")) {
            return true
        }
        val region = requireEditableRegion(sender, args, "/regions ${args[0]} <id>") ?: return true
        if (!canManage(sender, region)) return true
        val result = plugin.publishing().saveDraft(sender, region.copy(enabled = enabled))
        if (!result.success) {
            plugin.lang().sendRaw(sender, "§c操作失败: ${result.reason}")
            return true
        }
        plugin.audit().record(sender, region.id, if (enabled) "region.enable" else "region.disable")
        plugin.lang().sendRaw(sender, "§a区域 ${region.id} 已${if (enabled) "启用" else "禁用"}。")
        return true
    }

    private fun bind(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.bind")) {
            return true
        }
        val region = requireEditableRegion(sender, args, "/regions bind <id> <cuboid|lands> ...") ?: return true
        if (!canManage(sender, region)) return true
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
        if (!allow(sender, plugin.authority().canCreate(sender, source))) return true
        val result = plugin.publishing().saveDraft(sender, withOwnerSnapshot(region.copy(source = source)))
        if (!result.success) {
            plugin.lang().sendRaw(sender, "§c绑定失败: ${result.reason}")
            return true
        }
        plugin.audit().record(sender, region.id, "region.bind", details = mapOf("source" to source.describe()))
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
        val region = plugin.publishing().editable(args[2])
        if (region == null) {
            plugin.lang().send(sender, "not-found", mapOf("id" to args[2]))
            return true
        }
        if (!canManage(sender, region)) return true
        val key = args[3].lowercase(Locale.ROOT)
        val flags = LinkedHashMap(region.flags)
        flags[key] = FlagConfig(key, args[4].lowercase(Locale.ROOT), parsePairs(args, 5))
        val result = plugin.publishing().saveDraft(sender, region.copy(flags = flags))
        if (!result.success) {
            plugin.lang().sendRaw(sender, "§c设置失败: ${result.reason}")
            return true
        }
        plugin.audit().record(sender, region.id, "region.flag.set", details = mapOf("flag" to key, "value" to args[4]))
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
        val region = plugin.publishing().editable(args[2])
        if (region == null) {
            plugin.lang().send(sender, "not-found", mapOf("id" to args[2]))
            return true
        }
        if (!canManage(sender, region)) return true
        val type = args[3].lowercase(Locale.ROOT)
        val result = plugin.publishing().saveDraft(sender, region.copy(mode = ModeConfig(type, parsePairs(args, 4))))
        if (!result.success) {
            plugin.lang().sendRaw(sender, "§c设置失败: ${result.reason}")
            return true
        }
        plugin.audit().record(sender, region.id, "region.mode.set", details = mapOf("mode" to type))
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
        val region = plugin.publishing().editable(args[2])
        if (region == null) {
            plugin.lang().send(sender, "not-found", mapOf("id" to args[2]))
            return true
        }
        if (!canManage(sender, region)) return true
        val values = parsePairs(args, 4)
        val scope = when (values.remove("scope")?.lowercase(Locale.ROOT)) {
            "timed" -> EffectScope.TIMED
            "until_mode_end", "until-mode-end" -> EffectScope.UNTIL_MODE_END
            else -> EffectScope.WHILE_INSIDE
        }
        val combination = when (values.remove("combination")?.lowercase(Locale.ROOT)) {
            "exclusive" -> EffectCombination.EXCLUSIVE
            "stack" -> EffectCombination.STACK
            "merge_by_type", "merge-by-type", "merge" -> EffectCombination.MERGE_BY_TYPE
            else -> EffectCombination.HIGHEST_PRIORITY
        }
        val effects = ArrayList(region.effects)
        effects.add(EffectConfig(args[3].lowercase(Locale.ROOT), scope, values, combination))
        val result = plugin.publishing().saveDraft(sender, region.copy(effects = effects))
        if (!result.success) {
            plugin.lang().sendRaw(sender, "§c添加失败: ${result.reason}")
            return true
        }
        plugin.audit().record(sender, region.id, "region.effect.add", details = mapOf("effect" to args[3]))
        plugin.lang().sendRaw(sender, "§a区域 ${region.id} 已添加 effect ${args[3]}。")
        return true
    }

    private fun publish(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.publish")) return true
        val regionId = args.getOrNull(1)
        if (regionId == null) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions publish <id>"))
            return true
        }
        val result = plugin.publishing().publish(sender, regionId)
        if (result.success) plugin.lang().sendRaw(sender, "§a区域 $regionId 的草稿已发布。")
        else plugin.lang().sendRaw(sender, "§c发布失败: ${result.reason}")
        return true
    }

    private fun trial(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender as? Player
        if (player == null) {
            plugin.lang().send(sender, "player-only")
            return true
        }
        val regionId = args.getOrNull(1)
        val operation = args.getOrNull(2)?.lowercase(Locale.ROOT) ?: "start"
        if (regionId == null) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions trial <id> [start|stop]"))
            return true
        }
        val result = if (operation == "stop" || operation == "end") {
            plugin.trials().stop(player, "command-stop")
        } else {
            plugin.trials().start(player, regionId)
        }
        if (result.success) {
            plugin.lang().sendRaw(player, if (operation == "stop" || operation == "end") "§e隔离试运行已结束。" else "§a隔离试运行已开始。")
        } else {
            plugin.lang().sendRaw(player, "§c试运行失败: ${result.reason}")
        }
        return true
    }

    private fun preview(sender: CommandSender, args: Array<String>): Boolean {
        val regionId = args.getOrNull(1)
        if (regionId == null) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions preview <id>"))
            return true
        }
        val region = plugin.publishing().draft(regionId)
        if (region == null) {
            plugin.lang().sendRaw(sender, "§c区域 $regionId 没有可预览的草稿。")
            return true
        }
        if (!allow(sender, plugin.authority().canView(sender, region))) return true
        val report = plugin.publishing().previewReport(sender, regionId) ?: return true
        plugin.lang().sendRaw(sender, "§6$regionId 发布预览 · revision ${region.revision}")
        plugin.lang().sendRaw(sender, "§7变化: ${report.changes.size}，问题: ${report.issues.size}，确定重叠: ${report.resolution.orderedRegions.size}")
        report.dependencies.forEach {
            plugin.lang().sendRaw(sender, "${if (it.available) "§a" else "§c"}依赖 ${it.id}: ${it.detail}")
        }
        val displayedMode = report.resolution.primaryModeRegion
            ?: report.resolution.orderedRegions.firstOrNull { it.mode != null }
        plugin.lang().sendRaw(
            sender,
            "§b最终 Mode: ${displayedMode?.let { "${it.id}:${it.mode?.type}" } ?: "无"}；主 Trigger Region: ${report.resolution.primaryTriggerRegion?.id ?: "无"}",
        )
        report.resolution.flags.values.take(12).forEach {
            plugin.lang().sendRaw(sender, "§7Flag ${it.key}=${it.config.value} §8← ${it.sourceRegionId}")
        }
        report.resolution.effects.take(12).forEach {
            plugin.lang().sendRaw(
                sender,
                "§7Effect ${it.config.type} [${it.config.combination.name.lowercase(Locale.ROOT)}] §8← ${it.sourceRegionId}",
            )
        }
        sendIssues(sender, report.issues)
        return true
    }

    private fun withdraw(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.publish")) return true
        val regionId = args.getOrNull(1)
        if (regionId == null) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions withdraw <id>"))
            return true
        }
        val result = plugin.publishing().withdraw(sender, regionId)
        if (result.success) plugin.lang().sendRaw(sender, "§e区域 $regionId 已撤回，并生成可继续编辑的草稿。")
        else plugin.lang().sendRaw(sender, "§c撤回失败: ${result.reason}")
        return true
    }

    private fun history(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.rollback")) return true
        val region = requireEditableRegion(sender, args, "/regions history <id>") ?: return true
        if (!allow(sender, plugin.authority().canView(sender, region))) return true
        val revisions = plugin.publishing().history(region.id)
        plugin.lang().sendRaw(sender, "§6${region.id} 的已发布版本 (${revisions.size})")
        if (revisions.isEmpty()) plugin.lang().sendRaw(sender, "§7暂无已发布历史。")
        revisions.take(20).forEach { snapshot ->
            plugin.lang().sendRaw(
                sender,
                "§7- §e#${snapshot.revision} §f${snapshot.name} §8mode=${snapshot.mode?.type ?: "none"}, state=${snapshot.lifecycle.name.lowercase(Locale.ROOT)}",
            )
        }
        return true
    }

    private fun rollback(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.rollback")) return true
        val regionId = args.getOrNull(1)
        val revision = args.getOrNull(2)?.toLongOrNull()
        if (regionId == null || revision == null) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions rollback <id> <revision>"))
            return true
        }
        val result = plugin.publishing().rollback(sender, regionId, revision)
        if (result.success) plugin.lang().sendRaw(sender, "§a区域 $regionId 已从版本 #$revision 回滚并发布为新版本。")
        else plugin.lang().sendRaw(sender, "§c回滚失败: ${result.reason}")
        return true
    }

    private fun archive(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.archive")) return true
        val regionId = args.getOrNull(1)
        if (regionId == null) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions archive <id>"))
            return true
        }
        val result = plugin.publishing().archive(sender, regionId)
        if (result.success) plugin.lang().sendRaw(sender, "§e区域 $regionId 已归档。")
        else plugin.lang().sendRaw(sender, "§c归档失败: ${result.reason}")
        return true
    }

    private fun freeze(sender: CommandSender, args: Array<String>): Boolean {
        if (!allow(sender, plugin.authority().canUseGlobalAdministration(sender))) return true
        val region = requireRegion(sender, args, "/regions freeze <id> [reason]") ?: return true
        val reason = join(args, 2).ifBlank { "manual-command" }
        val result = plugin.lifecycle().freeze(sender, region.id, reason)
        if (result.success) plugin.lang().sendRaw(sender, "§a区域 ${region.id} 已冻结，运行状态与玩家会话已安全清理。")
        else plugin.lang().sendRaw(sender, "§c冻结失败: ${result.reason}")
        return true
    }

    private fun unfreeze(sender: CommandSender, args: Array<String>): Boolean {
        if (!allow(sender, plugin.authority().canUseGlobalAdministration(sender))) return true
        val region = requireRegion(sender, args, "/regions unfreeze <id> [reason]") ?: return true
        val reason = join(args, 2).ifBlank { "manual-command" }
        val result = plugin.lifecycle().unfreeze(sender, region.id, reason)
        if (result.success) plugin.lang().sendRaw(sender, "§a区域 ${region.id} 已复核并重新发布。")
        else plugin.lang().sendRaw(sender, "§c解冻失败: ${result.reason}")
        return true
    }

    private fun audit(sender: CommandSender, args: Array<String>): Boolean {
        if (!allow(sender, plugin.authority().canUseGlobalAdministration(sender))) return true
        val regionId = args.getOrNull(1)
        if (regionId != null && plugin.regions().find(regionId) == null) {
            plugin.lang().send(sender, "not-found", mapOf("id" to regionId))
            return true
        }
        val limit = args.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val events = plugin.audit().recent(regionId, limit)
        plugin.lang().sendRaw(sender, "§6Regions 审计记录 (${events.size})")
        if (events.isEmpty()) plugin.lang().sendRaw(sender, "§7暂无记录。")
        for (event in events) {
            val reason = event.reason?.let { " reason=$it" } ?: ""
            plugin.lang().sendRaw(sender, "§7${DATE_FORMAT.format(Instant.ofEpochMilli(event.createdAtMillis))} §f${event.regionId} §e${event.action} §7by ${event.actorName}$reason")
        }
        return true
    }

    private fun game(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size < 3) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions game <id> <ready|start|status|end>"))
            return true
        }
        val region = plugin.regions().find(args[1])
        if (region == null) {
            plugin.lang().send(sender, "not-found", mapOf("id" to args[1]))
            return true
        }
        val isRace = plugin.raceModes().isRaceMode(region)
        val isRound = plugin.roundModes().isRoundMode(region)
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
                if (!canManage(sender, region)) return true
                val handled = if (isRace) {
                    plugin.raceModes().startCommand(sender, args[1])
                } else if (isRound) {
                    plugin.roundModes().startCommand(sender, args[1])
                } else {
                    false
                }
                if (!handled) {
                    plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions game <id> <ready|start|status|end>"))
                } else {
                    plugin.audit().record(sender, region.id, "game.start.requested", "manual-command")
                }
            }
            "status" -> plugin.lang().sendRaw(sender, "§e${args[1]}: ${if (isRace) plugin.raceModes().status(args[1]) else if (isRound) plugin.roundModes().status(args[1]) else plugin.combatModes().status(args[1])}")
            "end", "stop" -> {
                if (!canManage(sender, region)) return true
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
                    plugin.audit().record(sender, region.id, "game.end", "manual-command")
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
        if (!allow(sender, plugin.authority().canUseGlobalAdministration(sender))) {
            return true
        }
        plugin.reloadRegions()
        plugin.audit().record(sender, "<global>", "plugin.reload")
        plugin.lang().send(sender, "reloaded")
        return true
    }

    private fun validate(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.validate")) {
            return true
        }
        val issues =
            if (args.size >= 2) {
                val region = plugin.publishing().editable(args[1])
                if (region == null) {
                    plugin.lang().send(sender, "not-found", mapOf("id" to args[1]))
                    return true
                }
                if (!canManage(sender, region)) return true
                plugin.publishing().publishingIssues(sender, region)
            } else {
                plugin.authority().visibleRegions(sender, plugin.regions().all())
                    .map { plugin.publishing().editable(it.id) ?: it }
                    .flatMap { plugin.publishing().publishingIssues(sender, it) }
                    .distinctBy { Triple(it.regionId, it.severity, it.message) }
            }
        sendIssues(sender, issues)
        return true
    }

    private fun inspect(sender: CommandSender, args: Array<String>): Boolean {
        if (!allow(sender, plugin.authority().canUseGlobalAdministration(sender))) {
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
        if (!allow(sender, plugin.authority().canUseGlobalAdministration(sender))) {
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
        plugin.trials().stop(player, "manual-cleanup")
        plugin.combatModes().restoreIfPending(player, "manual-cleanup")
        plugin.roundModes().restoreIfPending(player, "manual-cleanup")
        plugin.lang().send(sender, "cleanup-done", mapOf("player" to player.name, "count" to count.toString()))
        plugin.audit().record(sender, "<global>", "session.cleanup", "manual-command", mapOf("player" to player.name, "count" to count.toString()))
        return true
    }

    private fun doctor(sender: CommandSender): Boolean {
        if (!allow(sender, plugin.authority().canUseGlobalAdministration(sender))) {
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
        plugin.lang().send(sender, "doctor-line", mapOf("message" to "conditions: ${plugin.conditions().all().joinToString(", ")}"))
        plugin.lang().send(sender, "doctor-line", mapOf("message" to "templates: ${plugin.templates().all().size}"))
        val highRisk = plugin.capabilities().all().count { it.risk == CapabilityRisk.HIGH }
        val summary = CapabilityKind.entries.joinToString(", ") { kind ->
            "${kind.name.lowercase(Locale.ROOT)}=${plugin.capabilities().stableIds(kind).size}"
        }
        plugin.lang().send(sender, "doctor-line", mapOf("message" to "capabilities: $summary, high-risk=$highRisk"))
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

    private fun sendHelp(sender: CommandSender) {
        if (canEnterManagementSilent(sender)) {
            plugin.lang().send(sender, "help")
            plugin.lang().send(sender, "help-publishing")
        } else {
            plugin.lang().send(sender, "help-player")
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

    private fun requireEditableRegion(sender: CommandSender, args: Array<String>, usage: String): RegionDefinition? {
        if (args.size < 2) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to usage))
            return null
        }
        val region = plugin.publishing().editable(args[1])
        if (region == null) plugin.lang().send(sender, "not-found", mapOf("id" to args[1]))
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

    private fun withOwnerSnapshot(region: RegionDefinition): RegionDefinition {
        val metadata = LinkedHashMap(region.metadata)
        val ownerId = plugin.sources().find(region.source.type)?.ownerId(region.source)
        if (ownerId == null) metadata.remove(org.cubexmc.regions.service.RegionAuthorityService.SOURCE_OWNER_METADATA)
        else metadata[org.cubexmc.regions.service.RegionAuthorityService.SOURCE_OWNER_METADATA] = ownerId.toString()
        return region.copy(metadata = metadata)
    }

    private fun has(sender: CommandSender, permission: String): Boolean {
        if (sender.hasPermission(permission) || plugin.authority().isRuler(sender) || plugin.authority().isSuperAdmin(sender)) {
            return true
        }
        plugin.lang().send(sender, "no-permission")
        return false
    }

    private fun canEnterManagement(sender: CommandSender): Boolean {
        if (canEnterManagementSilent(sender)) {
            return true
        }
        allow(sender, plugin.authority().canEnterManagement(sender))
        return false
    }

    private fun canEnterManagementSilent(sender: CommandSender): Boolean =
        plugin.authority().canEnterManagement(sender).allowed

    private fun canManage(sender: CommandSender, region: RegionDefinition): Boolean =
        allow(sender, plugin.authority().canManage(sender, region))

    private fun allow(sender: CommandSender, decision: AuthorityDecision): Boolean {
        if (decision.allowed) return true
        plugin.lang().send(sender, decision.denial?.messageKey ?: "no-permission")
        return false
    }

    override fun suggest(commandSourceStack: CommandSourceStack, args: Array<String>): Collection<String> =
        suggestions(commandSourceStack.sender, args)

    private fun suggestions(sender: CommandSender, args: Array<String>): List<String> {
        if (args.size == 1) {
            val commands = if (canEnterManagementSilent(sender)) {
                listOf("gui", "list", "create", "remove", "enable", "disable", "bind", "mode", "flag", "effect", "trial", "preview", "publish", "withdraw", "history", "rollback", "archive", "freeze", "unfreeze", "audit", "game", "reload", "validate", "inspect", "cleanup", "doctor", "help")
            } else {
                listOf("game", "help")
            }
            return startsWith(commands, args[0])
        }
        if (!canEnterManagementSilent(sender) && !args[0].equals("game", ignoreCase = true)) {
            return emptyList()
        }
        if (args.size == 2 && listOf("validate", "remove", "enable", "disable", "bind", "trial", "preview", "publish", "withdraw", "unpublish", "history", "rollback", "archive", "freeze", "unfreeze", "audit", "game").contains(args[0].lowercase(Locale.ROOT))) {
            return startsWith(visibleRegionIds(sender), args[1])
        }
        if (args.size == 3 && args[0].equals("trial", ignoreCase = true)) {
            return startsWith(listOf("start", "stop"), args[2])
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
            return startsWith(visibleRegionIds(sender), args[2])
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

    private fun visibleRegionIds(sender: CommandSender): List<String> =
        plugin.authority().visibleRegions(sender, plugin.regions().all()).map { it.id }

    companion object {
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault())
    }
}
