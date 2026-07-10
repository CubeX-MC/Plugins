package org.cubexmc.contract.economy

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.cubexmc.contract.ContractPlugin
import java.math.BigDecimal
import java.util.UUID

class EconomyService(private val plugin: ContractPlugin) {
    private var economy: Economy? = null

    fun setup(): Boolean {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false
        }
        val provider = Bukkit.getServicesManager().getRegistration(Economy::class.java) ?: return false
        economy = provider.provider
        plugin.logger.info("Vault economy hooked: ${provider.provider.name}")
        return true
    }

    fun has(player: Player, amount: BigDecimal): Boolean {
        if (amount.signum() < 0) {
            return false
        }
        if (amount.signum() == 0) {
            return true
        }
        return economy?.has(player, amount.toDouble()) == true
    }

    fun withdraw(player: Player, amount: BigDecimal): TransactionResult {
        if (amount.signum() < 0) {
            return TransactionResult.fail("amount must not be negative")
        }
        if (amount.signum() == 0) {
            return TransactionResult.ok()
        }
        val activeEconomy = economy ?: return TransactionResult.fail("Vault economy is not available")
        val response = activeEconomy.withdrawPlayer(player, amount.toDouble())
        if (!response.transactionSuccess()) {
            return TransactionResult.fail(response.errorMessage)
        }
        return TransactionResult.ok()
    }

    fun deposit(playerUuid: UUID, amount: BigDecimal): TransactionResult {
        if (amount.signum() < 0) {
            return TransactionResult.fail("amount must not be negative")
        }
        if (amount.signum() == 0) {
            return TransactionResult.ok()
        }
        val activeEconomy = economy ?: return TransactionResult.fail("Vault economy is not available")
        val player = Bukkit.getOfflinePlayer(playerUuid)
        val response = activeEconomy.depositPlayer(player, amount.toDouble())
        if (!response.transactionSuccess()) {
            return TransactionResult.fail(response.errorMessage)
        }
        return TransactionResult.ok()
    }

    fun format(amount: BigDecimal): String =
        economy?.format(amount.toDouble()) ?: "$%.2f".format(amount.toDouble())

    class TransactionResult(
        private val success: Boolean,
        private val reason: String,
    ) {
        fun success(): Boolean = success

        fun reason(): String = reason

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is TransactionResult) {
                return false
            }
            return success == other.success && reason == other.reason
        }

        override fun hashCode(): Int = 31 * success.hashCode() + reason.hashCode()

        override fun toString(): String = "TransactionResult[success=$success, reason=$reason]"

        companion object {
            @JvmStatic
            fun ok(): TransactionResult = TransactionResult(true, "")

            @JvmStatic
            fun fail(reason: String?): TransactionResult =
                TransactionResult(
                    false,
                    if (reason.isNullOrBlank()) "economy transaction failed" else reason,
                )
        }
    }
}
