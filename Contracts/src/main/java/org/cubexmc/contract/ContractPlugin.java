package org.cubexmc.contract;

import org.bukkit.command.PluginCommand;
import org.bukkit.scheduler.BukkitTask;
import org.cubexmc.config.LegacyTextToMiniMessageStep;
import org.cubexmc.config.MigrationException;
import org.cubexmc.config.MigrationPlan;
import org.cubexmc.config.MigrationRunner;
import org.cubexmc.config.NoOpMigrationStep;
import org.cubexmc.config.ResourceFiles;
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
    private ResourceFiles resourceFiles;

    @Override
    protected void enablePlugin() {
        this.resourceFiles = new ResourceFiles(this);
        saveDefaultFiles();
        try {
            migrateConfigAndLang();
        } catch (MigrationException ex) {
            getLogger().severe("Contracts enable aborted: migration failed. " + ex.getMessage());
            abortEnable("Contracts migration failed. See logs for details.");
        }
        reloadConfig();

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
        // Persist pending in-memory changes before re-reading from disk; otherwise an admin reload within the
        // flush window would silently discard recent accepts/disputes/submissions. Only re-read if the flush
        // succeeded, so an I/O failure can never trade away unsaved state.
        boolean canReloadData = true;
        try {
            contractStorage.flushIfDirty();
        } catch (IOException ex) {
            canReloadData = false;
            getLogger().warning("Reload: could not flush contracts; keeping in-memory state and skipping data"
                + " reload. " + ex.getMessage());
        }
        saveDefaultFiles();
        try {
            migrateConfigAndLang();
        } catch (MigrationException ex) {
            getLogger().severe("Contracts reload aborted: migration failed. " + ex.getMessage());
            return;
        }
        reloadConfig();
        languageManager.load();
        if (canReloadData) {
            try {
                contractStorage.load();
            } catch (RuntimeException ex) {
                getLogger().severe("Reload: contract data unreadable (" + ex.getMessage()
                    + "); keeping current in-memory contracts.");
            }
        }
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
        resourceFiles.saveIfMissing(java.util.List.of("config.yml", "lang/zh_CN.yml", "lang/en_US.yml"));
    }

    private void migrateConfigAndLang() throws MigrationException {
        MigrationRunner migrations = new MigrationRunner(this);
        migrations.run(MigrationPlan.yaml("Contracts config", "config.yml")
                .versionKey("config-version")
                .targetVersion(2)
                .addStep(new NoOpMigrationStep(1, 2, "Add Contracts config-version.")));
        migrateLang(migrations, "zh_CN");
        migrateLang(migrations, "en_US");
    }

    private void migrateLang(MigrationRunner migrations, String locale) throws MigrationException {
        migrations.run(MigrationPlan.yaml("Contracts lang " + locale, "lang/" + locale + ".yml")
                .versionKey("lang-version")
                .targetVersion(2)
                .addStep(new LegacyTextToMiniMessageStep(1, 2)));
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
