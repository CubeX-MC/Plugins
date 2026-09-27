package org.cubexmc.gui

import org.bukkit.Bukkit
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * A built inventory screen: holds the [Inventory] plus the per-slot [InventoryButton]s.
 * Build a menu by placing buttons/decorations, then hand it to [MenuRegistry.open].
 */
public class Menu private constructor(public val inventory: Inventory, public val title: String) {
    public constructor(title: String, rows: Int) : this(Bukkit.createInventory(null, rows * 9, title), title)

    /** Adapt an existing holder-based menu without replacing its inventory identity or metadata. */
    public constructor(inventory: Inventory) : this(inventory, "")
    private val buttons: MutableMap<Int, InventoryButton> = HashMap()

    /** Runs when this menu's inventory is closed and not immediately replaced by another menu. */
    public var onClose: () -> Unit = {}

    public fun button(slot: Int, button: InventoryButton) {
        buttons[slot] = button
        inventory.setItem(slot, button.icon)
    }

    public fun button(slot: Int, icon: ItemStack, onClick: (InventoryClickEvent) -> Unit): Unit =
        button(slot, InventoryButton(icon, onClick))

    /** Places an icon with no click behaviour (clicks are still cancelled by the registry). */
    public fun decoration(slot: Int, icon: ItemStack) {
        inventory.setItem(slot, icon)
    }

    public fun handleClick(event: InventoryClickEvent) {
        buttons[event.rawSlot]?.onClick?.invoke(event)
    }
}
