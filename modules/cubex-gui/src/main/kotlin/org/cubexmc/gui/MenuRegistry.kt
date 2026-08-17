package org.cubexmc.gui

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

/**
 * Tracks the open [Menu] per player and routes inventory events to it (AuctionHouse `GUIManager`
 * style). Replaces title-string matching plus central slot dispatch.
 */
class MenuRegistry : Listener {
    private val open: MutableMap<UUID, Menu> = HashMap()

    fun open(player: Player, menu: Menu) {
        open[player.uniqueId] = menu
        player.openInventory(menu.inventory)
    }

    /** The menu this player currently has open, or null. Exposed for reload/shutdown handling. */
    fun openMenu(playerId: UUID): Menu? = open[playerId]

    fun closeAll() {
        for (playerId in ArrayList(open.keys)) {
            Bukkit.getPlayer(playerId)?.closeInventory()
        }
        open.clear()
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val menu = open[player.uniqueId] ?: return
        if (event.view.topInventory !== menu.inventory) {
            return
        }
        event.isCancelled = true
        val slot = event.rawSlot
        if (slot < 0 || slot >= event.view.topInventory.size) {
            return
        }
        menu.handleClick(event)
    }

    /** Nothing in a menu is ever meant to move, so a drag over its slots is refused outright. */
    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val menu = open[player.uniqueId] ?: return
        if (event.view.topInventory === menu.inventory) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val playerId = event.player.uniqueId
        val menu = open[playerId] ?: return
        // Only drop tracking on a genuine close; a synchronous reopen has already swapped in a new menu.
        if (menu.inventory === event.inventory) {
            open.remove(playerId)
            menu.onClose()
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        open.remove(event.player.uniqueId)
    }
}
