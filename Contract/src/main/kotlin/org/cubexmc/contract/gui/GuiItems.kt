package org.cubexmc.contract.gui

import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/** Shared low-level item builders used by the contract menus and the renderer. */

/**
 * Icons shared by several menus.
 *
 * Every menu click is cancelled, but an icon on display is only ever one unhandled exception away
 * from ending up in a player's inventory, so menus never show a block players cannot legitimately
 * obtain — no barrier, no command block. Keep new icons survival-obtainable for the same reason.
 */
internal object GuiIcons {
    /** Cancel, abandon, delete — an action that destroys something. */
    val DESTRUCTIVE: Material = Material.RED_DYE

    /** A closed or cancelled contract: reached its end, nothing left to do. */
    val INACTIVE: Material = Material.GRAY_DYE
}

/**
 * Names and lore arrive already rendered by the i18n service, so these builders only assemble the
 * stack — there is no colour translation step left to forget.
 */
internal fun button(material: Material, name: String, vararg lore: String): ItemStack =
    named(material, name, lore.toList())

internal fun named(material: Material, name: String, lore: List<String>): ItemStack {
    val item = ItemStack(material)
    val meta = item.itemMeta
    if (meta != null) {
        meta.setDisplayName(name)
        meta.lore = lore
        item.itemMeta = meta
    }
    return item
}

internal fun fillBorder(inventory: Inventory) {
    val pane = button(Material.GRAY_STAINED_GLASS_PANE, " ")
    for (index in 0 until inventory.size) {
        val row = index / 9
        val col = index % 9
        if (row == 0 || row == inventory.size / 9 - 1 || col == 0 || col == 8) {
            inventory.setItem(index, pane)
        }
    }
}
