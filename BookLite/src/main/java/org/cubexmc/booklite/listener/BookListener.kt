@file:Suppress("DEPRECATION")

package org.cubexmc.booklite.listener

import java.sql.SQLException
import java.util.Locale
import org.bukkit.Material
import org.bukkit.block.Lectern
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.event.player.PlayerEditBookEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import org.cubexmc.booklite.BookLitePlugin
import org.cubexmc.booklite.lang.LanguageManager
import org.cubexmc.booklite.model.BookRecord
import org.cubexmc.booklite.service.BookCodec
import org.cubexmc.booklite.service.BookRestorer
import org.cubexmc.booklite.service.BookService

class BookListener(
    private val plugin: BookLitePlugin,
    private val books: BookService,
    private val codec: BookCodec,
    private val restorer: BookRestorer,
    private val lang: LanguageManager,
) : Listener {
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSign(event: PlayerEditBookEvent) {
        if (!event.isSigning) {
            return
        }
        if (!plugin.configManager().isAutoConvertSignedBooks()) {
            return
        }
        if (plugin.configManager().isUninstallMode()) {
            return
        }

        val player = event.player
        val originalMeta = event.newBookMeta
        val draft = codec.createRecord(originalMeta, player.name)
        val validation = codec.validate(draft)
        if (!validation.ok()) {
            event.isCancelled = true
            sendValidation(player, validation)
            return
        }

        val record = try {
            books.saveOrGet(draft)
        } catch (exception: SQLException) {
            books.logStorageFailure("sign", exception)
            lang.send(player, "book.fail_storage")
            return
        }
        val generation = codec.generationToInt(originalMeta)
        val slot = event.slot
        // Spigot strips PDC from the meta passed to setNewBookMeta() during the
        // writable->written conversion, so swap on the next tick where
        // setItemMeta keeps PDC intact.
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!player.isOnline) {
                return@Runnable
            }
            val current = player.inventory.getItem(slot)
            if (current == null || current.type != Material.WRITTEN_BOOK) {
                return@Runnable
            }
            if (codec.isBookLite(current)) {
                return@Runnable
            }
            player.inventory.setItem(slot, codec.createShell(record, generation, current.amount))
            if (plugin.configManager().isLogConversions()) {
                plugin.logger.info(player.name + " signed BookLite book " + record.id())
            }
        })
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onReadSuppressVanilla(event: PlayerInteractEvent) {
        if (heldBookReadAttempt(event) == null) {
            return
        }
        cancelVanillaBookOpen(event)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onRead(event: PlayerInteractEvent) {
        val attempt = heldBookReadAttempt(event)
        if (attempt != null) {
            cancelVanillaBookOpen(event)
            openHeldBookLite(event, attempt)
            return
        }

        if (!isMainHandRightClick(event)) {
            return
        }
        if (event.isCancelled) {
            return
        }
        tryOpenLectern(event)
    }

    private fun openHeldBookLite(event: PlayerInteractEvent, attempt: ReadAttempt) {
        if (!event.player.hasPermission("booklite.use")) {
            lang.send(event.player, "commands.no_permission")
            return
        }
        openBookLite(event.player, attempt.id, attempt.generation)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPrepareCraft(event: PrepareItemCraftEvent) {
        if (!plugin.configManager().isAllowCraftingCopy()) {
            return
        }
        val inventory = event.inventory
        var shell: ItemStack? = null
        var blankBooks = 0
        var hasOtherIngredient = false

        for (item in inventory.matrix) {
            if (item == null || item.type == Material.AIR) {
                continue
            }
            if (codec.isBookLite(item)) {
                if (shell != null) {
                    inventory.result = null
                    return
                }
                shell = item
            } else if (item.type == Material.WRITABLE_BOOK) {
                blankBooks++
            } else {
                hasOtherIngredient = true
            }
        }
        if (hasOtherIngredient) {
            return
        }
        val currentShell = shell ?: return
        if (blankBooks <= 0) {
            return
        }

        val generation = codec.readGeneration(currentShell)
        if (!codec.canCopyGeneration(generation)) {
            inventory.result = null
            return
        }

        try {
            val record = books.find(codec.readBookId(currentShell))
            if (record == null) {
                inventory.result = null
                return
            }
            inventory.result = codec.createShell(record, codec.nextGeneration(generation), blankBooks)
        } catch (exception: SQLException) {
            books.logStorageFailure("craft", exception)
            inventory.result = null
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        if (!plugin.configManager().isUninstallMode()) {
            return
        }
        if (!plugin.configManager().isPassiveOnPlayerJoin()) {
            return
        }
        restorer.restoreInventoryAsync(event.player.inventory, plugin.configManager().getMaxItemsPerTick())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        if (!plugin.configManager().isUninstallMode()) {
            return
        }
        if (!plugin.configManager().isPassiveOnInventoryOpen()) {
            return
        }
        restorer.restoreInventoryAsync(event.inventory, plugin.configManager().getMaxItemsPerTick())
    }

    private fun shouldOpenHeldBook(event: PlayerInteractEvent): Boolean {
        if (event.action != Action.RIGHT_CLICK_BLOCK) {
            return true
        }
        val block = event.clickedBlock ?: return true
        // Leave interactive blocks (empty lecterns, chests, doors) to vanilla so a
        // shell book can still be placed into a lectern and containers open normally.
        return !block.type.isInteractable
    }

    private fun heldBookReadAttempt(event: PlayerInteractEvent): ReadAttempt? {
        if (!isMainHandRightClick(event)) {
            return null
        }
        if (!shouldOpenHeldBook(event)) {
            return null
        }
        val item = event.item
        val id = codec.readResolvableBookId(item) ?: return null
        return ReadAttempt(id, codec.readGeneration(item))
    }

    private fun isMainHandRightClick(event: PlayerInteractEvent): Boolean =
        event.hand == EquipmentSlot.HAND &&
            (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK)

    private fun tryOpenLectern(event: PlayerInteractEvent): Boolean {
        if (!plugin.configManager().isLecternEnabled()) {
            return false
        }
        if (event.action != Action.RIGHT_CLICK_BLOCK) {
            return false
        }
        val block = event.clickedBlock ?: return false
        if (block.type != Material.LECTERN) {
            return false
        }
        val lectern = block.state as? Lectern ?: return false
        val book = lectern.inventory.getItem(0)
        val id = codec.readResolvableBookId(book) ?: return false
        if (!event.player.hasPermission("booklite.use")) {
            lang.send(event.player, "commands.no_permission")
            cancelVanillaBookOpen(event)
            return true
        }
        cancelVanillaBookOpen(event)
        openBookLite(event.player, id, codec.readGeneration(book))
        return true
    }

    private fun cancelVanillaBookOpen(event: PlayerInteractEvent) {
        event.isCancelled = true
        event.setUseItemInHand(Event.Result.DENY)
    }

    private fun openBookLite(player: Player, id: String, generation: Int) {
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            var resolution = BookService.Resolution.notFound()
            try {
                resolution = books.resolveAndMarkAccessed(id)
            } catch (exception: SQLException) {
                books.logStorageFailure("read", exception)
            }
            val finalResolution = resolution
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!player.isOnline) {
                    return@Runnable
                }
                if (finalResolution.ambiguous()) {
                    lang.send(player, "commands.ambiguous_id")
                    return@Runnable
                }
                val finalRecord = finalResolution.record()
                displayBook(player, id, finalRecord, generation)
                if (finalRecord == null) {
                    lang.send(player, "book.fail_missing")
                }
            })
        })
    }

    private fun displayBook(player: Player, id: String, record: BookRecord?, generation: Int) {
        val readable = codec.createReadable(record, generation)
        val slot = player.inventory.heldItemSlot
        val original = player.inventory.getItem(slot)

        // Refresh shell title/author so any record update is reflected after
        // the book GUI closes and the shell becomes visible again.
        if (record != null &&
            original != null &&
            codec.isBookLite(original) &&
            matchesResolvedId(codec.readResolvableBookId(original), id, record.id())
        ) {
            val meta = original.itemMeta as BookMeta
            codec.applyShellMeta(meta, record, codec.readGeneration(original))
            original.itemMeta = meta
        }

        // ClientboundOpenBookPacket only carries the hand, not item NBT -- the
        // client renders whatever is in main hand. Put the full-content book in
        // the slot for the open packet, then restore on the next tick. The book
        // GUI blocks inventory interaction, so the swap is invisible.
        val restore = original
        player.closeInventory()
        player.inventory.setItem(slot, readable)
        player.openBook(player.inventory.getItem(slot) ?: readable)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!player.isOnline) {
                return@Runnable
            }
            val current = player.inventory.getItem(slot)
            if (current != null && current.isSimilar(readable)) {
                player.inventory.setItem(slot, restore)
            }
        }, 1L)
    }

    private fun matchesResolvedId(currentId: String?, inputId: String?, resolvedId: String): Boolean {
        if (currentId == null) {
            return false
        }
        val current = currentId.lowercase(Locale.getDefault())
        val input = inputId ?: ""
        val resolved = resolvedId.lowercase(Locale.getDefault())
        return current == input.lowercase(Locale.getDefault()) || current == resolved || resolved.startsWith(current)
    }

    private class ReadAttempt(
        val id: String,
        val generation: Int,
    )

    private fun sendValidation(player: Player, validation: BookCodec.ValidationResult) {
        val placeholders = HashMap<String, String>()
        placeholders["actual"] = validation.actual().toString()
        placeholders["max"] = validation.max().toString()
        lang.send(player, validation.key(), placeholders)
    }
}
