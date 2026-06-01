package org.cubexmc.contract;

import org.bukkit.command.PluginCommand;
import org.bukkit.scheduler.BukkitTask;
import org.cubexmc.contract.command.ContractCommand;
import org.cubexmc.contract.config.LanguageManager;
import org.cubexmc.contract.economy.EconomyService;
import org.cubexmc.contract.gui.ContractGui;
import org.cubexmc.contract.service.ContractService;
import org.cubexmc.contract.storage.ContractStorage;
import org.cubexmc.contract.storage.EventLog;
import org.cubexmc.contract.storage.PendingTransactionStore;
import org.cubexmc.core.CubexPlugin;

import java.io.File;
import java.io.IOException;

public final class ContractPlugin extends CubexPlugin {
    private LanguageManager languageManager;
    private EconomyService economyService;
    private ContractStorage contractStorage;
    private PendingTransactionStore pendingStore;
    private EventLog eventLog;
    private ContractService contractService;
    private ContractGui contractGui;

    @Override
    protected void enablePlugin() {
        saveDefaultFiles();

        languageManager = new LanguageManager(this);
        languageManager.load();

        economyService = new EconomyService(this);
        if (!economyService.setup()) {
            abortEnable("Vault economy provider not found. Contracts will be disabled.");
        }

        contractStorage = new ContractStorage(this);
        contractStorage.load();
        Runnable saveContractStorage = () -> {
            if (contractStorage != null) {
                try {
                    contractStorage.save();
                } catch (IOException ex) {
                    getLogger().warning("Failed to save contracts on disable: " + ex.getMessage());
                }
            }
        };
        bind(saveContractStorage);
        pendingStore = new PendingTransactionStore(this);
        eventLog = new EventLog(this);
        contractService = new ContractService(this, contractStorage, economyService, pendingStore, eventLog);
        contractService.recoverPendingTransactions();
        contractGui = new ContractGui(this);
        Runnable closeContractGui = () -> {
            if (contractGui != null) {
                contractGui.closeSessions();
            }
        };
        bind(closeContractGui);
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
    protected void disablePlugin() {
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
        saveResourcesIfMissing("config.yml", "lang/zh_CN.yml", "lang/en_US.yml");
    }

    private void scheduleFlush() {
        long intervalSeconds = Math.max(5, getConfig().getLong("storage.flush-interval-seconds", 30));
        long periodTicks = intervalSeconds * 20L;
        BukkitTask task = getServer().getScheduler().runTaskTimer(this, () -> {
            try {
                contractStorage.flushIfDirty();
            } catch (IOException ex) {
                getLogger().warning("Async flush failed: " + ex.getMessage());
            }
        }, periodTicks, periodTicks);
        bindTask(task, handle -> ((BukkitTask) handle).cancel());
    }

    private void scheduleCleanup() {
        long intervalMinutes = Math.max(1, getConfig().getLong("expiry.cleanup-interval-minutes", 10));
        long periodTicks = intervalMinutes * 60L * 20L;
        BukkitTask task = getServer().getScheduler().runTaskTimer(this, () -> {
            int changed = contractService.cleanupExpired();
            if (changed > 0) {
                getLogger().info("Processed " + changed + " expired or auto-approved contracts.");
            }
        }, periodTicks, periodTicks);
        bindTask(task, handle -> ((BukkitTask) handle).cancel());
    }
}
