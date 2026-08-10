package org.cubexmc.regions.gui

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.regions.gui.GuiText.Ui
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.RegionDefinition
import java.util.Locale

/** Builds the recurring buttons shared by several menus. */
internal class GuiItems(private val text: GuiText, private val keys: GuiKeys) {
    fun region(region: RegionDefinition): ItemStack {
        val material = if (region.enabled) Material.EMERALD_BLOCK else Material.COAL_BLOCK
        val item = text.named(
            material,
            text.text("gui.item.region.name", mapOf("name" to region.name)),
            text.lore(
                "gui.item.region.lore",
                mapOf(
                    "id" to region.id,
                    "source" to region.source.describe(),
                    "lifecycle" to region.lifecycle.name.lowercase(Locale.ROOT),
                    "revision" to region.revision.toString(),
                    "mode" to (region.mode?.type ?: text.text("gui.common.none")),
                    "flags" to region.flags.size.toString(),
                    "effects" to region.effects.size.toString(),
                ),
            ),
        )
        item.itemMeta?.let { meta ->
            meta.persistentDataContainer.set(keys.region, PersistentDataType.STRING, region.id)
            item.itemMeta = meta
        }
        return item
    }

    fun mode(type: String, current: String?): ItemStack {
        val active = type.equals(current, ignoreCase = true)
        val lore = listOf(text.text(if (active) "gui.mode.current" else "gui.mode.switch")) +
            text.lore("gui.mode.type.$type.lore")
        return text.named(
            modeMaterial(type),
            "${if (active) Ui.GREEN else Ui.YELLOW}${text.label("gui.mode.type.$type.name", type)}",
            lore,
        )
    }

    fun flag(flag: String, value: String): ItemStack {
        val normalized = value.lowercase(Locale.ROOT)
        val material = when (normalized) {
            "deny" -> Material.RED_CONCRETE
            "allow" -> Material.LIME_CONCRETE
            else -> Material.GRAY_CONCRETE
        }
        return text.named(
            material,
            text.text(
                "gui.flag.entry.name",
                mapOf(
                    "flag" to text.label("gui.flag.key.$flag", flag),
                    "color" to statusColor(normalized),
                    "value" to text.label("gui.flag.value.$normalized", normalized),
                ),
            ),
            text.lore("gui.flag.entry.lore"),
        )
    }

    fun effect(index: Int, effect: EffectConfig): ItemStack = text.named(
        Material.BLAZE_POWDER,
        text.text(
            "gui.effect.entry.name",
            mapOf("index" to (index + 1).toString(), "type" to effect.type),
        ),
        text.lore(
            "gui.effect.entry.lore",
            mapOf(
                "scope" to effect.scope.name.lowercase(Locale.ROOT),
                "combination" to effect.combination.name.lowercase(Locale.ROOT),
                "values" to effect.values.entries
                    .joinToString(", ") { "${it.key}=${it.value}" }
                    .ifBlank { text.text("gui.effect.entry.no-values") },
            ),
        ),
    )

    fun templateMaterial(type: String?): Material = when (type?.lowercase(Locale.ROOT)) {
        "dual_pvp", "union_war" -> Material.DIAMOND_SWORD
        "run_race" -> Material.LEATHER_BOOTS
        "boat_race" -> Material.OAK_BOAT
        "horse_race" -> Material.SADDLE
        "hide_and_seek" -> Material.ENDER_EYE
        else -> Material.CAMPFIRE
    }

    fun back(): ItemStack = text.named(GuiIcons.BACK, text.text("gui.common.back"))

    fun describeVehicle(value: String): String = when (value.lowercase(Locale.ROOT)) {
        "none", "on_foot", "on-foot", "no_vehicle", "no-vehicle", "foot" -> text.text("gui.vehicle.on-foot")
        "any", "vehicle", "any_vehicle", "any-vehicle" -> text.text("gui.vehicle.any")
        "boat" -> text.text("gui.vehicle.boat")
        "horse" -> text.text("gui.vehicle.horse")
        "minecart" -> text.text("gui.vehicle.minecart")
        "pass", "ignore", "any_state", "any-state" -> text.text("gui.vehicle.ignore")
        else -> value
    }

    private fun modeMaterial(type: String): Material = when (type) {
        "dual_pvp" -> Material.IRON_SWORD
        "union_war" -> Material.SHIELD
        "run_race" -> Material.LEATHER_BOOTS
        "boat_race" -> Material.OAK_BOAT
        "horse_race" -> Material.SADDLE
        "hide_and_seek" -> Material.ENDER_EYE
        else -> Material.CAMPFIRE
    }

    private fun statusColor(value: String): String = when (value) {
        "deny" -> Ui.RED
        "allow" -> Ui.GREEN
        else -> Ui.GRAY
    }
}
