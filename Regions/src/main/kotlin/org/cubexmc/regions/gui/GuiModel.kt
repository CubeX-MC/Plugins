package org.cubexmc.regions.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.RegionSourceRef

internal enum class View {
    MAIN,
    DETAIL,
    SOURCE,
    MODE,
    FLAGS,
    EFFECTS,
    TRIGGERS,
    PUBLISH_PREVIEW,
    OWNED_AREAS,
    TEMPLATES,
    TEMPLATE_CONFIRM,
}

internal enum class OwnedAreaPurpose { CREATE, BIND }

internal enum class TemplatePurpose { CREATE, APPLY }

internal data class OwnedAreaContext(
    val purpose: OwnedAreaPurpose,
    val targetId: String,
    val targetName: String,
    val page: Int = 0,
)

internal data class TemplateContext(
    val targetId: String,
    val targetName: String,
    val source: RegionSourceRef,
    val page: Int = 0,
    val purpose: TemplatePurpose = TemplatePurpose.CREATE,
)

internal data class TemplateConfirmation(
    val templateId: String,
    val supplied: Map<String, String>,
)

internal class RegionsHolder(
    val view: View,
    val regionId: String? = null,
    val ownedArea: OwnedAreaContext? = null,
    val template: TemplateContext? = null,
    val templateConfirmation: TemplateConfirmation? = null,
) : InventoryHolder {
    override fun getInventory(): Inventory = Bukkit.createInventory(null, 9)
}


/**
 * Icons shared by several menus.
 *
 * Every menu click is cancelled, but an icon on display is only ever one unhandled exception away
 * from ending up in a player's inventory, so menus never show a block players cannot legitimately
 * obtain — no barrier, no command block. Keep new icons survival-obtainable for the same reason.
 */
internal object GuiIcons {
    val BACK: Material = Material.ARROW
    val CLOSE: Material = Material.RED_STAINED_GLASS_PANE
    val EMPTY: Material = Material.GRAY_STAINED_GLASS_PANE
    val BLOCKED: Material = Material.RED_CONCRETE
    val CLEAR: Material = Material.BUCKET
    val TRIGGER: Material = Material.REPEATER
}

/** Persistent-data keys used to carry a selection through an inventory click. */
internal class GuiKeys(plugin: RegionsPlugin) {
    val region: NamespacedKey = NamespacedKey(plugin, "region_id")
    val land: NamespacedKey = NamespacedKey(plugin, "owned_land")
    val area: NamespacedKey = NamespacedKey(plugin, "owned_area")
    val template: NamespacedKey = NamespacedKey(plugin, "region_template")
}

internal object GuiSlots {
    const val OWNED_AREA_PAGE_SIZE = 45
    const val TEMPLATE_PAGE_SIZE = 45

    val MODE_CONFIGURATION: Set<Int> = setOf(
        19, 20, 21, 22, 23, 24, 25, 26, 28, 29, 30, 31, 32, 33, 34,
        36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 50, 51,
    )

    /**
     * Slots offered on the flag page. Flags with no runtime are listed but hidden at render time by
     * the registry check, so this map never promises a rule Regions cannot enforce.
     */
    val FLAGS: Map<Int, String> = linkedMapOf(
        10 to "pvp",
        11 to "fly",
        12 to "vanish",
        13 to "item_drop",
        14 to "item_pickup",
        15 to "block_break",
        16 to "block_place",
        19 to "vehicle_enter",
        20 to "commands",
        21 to "teleport_out",
    )

    fun modeConfiguration(type: String): Set<Int> =
        when (type.lowercase(java.util.Locale.ROOT)) {
            "dual_pvp" -> setOf(19, 20, 21, 22, 25, 28, 29, 30, 31, 33, 34)
            "union_war" -> setOf(19, 20, 21, 22, 23, 25, 28, 29, 30, 31, 33, 34)
            "run_race", "boat_race", "horse_race" ->
                setOf(19, 20, 24, 26, 32, 36, 37, 38, 39, 40, 41, 42, 43, 44, 51)
            "hide_and_seek" -> setOf(19, 20, 21, 22, 25, 33, 34, 42, 44, 45, 46, 47, 50)
            else -> emptySet()
        }
}
