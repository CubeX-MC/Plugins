package org.cubexmc.ecobalancer.utils

import net.milkbowl.vault.economy.Economy
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.java.JavaPlugin

object VaultUtils {
    private var economy: Economy? = null

    @JvmStatic
    fun setupEconomy(plugin: JavaPlugin): Boolean {
        if (plugin.server.pluginManager.getPlugin("Vault") == null) {
            plugin.logger.severe("未找到Vault插件，经济系统无法初始化")
            return false
        }

        val rsp = plugin.server.servicesManager.getRegistration(Economy::class.java)
        if (rsp == null) {
            plugin.logger.severe("未找到Vault经济服务提供者")
            return false
        }

        economy = rsp.provider
        return economy != null
    }

    @JvmStatic
    fun getEconomy(): Economy? = economy

    @JvmStatic
    fun hasAccount(player: OfflinePlayer): Boolean = requireEconomy().hasAccount(player)

    @JvmStatic
    fun getBalance(player: OfflinePlayer): Double = requireEconomy().getBalance(player)

    @JvmStatic
    fun depositPlayer(player: OfflinePlayer, amount: Double): Boolean =
        requireEconomy().depositPlayer(player, amount).transactionSuccess()

    @JvmStatic
    fun withdrawPlayer(player: OfflinePlayer, amount: Double): Boolean =
        requireEconomy().withdrawPlayer(player, amount).transactionSuccess()

    @JvmStatic
    fun setupTaxAccount(taxAccountName: String): Boolean {
        val economy = requireEconomy()
        if (economy.hasAccount(taxAccountName)) {
            return true
        }
        return economy.createPlayerAccount(taxAccountName)
    }

    @JvmStatic
    fun getTaxAccountBalance(taxAccountName: String): Double {
        val economy = requireEconomy()
        if (!economy.hasAccount(taxAccountName)) {
            return 0.0
        }
        return economy.getBalance(taxAccountName)
    }

    @JvmStatic
    fun depositToTaxAccount(taxAccountName: String, amount: Double): Boolean {
        val economy = requireEconomy()
        if (!economy.hasAccount(taxAccountName) && !setupTaxAccount(taxAccountName)) {
            return false
        }
        return economy.depositPlayer(taxAccountName, amount).transactionSuccess()
    }

    private fun requireEconomy(): Economy =
        economy ?: throw IllegalStateException("Vault经济系统未初始化")
}
