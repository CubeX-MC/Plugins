package org.cubexmc.metro.integration

import java.util.UUID
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.cubexmc.metro.Metro

class VaultIntegration(private val plugin: Metro) {
    var economy: Economy? = null
        private set
    var isEnabled: Boolean = setupEconomy()
        private set

    private fun setupEconomy(): Boolean {
        if (plugin.server.pluginManager.getPlugin("Vault") == null) {
            return false
        }
        val registration = plugin.server.servicesManager.getRegistration(Economy::class.java) ?: return false
        economy = registration.provider
        return economy != null
    }

    fun has(player: Player, amount: Double): Boolean {
        if (!isEnabled) {
            return false
        }
        return economy?.has(player, amount) ?: false
    }

    fun withdraw(player: Player, amount: Double): Boolean {
        if (!isEnabled) {
            return false
        }
        return economy?.withdrawPlayer(player, amount)?.transactionSuccess() ?: false
    }

    fun deposit(uuid: UUID?, amount: Double): Boolean {
        if (!isEnabled || uuid == null) {
            return false
        }
        val offlinePlayer = Bukkit.getOfflinePlayer(uuid)
        return economy?.depositPlayer(offlinePlayer, amount)?.transactionSuccess() ?: false
    }

    fun format(amount: Double): String {
        if (!isEnabled) {
            return amount.toString()
        }
        return economy?.format(amount) ?: amount.toString()
    }
}
