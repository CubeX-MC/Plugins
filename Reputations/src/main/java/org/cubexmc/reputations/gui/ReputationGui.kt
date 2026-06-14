package org.cubexmc.reputations.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.cubexmc.reputations.ReputationsPlugin
import org.cubexmc.reputations.api.ReputationField
import org.cubexmc.reputations.service.ReputationServiceImpl
import org.cubexmc.reputations.storage.ReputationStore
import org.cubexmc.reputations.util.Colors
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Read-only viewer: shows a player's registered fields and their values, one item per field. */
class ReputationGui(
    private val plugin: ReputationsPlugin,
    private val service: ReputationServiceImpl,
    private val store: ReputationStore,
) : Listener {
    private val open: MutableMap<UUID, Inventory> = HashMap()

    fun open(viewer: Player, targetId: UUID, targetName: String) {
        store.cacheName(targetId, targetName)
        val fields = service.fields().sortedWith(compareBy({ it.namespace() }, { it.id() }))
        val capacity = min(fields.size, MAX_SLOTS)
        val rows = if (capacity == 0) 1 else min(MAX_ROWS, (capacity + 8) / 9)
        val inventory = Bukkit.createInventory(null, rows * 9, title(targetName))
        for ((index, field) in fields.withIndex()) {
            if (index >= rows * 9) {
                break
            }
            inventory.setItem(index, item(field, service.get(targetId, field.key())))
        }
        open[viewer.uniqueId] = inventory
        viewer.openInventory(inventory)
    }

    private fun title(targetName: String): String {
        val prefix = plugin.config.getString("gui.title") ?: "&#F4D03F玩家信誉"
        return Colors.color("$prefix · $targetName")
    }

    private fun item(field: ReputationField, value: Double): ItemStack {
        val material = Material.matchMaterial(field.icon()) ?: Material.PAPER
        val item = ItemStack(material)
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName(Colors.color("&#F4D03F${field.displayName()}"))
            val lore = ArrayList<String>()
            lore.add(Colors.color("&#CFD8DC数值: &#FFFFFF${format(value)}"))
            lore.add(Colors.color("&#CFD8DC来源: &#FFFFFF${field.namespace()}"))
            if (field.description().isNotEmpty()) {
                lore.add(Colors.color("&#9AA5B1${field.description()}"))
            }
            meta.lore = lore
            item.itemMeta = meta
        }
        return item
    }

    private fun format(value: Double): String =
        if (value == Math.rint(value)) value.toLong().toString() else value.toString()

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val inventory = open[player.uniqueId] ?: return
        if (event.view.topInventory === inventory) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val inventory = open[event.player.uniqueId] ?: return
        if (inventory === event.inventory) {
            open.remove(event.player.uniqueId)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        open.remove(event.player.uniqueId)
    }

    private companion object {
        const val MAX_ROWS = 6
        const val MAX_SLOTS = MAX_ROWS * 9
    }
}
