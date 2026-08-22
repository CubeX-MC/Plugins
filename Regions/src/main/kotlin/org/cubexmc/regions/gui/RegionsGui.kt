package org.cubexmc.regions.gui

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.cubexmc.gui.chat.AcceptResult
import org.cubexmc.gui.chat.ChatInputState
import org.cubexmc.gui.chat.ChatOutcome
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
    /**
     * Prompt bookkeeping — timeout, cancel keyword and the de-duplication between the two chat event
     * chains — lives in `cubex-gui`'s [ChatInputState] (11 unit tests). Regions was the only plugin
     * that handled both chains correctly, so its shape is the one that was lifted into the module.
     */
    private val chatInputs = ChatInputState<(String) -> Unit>(
        cancelKeywords = { listOf(CANCEL_WORD, text.text("gui.prompt.cancel-word")) },
    )

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

    /** Nothing in a Regions menu is ever meant to move, so a drag over its slots is refused outright. */
    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.holder !is RegionsHolder) return
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val message = PlainTextComponentSerializer.plainText().serialize(event.message())
        if (capture(event.player, message)) {
            event.isCancelled = true
        }
    }

    /**
     * Paper only routes chat through [AsyncChatEvent] while no plugin listens to the legacy event; as
     * soon as one does — CMI and Contract both do on a typical server — every message takes the legacy
     * path and the modern event never fires, which used to leave the prompt uncaptured and echo the
     * player's answer to public chat. Handling both keeps the prompt working either way.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onLegacyChat(event: AsyncPlayerChatEvent) {
        if (capture(event.player, event.message)) {
            event.isCancelled = true
        }
    }

    /**
     * Hands [message] to [player]'s pending prompt, returning whether the line belongs to Regions and
     * must be kept off public chat. A line already taken through the other chat event is swallowed
     * again so a server that fires both does not echo it.
     */
    private fun capture(player: Player, message: String): Boolean {
        val playerId = player.uniqueId
        return when (val result = chatInputs.accept(playerId, message)) {
            AcceptResult.NotOurs -> false
            AcceptResult.AlreadyTaken -> true
            is AcceptResult.Accepted -> {
                plugin.regionScheduler().runAtEntity(player, Runnable {
                    chatInputs.settle(playerId)
                    when (val outcome = result.outcome) {
                        ChatOutcome.Cancelled -> {
                            text.send(player, "gui.prompt.cancelled")
                            openMain(player)
                        }

                        is ChatOutcome.Submitted -> result.payload(outcome.text)

                        // Regions offers neither a clear keyword nor a timeout, so these cannot occur.
                        else -> Unit
                    }
                })
                true
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        chatInputs.forget(event.player.uniqueId)
    }

    internal fun promptLine(player: Player, promptKey: String, placeholders: Map<String, String> = emptyMap(), onSubmit: (String) -> Unit) {
        // Regions prompts never time out; 0 means "no deadline".
        chatInputs.open(player.uniqueId, allowClear = false, timeoutMillis = 0L, payload = onSubmit)
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

    private companion object {
        const val CANCEL_WORD = "cancel"
    }
}
