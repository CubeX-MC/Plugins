package org.cubexmc.metro.listener

import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.entity.PlayerLeashEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.vehicle.VehicleExitEvent
import org.cubexmc.metro.Metro
import org.cubexmc.metro.manager.LanguageManager

/** Handles player interaction with visual train entities in entity-model mode. */
class EntityModelListener(private val plugin: Metro) : Listener {
    private val language = plugin.languageManager

    @EventHandler(priority = EventPriority.NORMAL)
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        val clicked = event.rightClicked as? LivingEntity ?: return
        val controller = plugin.entityModelController ?: return
        if (!controller.isModelEntity(clicked.uniqueId)) return

        event.isCancelled = true

        val player = event.player
        val cart = controller.getCartByModelEntity(clicked.uniqueId) ?: return
        if (cart.isDead) return

        val train = plugin.lineServiceManager?.getTrainByMinecart(cart.uniqueId) ?: return
        if (train.isPassenger(player)) return
        if (train.isMoving) {
            player.sendMessage(language.getMessage("passenger.cannot_board_moving"))
            return
        }

        val line = train.line
        if (plugin.config.getBoolean("economy.enabled", true) && line != null) {
            val ticketPrice = line.ticketPrice
            if (ticketPrice > 0) {
                val vault = plugin.vaultIntegration
                if (vault != null && vault.isEnabled()) {
                    if (!vault.has(player, ticketPrice)) {
                        player.sendMessage(
                            language.getMessage(
                                "economy.insufficient_funds",
                                LanguageManager.put(LanguageManager.args(), "price", vault.format(ticketPrice)),
                            ),
                        )
                        return
                    }
                    vault.withdraw(player, ticketPrice)
                    player.sendMessage(
                        language.getMessage(
                            "economy.paid_boarding",
                            LanguageManager.put(LanguageManager.args(), "price", vault.format(ticketPrice)),
                        ),
                    )
                }
            }
        }

        if (controller.isMultiPassenger()) {
            if (!clicked.addPassenger(player)) return
        } else {
            if (clicked.passengers.isNotEmpty()) {
                player.sendMessage(language.getMessage("passenger.cannot_board_moving"))
                return
            }
            if (!clicked.addPassenger(player)) return
        }

        train.addPassenger(player, cart)
        if (line != null) {
            val colorized = ChatColor.translateAlternateColorCodes('&', line.color)
            player.sendMessage(
                language.getMessage(
                    "passenger.boarded",
                    LanguageManager.put(
                        LanguageManager.put(LanguageManager.args(), "color_code", colorized),
                        "line_name",
                        line.name,
                    ),
                ),
            )
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onEntityDismount(event: VehicleExitEvent) {
        val player = event.exited as? Player ?: return
        val controller = plugin.entityModelController ?: return
        val dismounted = event.vehicle
        if (!controller.isModelEntity(dismounted.uniqueId)) return

        val cart = controller.getCartByModelEntity(dismounted.uniqueId) ?: return
        plugin.lineServiceManager?.getTrainByMinecart(cart.uniqueId)?.removePassenger(player)
        plugin.scoreboardManager?.clearPlayerDisplay(player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageEvent) {
        val controller = plugin.entityModelController ?: return
        if (!controller.isModelEntity(event.entity.uniqueId)) return

        event.isCancelled = true
        val player = (event as? EntityDamageByEntityEvent)?.damager as? Player ?: return
        if (player.hasPermission("railway.admin") && player.gameMode == GameMode.CREATIVE) {
            event.isCancelled = false
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityTarget(event: EntityTargetEvent) {
        val controller = plugin.entityModelController ?: return
        if (controller.isModelEntity(event.entity.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerLeashEntity(event: PlayerLeashEntityEvent) {
        val controller = plugin.entityModelController ?: return
        if (controller.isModelEntity(event.entity.uniqueId)) event.isCancelled = true
    }
}
