package org.cubexmc.contract.gui.framework

import org.bukkit.Bukkit
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * A built inventory screen: holds the [Inventory] plus the per-slot [InventoryButton]s.
 * Build a menu by placing buttons/decorations, then hand it to [MenuRegistry.open].
 */
class Menu(val title: String, rows: Int) {
    val inventory: Inventory = Bukkit.createInventory(null, rows * 9, title)
    private val buttons: MutableMap<Int, InventoryButton> = HashMap()

    /** Runs when this menu's inventory is closed and not immediately replaced by another menu. */
    var onClose: () -> Unit = {}

    fun button(slot: Int, button: InventoryButton) {
        buttons[slot] = button
        inventory.setItem(slot, button.icon)
    }

    fun button(slot: Int, icon: ItemStack, onClick: (InventoryClickEvent) -> Unit) =
        button(slot, InventoryButton(icon, onClick))

    /** Places an icon with no click behaviour (clicks are still cancelled by the registry). */
    fun decoration(slot: Int, icon: ItemStack) {
        inventory.setItem(slot, icon)
    }

    fun handleClick(event: InventoryClickEvent) {
        buttons[event.rawSlot]?.onClick?.invoke(event)
    }
}
