package org.cubexmc.contract.gui

import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/** Shared low-level item builders used by the contract menus and the renderer. */

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
