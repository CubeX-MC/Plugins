package org.cubexmc.metro.gui

import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.cubexmc.metro.util.ColorUtil

/** 简化物品创建的工具类。 */
class ItemBuilder @JvmOverloads constructor(material: Material, amount: Int = 1) {
    private val item = ItemStack(material, amount)
    private val meta: ItemMeta? = item.itemMeta

    fun name(name: String): ItemBuilder {
        meta?.setDisplayName(ColorUtil.colorize(name))
        return this
    }

    fun lore(vararg lore: String): ItemBuilder {
        meta?.lore = lore.map { ColorUtil.colorize(it) }
        return this
    }

    fun lore(lore: List<String>): ItemBuilder {
        meta?.lore = lore.map { ColorUtil.colorize(it) }
        return this
    }

    fun glow(): ItemBuilder {
        if (meta != null) {
            meta.addEnchant(Enchantment.DURABILITY, 1, true)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        }
        return this
    }

    fun hideAttributes(): ItemBuilder {
        meta?.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        return this
    }

    fun build(): ItemStack {
        if (meta != null) item.itemMeta = meta
        return item
    }
}
