package org.cubexmc.contract.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.cubexmc.contract.ContractPlugin;

import java.math.BigDecimal;
import java.util.UUID;

public final class EconomyService {
    private final ContractPlugin plugin;
    private Economy economy;

    public EconomyService(ContractPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean setup() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            return false;
        }
        economy = provider.getProvider();
        plugin.getLogger().info("Vault economy hooked: " + economy.getName());
        return true;
    }

    public boolean has(Player player, BigDecimal amount) {
        return economy != null && economy.has(player, amount.doubleValue());
    }

    public TransactionResult withdraw(Player player, BigDecimal amount) {
        if (economy == null) {
            return TransactionResult.fail("Vault economy is not available");
        }
        EconomyResponse response = economy.withdrawPlayer(player, amount.doubleValue());
        if (!response.transactionSuccess()) {
            return TransactionResult.fail(response.errorMessage);
        }
        return TransactionResult.ok();
    }

    public TransactionResult deposit(UUID playerUuid, BigDecimal amount) {
        if (economy == null) {
            return TransactionResult.fail("Vault economy is not available");
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
        EconomyResponse response = economy.depositPlayer(player, amount.doubleValue());
        if (!response.transactionSuccess()) {
            return TransactionResult.fail(response.errorMessage);
        }
        return TransactionResult.ok();
    }

    public String format(BigDecimal amount) {
        if (economy != null) {
            return economy.format(amount.doubleValue());
        }
        return String.format("$%.2f", amount.doubleValue());
    }

    public record TransactionResult(boolean success, String reason) {
        public static TransactionResult ok() {
            return new TransactionResult(true, "");
        }

        public static TransactionResult fail(String reason) {
            return new TransactionResult(false, reason == null || reason.isBlank() ? "economy transaction failed" : reason);
        }
    }
}
