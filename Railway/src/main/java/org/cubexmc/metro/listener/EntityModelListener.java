package org.cubexmc.metro.listener;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.cubexmc.metro.Metro;
import org.cubexmc.metro.manager.LanguageManager;
import org.cubexmc.metro.model.EntityModelController;
import org.cubexmc.metro.service.LineServiceManager;
import org.cubexmc.metro.train.TrainInstance;

/**
 * Handles player interaction with entity model visuals when entity_model mode is enabled.
 * <p>
 * This listener mirrors the boarding/dismounting logic from {@link VehicleListener}
 * but routes interactions through the visual entity instead of the minecart.
 */
public class EntityModelListener implements Listener {

    private final Metro plugin;
    private final LanguageManager language;

    public EntityModelListener(Metro plugin) {
        this.plugin = plugin;
        this.language = plugin.getLanguageManager();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!(clicked instanceof LivingEntity)) return;

        EntityModelController ctrl = plugin.getEntityModelController();
        if (ctrl == null) return;
        if (!ctrl.isModelEntity(clicked.getUniqueId())) return;

        // This is one of our visual model entities
        event.setCancelled(true);

        Player player = event.getPlayer();
        Minecart cart = ctrl.getCartByModelEntity(clicked.getUniqueId());
        if (cart == null || cart.isDead()) return;

        LineServiceManager manager = plugin.getLineServiceManager();
        TrainInstance train = manager.getTrainByMinecart(cart.getUniqueId());
        if (train == null) return;
        if (train.isPassenger(player)) return;

        // Only allow boarding during WAITING state
        if (train.isMoving()) {
            player.sendMessage(language.getMessage("passenger.cannot_board_moving"));
            return;
        }

        // Economy check
        if (plugin.getConfig().getBoolean("economy.enabled", true) && train.getLine() != null) {
            double ticketPrice = train.getLine().getTicketPrice();
            if (ticketPrice > 0) {
                org.cubexmc.metro.integration.VaultIntegration vault = plugin.getVaultIntegration();
                if (vault != null && vault.isEnabled()) {
                    if (!vault.has(player, ticketPrice)) {
                        player.sendMessage(language.getMessage("economy.insufficient_funds",
                                LanguageManager.put(LanguageManager.args(), "price",
                                        vault.format(ticketPrice))));
                        return;
                    }
                    vault.withdraw(player, ticketPrice);
                    player.sendMessage(language.getMessage("economy.paid_boarding",
                            LanguageManager.put(LanguageManager.args(), "price",
                                    vault.format(ticketPrice))));
                }
            }
        }

        // Board the player onto the model entity
        LivingEntity model = (LivingEntity) clicked;
        boolean boarded;
        if (ctrl.isMultiPassenger()) {
            boarded = model.addPassenger(player);
        } else {
            if (!model.getPassengers().isEmpty()) {
                player.sendMessage(language.getMessage("passenger.cannot_board_moving"));
                return;
            }
            boarded = model.addPassenger(player);
        }

        if (!boarded) return;

        // Register passenger in the train's registry
        train.addPassenger(player, cart);

        // Show welcome message
        if (train.getLine() != null) {
            String colorized = ChatColor.translateAlternateColorCodes('&', train.getLine().getColor());
            player.sendMessage(language.getMessage("passenger.boarded",
                    LanguageManager.put(LanguageManager.put(LanguageManager.args(),
                            "color", colorized),
                            "line_name", train.getLine().getName())));
        }

    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onEntityDismount(VehicleExitEvent event) {
        Entity entity = event.getExited();
        if (!(entity instanceof Player)) return;

        Entity dismounted = event.getVehicle();
        if (dismounted == null) return;

        EntityModelController ctrl = plugin.getEntityModelController();
        if (ctrl == null) return;
        if (!ctrl.isModelEntity(dismounted.getUniqueId())) return;

        Player player = (Player) entity;
        Minecart cart = ctrl.getCartByModelEntity(dismounted.getUniqueId());
        if (cart == null) return;

        LineServiceManager manager = plugin.getLineServiceManager();
        TrainInstance train = manager.getTrainByMinecart(cart.getUniqueId());

        if (train != null) {
            train.removePassenger(player);
        }
        if (plugin.getScoreboardManager() != null) {
            plugin.getScoreboardManager().clearPlayerDisplay(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        EntityModelController ctrl = plugin.getEntityModelController();
        if (ctrl == null) return;
        if (!ctrl.isModelEntity(entity.getUniqueId())) return;

        // Protect model entities from all damage
        event.setCancelled(true);

        // Allow OP players in creative to remove model entities
        if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent damageByEntity) {
            if (damageByEntity.getDamager() instanceof Player player) {
                if (player.hasPermission("railway.admin") && player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
                    event.setCancelled(false);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetEvent event) {
        Entity entity = event.getEntity();
        EntityModelController ctrl = plugin.getEntityModelController();
        if (ctrl == null) return;
        if (ctrl.isModelEntity(entity.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerLeashEntity(PlayerLeashEntityEvent event) {
        EntityModelController ctrl = plugin.getEntityModelController();
        if (ctrl == null) return;
        if (ctrl.isModelEntity(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
