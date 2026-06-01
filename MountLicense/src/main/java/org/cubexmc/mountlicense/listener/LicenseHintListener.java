package org.cubexmc.mountlicense.listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.mountlicense.MountLicensePlugin;
import org.cubexmc.mountlicense.lang.LanguageManager;
import org.cubexmc.mountlicense.service.ItemFactory;

public class LicenseHintListener implements Listener {

    private static final String REGISTER_PERMISSION = "mountlicense.register";
    private static final long HINT_DEBOUNCE_MS = 2000L;

    private final MountLicensePlugin plugin;
    private final ItemFactory itemFactory;
    private final LanguageManager lang;
    private final Map<UUID, Long> lastHint = new ConcurrentHashMap<>();

    public LicenseHintListener(MountLicensePlugin plugin, ItemFactory itemFactory, LanguageManager lang) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
        this.lang = lang;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack newHand = player.getInventory().getItem(event.getNewSlot());
        maybeShowHint(player, newHand);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        maybeShowHint(event.getPlayer(), event.getOffHandItem());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                maybeShowHint(player, player.getInventory().getItemInMainHand());
            }
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastHint.remove(event.getPlayer().getUniqueId());
    }

    void maybeShowHint(Player player, ItemStack hand) {
        if (player == null || !player.hasPermission(REGISTER_PERMISSION)) return;
        if (!itemFactory.isLicense(hand)) return;

        long now = System.currentTimeMillis();
        Long last = lastHint.get(player.getUniqueId());
        if (last != null && now - last < HINT_DEBOUNCE_MS) return;
        lastHint.put(player.getUniqueId(), now);

        lang.sendActionBar(player, "license_item.hint_actionbar");
    }
}
