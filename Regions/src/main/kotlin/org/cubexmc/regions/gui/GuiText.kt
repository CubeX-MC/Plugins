package org.cubexmc.regions.gui

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.cubexmc.regions.RegionsPlugin

/**
 * Every string the GUI shows comes from the active language file.
 *
 * Menu entries follow a `<key>.name` / `<key>.lore` pair so [item] can build a whole button from one
 * key, which is what keeps the menu classes readable once the literals move out of the code.
 */
class GuiText(private val plugin: RegionsPlugin) {
    fun text(key: String, placeholders: Map<String, String> = emptyMap()): String =
        plugin.lang().message(key, placeholders)

    fun component(key: String, placeholders: Map<String, String> = emptyMap()): Component =
        plugin.lang().component(key, placeholders)

    fun lore(key: String, placeholders: Map<String, String> = emptyMap()): List<String> =
        plugin.lang().messageList(key, placeholders)

    /** Builds a button from `<key>.name` and, when present, `<key>.lore`. */
    fun item(
        material: Material,
        key: String,
        placeholders: Map<String, String> = emptyMap(),
        extraLore: List<String> = emptyList(),
    ): ItemStack = named(
        material,
        text("$key.name", placeholders),
        lore("$key.lore", placeholders) + extraLore,
    )

    fun named(material: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        if (meta != null) {
            meta.displayName(plugin.lang().render(name))
            meta.lore(lore.map { plugin.lang().render(it) })
            item.itemMeta = meta
        }
        return item
    }

    fun send(player: org.bukkit.entity.Player, key: String, placeholders: Map<String, String> = emptyMap()) {
        plugin.lang().sendRaw(player, text(key, placeholders))
    }

    /** Falls back to the raw id when a capability has no translation, so nothing renders blank. */
    fun label(key: String, fallback: String): String =
        if (plugin.lang().has(key)) text(key) else fallback

    /**
     * Colours for labels the code assembles rather than reads whole from the language file — a
     * status tint in front of a translated name, for example.
     *
     * Deliberately `&` codes and not MiniMessage tags: these are concatenated with strings the i18n
     * service has *already* rendered, so they are read back by
     * [org.cubexmc.regions.config.LanguageManager.render] and never re-parsed as MiniMessage.
     */
    object Ui {
        const val DARK_GREEN = "&2"
        const val AQUA = "&b"
        const val GRAY = "&7"
        const val GREEN = "&a"
        const val YELLOW = "&e"
        const val RED = "&c"
        const val DARK_GRAY = "&8"
    }
}
