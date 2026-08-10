package org.cubexmc.regions.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.service.RegionOverlapResolver

/** The region list, the per-region hub, and the source binding page. */
internal class RegionOverviewMenu(private val gui: RegionsGui) {
    private val plugin get() = gui.plugin
    private val text get() = gui.text
    private val items get() = gui.items

    fun openMain(player: Player) {
        if (!gui.canEnterManagement(player)) return
        val regions = plugin.authority().visibleRegions(player, plugin.regions().all())
            .map { gui.editable(it.id) ?: it }
            .sortedWith(RegionOverlapResolver.REGION_ORDER)
        val inventory = Bukkit.createInventory(RegionsHolder(View.MAIN), 54, text.component("gui.main.title"))
        for ((index, region) in regions.take(45).withIndex()) {
            inventory.setItem(index, items.region(region))
        }
        inventory.setItem(45, text.item(Material.WRITABLE_BOOK, "gui.main.create"))
        if (plugin.authority().isSuperAdmin(player)) {
            inventory.setItem(47, text.item(Material.COMPASS, "gui.main.reload"))
        }
        inventory.setItem(49, text.item(Material.MAP, "gui.main.count", mapOf("count" to regions.size.toString())))
        inventory.setItem(51, text.item(Material.SPYGLASS, "gui.main.doctor"))
        inventory.setItem(53, text.named(GuiIcons.CLOSE, text.text("gui.common.close")))
        player.openInventory(inventory)
    }

    fun clickMain(player: Player, item: ItemStack?, slot: Int) {
        when (slot) {
            53 -> return player.closeInventory()
            45 -> return gui.promptCreateRegion(player)
            47 -> return reload(player)
            51 -> return runDoctor(player)
        }
        val meta = item?.itemMeta ?: return
        val regionId = meta.persistentDataContainer.get(gui.keys.region, PersistentDataType.STRING) ?: return
        openDetail(player, regionId)
    }

    private fun reload(player: Player) {
        if (!gui.allow(player, plugin.authority().canUseGlobalAdministration(player))) return
        plugin.reloadRegions()
        text.send(player, "gui.main.reloaded")
        openMain(player)
    }

    private fun runDoctor(player: Player) {
        val visible = plugin.authority().visibleRegions(player, plugin.regions().all())
            .map { gui.editable(it.id) ?: it }
        val issues = plugin.validation().validateAll(visible)
        if (issues.isEmpty()) {
            text.send(player, "gui.doctor.clean")
            return
        }
        text.send(player, "gui.doctor.found", mapOf("count" to issues.size.toString()))
        for (issue in issues.take(8)) {
            text.send(
                player,
                "gui.doctor.line",
                mapOf("id" to issue.regionId, "severity" to issue.severity.name, "message" to issue.message),
            )
        }
    }

    fun openDetail(player: Player, regionId: String) {
        val region = gui.editable(regionId) ?: return openMain(player)
        if (!gui.canManageRegion(player, region)) return
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.DETAIL, region.id),
            54,
            text.component("gui.detail.title", mapOf("id" to region.id)),
        )
        inventory.setItem(4, items.region(region))
        inventory.setItem(10, text.item(Material.ENDER_EYE, "gui.detail.source", mapOf("source" to region.source.describe())))
        inventory.setItem(
            12,
            text.item(Material.DIAMOND_SWORD, "gui.detail.mode", mapOf("mode" to (region.mode?.type ?: text.text("gui.common.none")))),
        )
        inventory.setItem(14, text.item(Material.OAK_SIGN, "gui.detail.flags", mapOf("count" to region.flags.size.toString())))
        inventory.setItem(16, text.item(Material.BLAZE_POWDER, "gui.detail.effects", mapOf("count" to region.effects.size.toString())))
        inventory.setItem(
            22,
            text.item(GuiIcons.TRIGGER, "gui.detail.triggers", mapOf("count" to region.triggers.values.sumOf { it.size }.toString())),
        )
        inventory.setItem(
            28,
            text.named(
                if (region.enabled) Material.REDSTONE_TORCH else Material.LEVER,
                text.text(if (region.enabled) "gui.detail.disable" else "gui.detail.enable"),
            ),
        )
        inventory.setItem(30, text.item(Material.WRITABLE_BOOK, "gui.detail.validate"))
        inventory.setItem(32, text.item(Material.MILK_BUCKET, "gui.detail.cleanup"))
        inventory.setItem(36, text.item(Material.LAVA_BUCKET, "gui.detail.delete"))
        if (plugin.publishing().draft(region.id) != null) {
            inventory.setItem(
                38,
                text.item(Material.EMERALD, "gui.detail.publish", mapOf("revision" to region.revision.toString())),
            )
        }
        if (plugin.regions().find(region.id)?.lifecycle == RegionLifecycle.PUBLISHED) {
            inventory.setItem(40, text.item(Material.PAPER, "gui.detail.withdraw"))
        }
        inventory.setItem(
            42,
            text.item(Material.BOOK, "gui.detail.history", mapOf("count" to plugin.publishing().history(region.id).size.toString())),
        )
        if (plugin.publishing().draft(region.id) != null) {
            val active = plugin.trials().active(player.uniqueId)?.regionId == region.id
            inventory.setItem(
                44,
                text.item(
                    if (active) Material.REDSTONE_BLOCK else Material.SPYGLASS,
                    if (active) "gui.detail.trial-stop" else "gui.detail.trial-start",
                ),
            )
        }
        inventory.setItem(34, items.back())
        player.openInventory(inventory)
    }

    fun clickDetail(player: Player, regionId: String, slot: Int) {
        val region = gui.editable(regionId) ?: return openMain(player)
        when (slot) {
            10 -> openSource(player, region.id)
            12 -> gui.openMode(player, region.id)
            14 -> gui.openFlags(player, region.id)
            16 -> gui.openEffects(player, region.id)
            22 -> gui.openTriggers(player, region.id)
            28 -> gui.saveAndReopen(player, region.copy(enabled = !region.enabled)) { openDetail(player, regionId) }
            30 -> gui.sendValidation(player, region)
            32 -> {
                val count = plugin.sessions().cleanup(player, "gui-cleanup")
                plugin.lang().send(player, "cleanup-done", mapOf("player" to player.name, "count" to count.toString()))
            }
            34 -> openMain(player)
            36 -> promptDelete(player, region)
            38 -> gui.openPublishPreview(player, region.id)
            40 -> withdraw(player, region)
            42 -> gui.sendRevisionHistory(player, region)
            44 -> toggleTrial(player, region)
        }
    }

    private fun withdraw(player: Player, region: RegionDefinition) {
        val result = plugin.publishing().withdraw(player, region.id)
        if (result.success) {
            text.send(player, "gui.detail.withdrawn", mapOf("id" to region.id))
        } else {
            text.send(player, "gui.detail.withdraw-failed", mapOf("reason" to result.reason))
        }
        openDetail(player, region.id)
    }

    private fun toggleTrial(player: Player, region: RegionDefinition) {
        val active = plugin.trials().active(player.uniqueId)?.regionId == region.id
        val result = if (active) plugin.trials().stop(player, "gui-stop") else plugin.trials().start(player, region.id)
        if (result.success) {
            text.send(player, if (active) "gui.trial.stopped" else "gui.trial.started")
        } else {
            text.send(player, "gui.trial.failed", mapOf("reason" to result.reason))
        }
        openDetail(player, region.id)
    }

    private fun promptDelete(player: Player, region: RegionDefinition) {
        gui.promptLine(player, "gui.prompt.delete", mapOf("id" to region.id)) { raw ->
            if (!raw.trim().equals(region.id, ignoreCase = true)) {
                text.send(player, "gui.delete.cancelled")
                openDetail(player, region.id)
                return@promptLine
            }
            if (!gui.canManageRegion(player, region)) return@promptLine
            plugin.sessions().cleanupRegionAll(region.id, "gui-delete-${region.id}")
            val result = plugin.regions().remove(region.id)
            if (!result.success) {
                text.send(player, "gui.delete.failed", mapOf("reason" to result.reason))
            } else {
                plugin.audit().record(player, region.id, "region.remove", "gui")
                text.send(player, "gui.delete.ok", mapOf("id" to region.id))
            }
            openMain(player)
        }
    }

    fun openSource(player: Player, regionId: String) {
        val region = gui.editable(regionId) ?: return openMain(player)
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.SOURCE, region.id),
            27,
            text.component("gui.source.title", mapOf("id" to region.id)),
        )
        inventory.setItem(4, items.region(region))
        inventory.setItem(10, text.item(Material.GRASS_BLOCK, "gui.source.lands", mapOf("source" to region.source.describe())))
        if (plugin.authority().isSuperAdmin(player)) {
            inventory.setItem(12, text.item(Material.STONE_AXE, "gui.source.cuboid"))
        }
        inventory.setItem(14, text.item(Material.NAME_TAG, "gui.source.rename", mapOf("name" to region.name)))
        inventory.setItem(16, text.item(Material.COMPARATOR, "gui.source.priority", mapOf("priority" to region.priority.toString())))
        inventory.setItem(22, items.back())
        player.openInventory(inventory)
    }

    fun clickSource(player: Player, regionId: String, slot: Int, rightClick: Boolean) {
        val region = gui.editable(regionId) ?: return openMain(player)
        when (slot) {
            10 -> gui.openOwnedAreas(player, OwnedAreaContext(OwnedAreaPurpose.BIND, region.id, region.name))
            12 -> if (rightClick) {
                promptBindCuboid(player, region)
            } else {
                gui.saveAndReopen(player, region.copy(source = GuiValues.cuboidFromCurrentChunk(player, region.id))) {
                    openSource(player, region.id)
                }
            }
            14 -> promptRename(player, region)
            16 -> gui.saveAndReopen(player, region.copy(priority = region.priority + if (rightClick) -1 else 1)) {
                openSource(player, region.id)
            }
            22 -> openDetail(player, regionId)
        }
    }

    private fun promptBindCuboid(player: Player, region: RegionDefinition) {
        gui.promptLine(player, "gui.prompt.cuboid") { raw ->
            val parts = raw.split(',', ' ', ';').map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size != 7 || parts.drop(1).any { it.toDoubleOrNull() == null }) {
                text.send(player, "gui.source.cuboid-format")
                openSource(player, region.id)
                return@promptLine
            }
            val source = org.cubexmc.regions.model.RegionSourceRef(
                "cuboid",
                linkedMapOf(
                    "id" to region.id,
                    "name" to region.name,
                    "world" to parts[0],
                    "min-x" to parts[1],
                    "min-y" to parts[2],
                    "min-z" to parts[3],
                    "max-x" to parts[4],
                    "max-y" to parts[5],
                    "max-z" to parts[6],
                ),
            )
            gui.saveAndReopen(
                player,
                region.copy(source = source, ownerPolicy = org.cubexmc.regions.model.OwnerPolicy.ADMIN),
            ) { openSource(player, region.id) }
        }
    }

    private fun promptRename(player: Player, region: RegionDefinition) {
        gui.promptLine(player, "gui.prompt.rename") { raw ->
            val name = raw.trim()
            if (name.isBlank()) {
                text.send(player, "gui.source.name-blank")
                openSource(player, region.id)
                return@promptLine
            }
            gui.saveAndReopen(player, region.copy(name = name)) { openSource(player, region.id) }
        }
    }
}
