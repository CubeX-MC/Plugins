package org.cubexmc.regions.gui

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.service.AuthorityDecision
import org.cubexmc.regions.service.RegionAuthorityService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates the Regions inventory menus.
 *
 * This class owns the listener wiring, chat prompts, authorization and draft saving; each menu area
 * lives in its own class and navigates through the `open*` methods here.
 */
class RegionsGui(internal val plugin: RegionsPlugin) : Listener {
    private val pendingInputs: MutableMap<UUID, PendingInput> = ConcurrentHashMap()

    internal val text = GuiText(plugin)
    internal val keys = GuiKeys(plugin)
    internal val items = GuiItems(text, keys)

    private val overview = RegionOverviewMenu(this)
    private val modeMenu = RegionModeMenu(this)
    private val rules = RegionRuleMenu(this)
    private val publish = RegionPublishMenu(this)
    private val creation = RegionCreationMenu(this)

    fun openMain(player: Player) = overview.openMain(player)

    fun openDetail(player: Player, regionId: String) = overview.openDetail(player, regionId)

    internal fun openSource(player: Player, regionId: String) = overview.openSource(player, regionId)

    internal fun openMode(player: Player, regionId: String) = modeMenu.open(player, regionId)

    internal fun openFlags(player: Player, regionId: String) = rules.openFlags(player, regionId)

    internal fun openEffects(player: Player, regionId: String) = rules.openEffects(player, regionId)

    internal fun openTriggers(player: Player, regionId: String) = rules.openTriggers(player, regionId)

    internal fun openPublishPreview(player: Player, regionId: String) = publish.open(player, regionId)

    internal fun sendRevisionHistory(player: Player, region: RegionDefinition) =
        publish.sendRevisionHistory(player, region)

    internal fun sendValidation(player: Player, region: RegionDefinition) =
        publish.sendValidation(player, region)

    internal fun promptCreateRegion(player: Player) = creation.promptCreateRegion(player)

    internal fun openOwnedAreas(player: Player, context: OwnedAreaContext) =
        creation.openOwnedAreas(player, context)

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? RegionsHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (!canEnterManagement(player)) {
            player.closeInventory()
            return
        }
        holder.regionId?.let { regionId ->
            val region = editable(regionId)
            if (region == null || !canManageRegion(player, region)) {
                player.closeInventory()
                return
            }
        }
        val slot = event.rawSlot
        if (slot < 0 || slot >= event.inventory.size) {
            return
        }
        val rightClick = event.click.isRightClick
        when (holder.view) {
            View.MAIN -> overview.clickMain(player, event.currentItem, slot)
            View.DETAIL -> overview.clickDetail(player, holder.regionId ?: return, slot)
            View.SOURCE -> overview.clickSource(player, holder.regionId ?: return, slot, rightClick)
            View.MODE -> modeMenu.click(player, holder.regionId ?: return, slot, rightClick)
            View.FLAGS -> rules.clickFlags(player, holder.regionId ?: return, slot)
            View.EFFECTS -> rules.clickEffects(player, holder.regionId ?: return, slot)
            View.TRIGGERS -> rules.clickTriggers(player, holder.regionId ?: return, slot)
            View.PUBLISH_PREVIEW -> publish.click(player, holder.regionId ?: return, slot)
            View.OWNED_AREAS -> creation.clickOwnedArea(player, holder, event.currentItem, slot)
            View.TEMPLATES -> creation.clickTemplate(player, holder, event.currentItem, slot)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val pending = pendingInputs.remove(event.player.uniqueId) ?: return
        event.isCancelled = true
        val message = PlainTextComponentSerializer.plainText().serialize(event.message())
        plugin.regionScheduler().runAtEntity(event.player, Runnable {
            if (isCancelWord(message.trim())) {
                text.send(event.player, "gui.prompt.cancelled")
                openMain(event.player)
                return@Runnable
            }
            pending.onSubmit(message)
        })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        pendingInputs.remove(event.player.uniqueId)
    }

    internal fun promptLine(player: Player, promptKey: String, placeholders: Map<String, String> = emptyMap(), onSubmit: (String) -> Unit) {
        pendingInputs[player.uniqueId] = PendingInput(onSubmit)
        player.closeInventory()
        text.send(player, promptKey, placeholders)
        text.send(player, "gui.prompt.hint")
    }

    /** The draft if one exists, otherwise the published definition; editing always targets a draft. */
    internal fun editable(regionId: String): RegionDefinition? = plugin.publishing().editable(regionId)

    internal fun saveAndReopen(player: Player, region: RegionDefinition, reopen: () -> Unit) {
        val existing = editable(region.id)
        if (existing != null && !canManageRegion(player, existing)) return
        val candidate = withOwnerSnapshot(region)
        if (!canManageRegion(player, candidate)) return
        val result = if (existing == null) {
            plugin.publishing().createDraft(player, candidate)
        } else {
            plugin.publishing().saveDraft(player, candidate)
        }
        if (!result.success) {
            text.send(player, "gui.save.failed", mapOf("reason" to result.reason))
            return
        }
        text.send(player, "gui.save.ok", mapOf("id" to region.id))
        reopen()
    }

    /**
     * Records the current source owner on the draft so authorization can detect a transfer later
     * without trusting a cached UUID at check time.
     */
    private fun withOwnerSnapshot(region: RegionDefinition): RegionDefinition {
        val metadata = LinkedHashMap(region.metadata)
        val ownerId = plugin.sources().find(region.source.type)?.ownerId(region.source)
        if (ownerId == null) {
            metadata.remove(RegionAuthorityService.SOURCE_OWNER_METADATA)
        } else {
            metadata[RegionAuthorityService.SOURCE_OWNER_METADATA] = ownerId.toString()
        }
        return region.copy(metadata = metadata)
    }

    internal fun canEnterManagement(player: Player): Boolean =
        allow(player, plugin.authority().canEnterManagement(player))

    internal fun canManageRegion(player: Player, region: RegionDefinition): Boolean =
        allow(player, plugin.authority().canManage(player, region))

    internal fun allow(player: Player, decision: AuthorityDecision): Boolean {
        if (decision.allowed) return true
        plugin.lang().send(player, decision.denial?.messageKey ?: "no-permission")
        return false
    }

    private fun isCancelWord(message: String): Boolean =
        message.equals("cancel", ignoreCase = true) ||
            message.equals(text.text("gui.prompt.cancel-word"), ignoreCase = true)
}
