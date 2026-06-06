package org.cubexmc.ecobalancer.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.utils.MessageUtils

class AdminLoginListener(private val plugin: EcoBalancer) : Listener {
    @EventHandler
    fun onAdminLogin(event: PlayerJoinEvent) {
        val player = event.player
        if (player.hasPermission("ecobalancer.admin") && plugin.config.getBoolean("info-on-login")) {
            if (plugin.useTaxAccount()) {
                val placeholders: MutableMap<String, String> = HashMap()
                placeholders["tax_account_name"] = plugin.taxAccountName ?: ""
                placeholders["tax_account_balance"] = plugin.taxAccountBalance
                MessageUtils.sendMessage(player, plugin.getFormattedMessage("messages.tax_account_enabled", placeholders), null, false)
            } else {
                MessageUtils.sendMessage(player, plugin.getFormattedMessage("messages.tax_account_disabled", null), null, false)
            }
        }
    }
}
