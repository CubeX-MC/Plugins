package org.cubexmc.contract;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.cubexmc.contract.command.ContractCommand;
import org.cubexmc.contract.config.LanguageManager;
import org.cubexmc.contract.economy.EconomyService;
import org.cubexmc.contract.gui.ContractGui;
import org.cubexmc.contract.service.ContractService;
import org.cubexmc.contract.storage.ContractStorage;
import org.cubexmc.contract.storage.EventLog;
import org.cubexmc.contract.storage.PendingTransactionStore;

import java.io.File;
import java.io.IOException;

public final class ContractPlugin extends JavaPlugin {
    private LanguageManager languageManager;
    private EconomyService economyService;
    private ContractStorage contractStorage;
    private PendingTransactionStore pendingStore;
    private EventLog eventLog;
    private ContractService contractService;
    private ContractGui contractGui;

    @Override
    public void onEnable() {
        saveDefaultFiles();

        languageManager = new LanguageManager(this);
        languageManager.load();

        economyService = new EconomyService(this);
        if (!economyService.setup()) {
            getLogger().severe("Vault economy provider not found. Contracts will be disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        contractStorage = new ContractStorage(this);
        contractStorage.load();
        pendingStore = new PendingTransactionStore(this);
        eventLog = new EventLog(this);
        contractService = new ContractService(this, contractStorage, economyService, pendingStore, eventLog);
        contractService.recoverPendingTransactions();
        contractGui = new ContractGui(this);
        getServer().getPluginManager().registerEvents(contractGui, this);

        ContractCommand command = new ContractCommand(this);
        PluginCommand pluginCommand = getCommand("contract");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        scheduleCleanup();
        scheduleFlush();
        new Metrics(this, 31491);
        getLogger().info("Contract enabled with " + contractStorage.all().size() + " stored contracts.");
    }

    @Override
    public void onDisable() {
        if (contractGui != null) {
            contractGui.closeSessions();
        }
        if (contractStorage != null) {
            try {
                contractStorage.save();
            } catch (IOException ex) {
                getLogger().warning("Failed to save contracts on disable: " + ex.getMessage());
            }
        }
    }

    public void reloadContracts() {
        if (contractGui != null) {
            contractGui.closeSessions();
        }
        saveDefaultFiles();
        reloadConfig();
        languageManager.load();
        contractStorage.load();
    }

    public LanguageManager lang() {
        return languageManager;
    }

    public EconomyService economy() {
        return economyService;
    }

    public ContractStorage storage() {
        return contractStorage;
    }

    public ContractService contracts() {
        return contractService;
    }

    public ContractGui gui() {
        return contractGui;
    }

    private void saveDefaultFiles() {
        saveDefaultConfig();
        saveLanguage();
    }

    private void saveLanguage() {
        saveLanguage("zh_CN");
        saveLanguage("en_US");
    }

    private void saveLanguage(String language) {
        File langFile = new File(getDataFolder(), "lang/" + language + ".yml");
        if (!langFile.exists()) {
            saveResource("lang/" + language + ".yml", false);
        }
    }

    private void scheduleFlush() {
        long intervalSeconds = Math.max(5, getConfig().getLong("storage.flush-interval-seconds", 30));
        long periodTicks = intervalSeconds * 20L;
        getServer().getScheduler().runTaskTimer(this, () -> {
            try {
                contractStorage.flushIfDirty();
            } catch (IOException ex) {
                getLogger().warning("Async flush failed: " + ex.getMessage());
            }
        }, periodTicks, periodTicks);
    }

    private void scheduleCleanup() {
        long intervalMinutes = Math.max(1, getConfig().getLong("expiry.cleanup-interval-minutes", 10));
        long periodTicks = intervalMinutes * 60L * 20L;
        getServer().getScheduler().runTaskTimer(this, () -> {
            int changed = contractService.cleanupExpired();
            if (changed > 0) {
                getLogger().info("Processed " + changed + " expired or auto-approved contracts.");
            }
        }, periodTicks, periodTicks);
    }
}
