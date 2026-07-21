package org.cubexmc.regions.gui

import org.bukkit.Bukkit
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.config.PaperText
import org.cubexmc.regions.model.ActionBlockConfig
import org.cubexmc.regions.model.ActionConfig
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.EffectCombination
import org.cubexmc.regions.model.EffectScope
import org.cubexmc.regions.model.FlagConfig
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.OwnerPolicy
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.model.RegionTrigger
import org.cubexmc.regions.model.TriggerExecution
import org.cubexmc.regions.service.AuthorityDecision
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RegionsGui(private val plugin: RegionsPlugin) : Listener {
    private val pendingInputs: MutableMap<UUID, PendingInput> = ConcurrentHashMap()

    fun openMain(player: Player) {
        if (!canEnterManagement(player)) {
            return
        }
        val regions = plugin.authority().visibleRegions(player, plugin.regions().all())
            .map { plugin.publishing().editable(it.id) ?: it }
            .sortedWith(compareByDescending<RegionDefinition> { it.priority }.thenBy { it.id })
        val inventory = Bukkit.createInventory(RegionsHolder(View.MAIN), 54, ui("${Ui.DARK_GREEN}Regions"))
        for ((index, region) in regions.take(45).withIndex()) {
            inventory.setItem(index, regionItem(region))
        }
        inventory.setItem(45, named(Material.WRITABLE_BOOK, "${Ui.AQUA}创建区域", listOf(
            "${Ui.GRAY}输入区域 ID 和名称后，",
            "${Ui.GRAY}从你拥有的 Lands area 中安全选择。",
        )))
        if (plugin.authority().isSuperAdmin(player)) {
            inventory.setItem(47, named(Material.COMPASS, "${Ui.GREEN}重载配置", listOf("${Ui.GRAY}点击从文件重新载入 regions 配置。")))
        }
        inventory.setItem(49, named(Material.MAP, "${Ui.YELLOW}区域总数: ${regions.size}", listOf(
            "${Ui.GRAY}这里只显示你当前拥有的场地。",
        )))
        inventory.setItem(51, named(Material.SPYGLASS, "${Ui.AQUA}系统检查", listOf("${Ui.GRAY}点击检查当前区域配置。")))
        inventory.setItem(53, named(Material.BARRIER, "${Ui.RED}关闭"))
        player.openInventory(inventory)
    }

    fun openDetail(player: Player, regionId: String) {
        val region = plugin.publishing().editable(regionId)
        if (region == null) {
            openMain(player)
            return
        }
        if (!canManageRegion(player, region)) return
        val inventory = Bukkit.createInventory(RegionsHolder(View.DETAIL, region.id), 54, ui("${Ui.DARK_GREEN}Region: ${region.id}"))
        inventory.setItem(4, regionItem(region))
        inventory.setItem(10, named(Material.ENDER_EYE, "${Ui.AQUA}区域来源", listOf(
            "${Ui.GRAY}${region.source.describe()}",
            "${Ui.DARK_GRAY}点击查看绑定命令。",
        )))
        inventory.setItem(12, named(Material.DIAMOND_SWORD, "${Ui.AQUA}玩法模式", listOf(
            "${Ui.GRAY}${region.mode?.type ?: "none"}",
            "${Ui.DARK_GRAY}点击设置 free_event / combat / race / round。",
        )))
        inventory.setItem(14, named(Material.OAK_SIGN, "${Ui.AQUA}Flags", listOf(
            "${Ui.GRAY}${region.flags.size} 个 flag",
            "${Ui.DARK_GRAY}点击切换常用规则。",
        )))
        inventory.setItem(16, named(Material.BLAZE_POWDER, "${Ui.AQUA}Effects", listOf(
            "${Ui.GRAY}${region.effects.size} 个 effect",
            "${Ui.DARK_GRAY}点击添加或移除常用效果。",
        )))
        inventory.setItem(22, named(Material.COMMAND_BLOCK, "${Ui.AQUA}Triggers & Actions", listOf(
            "${Ui.GRAY}${region.triggers.values.sumOf { it.size }} 个 action block",
            "${Ui.DARK_GRAY}点击配置进入/离开/开赛/完赛行为。",
        )))
        inventory.setItem(28, named(
            if (region.enabled) Material.REDSTONE_TORCH else Material.LEVER,
            if (region.enabled) "${Ui.YELLOW}点击禁用" else "${Ui.GREEN}点击启用",
        ))
        inventory.setItem(30, named(Material.WRITABLE_BOOK, "${Ui.AQUA}校验配置"))
        inventory.setItem(32, named(Material.MILK_BUCKET, "${Ui.AQUA}清理我的状态", listOf(
            "${Ui.GRAY}用于测试时立刻移除当前玩家的区域效果。",
        )))
        inventory.setItem(36, named(Material.LAVA_BUCKET, "${Ui.RED}删除区域", listOf(
            "${Ui.GRAY}点击后需要输入区域 ID 确认。",
            "${Ui.RED}会从 regions.yml 移除此区域配置。",
        )))
        if (plugin.publishing().draft(region.id) != null) {
            inventory.setItem(38, named(Material.EMERALD, "${Ui.GREEN}发布草稿", listOf(
                "${Ui.GRAY}校验通过后切换当前发布版本。",
                "${Ui.GRAY}当前草稿 revision: ${region.revision}",
            )))
        }
        if (plugin.regions().find(region.id)?.lifecycle == org.cubexmc.regions.model.RegionLifecycle.PUBLISHED) {
            inventory.setItem(40, named(Material.PAPER, "${Ui.YELLOW}撤回已发布版本", listOf(
                "${Ui.GRAY}停止场地运行并转回草稿。",
            )))
        }
        inventory.setItem(42, named(Material.BOOK, "${Ui.AQUA}版本历史", listOf(
            "${Ui.GRAY}${plugin.publishing().history(region.id).size} 个已保存版本。",
            "${Ui.GRAY}点击在聊天中查看 revision 编号。",
        )))
        if (plugin.publishing().draft(region.id) != null) {
            val trial = plugin.trials().active(player.uniqueId)
            val active = trial?.regionId == region.id
            inventory.setItem(44, named(
                if (active) Material.REDSTONE_BLOCK else Material.SPYGLASS,
                if (active) "${Ui.RED}结束我的隔离试运行" else "${Ui.AQUA}开始我的隔离试运行",
                listOf(
                    "${Ui.GRAY}仅把草稿的 Effect 与 Flag 应用于你自己。",
                    "${Ui.GRAY}不会启动 Mode、执行 Trigger 或影响其他玩家。",
                ),
            ))
        }
        inventory.setItem(34, named(Material.BARRIER, "${Ui.RED}返回"))
        player.openInventory(inventory)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? RegionsHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (!canEnterManagement(player)) {
            player.closeInventory()
            return
        }
        holder.regionId?.let { regionId ->
            val region = plugin.publishing().editable(regionId)
            if (region == null || !canManageRegion(player, region)) {
                player.closeInventory()
                return
            }
        }
        val slot = event.rawSlot
        if (slot < 0 || slot >= event.inventory.size) {
            return
        }
        when (holder.view) {
            View.MAIN -> clickMain(player, event.currentItem, slot)
            View.DETAIL -> clickDetail(player, holder.regionId ?: return, slot)
            View.SOURCE -> clickSource(player, holder.regionId ?: return, slot, event.click.isRightClick)
            View.MODE -> clickMode(player, holder.regionId ?: return, slot, event.click.isRightClick)
            View.FLAGS -> clickFlags(player, holder.regionId ?: return, slot)
            View.EFFECTS -> clickEffects(player, holder.regionId ?: return, slot)
            View.TRIGGERS -> clickTriggers(player, holder.regionId ?: return, slot)
            View.PUBLISH_PREVIEW -> clickPublishPreview(player, holder.regionId ?: return, slot)
            View.OWNED_AREAS -> clickOwnedArea(player, holder, event.currentItem, slot)
            View.TEMPLATES -> clickTemplate(player, holder, event.currentItem, slot)
        }
    }

    private fun clickMain(player: Player, item: ItemStack?, slot: Int) {
        if (slot == 53) {
            player.closeInventory()
            return
        }
        if (slot == 45) {
            return promptCreateRegion(player)
        }
        if (slot == 47) {
            if (!allow(player, plugin.authority().canUseGlobalAdministration(player))) return
            plugin.reloadRegions()
            plugin.lang().sendRaw(player, "§aRegions 配置已重载。")
            return openMain(player)
        }
        if (slot == 51) {
            val visible = plugin.authority().visibleRegions(player, plugin.regions().all())
                .map { plugin.publishing().editable(it.id) ?: it }
            val issues = plugin.validation().validateAll(visible)
            if (issues.isEmpty()) {
                plugin.lang().sendRaw(player, "§a没有发现配置问题。")
            } else {
                plugin.lang().sendRaw(player, "§e发现 ${issues.size} 个配置问题，详情见控制台/聊天。")
                for (issue in issues.take(8)) {
                    plugin.lang().sendRaw(player, "§7${issue.regionId}: ${issue.severity} ${issue.message}")
                }
            }
            return
        }
        val meta = item?.itemMeta ?: return
        val regionId = meta.persistentDataContainer.get(regionKey(), PersistentDataType.STRING) ?: return
        openDetail(player, regionId)
    }

    private fun clickDetail(player: Player, regionId: String, slot: Int) {
        val region = plugin.publishing().editable(regionId) ?: return openMain(player)
        when (slot) {
            10 -> openSource(player, region.id)
            12 -> openMode(player, region.id)
            14 -> openFlags(player, region.id)
            16 -> openEffects(player, region.id)
            22 -> openTriggers(player, region.id)
            28 -> saveAndReopen(player, region.copy(enabled = !region.enabled)) { openDetail(player, regionId) }
            30 -> sendValidation(player, region)
            32 -> {
                val count = plugin.sessions().cleanup(player, "gui-cleanup")
                plugin.lang().send(player, "cleanup-done", mapOf("player" to player.name, "count" to count.toString()))
            }
            36 -> promptDeleteRegion(player, region)
            38 -> {
                openPublishPreview(player, region.id)
            }
            40 -> {
                val result = plugin.publishing().withdraw(player, region.id)
                if (result.success) plugin.lang().sendRaw(player, "§e区域 ${region.id} 已撤回为草稿。")
                else plugin.lang().sendRaw(player, "§c撤回失败: ${result.reason}")
                openDetail(player, region.id)
            }
            42 -> sendRevisionHistory(player, region)
            44 -> {
                val active = plugin.trials().active(player.uniqueId)?.regionId == region.id
                val result = if (active) plugin.trials().stop(player, "gui-stop") else plugin.trials().start(player, region.id)
                if (result.success) {
                    plugin.lang().sendRaw(player, if (active) "§e隔离试运行已结束。" else "§a隔离试运行已开始；只有你会受到草稿 Effect 与 Flag 影响。")
                } else {
                    plugin.lang().sendRaw(player, "§c试运行失败: ${result.reason}")
                }
                openDetail(player, region.id)
            }
            34 -> openMain(player)
        }
    }

    private fun openPublishPreview(player: Player, regionId: String) {
        val draft = plugin.publishing().draft(regionId) ?: return openDetail(player, regionId)
        val report = plugin.publishing().previewReport(player, regionId) ?: return openDetail(player, regionId)
        val changes = report.changes
        val issues = report.issues
        val errors = issues.count { it.severity == org.cubexmc.regions.model.ValidationSeverity.ERROR }
        val warnings = issues.size - errors
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.PUBLISH_PREVIEW, regionId),
            54,
            ui("${Ui.DARK_GREEN}发布预览: $regionId"),
        )
        changes.take(36).forEachIndexed { index, change ->
            val material = when {
                change.before == null -> Material.LIME_DYE
                change.after == null -> Material.RED_DYE
                else -> Material.YELLOW_DYE
            }
            inventory.setItem(index, named(material, "${Ui.YELLOW}${change.path}", listOf(
                "${Ui.RED}原值: ${previewValue(change.before)}",
                "${Ui.GREEN}新值: ${previewValue(change.after)}",
            )))
        }
        if (changes.isEmpty()) {
            inventory.setItem(22, named(Material.PAPER, "${Ui.GRAY}配置内容没有变化", listOf(
                "${Ui.GRAY}仍可重新发布，用于恢复已撤回的相同版本。",
            )))
        }
        inventory.setItem(45, named(Material.BARRIER, "${Ui.RED}返回编辑"))
        inventory.setItem(46, named(
            if (report.dependencies.all { it.available }) Material.ENDER_CHEST else Material.TRAPPED_CHEST,
            if (report.dependencies.all { it.available }) "${Ui.GREEN}依赖已满足" else "${Ui.RED}存在缺失依赖",
            if (report.dependencies.isEmpty()) {
                listOf("${Ui.GRAY}此配置没有额外插件依赖。")
            } else {
                report.dependencies.map { dependency ->
                    "${if (dependency.available) Ui.GREEN else Ui.RED}${dependency.id}: ${dependency.detail}"
                }
            },
        ))
        inventory.setItem(47, named(
            if (errors > 0) Material.REDSTONE_BLOCK else if (warnings > 0) Material.YELLOW_CONCRETE else Material.LIME_CONCRETE,
            if (errors > 0) "${Ui.RED}发布被阻止: $errors 个错误" else "${Ui.GREEN}校验通过 · $warnings 个警告",
            issues.take(5).map { issue ->
                "${if (issue.severity == org.cubexmc.regions.model.ValidationSeverity.ERROR) Ui.RED else Ui.YELLOW}${issue.message}"
            } + if (issues.size > 5) listOf("${Ui.GRAY}另有 ${issues.size - 5} 项，使用 /regions validate $regionId 查看。") else emptyList(),
        ))
        inventory.setItem(49, named(if (errors > 0) Material.BARRIER else Material.EMERALD_BLOCK, if (errors > 0) "${Ui.RED}修复错误后才能发布" else "${Ui.GREEN}确认发布 revision ${draft.revision}", listOf(
            "${Ui.GRAY}共 ${changes.size} 项变化。",
            if (changes.size > 36) "${Ui.YELLOW}界面仅展示前 36 项；完整配置仍会校验。" else "${Ui.GRAY}发布前会再次校验权限、owner 与能力。",
        )))
        val resolution = report.resolution
        val displayedMode = resolution.primaryModeRegion
            ?: resolution.orderedRegions.firstOrNull { it.mode != null }
        inventory.setItem(48, named(Material.COMPARATOR, "${Ui.AQUA}最终有效规则", listOf(
            "${Ui.GRAY}Mode: ${displayedMode?.let { "${it.id}:${it.mode?.type}" } ?: "无"}",
            "${Ui.GRAY}主 Trigger Region: ${resolution.primaryTriggerRegion?.id ?: "无"}",
            "${Ui.GRAY}Flags: ${resolution.flags.values.joinToString { "${it.key}=${it.config.value}@${it.sourceRegionId}" }.ifBlank { "无" }}",
            "${Ui.GRAY}Effects: ${resolution.effects.joinToString { "${it.config.type}@${it.sourceRegionId}" }.ifBlank { "无" }}",
        )))
        inventory.setItem(50, named(Material.OBSERVER, "${Ui.YELLOW}确定重叠区域: ${resolution.orderedRegions.size}", resolution.orderedRegions.map {
            "${Ui.GRAY}${it.id} · priority=${it.priority} · ${it.source.type}"
        }))
        player.openInventory(inventory)
    }

    private fun clickPublishPreview(player: Player, regionId: String, slot: Int) {
        when (slot) {
            45 -> openDetail(player, regionId)
            49 -> {
                val result = plugin.publishing().publish(player, regionId)
                if (result.success) plugin.lang().sendRaw(player, "§a区域 $regionId 的草稿已发布。")
                else plugin.lang().sendRaw(player, "§c发布失败: ${result.reason}")
                openDetail(player, regionId)
            }
        }
    }

    private fun previewValue(value: String?): String {
        if (value == null) return "<无>"
        return if (value.length <= 80) value else value.take(77) + "..."
    }

    private fun openSource(player: Player, regionId: String) {
        val region = plugin.publishing().editable(regionId) ?: return openMain(player)
        val inventory = Bukkit.createInventory(RegionsHolder(View.SOURCE, region.id), 27, ui("${Ui.DARK_GREEN}Source: ${region.id}"))
        inventory.setItem(4, regionItem(region))
        inventory.setItem(10, named(Material.GRASS_BLOCK, "${Ui.AQUA}绑定 Lands 领地", listOf(
            "${Ui.GRAY}点击从你当前拥有的 Lands area 中选择。",
            "${Ui.GRAY}当前: ${region.source.describe()}",
        )))
        if (plugin.authority().isSuperAdmin(player)) {
            inventory.setItem(12, named(Material.STONE_AXE, "${Ui.AQUA}绑定 Cuboid", listOf(
                "${Ui.GRAY}左键: 用当前区块生成 Cuboid。",
                "${Ui.GRAY}右键: 输入 world,minX,minY,minZ,maxX,maxY,maxZ。",
            )))
        }
        inventory.setItem(14, named(Material.NAME_TAG, "${Ui.YELLOW}修改显示名称", listOf(
            "${Ui.GRAY}当前: ${region.name}",
            "${Ui.GRAY}点击输入新名称。",
        )))
        inventory.setItem(16, named(Material.COMPARATOR, "${Ui.YELLOW}优先级: ${region.priority}", listOf(
            "${Ui.GRAY}左键 +1，右键 -1。",
            "${Ui.GRAY}重叠区域中高优先级更先处理。",
        )))
        inventory.setItem(22, named(Material.BARRIER, "${Ui.RED}返回"))
        player.openInventory(inventory)
    }

    private fun clickSource(player: Player, regionId: String, slot: Int, rightClick: Boolean) {
        val region = plugin.publishing().editable(regionId) ?: return openMain(player)
        when (slot) {
            10 -> openOwnedAreas(player, OwnedAreaContext(OwnedAreaPurpose.BIND, region.id, region.name))
            12 -> if (rightClick) {
                promptBindCuboid(player, region)
            } else {
                saveAndReopen(player, region.copy(source = cuboidFromCurrentChunk(player, region.id))) { openSource(player, region.id) }
            }
            14 -> promptRename(player, region)
            16 -> saveAndReopen(player, region.copy(priority = region.priority + if (rightClick) -1 else 1)) { openSource(player, region.id) }
            22 -> openDetail(player, regionId)
        }
    }

    private fun openMode(player: Player, regionId: String) {
        val region = plugin.publishing().editable(regionId) ?: return openMain(player)
        val mode = region.mode ?: ModeConfig("free_event")
        val inventory = Bukkit.createInventory(RegionsHolder(View.MODE, region.id), 54, ui("${Ui.DARK_GREEN}Mode: ${region.id}"))
        inventory.setItem(4, regionItem(region))
        inventory.setItem(10, modeItem("free_event", region.mode?.type))
        inventory.setItem(11, modeItem("run_race", region.mode?.type, listOf("${Ui.GRAY}跑步比赛，参赛者不能骑乘载具。")))
        inventory.setItem(12, modeItem("dual_pvp", region.mode?.type, listOf(
            "${Ui.GRAY}双方或多人进场后确认开始。",
            "${Ui.GRAY}可用命令设置 kit、armor、min-players。",
        )))
        inventory.setItem(13, modeItem("boat_race", region.mode?.type, listOf("${Ui.GRAY}划船比赛，参赛者必须坐在船上。")))
        inventory.setItem(14, modeItem("union_war", region.mode?.type, listOf(
            "${Ui.GRAY}读取 UnionProvider 判断工会。",
            "${Ui.GRAY}可用命令设置 min-unions、kit、armor。",
        )))
        inventory.setItem(15, modeItem("horse_race", region.mode?.type, listOf("${Ui.GRAY}骑马比赛，参赛者必须骑马。")))
        inventory.setItem(16, modeItem("hide_and_seek", region.mode?.type, listOf(
            "${Ui.GRAY}藏猫猫小游戏，自动分配寻找者和隐藏者。",
            "${Ui.GRAY}寻找者攻击隐藏者即可找到对方，不造成伤害。",
        )))
        inventory.setItem(19, named(Material.PLAYER_HEAD, "${Ui.YELLOW}最小人数: ${mode.values["min-players"] ?: "2"}", listOf("${Ui.GRAY}左键 +1，右键 -1。")))
        inventory.setItem(20, named(Material.SKELETON_SKULL, "${Ui.YELLOW}最大人数: ${mode.values["max-players"] ?: "不限"}", listOf("${Ui.GRAY}左键 +1，右键 -1。", "${Ui.GRAY}降到 0 会移除此限制。")))
        inventory.setItem(21, named(Material.BELL, "${Ui.YELLOW}需要全员确认: ${mode.values["require-ready"] ?: "true"}", listOf("${Ui.GRAY}点击切换。")))
        inventory.setItem(22, named(Material.CHEST, "${Ui.YELLOW}替换战斗装备: ${mode.values["replace-gear"] ?: "true"}", listOf("${Ui.GRAY}点击切换。")))
        inventory.setItem(23, named(Material.BEACON, "${Ui.YELLOW}最小工会数: ${mode.values["min-unions"] ?: "2"}", listOf("${Ui.GRAY}union_war 使用。左键 +1，右键 -1。")))
        inventory.setItem(24, named(Material.MINECART, "${Ui.YELLOW}全局检查方式: ${describeVehicle(mode.values["vehicle"] ?: defaultVehicle(mode.type))}", listOf("${Ui.GRAY}点击循环: 步行/任意载具/船/马/矿车/不检查。")))
        inventory.setItem(25, named(Material.ENDER_PEARL, "${Ui.AQUA}复活点设为当前位置", listOf("${Ui.GRAY}${formatLocation(player.location)}")))
        inventory.setItem(26, named(Material.TRIPWIRE_HOOK, "${Ui.YELLOW}起点检查方式: ${describeVehicle(mode.values["start-vehicle"] ?: mode.values["vehicle"] ?: defaultVehicle(mode.type))}", listOf("${Ui.GRAY}点击循环，覆盖全局检查方式。")))
        inventory.setItem(28, named(Material.IRON_SWORD, "${Ui.AQUA}预设: 铁剑决斗", listOf("${Ui.GRAY}铁剑、弓、盾、铁甲、食物。")))
        inventory.setItem(29, named(Material.BOW, "${Ui.AQUA}预设: 弓斗", listOf("${Ui.GRAY}弓、箭、木剑、皮甲。")))
        inventory.setItem(30, named(Material.DIAMOND_SWORD, "${Ui.AQUA}预设: 钻石战", listOf("${Ui.GRAY}钻石剑、弓、盾、钻石甲。")))
        inventory.setItem(31, named(Material.BARRIER, "${Ui.RED}清除战斗装备预设"))
        inventory.setItem(32, named(Material.REDSTONE, "${Ui.YELLOW}终点检查方式: ${describeVehicle(mode.values["finish-vehicle"] ?: mode.values["vehicle"] ?: defaultVehicle(mode.type))}", listOf("${Ui.GRAY}点击循环，覆盖全局检查方式。")))
        inventory.setItem(33, named(Material.LODESTONE, "${Ui.YELLOW}当前复活点", listOf("${Ui.GRAY}${mode.values["respawn"] ?: mode.values["outside"] ?: "未设置"}")))
        inventory.setItem(34, named(Material.LAVA_BUCKET, "${Ui.RED}清除复活点"))
        inventory.setItem(36, named(Material.GREEN_WOOL, "${Ui.AQUA}起点设为当前位置", listOf("${Ui.GRAY}${mode.values["start"] ?: "未设置"}")))
        inventory.setItem(37, named(Material.RED_WOOL, "${Ui.AQUA}终点设为当前位置", listOf("${Ui.GRAY}${mode.values["finish"] ?: "未设置"}")))
        inventory.setItem(38, named(Material.YELLOW_WOOL, "${Ui.AQUA}追加检查点", listOf(
            "${Ui.GRAY}当前: ${checkpointCount(mode.values["checkpoints"])} 个",
            "${Ui.GRAY}本检查点方式: ${describeVehicle(mode.values["vehicle"] ?: defaultVehicle(mode.type))}",
            "${Ui.GRAY}${formatLocation(player.location)}",
        )))
        inventory.setItem(39, named(Material.SHEARS, "${Ui.RED}清空检查点"))
        inventory.setItem(40, named(Material.TARGET, "${Ui.YELLOW}必须在起点准备: ${mode.values["require-start"] ?: "true"}", listOf("${Ui.GRAY}点击切换。")))
        inventory.setItem(41, named(Material.ENDER_EYE, "${Ui.YELLOW}开赛传送到起点: ${mode.values["teleport-start"] ?: "false"}", listOf("${Ui.GRAY}点击切换。")))
        inventory.setItem(42, named(Material.LEVER, "${Ui.YELLOW}开赛方式: ${mode.values["start-mode"] ?: "vote"}", listOf("${Ui.GRAY}点击切换 vote / judge。")))
        inventory.setItem(43, named(Material.SLIME_BALL, "${Ui.YELLOW}判定半径: ${mode.values["radius"] ?: "2.5"}", listOf("${Ui.GRAY}左键 +1，右键 -1。")))
        inventory.setItem(44, named(Material.NAME_TAG, "${Ui.AQUA}裁判加入/移除自己", listOf("${Ui.GRAY}当前: ${mode.values["judges"] ?: "无"}")))
        inventory.setItem(45, named(Material.ENDER_EYE, "${Ui.YELLOW}寻找者数量: ${mode.values["seekers"] ?: "自动"}", listOf("${Ui.GRAY}hide_and_seek 使用。左键 +1，右键 -1。")))
        inventory.setItem(46, named(Material.CLOCK, "${Ui.YELLOW}躲藏时间: ${mode.values["hide-seconds"] ?: "30"} 秒", listOf("${Ui.GRAY}左键 +10 秒，右键 -10 秒。")))
        inventory.setItem(47, named(Material.RECOVERY_COMPASS, "${Ui.YELLOW}回合时长: ${mode.values["round-seconds"] ?: "300"} 秒", listOf("${Ui.GRAY}左键 +60 秒，右键 -60 秒。0 表示不限时。")))
        inventory.setItem(50, named(Material.PLAYER_HEAD, "${Ui.YELLOW}被找到后变寻找者: ${mode.values["found-becomes-seeker"] ?: "true"}", listOf("${Ui.GRAY}点击切换。")))
        inventory.setItem(51, named(Material.CLOCK, "${Ui.YELLOW}比赛超时: ${mode.values["timeout-seconds"] ?: "300"} 秒", listOf("${Ui.GRAY}赛跑模式使用。左键 +60 秒，右键 -60 秒。", "${Ui.GRAY}最少 60 秒。")))
        inventory.setItem(48, named(Material.PAPER, "${Ui.YELLOW}高级参数", listOf(
            "${Ui.GRAY}点击输入 key=value。",
            "${Ui.GRAY}输入 clear <key> 可移除参数。",
        )))
        inventory.setItem(49, named(Material.BARRIER, "${Ui.RED}返回"))
        val allowedSlots = modeConfigurationSlots(mode.type).toMutableSet()
        if (plugin.authority().isSuperAdmin(player)) allowedSlots.add(48)
        for (slot in MODE_CONFIGURATION_SLOTS) {
            if (!allowedSlots.contains(slot)) inventory.setItem(slot, null)
        }
        player.openInventory(inventory)
    }

    private fun clickMode(player: Player, regionId: String, slot: Int, rightClick: Boolean) {
        val region = plugin.publishing().editable(regionId) ?: return openMain(player)
        val mode = region.mode ?: ModeConfig("free_event")
        if (
            slot in MODE_CONFIGURATION_SLOTS &&
            slot != 48 &&
            !modeConfigurationSlots(mode.type).contains(slot)
        ) {
            return
        }
        if (slot == 48 && !plugin.authority().isSuperAdmin(player)) return
        when (slot) {
            10, 11, 12, 13, 14, 15, 16 -> {
                val type = when (slot) {
                    11 -> "run_race"
                    12 -> "dual_pvp"
                    13 -> "boat_race"
                    14 -> "union_war"
                    15 -> "horse_race"
                    16 -> "hide_and_seek"
                    else -> "free_event"
                }
                val values = if (region.mode?.type == type) region.mode.values else defaultModeValues(type)
                saveAndReopen(player, region.copy(mode = ModeConfig(type, values))) { openMode(player, regionId) }
            }
            19 -> saveModeValue(player, region, adjustInt(mode.values, "min-players", 2, rightClick, min = 1))
            20 -> saveModeValue(player, region, adjustInt(mode.values, "max-players", 0, rightClick, min = 0, removeAtZero = true))
            21 -> saveModeValue(player, region, toggleBool(mode.values, "require-ready", true))
            22 -> saveModeValue(player, region, toggleBool(mode.values, "replace-gear", true))
            23 -> saveModeValue(player, region, adjustInt(mode.values, "min-unions", 2, rightClick, min = 2))
            24 -> saveModeValue(player, region, cycleVehicle(mode.values, "vehicle", defaultVehicle(mode.type)))
            25 -> saveModeValue(player, region, LinkedHashMap(mode.values).apply { this["respawn"] = formatLocation(player.location) })
            26 -> saveModeValue(player, region, cycleVehicle(mode.values, "start-vehicle", mode.values["vehicle"] ?: defaultVehicle(mode.type)))
            28 -> saveModeValue(player, region, LinkedHashMap(mode.values).apply {
                put("kit", "IRON_SWORD:1,BOW:1,ARROW:16,COOKED_BEEF:8")
                put("armor", "IRON_BOOTS:1,IRON_LEGGINGS:1,IRON_CHESTPLATE:1,IRON_HELMET:1")
                put("offhand", "SHIELD:1")
                put("replace-gear", "true")
            })
            29 -> saveModeValue(player, region, LinkedHashMap(mode.values).apply {
                put("kit", "BOW:1,ARROW:32,WOODEN_SWORD:1,COOKED_BEEF:6")
                put("armor", "LEATHER_BOOTS:1,LEATHER_LEGGINGS:1,LEATHER_CHESTPLATE:1,LEATHER_HELMET:1")
                remove("offhand")
                put("replace-gear", "true")
            })
            30 -> saveModeValue(player, region, LinkedHashMap(mode.values).apply {
                put("kit", "DIAMOND_SWORD:1,BOW:1,ARROW:32,COOKED_BEEF:16")
                put("armor", "DIAMOND_BOOTS:1,DIAMOND_LEGGINGS:1,DIAMOND_CHESTPLATE:1,DIAMOND_HELMET:1")
                put("offhand", "SHIELD:1")
                put("replace-gear", "true")
            })
            31 -> saveModeValue(player, region, LinkedHashMap(mode.values).apply {
                remove("kit")
                remove("armor")
                remove("offhand")
                put("replace-gear", "false")
            })
            32 -> saveModeValue(player, region, cycleVehicle(mode.values, "finish-vehicle", mode.values["vehicle"] ?: defaultVehicle(mode.type)))
            34 -> saveModeValue(player, region, LinkedHashMap(mode.values).apply {
                remove("respawn")
                remove("outside")
            })
            36 -> saveModeValue(player, region, LinkedHashMap(mode.values).apply { put("start", formatLocation(player.location)) })
            37 -> saveModeValue(player, region, LinkedHashMap(mode.values).apply { put("finish", formatLocation(player.location)) })
            38 -> saveModeValue(player, region, LinkedHashMap(mode.values).apply {
                val existing = this["checkpoints"].orEmpty()
                this["checkpoints"] = if (existing.isBlank()) formatLocation(player.location) else "$existing;${formatLocation(player.location)}"
                val existingVehicles = this["checkpoint-vehicles"].orEmpty()
                val checkpointVehicle = this["vehicle"] ?: defaultVehicle(mode.type)
                this["checkpoint-vehicles"] = if (existingVehicles.isBlank()) checkpointVehicle else "$existingVehicles;$checkpointVehicle"
            })
            39 -> saveModeValue(player, region, LinkedHashMap(mode.values).apply {
                remove("checkpoints")
                remove("checkpoint-vehicles")
            })
            40 -> saveModeValue(player, region, toggleBool(mode.values, "require-start", true))
            41 -> saveModeValue(player, region, toggleBool(mode.values, "teleport-start", false))
            42 -> saveModeValue(player, region, LinkedHashMap(mode.values).apply {
                this["start-mode"] = if (this["start-mode"].equals("judge", ignoreCase = true)) "vote" else "judge"
            })
            43 -> saveModeValue(player, region, adjustDouble(mode.values, "radius", 2.5, rightClick, min = 1.0))
            44 -> saveModeValue(player, region, toggleJudge(mode.values, player))
            45 -> saveModeValue(player, region, adjustInt(mode.values, "seekers", 1, rightClick, min = 1))
            46 -> saveModeValue(player, region, adjustInt(mode.values, "hide-seconds", 30, rightClick, min = 0, step = 10))
            47 -> saveModeValue(player, region, adjustInt(mode.values, "round-seconds", 300, rightClick, min = 0, removeAtZero = true, step = 60))
            48 -> promptModeValue(player, region)
            50 -> saveModeValue(player, region, toggleBool(mode.values, "found-becomes-seeker", true))
            51 -> saveModeValue(player, region, adjustInt(mode.values, "timeout-seconds", 300, rightClick, min = 60, step = 60))
            49 -> openDetail(player, regionId)
        }
    }

    private fun openFlags(player: Player, regionId: String) {
        val region = plugin.publishing().editable(regionId) ?: return openMain(player)
        val inventory = Bukkit.createInventory(RegionsHolder(View.FLAGS, region.id), 54, ui("${Ui.DARK_GREEN}Flags: ${region.id}"))
        inventory.setItem(4, regionItem(region))
        for ((slot, flag) in FLAG_SLOTS) {
            if (!plugin.flags().isRegistered(flag)) continue
            inventory.setItem(slot, flagItem(flag, region.flags[flag]?.value ?: "pass"))
        }
        inventory.setItem(48, named(Material.PAPER, "${Ui.YELLOW}高级参数", listOf(
            "${Ui.GRAY}点击输入 <flag> <值> [key=value...]。",
            "${Ui.GRAY}例如 commands blocklist values=/spawn,/home。",
        )))
        inventory.setItem(49, named(Material.BARRIER, "${Ui.RED}返回"))
        player.openInventory(inventory)
    }

    private fun clickFlags(player: Player, regionId: String, slot: Int) {
        if (slot == 49) {
            return openDetail(player, regionId)
        }
        val flag = FLAG_SLOTS[slot] ?: return
        if (!plugin.flags().isRegistered(flag)) return
        val region = plugin.publishing().editable(regionId) ?: return openMain(player)
        if (slot == 48) {
            return promptFlag(player, region)
        }
        val flags = LinkedHashMap(region.flags)
        val next = nextFlagValue(flags[flag]?.value ?: "pass")
        if (next == "pass") {
            flags.remove(flag)
        } else {
            flags[flag] = FlagConfig(flag, next)
        }
        saveAndReopen(player, region.copy(flags = flags)) { openFlags(player, regionId) }
    }

    private fun openEffects(player: Player, regionId: String) {
        val region = plugin.publishing().editable(regionId) ?: return openMain(player)
        val inventory = Bukkit.createInventory(RegionsHolder(View.EFFECTS, region.id), 54, ui("${Ui.DARK_GREEN}Effects: ${region.id}"))
        inventory.setItem(4, regionItem(region))
        for ((index, effect) in region.effects.take(27).withIndex()) {
            inventory.setItem(index + 9, effectItem(index, effect))
        }
        inventory.setItem(37, named(Material.AMETHYST_SHARD, "${Ui.AQUA}添加缩放 0.35", listOf("${Ui.GRAY}小人国常用 effect。")))
        inventory.setItem(38, named(Material.ENDER_PEARL, "${Ui.AQUA}添加缩放 1.60", listOf("${Ui.GRAY}巨人场地常用 effect。")))
        inventory.setItem(39, named(Material.FEATHER, "${Ui.AQUA}允许飞行", listOf("${Ui.GRAY}离开区域会还原原状态。")))
        inventory.setItem(40, named(Material.SUGAR, "${Ui.AQUA}速度药水", listOf("${Ui.GRAY}potion SPEED amplifier=1。")))
        inventory.setItem(41, named(Material.GLASS_BOTTLE, "${Ui.AQUA}压制隐身", listOf("${Ui.GRAY}用于 vanish: deny 场地。")))
        inventory.setItem(43, named(Material.LAVA_BUCKET, "${Ui.RED}清空全部 Effects"))
        inventory.setItem(48, named(Material.PAPER, "${Ui.YELLOW}高级参数", listOf(
            "${Ui.GRAY}点击输入 <effect> [key=value...]。",
            "${Ui.GRAY}例如 scale value=0.5 或 potion effect=JUMP。",
        )))
        inventory.setItem(49, named(Material.BARRIER, "${Ui.RED}返回"))
        player.openInventory(inventory)
    }

    private fun clickEffects(player: Player, regionId: String, slot: Int) {
        if (slot == 49) {
            return openDetail(player, regionId)
        }
        val region = plugin.publishing().editable(regionId) ?: return openMain(player)
        val effects = ArrayList(region.effects)
        if (slot == 48) {
            return promptEffect(player, region)
        }
        if (slot in 9..35) {
            val index = slot - 9
            if (index < effects.size) {
                effects.removeAt(index)
                saveAndReopen(player, region.copy(effects = effects)) { openEffects(player, regionId) }
            }
            return
        }
        val effect = when (slot) {
            37 -> EffectConfig("scale", EffectScope.WHILE_INSIDE, mapOf("value" to "0.35"))
            38 -> EffectConfig("scale", EffectScope.WHILE_INSIDE, mapOf("value" to "1.60"))
            39 -> EffectConfig("allow_flight", EffectScope.WHILE_INSIDE, mapOf("value" to "true"))
            40 -> EffectConfig("potion", EffectScope.WHILE_INSIDE, mapOf("effect" to "SPEED", "amplifier" to "1"))
            41 -> EffectConfig("invisibility_suppression", EffectScope.WHILE_INSIDE)
            43 -> {
                saveAndReopen(player, region.copy(effects = emptyList())) { openEffects(player, regionId) }
                return
            }
            else -> return
        }
        effects.add(effect)
        saveAndReopen(player, region.copy(effects = effects)) { openEffects(player, regionId) }
    }

    private fun openTriggers(player: Player, regionId: String) {
        val region = plugin.publishing().editable(regionId) ?: return openMain(player)
        val inventory = Bukkit.createInventory(RegionsHolder(View.TRIGGERS, region.id), 54, ui("${Ui.DARK_GREEN}Triggers: ${region.id}"))
        inventory.setItem(4, regionItem(region))
        var slot = 9
        for ((trigger, blocks) in region.triggers) {
            if (slot > 35) {
                break
            }
            inventory.setItem(slot, named(Material.COMMAND_BLOCK, "${Ui.YELLOW}${trigger.key}: ${blocks.size}", blocks.take(4).map {
                "${Ui.GRAY}${it.name ?: "action"} [${it.execution.name.lowercase(Locale.ROOT)}] -> ${it.thenActions.joinToString(", ") { action -> action.type }}"
            } + listOf("${Ui.RED}点击清空这个触发器。")))
            slot += 1
        }
        inventory.setItem(37, named(Material.OAK_DOOR, "${Ui.AQUA}模板: 进入提示", listOf(
            "${Ui.GRAY}on_enter -> message text=欢迎进入区域",
        )))
        inventory.setItem(38, named(Material.FIREWORK_ROCKET, "${Ui.AQUA}模板: 开赛标题", listOf(
            "${Ui.GRAY}on_mode_start -> title title=开始!",
        )))
        inventory.setItem(39, named(Material.GOLD_INGOT, "${Ui.AQUA}模板: 完赛广播", listOf(
            "${Ui.GRAY}on_finish -> broadcast text={player} 完成比赛",
        )))
        inventory.setItem(41, named(Material.PAPER, "${Ui.YELLOW}添加 Action", listOf(
            "${Ui.GRAY}输入 <trigger> <action> [key=value...]。",
            "${Ui.GRAY}例如 on_enter message text=欢迎 {player}",
        )))
        inventory.setItem(43, named(Material.LAVA_BUCKET, "${Ui.RED}清空全部 Triggers"))
        inventory.setItem(49, named(Material.BARRIER, "${Ui.RED}返回"))
        player.openInventory(inventory)
    }

    private fun clickTriggers(player: Player, regionId: String, slot: Int) {
        val region = plugin.publishing().editable(regionId) ?: return openMain(player)
        if (slot == 49) {
            return openDetail(player, regionId)
        }
        if (slot in 9..35) {
            val trigger = region.triggers.keys.toList().getOrNull(slot - 9) ?: return
            val triggers = LinkedHashMap(region.triggers)
            triggers.remove(trigger)
            return saveAndReopen(player, region.copy(triggers = triggers)) { openTriggers(player, region.id) }
        }
        val block = when (slot) {
            37 -> RegionTrigger.ON_ENTER to ActionBlockConfig("gui-enter-message", thenActions = listOf(ActionConfig("message", mapOf("text" to "欢迎进入 {region_name}。"))))
            38 -> RegionTrigger.ON_MODE_START to ActionBlockConfig("gui-start-title", thenActions = listOf(ActionConfig("title", mapOf("title" to "开始!", "subtitle" to "{region_name}"))))
            39 -> RegionTrigger.ON_FINISH to ActionBlockConfig("gui-finish-broadcast", thenActions = listOf(ActionConfig("broadcast", mapOf("text" to "{player} 完成了 {region_name}，名次 #{rank}。"))))
            41 -> return promptTriggerAction(player, region)
            43 -> return saveAndReopen(player, region.copy(triggers = emptyMap())) { openTriggers(player, region.id) }
            else -> return
        }
        saveAndReopen(player, region.copy(triggers = appendTrigger(region.triggers, block.first, block.second))) { openTriggers(player, region.id) }
    }

    private fun promptCreateRegion(player: Player) {
        promptLine(player, "§a请输入新区域 ID。只能使用字母、数字、下划线和短横线。输入 cancel 取消。") { rawId ->
            val id = rawId.trim().lowercase(Locale.ROOT)
            if (!id.matches(Regex("[a-z0-9_-]{2,48}"))) {
                plugin.lang().sendRaw(player, "§c区域 ID 格式不正确。")
                openMain(player)
                return@promptLine
            }
            if (plugin.publishing().editable(id) != null) {
                plugin.lang().sendRaw(player, "§c区域 $id 已存在。")
                openMain(player)
                return@promptLine
            }
            promptLine(player, "§a请输入区域显示名称。输入 - 使用 ID 作为名称。") { rawName ->
                val name = rawName.trim().takeUnless { it == "-" || it.isBlank() } ?: id
                openOwnedAreas(player, OwnedAreaContext(OwnedAreaPurpose.CREATE, id, name))
            }
        }
    }

    private fun openOwnedAreas(player: Player, context: OwnedAreaContext) {
        val source = plugin.sources().find("lands")
        if (source == null || !source.isAvailable()) {
            plugin.lang().sendRaw(player, "§cLands 当前不可用，无法读取你拥有的 area。")
            return if (context.purpose == OwnedAreaPurpose.BIND) openSource(player, context.targetId) else openMain(player)
        }
        val options = source.getOwnedRegions(player.uniqueId)
        val pageCount = ((options.size + OWNED_AREA_PAGE_SIZE - 1) / OWNED_AREA_PAGE_SIZE).coerceAtLeast(1)
        val page = context.page.coerceIn(0, pageCount - 1)
        val shownContext = context.copy(page = page)
        val holderRegion = context.targetId.takeIf { context.purpose == OwnedAreaPurpose.BIND }
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.OWNED_AREAS, holderRegion, shownContext),
            54,
            ui("${Ui.DARK_GREEN}选择 Lands Area ${page + 1}/$pageCount"),
        )
        for ((slot, option) in options.drop(page * OWNED_AREA_PAGE_SIZE).take(OWNED_AREA_PAGE_SIZE).withIndex()) {
            val land = option.values["land"] ?: continue
            val area = option.values["area"] ?: "default"
            val item = named(Material.GRASS_BLOCK, "${Ui.GREEN}${option.name}", listOf(
                "${Ui.GRAY}Land: $land",
                "${Ui.GRAY}Area: $area",
                "${Ui.YELLOW}点击选择；保存前会再次检查主人身份。",
            ))
            item.itemMeta?.let { meta ->
                meta.persistentDataContainer.set(landKey(), PersistentDataType.STRING, land)
                meta.persistentDataContainer.set(areaKey(), PersistentDataType.STRING, area)
                item.itemMeta = meta
            }
            inventory.setItem(slot, item)
        }
        if (options.isEmpty()) {
            inventory.setItem(22, named(Material.BARRIER, "${Ui.RED}没有可用的 Lands area", listOf(
                "${Ui.GRAY}只有当前主人为你、且你同时拥有统治者权限的 area 才能使用。",
            )))
        }
        if (page > 0) inventory.setItem(45, named(Material.ARROW, "${Ui.YELLOW}上一页"))
        inventory.setItem(49, named(Material.MAP, "${Ui.AQUA}${options.size} 个可用 area", listOf(
            "${Ui.GRAY}页面 ${page + 1}/$pageCount",
        )))
        if (page + 1 < pageCount) inventory.setItem(53, named(Material.ARROW, "${Ui.YELLOW}下一页"))
        inventory.setItem(50, named(Material.BARRIER, "${Ui.RED}返回"))
        player.openInventory(inventory)
    }

    private fun clickOwnedArea(player: Player, holder: RegionsHolder, item: ItemStack?, slot: Int) {
        val context = holder.ownedArea ?: return openMain(player)
        when (slot) {
            45 -> return openOwnedAreas(player, context.copy(page = context.page - 1))
            50 -> return if (context.purpose == OwnedAreaPurpose.BIND) openSource(player, context.targetId) else openMain(player)
            53 -> return openOwnedAreas(player, context.copy(page = context.page + 1))
        }
        if (slot !in 0 until OWNED_AREA_PAGE_SIZE) return
        val meta = item?.itemMeta ?: return
        val land = meta.persistentDataContainer.get(landKey(), PersistentDataType.STRING) ?: return
        val area = meta.persistentDataContainer.get(areaKey(), PersistentDataType.STRING) ?: return
        val ref = RegionSourceRef("lands", linkedMapOf("land" to land, "area" to area))
        if (!allow(player, plugin.authority().canCreate(player, ref))) {
            plugin.lang().sendRaw(player, "§e该 area 的主人身份可能刚刚发生变化，列表已刷新。")
            return openOwnedAreas(player, context)
        }
        when (context.purpose) {
            OwnedAreaPurpose.CREATE -> {
                if (plugin.publishing().editable(context.targetId) != null) {
                    plugin.lang().sendRaw(player, "§c区域 ${context.targetId} 已存在，请重新创建。")
                    return openMain(player)
                }
                openTemplates(player, TemplateContext(context.targetId, context.targetName, ref))
            }
            OwnedAreaPurpose.BIND -> {
                val region = plugin.publishing().editable(context.targetId) ?: return openMain(player)
                saveAndReopen(player, region.copy(source = ref, ownerPolicy = OwnerPolicy.LANDS_OWNER)) {
                    openSource(player, region.id)
                }
            }
        }
    }

    private fun openTemplates(player: Player, context: TemplateContext) {
        val templates = plugin.templates().all()
        val pageCount = ((templates.size + TEMPLATE_PAGE_SIZE - 1) / TEMPLATE_PAGE_SIZE).coerceAtLeast(1)
        val page = context.page.coerceIn(0, pageCount - 1)
        val shownContext = context.copy(page = page)
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.TEMPLATES, ownedArea = null, template = shownContext),
            54,
            ui("${Ui.DARK_GREEN}选择场地模板 ${page + 1}/$pageCount"),
        )
        for ((slot, template) in templates.drop(page * TEMPLATE_PAGE_SIZE).take(TEMPLATE_PAGE_SIZE).withIndex()) {
            val triggerCount = template.triggers.values.sumOf { it.size }
            val lore = ArrayList<String>()
            lore.add("${Ui.GRAY}${template.description}")
            lore.add("${Ui.DARK_GRAY}将应用以下配置：")
            lore.add("${Ui.GRAY}Mode: ${template.mode?.type ?: "none"}")
            lore.add("${Ui.GRAY}Flags: ${template.flags.keys.joinToString(", ").ifBlank { "无" }}")
            lore.add("${Ui.GRAY}Effects: ${template.effects.joinToString(", ") { it.type }.ifBlank { "无" }}")
            lore.add("${Ui.GRAY}Triggers: $triggerCount")
            if (template.parameters.isNotEmpty()) lore.add("${Ui.YELLOW}参数: ${template.parameters.keys.joinToString(", ")}")
            lore.add("${Ui.GREEN}点击验证并创建场地。")
            val item = named(templateMaterial(template.mode?.type), "${Ui.AQUA}${template.name}", lore)
            item.itemMeta?.let { meta ->
                meta.persistentDataContainer.set(templateKey(), PersistentDataType.STRING, template.id)
                item.itemMeta = meta
            }
            inventory.setItem(slot, item)
        }
        if (templates.isEmpty()) {
            inventory.setItem(22, named(Material.BARRIER, "${Ui.RED}没有可用模板", listOf(
                "${Ui.GRAY}请检查 templates.yml。",
            )))
        }
        if (page > 0) inventory.setItem(45, named(Material.ARROW, "${Ui.YELLOW}上一页"))
        inventory.setItem(49, named(Material.BOOK, "${Ui.AQUA}${templates.size} 个模板", listOf(
            "${Ui.GRAY}页面 ${page + 1}/$pageCount",
            "${Ui.GRAY}模板会先经过能力 schema 和完整 Region 校验。",
        )))
        inventory.setItem(50, named(Material.BARRIER, "${Ui.RED}返回选择 Area"))
        if (page + 1 < pageCount) inventory.setItem(53, named(Material.ARROW, "${Ui.YELLOW}下一页"))
        player.openInventory(inventory)
    }

    private fun clickTemplate(player: Player, holder: RegionsHolder, item: ItemStack?, slot: Int) {
        val context = holder.template ?: return openMain(player)
        when (slot) {
            45 -> return openTemplates(player, context.copy(page = context.page - 1))
            50 -> return openOwnedAreas(player, OwnedAreaContext(OwnedAreaPurpose.CREATE, context.targetId, context.targetName))
            53 -> return openTemplates(player, context.copy(page = context.page + 1))
        }
        if (slot !in 0 until TEMPLATE_PAGE_SIZE) return
        val templateId = item?.itemMeta?.persistentDataContainer?.get(templateKey(), PersistentDataType.STRING) ?: return
        if (!allow(player, plugin.authority().canCreate(player, context.source))) {
            plugin.lang().sendRaw(player, "§e该 Lands area 的主人身份已变化，请重新选择。")
            return openOwnedAreas(player, OwnedAreaContext(OwnedAreaPurpose.CREATE, context.targetId, context.targetName))
        }
        if (plugin.publishing().editable(context.targetId) != null) {
            plugin.lang().sendRaw(player, "§c区域 ${context.targetId} 已存在，请重新创建。")
            return openMain(player)
        }
        val base = RegionDefinition(
            id = context.targetId,
            name = context.targetName,
            source = context.source,
            ownerPolicy = OwnerPolicy.LANDS_OWNER,
            mode = ModeConfig("free_event"),
        )
        val applied = plugin.templates().apply(templateId, base)
        val region = applied.region
        if (!applied.success || region == null) {
            plugin.lang().sendRaw(player, "§c模板无法应用: ${applied.errors.joinToString("; ")}")
            return openTemplates(player, context)
        }
        saveAndReopen(player, region) { openDetail(player, region.id) }
    }

    private fun promptBindCuboid(player: Player, region: RegionDefinition) {
        promptLine(player, "§a请输入 Cuboid: world,minX,minY,minZ,maxX,maxY,maxZ。输入 cancel 取消。") { raw ->
            val parts = raw.split(',', ' ', ';').map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size != 7 || parts.drop(1).any { it.toDoubleOrNull() == null }) {
                plugin.lang().sendRaw(player, "§c格式错误，需要 world,minX,minY,minZ,maxX,maxY,maxZ。")
                openSource(player, region.id)
                return@promptLine
            }
            val source = RegionSourceRef("cuboid", linkedMapOf(
                "id" to region.id,
                "name" to region.name,
                "world" to parts[0],
                "min-x" to parts[1],
                "min-y" to parts[2],
                "min-z" to parts[3],
                "max-x" to parts[4],
                "max-y" to parts[5],
                "max-z" to parts[6],
            ))
            saveAndReopen(player, region.copy(source = source, ownerPolicy = OwnerPolicy.ADMIN)) { openSource(player, region.id) }
        }
    }

    private fun promptRename(player: Player, region: RegionDefinition) {
        promptLine(player, "§a请输入新的区域显示名称。输入 cancel 取消。") { raw ->
            val name = raw.trim()
            if (name.isBlank()) {
                plugin.lang().sendRaw(player, "§c名称不能为空。")
                openSource(player, region.id)
                return@promptLine
            }
            saveAndReopen(player, region.copy(name = name)) { openSource(player, region.id) }
        }
    }

    private fun promptDeleteRegion(player: Player, region: RegionDefinition) {
        promptLine(player, "§c确认删除区域 ${region.id}？请输入区域 ID 确认，或输入 cancel 取消。") { raw ->
            if (!raw.trim().equals(region.id, ignoreCase = true)) {
                plugin.lang().sendRaw(player, "§7删除已取消。")
                openDetail(player, region.id)
                return@promptLine
            }
            if (!canManageRegion(player, region)) return@promptLine
            plugin.sessions().cleanupRegionAll(region.id, "gui-delete-${region.id}")
            val result = plugin.regions().remove(region.id)
            if (!result.success) {
                plugin.lang().sendRaw(player, "§c删除失败: ${result.reason}")
            } else {
                plugin.audit().record(player, region.id, "region.remove", "gui")
                plugin.lang().sendRaw(player, "§a已删除区域 ${region.id}。")
            }
            openMain(player)
        }
    }

    private fun promptModeValue(player: Player, region: RegionDefinition) {
        promptLine(player, "§a请输入模式参数 key=value，或 clear <key> 删除。输入 cancel 取消。") { raw ->
            val args = splitArgs(raw)
            val mode = region.mode ?: ModeConfig("free_event")
            val values = LinkedHashMap(mode.values)
            if (args.size == 2 && args[0].equals("clear", ignoreCase = true)) {
                values.remove(args[1])
            } else {
                val pair = args.firstOrNull()?.let { parsePair(it) }
                if (pair == null) {
                    plugin.lang().sendRaw(player, "§c请输入 key=value。")
                    openMode(player, region.id)
                    return@promptLine
                }
                values[pair.first] = pair.second
            }
            saveAndReopen(player, region.copy(mode = mode.copy(values = values))) { openMode(player, region.id) }
        }
    }

    private fun promptFlag(player: Player, region: RegionDefinition) {
        promptLine(player, "§a请输入 <flag> <值> [key=value...]，例如 commands blocklist values=/spawn,/home。") { raw ->
            val args = splitArgs(raw)
            if (args.size < 2) {
                plugin.lang().sendRaw(player, "§c请输入 <flag> <值>。")
                openFlags(player, region.id)
                return@promptLine
            }
            val flags = LinkedHashMap(region.flags)
            val flag = args[0].lowercase(Locale.ROOT)
            val value = args[1].lowercase(Locale.ROOT)
            if (value == "pass" || value == "clear") {
                flags.remove(flag)
            } else {
                flags[flag] = FlagConfig(flag, value, parsePairs(args.drop(2)))
            }
            saveAndReopen(player, region.copy(flags = flags)) { openFlags(player, region.id) }
        }
    }

    private fun promptEffect(player: Player, region: RegionDefinition) {
        promptLine(player, "§a请输入 <effect> [key=value...]；可用 combination=highest_priority|exclusive|stack|merge_by_type。") { raw ->
            val args = splitArgs(raw)
            if (args.isEmpty()) {
                plugin.lang().sendRaw(player, "§cEffect 类型不能为空。")
                openEffects(player, region.id)
                return@promptLine
            }
            val values = parsePairs(args.drop(1))
            val scope = parseEffectScope(values.remove("scope"))
            val combination = parseEffectCombination(values.remove("combination"))
            val effects = ArrayList(region.effects)
            effects.add(EffectConfig(args[0].lowercase(Locale.ROOT), scope, values, combination))
            saveAndReopen(player, region.copy(effects = effects)) { openEffects(player, region.id) }
        }
    }

    private fun promptTriggerAction(player: Player, region: RegionDefinition) {
        promptLine(player, "§a请输入 <trigger> <action> [key=value...]；execution=primary 可限制为主 Region 执行。") { raw ->
            val args = splitArgs(raw)
            if (args.size < 2) {
                plugin.lang().sendRaw(player, "§c请输入 <trigger> <action>。")
                openTriggers(player, region.id)
                return@promptLine
            }
            val trigger = RegionTrigger.fromKey(args[0])
            if (trigger == null) {
                plugin.lang().sendRaw(player, "§c未知 trigger: ${args[0]}")
                openTriggers(player, region.id)
                return@promptLine
            }
            val values = parsePairs(args.drop(2))
            val execution = when (values.remove("execution")?.lowercase(Locale.ROOT)) {
                "primary", "primary_region", "primary-region" -> TriggerExecution.PRIMARY_REGION
                else -> TriggerExecution.ALL_ACTIVE
            }
            val action = ActionConfig(args[1].lowercase(Locale.ROOT), values)
            val block = ActionBlockConfig(
                "gui-${trigger.key}-${action.type}",
                thenActions = listOf(action),
                execution = execution,
            )
            saveAndReopen(player, region.copy(triggers = appendTrigger(region.triggers, trigger, block))) { openTriggers(player, region.id) }
        }
    }

    private fun promptLine(player: Player, prompt: String, onSubmit: (String) -> Unit) {
        pendingInputs[player.uniqueId] = PendingInput(onSubmit)
        player.closeInventory()
        plugin.lang().sendRaw(player, prompt)
        plugin.lang().sendRaw(player, "§7这条消息不会发到公共聊天。输入 cancel 取消。")
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val pending = pendingInputs.remove(event.player.uniqueId) ?: return
        event.isCancelled = true
        val message = PlainTextComponentSerializer.plainText().serialize(event.message())
        plugin.regionScheduler().runAtEntity(event.player, Runnable {
            if (message.trim().equals("cancel", ignoreCase = true) || message.trim() == "取消") {
                plugin.lang().sendRaw(event.player, "§7已取消。")
                openMain(event.player)
                return@Runnable
            }
            pending.onSubmit(message)
        })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        pendingInputs.remove(event.player.uniqueId)
    }

    private fun saveAndReopen(player: Player, region: RegionDefinition, reopen: () -> Unit) {
        val existing = plugin.publishing().editable(region.id)
        if (existing != null && !canManageRegion(player, existing)) return
        val candidate = withOwnerSnapshot(region)
        if (!canManageRegion(player, candidate)) return
        val result = if (existing == null) {
            plugin.publishing().createDraft(player, candidate)
        } else {
            plugin.publishing().saveDraft(player, candidate)
        }
        if (!result.success) {
            plugin.lang().sendRaw(player, "§c操作失败: ${result.reason}")
            return
        }
        plugin.lang().sendRaw(player, "§a已保存区域 ${region.id} 的草稿。")
        reopen()
    }

    private fun sendRevisionHistory(player: Player, region: RegionDefinition) {
        val revisions = plugin.publishing().history(region.id)
        plugin.lang().sendRaw(player, "§6${region.id} 的已发布版本 (${revisions.size})")
        if (revisions.isEmpty()) plugin.lang().sendRaw(player, "§7暂无已发布历史。")
        revisions.take(20).forEach { snapshot ->
            plugin.lang().sendRaw(
                player,
                "§7- §e#${snapshot.revision} §f${snapshot.name} §8mode=${snapshot.mode?.type ?: "none"}",
            )
        }
        plugin.lang().sendRaw(player, "§7回滚命令: /regions rollback ${region.id} <revision>")
    }

    private fun withOwnerSnapshot(region: RegionDefinition): RegionDefinition {
        val metadata = LinkedHashMap(region.metadata)
        val ownerId = plugin.sources().find(region.source.type)?.ownerId(region.source)
        if (ownerId == null) metadata.remove(org.cubexmc.regions.service.RegionAuthorityService.SOURCE_OWNER_METADATA)
        else metadata[org.cubexmc.regions.service.RegionAuthorityService.SOURCE_OWNER_METADATA] = ownerId.toString()
        return region.copy(metadata = metadata)
    }

    private fun saveModeValue(player: Player, region: RegionDefinition, values: Map<String, String>) {
        val mode = region.mode ?: ModeConfig("free_event")
        saveAndReopen(player, region.copy(mode = mode.copy(values = values))) { openMode(player, region.id) }
    }

    private fun adjustInt(values: Map<String, String>, key: String, default: Int, subtract: Boolean, min: Int, removeAtZero: Boolean = false, step: Int = 1): Map<String, String> {
        val next = ((values[key]?.toIntOrNull() ?: default) + if (subtract) -step else step).coerceAtLeast(min)
        return LinkedHashMap(values).apply {
            if (removeAtZero && next <= 0) {
                remove(key)
            } else {
                this[key] = next.toString()
            }
        }
    }

    private fun toggleBool(values: Map<String, String>, key: String, default: Boolean): Map<String, String> =
        LinkedHashMap(values).apply {
            this[key] = (!(this[key]?.toBooleanStrictOrNull() ?: default)).toString()
        }

    private fun adjustDouble(values: Map<String, String>, key: String, default: Double, subtract: Boolean, min: Double): Map<String, String> {
        val next = ((values[key]?.toDoubleOrNull() ?: default) + if (subtract) -1.0 else 1.0).coerceAtLeast(min)
        return LinkedHashMap(values).apply { this[key] = "%.1f".format(Locale.ROOT, next) }
    }

    private fun cycleVehicle(values: Map<String, String>, key: String, default: String): Map<String, String> {
        val options = listOf("none", "any", "boat", "horse", "minecart", "pass")
        val current = values[key] ?: default
        val index = options.indexOfFirst { it.equals(current, ignoreCase = true) }
        val next = options[(if (index < 0) 0 else index + 1) % options.size]
        return LinkedHashMap(values).apply {
            if (next == default && key != "vehicle") {
                remove(key)
            } else {
                this[key] = next
            }
        }
    }

    private fun toggleJudge(values: Map<String, String>, player: Player): Map<String, String> {
        val judges = values["judges"]
            ?.split(',', ';')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toMutableList()
            ?: ArrayList()
        val hadSelf = judges.any { it.equals(player.name, ignoreCase = true) || it.equals(player.uniqueId.toString(), ignoreCase = true) }
        judges.removeIf { it.equals(player.name, ignoreCase = true) || it.equals(player.uniqueId.toString(), ignoreCase = true) }
        if (!hadSelf) {
            judges.add(player.name)
        }
        return LinkedHashMap(values).apply {
            if (judges.isEmpty()) remove("judges") else this["judges"] = judges.joinToString(",")
        }
    }

    private fun sendValidation(player: Player, region: RegionDefinition) {
        val issues = plugin.validation().validate(region)
        if (issues.isEmpty()) {
            plugin.lang().send(player, "validate-ok")
        } else {
            plugin.lang().send(player, "validate-header", mapOf("count" to issues.size.toString()))
            for (issue in issues) {
                plugin.lang().send(player, "validate-line", mapOf("id" to issue.regionId, "severity" to issue.severity.name, "message" to issue.message))
            }
        }
    }

    private fun cuboidFromCurrentChunk(player: Player, id: String): RegionSourceRef {
        val chunk = player.location.chunk
        val world = player.world
        val minX = chunk.x * 16
        val minZ = chunk.z * 16
        return RegionSourceRef("cuboid", linkedMapOf(
            "id" to id,
            "name" to id,
            "world" to world.name,
            "min-x" to minX.toString(),
            "min-y" to world.minHeight.toString(),
            "min-z" to minZ.toString(),
            "max-x" to (minX + 15).toString(),
            "max-y" to world.maxHeight.toString(),
            "max-z" to (minZ + 15).toString(),
        ))
    }

    private fun appendTrigger(
        current: Map<RegionTrigger, List<ActionBlockConfig>>,
        trigger: RegionTrigger,
        block: ActionBlockConfig,
    ): Map<RegionTrigger, List<ActionBlockConfig>> =
        LinkedHashMap(current).apply {
            this[trigger] = (this[trigger] ?: emptyList()) + block
        }

    private fun parseEffectScope(value: String?): EffectScope =
        when (value?.lowercase(Locale.ROOT)) {
            "until_mode_end", "until-mode-end", "mode" -> EffectScope.UNTIL_MODE_END
            "timed", "time" -> EffectScope.TIMED
            else -> EffectScope.WHILE_INSIDE
        }

    private fun parseEffectCombination(value: String?): EffectCombination =
        when (value?.lowercase(Locale.ROOT)) {
            "exclusive" -> EffectCombination.EXCLUSIVE
            "stack" -> EffectCombination.STACK
            "merge_by_type", "merge-by-type", "merge" -> EffectCombination.MERGE_BY_TYPE
            else -> EffectCombination.HIGHEST_PRIORITY
        }

    private fun parsePairs(args: List<String>): MutableMap<String, String> {
        val values = LinkedHashMap<String, String>()
        for (arg in args) {
            val pair = parsePair(arg) ?: continue
            values[pair.first] = pair.second
        }
        return values
    }

    private fun parsePair(raw: String): Pair<String, String>? {
        val index = raw.indexOf('=')
        if (index <= 0) {
            return null
        }
        val key = raw.substring(0, index).trim()
        if (key.isBlank()) {
            return null
        }
        return key to raw.substring(index + 1).trim()
    }

    private fun splitArgs(raw: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (char in raw) {
            when {
                quote != null && char == quote -> quote = null
                quote == null && (char == '"' || char == '\'') -> quote = char
                quote == null && char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.setLength(0)
                    }
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }

    private fun regionItem(region: RegionDefinition): ItemStack {
        val material = if (region.enabled) Material.EMERALD_BLOCK else Material.COAL_BLOCK
        val item = named(
            material,
            "${Ui.YELLOW}${region.name}",
            listOf(
                "${Ui.GRAY}ID: ${region.id}",
                "${Ui.GRAY}Source: ${region.source.describe()}",
                "${Ui.GRAY}Lifecycle: ${region.lifecycle.name.lowercase(Locale.ROOT)} · r${region.revision}",
                "${Ui.GRAY}Mode: ${region.mode?.type ?: "none"}",
                "${Ui.GRAY}Flags: ${region.flags.size}",
                "${Ui.GRAY}Effects: ${region.effects.size}",
            ),
        )
        val meta = item.itemMeta
        if (meta != null) {
            meta.persistentDataContainer.set(regionKey(), PersistentDataType.STRING, region.id)
            item.itemMeta = meta
        }
        return item
    }

    private fun modeItem(type: String, current: String?, extraLore: List<String> = emptyList()): ItemStack {
        val active = type.equals(current, ignoreCase = true)
        val material = when (type) {
            "dual_pvp" -> Material.IRON_SWORD
            "union_war" -> Material.SHIELD
            "run_race" -> Material.LEATHER_BOOTS
            "boat_race" -> Material.OAK_BOAT
            "horse_race" -> Material.SADDLE
            "hide_and_seek" -> Material.ENDER_EYE
            else -> Material.CAMPFIRE
        }
        val lore = ArrayList<String>()
        lore.add(if (active) "${Ui.GREEN}当前模式" else "${Ui.GRAY}点击切换到此模式")
        lore.addAll(extraLore)
        return named(material, "${if (active) Ui.GREEN else Ui.YELLOW}${type}", lore)
    }

    private fun templateMaterial(type: String?): Material = when (type?.lowercase(Locale.ROOT)) {
        "dual_pvp", "union_war" -> Material.DIAMOND_SWORD
        "run_race" -> Material.LEATHER_BOOTS
        "boat_race" -> Material.OAK_BOAT
        "horse_race" -> Material.SADDLE
        "hide_and_seek" -> Material.ENDER_EYE
        else -> Material.CAMPFIRE
    }

    private fun flagItem(flag: String, value: String): ItemStack {
        val normalized = value.lowercase(Locale.ROOT)
        val material = when (normalized) {
            "deny" -> Material.RED_CONCRETE
            "allow" -> Material.LIME_CONCRETE
            else -> Material.GRAY_CONCRETE
        }
        return named(material, "${Ui.YELLOW}${flag}: ${statusColor(normalized)}${normalized}", listOf(
            "${Ui.GRAY}点击循环: pass -> deny -> allow",
            "${Ui.DARK_GRAY}pass 表示不覆盖 Lands 或服务器规则。",
        ))
    }

    private fun effectItem(index: Int, effect: EffectConfig): ItemStack =
        named(Material.BLAZE_POWDER, "${Ui.YELLOW}${index + 1}. ${effect.type}", listOf(
            "${Ui.GRAY}scope: ${effect.scope.name.lowercase(Locale.ROOT)}",
            "${Ui.GRAY}combination: ${effect.combination.name.lowercase(Locale.ROOT)}",
            "${Ui.GRAY}${effect.values.entries.joinToString(", ") { "${it.key}=${it.value}" }.ifBlank { "无参数" }}",
            "${Ui.RED}点击移除此 effect。",
        ))

    private fun named(material: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        if (meta != null) {
            meta.displayName(ui(name))
            meta.lore(lore.map { ui(it) })
            item.itemMeta = meta
        }
        return item
    }

    private fun ui(value: String): Component = PaperText.parse(value)

    private fun formatLocation(location: org.bukkit.Location): String =
        "${location.world?.name ?: "world"},${"%.2f".format(Locale.ROOT, location.x)},${"%.2f".format(Locale.ROOT, location.y)},${"%.2f".format(Locale.ROOT, location.z)},${"%.2f".format(Locale.ROOT, location.yaw)},${"%.2f".format(Locale.ROOT, location.pitch)}"

    private fun checkpointCount(raw: String?): Int =
        raw?.split(';')?.count { it.isNotBlank() } ?: 0

    private fun defaultVehicle(type: String): String =
        when (type.lowercase(Locale.ROOT)) {
            "boat_race" -> "boat"
            "horse_race" -> "horse"
            "run_race" -> "none"
            else -> "pass"
        }

    private fun describeVehicle(value: String): String =
        when (value.lowercase(Locale.ROOT)) {
            "none", "on_foot", "on-foot", "no_vehicle", "no-vehicle", "foot" -> "步行"
            "any", "vehicle", "any_vehicle", "any-vehicle" -> "任意载具"
            "boat" -> "船"
            "horse" -> "马"
            "minecart" -> "矿车"
            "pass", "ignore", "any_state", "any-state" -> "不检查"
            else -> value
        }

    private fun defaultModeValues(type: String): Map<String, String> =
        when (type) {
            "dual_pvp" -> linkedMapOf("min-players" to "2", "require-ready" to "true", "replace-gear" to "true")
            "union_war" -> linkedMapOf("min-players" to "2", "min-unions" to "2", "require-ready" to "true", "replace-gear" to "true")
            "run_race", "boat_race", "horse_race" -> linkedMapOf("min-players" to "1", "start-mode" to "vote", "require-start" to "true", "radius" to "2.5", "timeout-seconds" to "300", "vehicle" to defaultVehicle(type))
            "hide_and_seek" -> linkedMapOf("min-players" to "2", "start-mode" to "vote", "hide-seconds" to "30", "round-seconds" to "300", "found-becomes-seeker" to "true")
            else -> emptyMap()
        }

    private fun modeConfigurationSlots(type: String): Set<Int> =
        when (type.lowercase(Locale.ROOT)) {
            "dual_pvp" -> setOf(19, 20, 21, 22, 25, 28, 29, 30, 31, 33, 34)
            "union_war" -> setOf(19, 20, 21, 22, 23, 25, 28, 29, 30, 31, 33, 34)
            "run_race", "boat_race", "horse_race" ->
                setOf(19, 20, 24, 26, 32, 36, 37, 38, 39, 40, 41, 42, 43, 44, 51)
            "hide_and_seek" -> setOf(19, 20, 21, 22, 25, 33, 34, 42, 44, 45, 46, 47, 50)
            else -> emptySet()
        }

    private fun nextFlagValue(value: String): String =
        when (value.lowercase(Locale.ROOT)) {
            "pass" -> "deny"
            "deny" -> "allow"
            else -> "pass"
        }

    private fun statusColor(value: String): String =
        when (value) {
            "deny" -> Ui.RED
            "allow" -> Ui.GREEN
            else -> Ui.GRAY
        }

    private fun canEnterManagement(player: Player): Boolean =
        allow(player, plugin.authority().canEnterManagement(player))

    private fun canManageRegion(player: Player, region: RegionDefinition): Boolean =
        allow(player, plugin.authority().canManage(player, region))

    private fun allow(player: Player, decision: AuthorityDecision): Boolean {
        if (decision.allowed) return true
        plugin.lang().send(player, decision.denial?.messageKey ?: "no-permission")
        return false
    }

    private class RegionsHolder(
        val view: View,
        val regionId: String? = null,
        val ownedArea: OwnedAreaContext? = null,
        val template: TemplateContext? = null,
    ) : InventoryHolder {
        override fun getInventory(): Inventory = Bukkit.createInventory(null, 9)
    }

    private class PendingInput(val onSubmit: (String) -> Unit)

    private enum class View {
        MAIN,
        DETAIL,
        SOURCE,
        MODE,
        FLAGS,
        EFFECTS,
        TRIGGERS,
        PUBLISH_PREVIEW,
        OWNED_AREAS,
        TEMPLATES,
    }

    private enum class OwnedAreaPurpose { CREATE, BIND }

    private data class OwnedAreaContext(
        val purpose: OwnedAreaPurpose,
        val targetId: String,
        val targetName: String,
        val page: Int = 0,
    )

    private data class TemplateContext(
        val targetId: String,
        val targetName: String,
        val source: RegionSourceRef,
        val page: Int = 0,
    )

    private fun regionKey(): NamespacedKey = NamespacedKey(plugin, "region_id")

    private fun landKey(): NamespacedKey = NamespacedKey(plugin, "owned_land")

    private fun areaKey(): NamespacedKey = NamespacedKey(plugin, "owned_area")

    private fun templateKey(): NamespacedKey = NamespacedKey(plugin, "region_template")

    companion object {
        private const val OWNED_AREA_PAGE_SIZE = 45
        private const val TEMPLATE_PAGE_SIZE = 45
        private val MODE_CONFIGURATION_SLOTS =
            setOf(19, 20, 21, 22, 23, 24, 25, 26, 28, 29, 30, 31, 32, 33, 34, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 50, 51)
        private val FLAG_SLOTS = linkedMapOf(
            10 to "pvp",
            11 to "fly",
            12 to "vanish",
            13 to "item_drop",
            14 to "item_pickup",
            15 to "block_break",
            16 to "block_place",
            19 to "vehicle_enter",
            20 to "commands",
            21 to "teleport_out",
        )
    }

    private object Ui {
        const val DARK_GREEN = "&2"
        const val AQUA = "&b"
        const val GRAY = "&7"
        const val GREEN = "&a"
        const val YELLOW = "&e"
        const val RED = "&c"
        const val DARK_GRAY = "&8"
    }
}
