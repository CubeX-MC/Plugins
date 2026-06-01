package org.cubexmc.mountlicense.listener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.mountlicense.MountLicensePlugin;
import org.cubexmc.mountlicense.lang.LanguageManager;
import org.cubexmc.mountlicense.model.VehicleRecord;
import org.cubexmc.mountlicense.service.ItemFactory;
import org.cubexmc.mountlicense.service.OwnershipService;
import org.cubexmc.mountlicense.service.RecallService;
import org.cubexmc.mountlicense.service.RecallService.Result;

public class KeyItemListener implements Listener {

    private final MountLicensePlugin plugin;
    private final ItemFactory itemFactory;
    private final OwnershipService ownership;
    private final RecallService recall;
    private final LanguageManager lang;

    public KeyItemListener(MountLicensePlugin plugin, ItemFactory itemFactory,
                           OwnershipService ownership, RecallService recall,
                           LanguageManager lang) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
        this.ownership = ownership;
        this.recall = recall;
        this.lang = lang;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAirInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR) return;
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!itemFactory.isKey(item)) return;

        UUID bound = itemFactory.readBoundVehicleId(item);
        Result result = bound != null
                ? recall.recallById(player, bound)
                : recall.recallNearest(player);

        recall.sendResultMessage(player, result, bound);
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMountInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!player.isSneaking()) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (!itemFactory.isKey(item)) return;

        Entity target = event.getRightClicked();
        UUID vehicleId = ownership.readVehicleId(target);
        if (vehicleId == null) {
            lang.send(player, "key.target_not_registered");
            event.setCancelled(true);
            return;
        }
        if (!ownership.isOwner(target, player.getUniqueId())
                && !player.hasPermission(OwnershipService.BYPASS_PERMISSION)) {
            lang.send(player, "key.target_not_owner");
            event.setCancelled(true);
            return;
        }

        VehicleRecord record = plugin.vehicleIndex().byId(vehicleId);
        String shortLabel = record != null ? record.shortId() : vehicleId.toString().substring(0, 8);
        itemFactory.bindKey(item, vehicleId, shortLabel);

        Map<String, String> ph = new HashMap<>();
        ph.put("short_id", shortLabel);
        ph.put("entity_type", target.getType().name());
        lang.send(player, "key.bound", ph);
        event.setCancelled(true);
    }

}
