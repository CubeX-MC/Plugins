package org.cubexmc.fawereplace.commands

import com.sk89q.worldedit.IncompleteRegionException
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import java.util.Locale
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.cubexmc.fawereplace.FAWEReplace
import org.cubexmc.fawereplace.LanguageManager
import org.cubexmc.fawereplace.tasks.CleaningTask

/**
 * 处理 /fawereplace 命令的执行逻辑
 */
class FaweReplaceCommand(
    private val plugin: FAWEReplace,
    private val cleaningTask: CleaningTask,
) : CommandExecutor {
    private val lang: LanguageManager = plugin.getLanguageManager()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // 权限检查
        if (!sender.hasPermission("fawereplace.use")) {
            sender.sendMessage(lang.getMessage("no_permission"))
            return true
        }

        // 参数检查
        if (args.isEmpty()) {
            sendUsage(sender, label)
            return true
        }

        // 处理子命令
        when (args[0].lowercase(Locale.ROOT)) {
            "start" -> handleStart(sender, args)
            "stop" -> handleStop(sender)
            "status" -> handleStatus(sender)
            "reload" -> handleReload(sender)
            "help" -> sendHelp(sender, label)
            "setregion" -> handleSetRegion(sender)
            "addrule" -> handleAddRule(sender, args)
            "removerule" -> handleRemoveRule(sender, args)
            "rules" -> handleRules(sender)
            else -> sendUsage(sender, label)
        }
        return true
    }

    /**
     * 处理 start 子命令
     */
    private fun handleStart(sender: CommandSender, args: Array<out String>) {
        // 检查世界是否已正确配置
        if (!plugin.isWorldConfigured()) {
            sender.sendMessage(lang.getMessage("start.world_not_configured", "world", plugin.getConfiguredWorldName()))
            sender.sendMessage(lang.getMessage("start.check_config"))
            return
        }

        if (cleaningTask.isRunning) {
            sender.sendMessage(lang.getMessage("start.already_running"))
            return
        }

        // 检查是否有 --fresh 参数，强制重新开始
        var forceRestart = false
        if (args.size > 1 && args[1].equals("--fresh", ignoreCase = true)) {
            forceRestart = true
        }

        cleaningTask.start(sender, forceRestart)
    }

    /**
     * 处理 stop 子命令
     */
    private fun handleStop(sender: CommandSender) {
        if (!cleaningTask.isRunning) {
            sender.sendMessage(lang.getMessage("stop.not_running"))
            return
        }

        cleaningTask.stop(sender)
        sender.sendMessage(lang.getMessage("stop.stopping"))
    }

    /**
     * 处理 status 子命令
     */
    private fun handleStatus(sender: CommandSender) {
        // 显示世界配置状态
        if (!plugin.isWorldConfigured()) {
            sender.sendMessage(lang.getMessage("status.world_status", "world", plugin.getConfiguredWorldName()))
            sender.sendMessage(lang.getMessage("status.reload_suggestion"))
            sender.sendMessage("")
        }

        cleaningTask.sendStatus(sender)
    }

    /**
     * 处理 reload 子命令
     */
    private fun handleReload(sender: CommandSender) {
        if (cleaningTask.isRunning) {
            sender.sendMessage(lang.getMessage("reload.cannot_while_running"))
            return
        }

        sender.sendMessage(lang.getMessage("reload.reloading"))

        // 调用主插件的配置重载方法
        if (plugin.reloadConfiguration()) {
            sender.sendMessage(lang.getMessage("reload.success"))
            sender.sendMessage(lang.getMessage("reload.world_found", "world", plugin.getConfiguredWorldName()))
            sender.sendMessage(lang.getMessage("reload.language_changed", "language", lang.getCurrentLanguage()))
            sender.sendMessage(lang.getMessage("reload.apply_next_start"))
        } else {
            sender.sendMessage(lang.getMessage("reload.failed"))
            if (!plugin.isWorldConfigured()) {
                sender.sendMessage(lang.getMessage("reload.world_not_found", "world", plugin.getConfiguredWorldName()))
                sender.sendMessage(lang.getMessage("reload.check_world_name"))
            } else {
                sender.sendMessage(lang.getMessage("reload.check_console"))
            }
        }
    }

    /**
     * 处理 setregion 子命令
     */
    private fun handleSetRegion(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(lang.getMessage("player_only"))
            return
        }

        val player = sender
        try {
            val session = WorldEdit.getInstance().sessionManager.get(BukkitAdapter.adapt(player))
            val weWorld = BukkitAdapter.adapt(player.world)
            val region = session.getSelection(weWorld)

            if (region == null) {
                sender.sendMessage(lang.getMessage("setregion.no_selection"))
                return
            }

            // 获取选区坐标
            val minX = region.minimumPoint.blockX
            val minY = region.minimumPoint.blockY
            val minZ = region.minimumPoint.blockZ
            val maxX = region.maximumPoint.blockX
            val maxY = region.maximumPoint.blockY
            val maxZ = region.maximumPoint.blockZ

            // 更新配置 (写入 rules.yml)
            plugin.getRulesConfig().set("world", player.world.name)
            plugin.getRulesConfig().set("target.start.x", minX)
            plugin.getRulesConfig().set("target.start.y", minY)
            plugin.getRulesConfig().set("target.start.z", minZ)
            plugin.getRulesConfig().set("target.end.x", maxX)
            plugin.getRulesConfig().set("target.end.y", maxY)
            plugin.getRulesConfig().set("target.end.z", maxZ)

            plugin.saveRulesConfig()
            plugin.reloadConfiguration() // 立即应用更改

            sender.sendMessage(lang.getMessage("setregion.success"))
            sender.sendMessage(
                lang.getMessage(
                    "setregion.coords",
                    "x1",
                    minX.toString(),
                    "y1",
                    minY.toString(),
                    "z1",
                    minZ.toString(),
                    "x2",
                    maxX.toString(),
                    "y2",
                    maxY.toString(),
                    "z2",
                    maxZ.toString(),
                ),
            )
            sender.sendMessage(lang.getMessage("setregion.saved"))
        } catch (exception: IncompleteRegionException) {
            sender.sendMessage(lang.getMessage("setregion.no_selection"))
        } catch (exception: Exception) {
            sender.sendMessage(lang.getMessage("error.file_error", "error", exception.message))
            exception.printStackTrace()
        }
    }

    /**
     * 处理 addrule 子命令
     */
    private fun handleAddRule(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            sender.sendMessage(lang.getMessage("rule.add_usage"))
            return
        }

        val origin = args[1].uppercase(Locale.ROOT)
        val target = args[2].uppercase(Locale.ROOT)

        // 检查是否为实体类型
        var entityType: EntityType? = null
        try {
            entityType = EntityType.valueOf(origin)
        } catch (ignored: IllegalArgumentException) {
        }

        if (Material.getMaterial(origin) == null && entityType == null) {
            sender.sendMessage(lang.getMessage("rule.invalid_type", "type", origin))
            return
        }

        // 如果是实体，目标必须是 AIR
        if (entityType != null) {
            if (target != "AIR") {
                sender.sendMessage(lang.getMessage("rule.entity_target_error"))
                return
            }

            // 添加实体规则
            val entities = plugin.getRulesConfig().getStringList("entities")
            if (!entities.contains(origin)) {
                entities.add(origin)
                plugin.getRulesConfig().set("entities", entities)
                plugin.saveRulesConfig()
                plugin.reloadConfiguration()
            }

            sender.sendMessage(lang.getMessage("rule.add_success", "origin", origin, "target", "AIR (Entity)"))
            sender.sendMessage(lang.getMessage("setregion.saved"))
            return
        }

        if (Material.getMaterial(target) == null && target != "AIR") {
            sender.sendMessage(lang.getMessage("rule.invalid_material", "material", target))
            return
        }

        // 获取当前规则列表 (从 rules.yml)
        val blocks = plugin.getRulesConfig().getMapList("blocks")

        // 检查是否已存在（如果存在则更新）
        var found = false
        val newBlocks = ArrayList<Map<String, Any?>>()
        for (map in blocks) {
            val newMap = HashMap<String, Any?>()
            for ((key, value) in map) {
                newMap[key.toString()] = value
            }

            val currentOrigin = newMap["origin"] ?: error("Missing origin in FAWEReplace rule")
            if (currentOrigin.toString() == origin) {
                newMap["target"] = target
                found = true
            }
            newBlocks.add(newMap)
        }

        if (!found) {
            val newRule = HashMap<String, Any?>()
            newRule["origin"] = origin
            newRule["target"] = target
            newBlocks.add(newRule)
        }

        plugin.getRulesConfig().set("blocks", newBlocks)
        plugin.saveRulesConfig()
        plugin.reloadConfiguration()

        sender.sendMessage(lang.getMessage("rule.add_success", "origin", origin, "target", target))
        sender.sendMessage(lang.getMessage("setregion.saved"))
    }

    /**
     * 处理 removerule 子命令
     */
    private fun handleRemoveRule(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            sender.sendMessage(lang.getMessage("rule.remove_usage"))
            return
        }

        val origin = args[1].uppercase(Locale.ROOT)

        // 检查是否为实体类型
        var entityType: EntityType? = null
        try {
            entityType = EntityType.valueOf(origin)
        } catch (ignored: IllegalArgumentException) {
        }

        if (entityType != null) {
            val entities = plugin.getRulesConfig().getStringList("entities")
            if (!entities.contains(origin)) {
                sender.sendMessage(lang.getMessage("rule.remove_failed", "origin", origin))
                return
            }

            entities.remove(origin)
            plugin.getRulesConfig().set("entities", entities)
            plugin.saveRulesConfig()
            plugin.reloadConfiguration()

            sender.sendMessage(lang.getMessage("rule.remove_success", "origin", origin))
            sender.sendMessage(lang.getMessage("setregion.saved"))
            return
        }

        // 获取当前规则列表 (从 rules.yml)
        val blocks = plugin.getRulesConfig().getMapList("blocks")
        if (blocks.isEmpty()) {
            sender.sendMessage(lang.getMessage("rule.remove_failed", "origin", origin))
            return
        }

        var found = false
        val newBlocks = ArrayList<Map<String, Any?>>()
        for (map in blocks) {
            val newMap = HashMap<String, Any?>()
            for ((key, value) in map) {
                newMap[key.toString()] = value
            }

            val currentOrigin = newMap["origin"] ?: error("Missing origin in FAWEReplace rule")
            if (currentOrigin.toString() == origin) {
                found = true
                continue // Skip adding this to new list
            }
            newBlocks.add(newMap)
        }

        if (!found) {
            sender.sendMessage(lang.getMessage("rule.remove_failed", "origin", origin))
            return
        }

        plugin.getRulesConfig().set("blocks", newBlocks)
        plugin.saveRulesConfig()
        plugin.reloadConfiguration()

        sender.sendMessage(lang.getMessage("rule.remove_success", "origin", origin))
        sender.sendMessage(lang.getMessage("setregion.saved"))
    }

    /**
     * 处理 rules 子命令
     */
    private fun handleRules(sender: CommandSender) {
        // 优先读取 rules.yml
        var blocks: List<Map<*, *>> = plugin.getRulesConfig().getMapList("blocks")
        if (blocks.isEmpty()) {
            blocks = plugin.config.getMapList("blocks")
        }

        var entities: List<String> = plugin.getRulesConfig().getStringList("entities")
        if (entities.isEmpty()) {
            entities = plugin.config.getStringList("entities.types")
        }

        if (blocks.isEmpty() && entities.isEmpty()) {
            sender.sendMessage(lang.getMessage("rule.list_empty"))
            return
        }

        val total = blocks.size + entities.size
        sender.sendMessage(lang.getMessage("rule.list_header", "count", total.toString()))

        for (map in blocks) {
            val origin = (map["origin"] ?: error("Missing origin in FAWEReplace rule")).toString()
            val target = (map["target"] ?: error("Missing target in FAWEReplace rule")).toString()
            sender.sendMessage(lang.getMessage("rule.list_format", "origin", origin, "target", target))
        }

        for (entity in entities) {
            sender.sendMessage(lang.getMessage("rule.list_format", "origin", entity, "target", "AIR (Entity)"))
        }
    }

    /**
     * 发送命令用法
     */
    private fun sendUsage(sender: CommandSender, label: String) {
        sender.sendMessage(lang.getMessage("usage", "label", label))
    }

    /**
     * 发送帮助信息
     */
    private fun sendHelp(sender: CommandSender, label: String) {
        sender.sendMessage(lang.getMessage("help.header"))
        sender.sendMessage(lang.getMessage("help.title"))
        sender.sendMessage("")
        sender.sendMessage(lang.getMessage("help.start", "label", label))
        sender.sendMessage(lang.getMessage("help.start_desc"))
        sender.sendMessage(lang.getMessage("help.start_fresh", "label", label))
        sender.sendMessage(lang.getMessage("help.start_fresh_desc"))
        sender.sendMessage(lang.getMessage("help.stop", "label", label))
        sender.sendMessage(lang.getMessage("help.stop_desc"))
        sender.sendMessage(lang.getMessage("help.status", "label", label))
        sender.sendMessage(lang.getMessage("help.reload", "label", label))
        sender.sendMessage(lang.getMessage("help.setregion", "label", label))
        sender.sendMessage(lang.getMessage("help.rules", "label", label))
        sender.sendMessage(lang.getMessage("help.addrule", "label", label))
        sender.sendMessage(lang.getMessage("help.removerule", "label", label))
        sender.sendMessage(lang.getMessage("help.help", "label", label))
        sender.sendMessage("")
        sender.sendMessage(lang.getMessage("help.aliases"))
        sender.sendMessage(lang.getMessage("help.footer"))
    }
}
