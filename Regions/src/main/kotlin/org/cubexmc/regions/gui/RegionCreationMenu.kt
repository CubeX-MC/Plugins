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
import org.cubexmc.regions.service.RegionTemplate
import org.cubexmc.regions.service.TemplateParameter
import org.cubexmc.regions.service.TemplateParameterType
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
            inventory.setItem(22, text.item(GuiIcons.EMPTY, "gui.area.empty"))
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

    fun openTemplatesForRegion(player: Player, regionId: String) {
        val region = gui.editable(regionId) ?: return gui.openMain(player)
        if (!gui.canManageRegion(player, region)) return
        openTemplates(
            player,
            TemplateContext(region.id, region.name, region.source, purpose = TemplatePurpose.APPLY),
        )
    }

    private fun openTemplates(player: Player, context: TemplateContext) {
        val currentContext = if (context.purpose == TemplatePurpose.APPLY) {
            val region = gui.editable(context.targetId) ?: return gui.openMain(player)
            if (!gui.canManageRegion(player, region)) return
            context.copy(targetName = region.name, source = region.source)
        } else {
            context
        }
        val templates = plugin.templates().all()
        val pageCount = ((templates.size + GuiSlots.TEMPLATE_PAGE_SIZE - 1) / GuiSlots.TEMPLATE_PAGE_SIZE).coerceAtLeast(1)
        val page = currentContext.page.coerceIn(0, pageCount - 1)
        val shownContext = currentContext.copy(page = page)
        val holderRegion = currentContext.targetId.takeIf { currentContext.purpose == TemplatePurpose.APPLY }
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.TEMPLATES, holderRegion, template = shownContext),
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
            } + text.text(
                if (currentContext.purpose == TemplatePurpose.APPLY) {
                    "gui.template.entry.apply-hint"
                } else {
                    "gui.template.entry.create-hint"
                },
            )
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
            inventory.setItem(22, text.item(GuiIcons.EMPTY, "gui.template.empty"))
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
        inventory.setItem(
            50,
            text.named(
                GuiIcons.BACK,
                text.text(
                    if (currentContext.purpose == TemplatePurpose.APPLY) {
                        "gui.template.back-to-detail"
                    } else {
                        "gui.template.back-to-areas"
                    },
                ),
            ),
        )
        if (page + 1 < pageCount) inventory.setItem(53, text.named(Material.ARROW, text.text("gui.common.next-page")))
        player.openInventory(inventory)
    }

    fun clickTemplate(player: Player, holder: RegionsHolder, item: ItemStack?, slot: Int) {
        val context = holder.template ?: return gui.openMain(player)
        when (slot) {
            45 -> return openTemplates(player, context.copy(page = context.page - 1))
            53 -> return openTemplates(player, context.copy(page = context.page + 1))
            50 -> return if (context.purpose == TemplatePurpose.APPLY) {
                gui.openDetail(player, context.targetId)
            } else {
                openOwnedAreas(
                    player,
                    OwnedAreaContext(OwnedAreaPurpose.CREATE, context.targetId, context.targetName),
                )
            }
        }
        if (slot !in 0 until GuiSlots.TEMPLATE_PAGE_SIZE) return
        val templateId = item?.itemMeta?.persistentDataContainer
            ?.get(gui.keys.template, PersistentDataType.STRING) ?: return
        if (context.purpose == TemplatePurpose.CREATE) {
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
        } else {
            val region = gui.editable(context.targetId) ?: return gui.openMain(player)
            if (!gui.canManageRegion(player, region)) return
        }
        val template = plugin.templates().find(templateId)
        if (template == null) {
            text.send(player, "gui.template.failed", mapOf("errors" to templateId))
            return openTemplates(player, context)
        }
        collectParameters(player, context, template, template.parameters.values.toList(), emptyMap())
    }

    /**
     * 逐个把模板声明的参数问出来，凑齐了再套用。
     *
     * 模板不声明参数时（多数模板如此）直接套用，流程和以前一样。声明了参数的模板不再随包硬编码
     * 一个占位值——像复活点那种只校验格式不校验世界是否存在的字段，硬编码会一路通过校验，
     * 直到玩家死在场地里才发现坐标是错的。
     */
    private fun collectParameters(
        player: Player,
        context: TemplateContext,
        template: RegionTemplate,
        pending: List<TemplateParameter>,
        collected: Map<String, String>,
    ) {
        val parameter = pending.firstOrNull() ?: return finishTemplateSelection(player, context, template, collected)
        val promptKey = if (parameter.type == TemplateParameterType.LOCATION) {
            "gui.prompt.template-location"
        } else {
            "gui.prompt.template-value"
        }
        gui.promptLine(player, promptKey, mapOf("parameter" to parameter.id)) { raw ->
            val value = resolveHereKeyword(player, parameter, raw)
            val error = parameter.validate(value)
            if (error != null) {
                text.send(player, "gui.template.parameter-invalid", mapOf("reason" to error))
                return@promptLine collectParameters(player, context, template, pending, collected)
            }
            collectParameters(player, context, template, pending.drop(1), collected + (parameter.id to value))
        }
    }

    /** 位置参数支持一个 `here`，直接取玩家脚下的坐标，省得对着屏幕手抄 world,x,y,z。 */
    private fun resolveHereKeyword(player: Player, parameter: TemplateParameter, raw: String): String {
        val trimmed = raw.trim()
        if (parameter.type != TemplateParameterType.LOCATION) return trimmed
        val here = trimmed.equals("here", ignoreCase = true) ||
            trimmed.equals(text.text("gui.prompt.here-word"), ignoreCase = true)
        if (!here) return trimmed
        val location = player.location
        return "${location.world?.name},${location.blockX},${location.blockY},${location.blockZ}"
    }

    private fun finishTemplateSelection(
        player: Player,
        context: TemplateContext,
        template: RegionTemplate,
        supplied: Map<String, String>,
    ) {
        if (context.purpose == TemplatePurpose.APPLY) {
            return openTemplateConfirmation(player, context, template, supplied)
        }
        // 问参数期间玩家可能已经被撤权，或者这块地被别人先绑走了——写之前重新核一次。
        if (!gui.allow(player, plugin.authority().canCreate(player, context.source))) {
            text.send(player, "gui.template.owner-changed")
            return gui.openMain(player)
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
        val applied = plugin.templates().apply(template.id, base, supplied)
        val region = applied.region
        if (!applied.success || region == null) {
            text.send(player, "gui.template.failed", mapOf("errors" to applied.errors.joinToString("; ")))
            return openTemplates(player, context)
        }
        gui.saveAndReopen(player, region) { gui.openDetail(player, region.id) }
    }

    private fun openTemplateConfirmation(
        player: Player,
        context: TemplateContext,
        template: RegionTemplate,
        supplied: Map<String, String>,
    ) {
        val current = gui.editable(context.targetId) ?: return gui.openMain(player)
        if (!gui.canManageRegion(player, current)) return
        val applied = plugin.templates().apply(template.id, current, supplied)
        val candidate = applied.region
        if (!applied.success || candidate == null) {
            text.send(player, "gui.template.failed", mapOf("errors" to applied.errors.joinToString("; ")))
            return openTemplates(player, context)
        }
        val inventory = Bukkit.createInventory(
            RegionsHolder(
                View.TEMPLATE_CONFIRM,
                current.id,
                template = context,
                templateConfirmation = TemplateConfirmation(template.id, supplied),
            ),
            27,
            text.component("gui.template.confirm.title", mapOf("id" to current.id)),
        )
        inventory.setItem(4, items.region(current))
        inventory.setItem(
            11,
            text.item(
                items.templateMaterial(candidate.mode?.type),
                "gui.template.confirm.summary",
                mapOf(
                    "name" to template.name,
                    "mode" to (candidate.mode?.type ?: text.text("gui.common.none")),
                    "flags" to candidate.flags.size.toString(),
                    "effects" to candidate.effects.size.toString(),
                    "triggers" to candidate.triggers.values.sumOf { it.size }.toString(),
                ),
            ),
        )
        inventory.setItem(13, text.item(Material.REDSTONE_BLOCK, "gui.template.confirm.warning"))
        inventory.setItem(15, text.item(Material.LIME_CONCRETE, "gui.template.confirm.apply"))
        inventory.setItem(22, items.back())
        player.openInventory(inventory)
    }

    fun clickTemplateConfirmation(player: Player, holder: RegionsHolder, slot: Int) {
        val context = holder.template ?: return gui.openMain(player)
        if (slot == 22) return openTemplates(player, context)
        if (slot != 15) return
        val confirmation = holder.templateConfirmation ?: return openTemplates(player, context)
        val current = gui.editable(context.targetId) ?: return gui.openMain(player)
        if (!gui.canManageRegion(player, current)) return
        val applied = plugin.templates().apply(confirmation.templateId, current, confirmation.supplied)
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
