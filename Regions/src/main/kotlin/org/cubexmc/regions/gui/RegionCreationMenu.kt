package org.cubexmc.regions.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.OwnerPolicy
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import java.util.Locale

/**
 * The creation wizard: pick an owned Lands area, then a template.
 *
 * Ownership is re-checked at every step, so an area that changed hands between opening the picker and
 * clicking it cannot be used.
 */
internal class RegionCreationMenu(private val gui: RegionsGui) {
    private val plugin get() = gui.plugin
    private val text get() = gui.text
    private val items get() = gui.items

    fun promptCreateRegion(player: Player) {
        gui.promptLine(player, "gui.prompt.create-id") { rawId ->
            val id = rawId.trim().lowercase(Locale.ROOT)
            if (!id.matches(REGION_ID)) {
                text.send(player, "gui.create.bad-id")
                gui.openMain(player)
                return@promptLine
            }
            if (gui.editable(id) != null) {
                text.send(player, "gui.create.exists", mapOf("id" to id))
                gui.openMain(player)
                return@promptLine
            }
            gui.promptLine(player, "gui.prompt.create-name") { rawName ->
                val name = rawName.trim().takeUnless { it == "-" || it.isBlank() } ?: id
                openOwnedAreas(player, OwnedAreaContext(OwnedAreaPurpose.CREATE, id, name))
            }
        }
    }

    fun openOwnedAreas(player: Player, context: OwnedAreaContext) {
        val source = plugin.sources().find("lands")
        if (source == null || !source.isAvailable()) {
            text.send(player, "gui.create.lands-unavailable")
            return if (context.purpose == OwnedAreaPurpose.BIND) {
                gui.openSource(player, context.targetId)
            } else {
                gui.openMain(player)
            }
        }
        val options = source.getOwnedRegions(player.uniqueId)
        val pageCount = ((options.size + GuiSlots.OWNED_AREA_PAGE_SIZE - 1) / GuiSlots.OWNED_AREA_PAGE_SIZE).coerceAtLeast(1)
        val page = context.page.coerceIn(0, pageCount - 1)
        val shownContext = context.copy(page = page)
        val holderRegion = context.targetId.takeIf { context.purpose == OwnedAreaPurpose.BIND }
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.OWNED_AREAS, holderRegion, shownContext),
            54,
            text.component("gui.area.title", mapOf("page" to (page + 1).toString(), "pages" to pageCount.toString())),
        )
        val shown = options.drop(page * GuiSlots.OWNED_AREA_PAGE_SIZE).take(GuiSlots.OWNED_AREA_PAGE_SIZE)
        for ((slot, option) in shown.withIndex()) {
            val land = option.values["land"] ?: continue
            val area = option.values["area"] ?: "default"
            val item = text.item(
                Material.GRASS_BLOCK,
                "gui.area.entry",
                mapOf("name" to option.name, "land" to land, "area" to area),
            )
            item.itemMeta?.let { meta ->
                meta.persistentDataContainer.set(gui.keys.land, PersistentDataType.STRING, land)
                meta.persistentDataContainer.set(gui.keys.area, PersistentDataType.STRING, area)
                item.itemMeta = meta
            }
            inventory.setItem(slot, item)
        }
        if (options.isEmpty()) {
            inventory.setItem(22, text.item(Material.BARRIER, "gui.area.empty"))
        }
        if (page > 0) inventory.setItem(45, text.named(Material.ARROW, text.text("gui.common.previous-page")))
        inventory.setItem(
            49,
            text.item(
                Material.MAP,
                "gui.area.count",
                mapOf("count" to options.size.toString(), "page" to (page + 1).toString(), "pages" to pageCount.toString()),
            ),
        )
        if (page + 1 < pageCount) inventory.setItem(53, text.named(Material.ARROW, text.text("gui.common.next-page")))
        inventory.setItem(50, items.back())
        player.openInventory(inventory)
    }

    fun clickOwnedArea(player: Player, holder: RegionsHolder, item: ItemStack?, slot: Int) {
        val context = holder.ownedArea ?: return gui.openMain(player)
        when (slot) {
            45 -> return openOwnedAreas(player, context.copy(page = context.page - 1))
            53 -> return openOwnedAreas(player, context.copy(page = context.page + 1))
            50 -> return if (context.purpose == OwnedAreaPurpose.BIND) {
                gui.openSource(player, context.targetId)
            } else {
                gui.openMain(player)
            }
        }
        if (slot !in 0 until GuiSlots.OWNED_AREA_PAGE_SIZE) return
        val meta = item?.itemMeta ?: return
        val land = meta.persistentDataContainer.get(gui.keys.land, PersistentDataType.STRING) ?: return
        val area = meta.persistentDataContainer.get(gui.keys.area, PersistentDataType.STRING) ?: return
        val ref = RegionSourceRef("lands", linkedMapOf("land" to land, "area" to area))
        if (!gui.allow(player, plugin.authority().canCreate(player, ref))) {
            text.send(player, "gui.area.owner-changed")
            return openOwnedAreas(player, context)
        }
        when (context.purpose) {
            OwnedAreaPurpose.CREATE -> {
                if (gui.editable(context.targetId) != null) {
                    text.send(player, "gui.create.exists", mapOf("id" to context.targetId))
                    return gui.openMain(player)
                }
                openTemplates(player, TemplateContext(context.targetId, context.targetName, ref))
            }
            OwnedAreaPurpose.BIND -> {
                val region = gui.editable(context.targetId) ?: return gui.openMain(player)
                gui.saveAndReopen(player, region.copy(source = ref, ownerPolicy = OwnerPolicy.LANDS_OWNER)) {
                    gui.openSource(player, region.id)
                }
            }
        }
    }

    private fun openTemplates(player: Player, context: TemplateContext) {
        val templates = plugin.templates().all()
        val pageCount = ((templates.size + GuiSlots.TEMPLATE_PAGE_SIZE - 1) / GuiSlots.TEMPLATE_PAGE_SIZE).coerceAtLeast(1)
        val page = context.page.coerceIn(0, pageCount - 1)
        val shownContext = context.copy(page = page)
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.TEMPLATES, null, template = shownContext),
            54,
            text.component("gui.template.title", mapOf("page" to (page + 1).toString(), "pages" to pageCount.toString())),
        )
        val none = text.text("gui.common.none")
        val shown = templates.drop(page * GuiSlots.TEMPLATE_PAGE_SIZE).take(GuiSlots.TEMPLATE_PAGE_SIZE)
        for ((slot, template) in shown.withIndex()) {
            val lore = text.lore(
                "gui.template.entry.lore",
                mapOf(
                    "description" to template.description,
                    "mode" to (template.mode?.type ?: none),
                    "flags" to template.flags.keys.joinToString(", ").ifBlank { none },
                    "effects" to template.effects.joinToString(", ") { it.type }.ifBlank { none },
                    "triggers" to template.triggers.values.sumOf { it.size }.toString(),
                ),
            ) + if (template.parameters.isNotEmpty()) {
                listOf(text.text("gui.template.parameters", mapOf("keys" to template.parameters.keys.joinToString(", "))))
            } else {
                emptyList()
            }
            val item = text.named(
                items.templateMaterial(template.mode?.type),
                text.text("gui.template.entry.name", mapOf("name" to template.name)),
                lore,
            )
            item.itemMeta?.let { meta ->
                meta.persistentDataContainer.set(gui.keys.template, PersistentDataType.STRING, template.id)
                item.itemMeta = meta
            }
            inventory.setItem(slot, item)
        }
        if (templates.isEmpty()) {
            inventory.setItem(22, text.item(Material.BARRIER, "gui.template.empty"))
        }
        if (page > 0) inventory.setItem(45, text.named(Material.ARROW, text.text("gui.common.previous-page")))
        inventory.setItem(
            49,
            text.item(
                Material.BOOK,
                "gui.template.count",
                mapOf("count" to templates.size.toString(), "page" to (page + 1).toString(), "pages" to pageCount.toString()),
            ),
        )
        inventory.setItem(50, text.named(Material.BARRIER, text.text("gui.template.back-to-areas")))
        if (page + 1 < pageCount) inventory.setItem(53, text.named(Material.ARROW, text.text("gui.common.next-page")))
        player.openInventory(inventory)
    }

    fun clickTemplate(player: Player, holder: RegionsHolder, item: ItemStack?, slot: Int) {
        val context = holder.template ?: return gui.openMain(player)
        when (slot) {
            45 -> return openTemplates(player, context.copy(page = context.page - 1))
            53 -> return openTemplates(player, context.copy(page = context.page + 1))
            50 -> return openOwnedAreas(
                player,
                OwnedAreaContext(OwnedAreaPurpose.CREATE, context.targetId, context.targetName),
            )
        }
        if (slot !in 0 until GuiSlots.TEMPLATE_PAGE_SIZE) return
        val templateId = item?.itemMeta?.persistentDataContainer
            ?.get(gui.keys.template, PersistentDataType.STRING) ?: return
        if (!gui.allow(player, plugin.authority().canCreate(player, context.source))) {
            text.send(player, "gui.template.owner-changed")
            return openOwnedAreas(
                player,
                OwnedAreaContext(OwnedAreaPurpose.CREATE, context.targetId, context.targetName),
            )
        }
        if (gui.editable(context.targetId) != null) {
            text.send(player, "gui.create.exists", mapOf("id" to context.targetId))
            return gui.openMain(player)
        }
        val base = RegionDefinition(
            id = context.targetId,
            name = context.targetName,
            source = context.source,
            ownerPolicy = OwnerPolicy.LANDS_OWNER,
            mode = ModeConfig("free_event"),
        )
        val applied = plugin.templates().apply(templateId, base)
        val region = applied.region
        if (!applied.success || region == null) {
            text.send(player, "gui.template.failed", mapOf("errors" to applied.errors.joinToString("; ")))
            return openTemplates(player, context)
        }
        gui.saveAndReopen(player, region) { gui.openDetail(player, region.id) }
    }

    private companion object {
        val REGION_ID = Regex("[a-z0-9_-]{2,48}")
    }
}
