package org.cubexmc.metro.integration

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.cubexmc.metro.Metro
import java.util.UUID

class VaultIntegration(private val plugin: Metro) {

    private var economy: Economy? = null
    private val enabled: Boolean = setupEconomy()

    private fun setupEconomy(): Boolean {
        if (plugin.server.pluginManager.getPlugin("Vault") == null) {
            return false
        }
        val rsp = plugin.server.servicesManager.getRegistration(Economy::class.java) ?: return false
        economy = rsp.provider
        return economy != null
    }

    fun isEnabled(): Boolean = enabled

    fun getEconomy(): Economy? = economy

    fun has(player: Player, amount: Double): Boolean {
        val economy = this.economy
        if (!enabled || economy == null) return false
        return economy.has(player, amount)
    }

    fun withdraw(player: Player, amount: Double): Boolean {
        val economy = this.economy
        if (!enabled || economy == null) return false
        return economy.withdrawPlayer(player, amount).transactionSuccess()
    }

    fun deposit(uuid: UUID?, amount: Double): Boolean {
        val economy = this.economy
        if (!enabled || uuid == null || economy == null) return false
        val offlinePlayer = Bukkit.getOfflinePlayer(uuid)
        return economy.depositPlayer(offlinePlayer, amount).transactionSuccess()
    }

    fun format(amount: Double): String {
        val economy = this.economy
        if (!enabled || economy == null) return amount.toString()
        return economy.format(amount)
    }
}
