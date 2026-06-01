package org.cubexmc.mountlicense.integration;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.cubexmc.mountlicense.MountLicensePlugin;

public class EconomyHook {

    private final MountLicensePlugin plugin;
    private Object economy;
    private boolean ready;

    public EconomyHook(MountLicensePlugin plugin) {
        this.plugin = plugin;
        attach();
    }

    public boolean isReady() {
        return ready && plugin.configManager().isEconomyEnabled();
    }

    private void attach() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(economyClass);
            if (rsp == null) return;
            economy = rsp.getProvider();
            ready = true;
            plugin.getLogger().info("Vault economy hook attached.");
        } catch (ClassNotFoundException ex) {
            plugin.getLogger().warning("Vault detected but Economy class missing.");
        }
    }

    public boolean has(OfflinePlayer player, double amount) {
        if (!isReady() || amount <= 0) return true;
        try {
            return (boolean) economy.getClass()
                    .getMethod("has", OfflinePlayer.class, double.class)
                    .invoke(economy, player, amount);
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Economy.has failed: " + ex.getMessage());
            return true;
        }
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        if (!isReady() || amount <= 0) return true;
        try {
            Object response = economy.getClass()
                    .getMethod("withdrawPlayer", OfflinePlayer.class, double.class)
                    .invoke(economy, player, amount);
            return (boolean) response.getClass().getField("transactionSuccess").get(response);
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Economy.withdraw failed: " + ex.getMessage());
            return false;
        }
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        if (!isReady() || amount <= 0) return true;
        try {
            Object response = economy.getClass()
                    .getMethod("depositPlayer", OfflinePlayer.class, double.class)
                    .invoke(economy, player, amount);
            return (boolean) response.getClass().getField("transactionSuccess").get(response);
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Economy.deposit failed: " + ex.getMessage());
            return false;
        }
    }
}
