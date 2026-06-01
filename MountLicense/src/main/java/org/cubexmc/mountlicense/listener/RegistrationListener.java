package org.cubexmc.mountlicense.listener;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.mountlicense.MountLicensePlugin;
import org.cubexmc.mountlicense.service.PdcKeys;
import org.cubexmc.mountlicense.service.RegistryService;
import org.cubexmc.mountlicense.service.RegistryService.Result;
import org.bukkit.persistence.PersistentDataType;

public class RegistrationListener implements Listener {

    private final MountLicensePlugin plugin;
    private final RegistryService registryService;
    private final PdcKeys keys;

    public RegistrationListener(MountLicensePlugin plugin, RegistryService registryService, PdcKeys keys) {
        this.plugin = plugin;
        this.registryService = registryService;
        this.keys = keys;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        if (!player.isSneaking()) return;

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isLicenseRole(hand)) return;

        Entity target = event.getRightClicked();
        double distance = player.getLocation().distance(target.getLocation());
        if (distance > plugin.configManager().getMaxInteractDistance()) return;

        Result result = registryService.tryRegister(player, target, hand);
        if (result == Result.SUCCESS || result != Result.NO_PROFILE) {
            event.setCancelled(true);
        }
    }

    private boolean isLicenseRole(ItemStack item) {
        if (item == null) return false;
        if (item.getItemMeta() == null) return false;
        String role = item.getItemMeta().getPersistentDataContainer()
                .get(keys.itemRole(), PersistentDataType.STRING);
        return PdcKeys.ITEM_ROLE_LICENSE.equals(role);
    }
}
