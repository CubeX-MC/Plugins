package org.cubexmc.gui

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.OfflinePlayer
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType

/**
 * How display text is turned into what the client renders.
 *
 * Plugins differ here and that is deliberate: Metro/Railway hand in `&`-coded strings, while plugins
 * that render through `cubex-i18n` hand in already-styled text. Passing the wrong styler would either
 * double-process or leave raw codes visible, so it is an explicit constructor argument.
 */
fun interface TextStyler {
    fun style(input: String): String

    companion object {
        /** Leaves text exactly as given — correct for output already rendered by `cubex-i18n`. */
        @JvmField
        val NONE: TextStyler = TextStyler { it }
    }
}

/**
 * Fluent [ItemStack] construction, replacing the three near-identical builders that Metro, Railway
 * and RuleGems each carried.
 *
 * The builder mutates one [ItemMeta] and only writes it back in [build], so a builder can be reused
 * up to the point it is built.
 */
class ItemBuilder @JvmOverloads constructor(
    material: Material,
    amount: Int = 1,
    private val styler: TextStyler = TextStyler.NONE,
) {
    private val item: ItemStack = ItemStack(material, amount)
    private val meta: ItemMeta? = item.itemMeta
    private val loreLines: MutableList<String> = ArrayList()

    fun name(name: String?): ItemBuilder = apply {
        if (name != null) meta?.setDisplayName(styler.style(name))
    }

    fun amount(amount: Int): ItemBuilder = apply {
        item.amount = amount.coerceIn(1, item.maxStackSize.coerceAtLeast(1))
    }

    /** Replaces the whole lore. */
    fun lore(vararg lines: String?): ItemBuilder = lore(lines.toList())

    /** Replaces the whole lore. */
    fun lore(lines: List<String?>?): ItemBuilder = apply {
        loreLines.clear()
        lines?.forEach { line -> if (line != null) loreLines.add(styler.style(line)) }
    }

    fun addLore(vararg lines: String?): ItemBuilder = apply {
        lines.forEach { line -> if (line != null) loreLines.add(styler.style(line)) }
    }

    fun addLore(lines: List<String?>?): ItemBuilder = apply {
        lines?.forEach { line -> if (line != null) loreLines.add(styler.style(line)) }
    }

    fun addEmptyLore(): ItemBuilder = apply { loreLines.add("") }

    fun enchant(enchantment: Enchantment, level: Int): ItemBuilder = apply {
        meta?.addEnchant(enchantment, level, true)
    }

    /** The enchanted shimmer without a real enchantment, used to mark a selected button. */
    fun glow(): ItemBuilder = apply {
        val currentMeta = meta ?: return@apply
        GLOW_ENCHANTMENT?.let { currentMeta.addEnchant(it, 1, true) }
        currentMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
    }

    fun flags(vararg flags: ItemFlag): ItemBuilder = apply {
        if (flags.isNotEmpty()) meta?.addItemFlags(*flags)
    }

    fun hideAttributes(): ItemBuilder = apply { meta?.addItemFlags(ItemFlag.HIDE_ATTRIBUTES) }

    fun customModelData(data: Int?): ItemBuilder = apply { meta?.setCustomModelData(data) }

    fun skullOwner(player: OfflinePlayer?): ItemBuilder = apply {
        val currentMeta = meta
        if (currentMeta is SkullMeta && player != null) currentMeta.owningPlayer = player
    }

    fun data(key: NamespacedKey?, value: String?): ItemBuilder = apply {
        if (key != null && value != null) {
            meta?.persistentDataContainer?.set(key, PersistentDataType.STRING, value)
        }
    }

    fun data(key: NamespacedKey?, value: Int): ItemBuilder = apply {
        if (key != null) meta?.persistentDataContainer?.set(key, PersistentDataType.INTEGER, value)
    }

    /**
     * Tags this stack as a GUI button. Any button that escapes into a player's inventory can then be
     * recognised and removed — Metro added this after buttons leaked into survival inventories.
     */
    fun guiMarker(key: NamespacedKey?): ItemBuilder = apply {
        if (key != null) {
            meta?.persistentDataContainer?.set(key, PersistentDataType.BYTE, 1.toByte())
        }
    }

    fun build(): ItemStack {
        val currentMeta = meta ?: return item
        if (loreLines.isNotEmpty()) currentMeta.lore = ArrayList(loreLines)
        item.itemMeta = currentMeta
        return item
    }

    companion object {
        /**
         * Resolved reflectively because the "harmless enchantment" constant was renamed across the
         * versions this repo targets (`DURABILITY` on 1.18, `UNBREAKING` on 1.21). A null result just
         * means [glow] adds no enchantment rather than failing the whole item.
         */
        private val GLOW_ENCHANTMENT: Enchantment? = resolveGlowEnchantment()

        private fun resolveGlowEnchantment(): Enchantment? {
            for (name in arrayOf("DURABILITY", "UNBREAKING")) {
                val found = runCatching {
                    Enchantment::class.java.getField(name).get(null) as? Enchantment
                }.getOrNull()
                if (found != null) return found
            }
            return null
        }

        @JvmStatic
        @JvmOverloads
        fun of(material: Material, styler: TextStyler = TextStyler.NONE): ItemBuilder =
            ItemBuilder(material, 1, styler)
    }
}
