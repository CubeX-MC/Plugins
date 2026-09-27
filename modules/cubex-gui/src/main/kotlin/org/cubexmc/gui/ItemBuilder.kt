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
public fun interface TextStyler {
    public fun style(input: String): String

    public companion object {
        /** Leaves text exactly as given — correct for output already rendered by `cubex-i18n`. */
        @JvmField
        public val NONE: TextStyler = TextStyler { it }
    }
}

/**
 * Fluent [ItemStack] construction, replacing the three near-identical builders that Metro, Railway
 * and RuleGems each carried.
 *
 * The builder mutates one [ItemMeta] and only writes it back in [build], so a builder can be reused
 * up to the point it is built.
 */
public class ItemBuilder @JvmOverloads constructor(
    material: Material,
    amount: Int = 1,
    private val styler: TextStyler = TextStyler.NONE,
) {
    private val item: ItemStack = ItemStack(material, amount)
    private val meta: ItemMeta? = item.itemMeta
    private val loreLines: MutableList<String> = ArrayList()

    public fun name(name: String?): ItemBuilder = apply {
        if (name != null) meta?.setDisplayName(styler.style(name))
    }

    public fun amount(amount: Int): ItemBuilder = apply {
        item.amount = amount.coerceIn(1, item.maxStackSize.coerceAtLeast(1))
    }

    /** Replaces the whole lore. */
    public fun lore(vararg lines: String?): ItemBuilder = lore(lines.toList())

    /** Replaces the whole lore. */
    public fun lore(lines: List<String?>?): ItemBuilder = apply {
        loreLines.clear()
        lines?.forEach { line -> if (line != null) loreLines.add(styler.style(line)) }
    }

    public fun addLore(vararg lines: String?): ItemBuilder = apply {
        lines.forEach { line -> if (line != null) loreLines.add(styler.style(line)) }
    }

    public fun addLore(lines: List<String?>?): ItemBuilder = apply {
        lines?.forEach { line -> if (line != null) loreLines.add(styler.style(line)) }
    }

    public fun addEmptyLore(): ItemBuilder = apply { loreLines.add("") }

    public fun enchant(enchantment: Enchantment, level: Int): ItemBuilder = apply {
        meta?.addEnchant(enchantment, level, true)
    }

    /** The enchanted shimmer without a real enchantment, used to mark a selected button. */
    public fun glow(): ItemBuilder = apply {
        val currentMeta = meta ?: return@apply
        GLOW_ENCHANTMENT?.let { currentMeta.addEnchant(it, 1, true) }
        currentMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
    }

    /** Prefer a cosmetic glint override; older servers use the harmless fishing enchantment. */
    public fun cosmeticGlow(): ItemBuilder = apply { applyGlowEffect(meta) }

    /** Hide decorative item details without changing [hideAttributes]'s existing contract. */
    public fun hideDetails(): ItemBuilder = apply {
        meta?.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE)
        runCatching { ItemFlag.valueOf("HIDE_POTION_EFFECTS") }.getOrNull()?.let { meta?.addItemFlags(it) }
    }

    public fun flags(vararg flags: ItemFlag): ItemBuilder = apply {
        if (flags.isNotEmpty()) meta?.addItemFlags(*flags)
    }

    public fun hideAttributes(): ItemBuilder = apply { meta?.addItemFlags(ItemFlag.HIDE_ATTRIBUTES) }

    public fun customModelData(data: Int?): ItemBuilder = apply { meta?.setCustomModelData(data) }

    public fun skullOwner(player: OfflinePlayer?): ItemBuilder = apply {
        val currentMeta = meta
        if (currentMeta is SkullMeta && player != null) currentMeta.owningPlayer = player
    }

    public fun data(key: NamespacedKey?, value: String?): ItemBuilder = apply {
        if (key != null && value != null) {
            meta?.persistentDataContainer?.set(key, PersistentDataType.STRING, value)
        }
    }

    public fun data(key: NamespacedKey?, value: Int): ItemBuilder = apply {
        if (key != null) meta?.persistentDataContainer?.set(key, PersistentDataType.INTEGER, value)
    }

    /**
     * Tags this stack as a GUI button. Any button that escapes into a player's inventory can then be
     * recognised and removed — Metro added this after buttons leaked into survival inventories.
     */
    public fun guiMarker(key: NamespacedKey?): ItemBuilder = apply {
        if (key != null) {
            meta?.persistentDataContainer?.set(key, PersistentDataType.BYTE, 1.toByte())
        }
    }

    public fun build(): ItemStack {
        val currentMeta = meta ?: return item
        if (loreLines.isNotEmpty()) currentMeta.lore = ArrayList(loreLines)
        item.itemMeta = currentMeta
        return item
    }

    public companion object {
        @JvmStatic
        public fun applyGlowEffect(meta: ItemMeta?) {
            if (meta == null) return
            try {
                val method = meta.javaClass.getMethod("setEnchantmentGlintOverride", java.lang.Boolean::class.java)
                method.invoke(meta, true)
                return
            } catch (_: ReflectiveOperationException) {
                // The override is absent on older server APIs.
            } catch (_: LinkageError) {
                // A server can expose metadata from an older API implementation.
            }
            try {
                Enchantment.getByKey(NamespacedKey.minecraft("luck_of_the_sea"))?.let {
                    meta.addEnchant(it, 1, true)
                }
            } catch (_: LinkageError) {
                // No usable enchantment API: the item still renders without a glint.
            }
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        }

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
        public fun of(material: Material, styler: TextStyler = TextStyler.NONE): ItemBuilder =
            ItemBuilder(material, 1, styler)
    }
}
