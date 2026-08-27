package org.cubexmc.economy

import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Account transfers, separate from [VaultEconomy.charge]'s consumed-service semantics.
 * An explicit failed deposit is compensated. An exception or absent response is ambiguous:
 * do not retry or refund blindly; the operator must reconcile it with the economy provider.
 * Call on the server thread. This is not a durable transaction or a crash recovery journal.
 */
class VaultTransfers @JvmOverloads constructor(
    private val economy: Economy,
    private val lookup: OfflinePlayerLookup = BukkitOfflinePlayerLookup,
    private val logger: Logger = Logger.getLogger("CubeX"),
) {
    enum class Result { SUCCESS, NO_ECONOMY, INVALID_AMOUNT, INSUFFICIENT, ROLLBACK_FAILED, REVIEW_REQUIRED, FAILED }

    fun transfer(fromName: String, toName: String, amount: Double): Result {
        if (!amount.isFinite() || amount <= 0.0) return Result.INVALID_AMOUNT
        val from: Account
        val to: Account
        try {
            from = resolve(EconomyAccount.parse(fromName)) ?: return Result.FAILED
            to = resolve(EconomyAccount.parse(toName)) ?: return Result.FAILED
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "Cannot resolve transfer accounts '$fromName' -> '$toName'.", failure)
            return Result.FAILED
        }
        // One provider monitor also serializes overlapping pairs, without an unbounded lock map.
        return synchronized(economy) {
            try {
                if (!has(from, amount)) return@synchronized Result.INSUFFICIENT
                val withdrawal = withdraw(from, amount)
                    ?: return@synchronized uncertain(fromName, toName, amount)
                if (!withdrawal.transactionSuccess()) return@synchronized Result.FAILED
                val deposited = deposit(to, amount)
                    ?: return@synchronized uncertain(fromName, toName, amount)
                if (deposited.transactionSuccess()) return@synchronized Result.SUCCESS
                val refund = deposit(from, amount)
                    ?: return@synchronized uncertain(fromName, toName, amount)
                if (refund.transactionSuccess()) Result.FAILED else {
                    logger.severe("Transfer compensation failed: '$fromName' -> '$toName', amount=$amount. Reconcile manually.")
                    Result.ROLLBACK_FAILED
                }
            } catch (failure: Exception) {
                logger.log(Level.SEVERE, "Economy provider threw during a transfer. Do not retry before reconciliation.", failure)
                uncertain(fromName, toName, amount)
            }
        }
    }

    private fun uncertain(from: String, to: String, amount: Double): Result {
        logger.severe("Transfer outcome unknown: '$from' -> '$to', amount=$amount. Reconcile manually; do not retry automatically.")
        return Result.REVIEW_REQUIRED
    }

    @Suppress("DEPRECATION")
    private fun resolve(spec: EconomyAccount): Account? = when (spec) {
        EconomyAccount.None -> null
        is EconomyAccount.PlayerUuid -> Account.Player(lookup.byUuid(spec.uuid))
        is EconomyAccount.RawName -> Account.Named(spec.name)
        is EconomyAccount.Bank -> if (economy.hasBankSupport() && economy.bankBalance(spec.name)?.transactionSuccess() == true) {
            Account.Bank(spec.name)
        } else null
        is EconomyAccount.PlayerName -> {
            val known = lookup.knownByName(spec.name)
            when {
                known is NameLookup.Found -> Account.Player(known.player)
                economy.hasAccount(spec.name) -> Account.Named(spec.name)
                else -> (lookup.byName(spec.name) as? NameLookup.Found)?.let { Account.Player(it.player) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun has(account: Account, amount: Double): Boolean = when (account) {
        is Account.Player -> economy.has(account.player, amount)
        is Account.Named -> economy.has(account.name, amount)
        is Account.Bank -> economy.bankHas(account.name, amount)?.transactionSuccess() == true
    }

    @Suppress("DEPRECATION")
    private fun withdraw(account: Account, amount: Double): EconomyResponse? = when (account) {
        is Account.Player -> economy.withdrawPlayer(account.player, amount)
        is Account.Named -> economy.withdrawPlayer(account.name, amount)
        is Account.Bank -> economy.bankWithdraw(account.name, amount)
    }

    @Suppress("DEPRECATION")
    private fun deposit(account: Account, amount: Double): EconomyResponse? = when (account) {
        is Account.Player -> economy.depositPlayer(account.player, amount)
        is Account.Named -> economy.depositPlayer(account.name, amount)
        is Account.Bank -> economy.bankDeposit(account.name, amount)
    }

    private sealed interface Account {
        class Player(val player: OfflinePlayer) : Account
        class Named(val name: String) : Account
        class Bank(val name: String) : Account
    }

    companion object {
        @JvmStatic
        fun hook(plugin: Plugin): VaultTransfers? {
            val vault = plugin.server.pluginManager.getPlugin("Vault") ?: return null
            if (!vault.isEnabled) return null
            val registration = plugin.server.servicesManager.getRegistration(Economy::class.java) ?: return null
            return VaultTransfers(registration.provider, BukkitOfflinePlayerLookup, plugin.logger)
        }
    }
}
