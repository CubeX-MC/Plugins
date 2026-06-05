package org.cubexmc.mountlicense.integration

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.RegisteredServiceProvider
import org.cubexmc.mountlicense.MountLicensePlugin

class EconomyHook(private val plugin: MountLicensePlugin) {
    private var economy: Any? = null
    private var ready = false

    init {
        attach()
    }

    fun isReady(): Boolean = ready && plugin.configManager().isEconomyEnabled()

    private fun attach() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return
        try {
            val economyClass = Class.forName("net.milkbowl.vault.economy.Economy")
            val rsp: RegisteredServiceProvider<*> = Bukkit.getServicesManager().getRegistration(economyClass)
                ?: return
            economy = rsp.provider
            ready = true
            plugin.logger.info("Vault economy hook attached.")
        } catch (ex: ClassNotFoundException) {
            plugin.logger.warning("Vault detected but Economy class missing.")
        }
    }

    fun has(player: OfflinePlayer, amount: Double): Boolean {
        val currentEconomy = economy
        if (!isReady() || amount <= 0 || currentEconomy == null) return true
        return try {
            currentEconomy.javaClass
                .getMethod("has", OfflinePlayer::class.java, java.lang.Double.TYPE)
                .invoke(currentEconomy, player, amount) as Boolean
        } catch (ex: ReflectiveOperationException) {
            plugin.logger.warning("Economy.has failed: ${ex.message}")
            true
        }
    }

    fun withdraw(player: OfflinePlayer, amount: Double): Boolean {
        val currentEconomy = economy
        if (!isReady() || amount <= 0 || currentEconomy == null) return true
        return try {
            val response = currentEconomy.javaClass
                .getMethod("withdrawPlayer", OfflinePlayer::class.java, java.lang.Double.TYPE)
                .invoke(currentEconomy, player, amount)
            response.javaClass.getField("transactionSuccess").get(response) as Boolean
        } catch (ex: ReflectiveOperationException) {
            plugin.logger.warning("Economy.withdraw failed: ${ex.message}")
            false
        }
    }

    fun deposit(player: OfflinePlayer, amount: Double): Boolean {
        val currentEconomy = economy
        if (!isReady() || amount <= 0 || currentEconomy == null) return true
        return try {
            val response = currentEconomy.javaClass
                .getMethod("depositPlayer", OfflinePlayer::class.java, java.lang.Double.TYPE)
                .invoke(currentEconomy, player, amount)
            response.javaClass.getField("transactionSuccess").get(response) as Boolean
        } catch (ex: ReflectiveOperationException) {
            plugin.logger.warning("Economy.deposit failed: ${ex.message}")
            false
        }
    }
}
