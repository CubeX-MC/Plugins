package org.cubexmc.ecobalancer.listeners;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.cubexmc.ecobalancer.EcoBalancer;

import java.util.HashMap;
import java.util.Map;

public class AdminLoginListener implements Listener {
    EcoBalancer plugin;
    public AdminLoginListener(EcoBalancer plugin) {
        this.plugin = plugin;
    }
    @EventHandler
    public void onAdminLogin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("ecobalancer.admin") && plugin.getConfig().getBoolean("info-on-login")) {
            if (plugin.useTaxAccount()) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("tax_account_name", plugin.getTaxAccountName());
                placeholders.put("tax_account_balance", plugin.getTaxAccountBalance());
                org.cubexmc.ecobalancer.utils.MessageUtils.sendMessage(player, plugin.getFormattedMessage("messages.tax_account_enabled", placeholders), null, false);
            } else {
                org.cubexmc.ecobalancer.utils.MessageUtils.sendMessage(player, plugin.getFormattedMessage("messages.tax_account_disabled", null), null, false);
            }
        }
    }
}