package org.cubexmc.mountlicense.listener

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.cubexmc.mountlicense.MountLicensePlugin
import org.cubexmc.mountlicense.lang.LanguageManager
import org.cubexmc.mountlicense.service.ItemFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LicenseHintListener(
    private val plugin: MountLicensePlugin,
    private val itemFactory: ItemFactory,
    private val lang: LanguageManager,
) : Listener {
    private val lastHint: MutableMap<UUID, Long> = ConcurrentHashMap()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemHeld(event: PlayerItemHeldEvent) {
        val player = event.player
        val newHand = player.inventory.getItem(event.newSlot)
        maybeShowHint(player, newHand)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSwapHandItems(event: PlayerSwapHandItemsEvent) {
        maybeShowHint(event.player, event.offHandItem)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                if (player.isOnline) {
                    maybeShowHint(player, player.inventory.itemInMainHand)
                }
            },
            20L,
        )
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastHint.remove(event.player.uniqueId)
    }

    fun maybeShowHint(player: Player?, hand: ItemStack?) {
        if (player == null || !player.hasPermission(REGISTER_PERMISSION)) return
        if (!itemFactory.isLicense(hand)) return

        val now = System.currentTimeMillis()
        val last = lastHint[player.uniqueId]
        if (last != null && now - last < HINT_DEBOUNCE_MS) return
        lastHint[player.uniqueId] = now

        lang.sendActionBar(player, "license_item.hint_actionbar")
    }

    private companion object {
        const val REGISTER_PERMISSION: String = "mountlicense.register"
        const val HINT_DEBOUNCE_MS: Long = 2000L
    }
}
