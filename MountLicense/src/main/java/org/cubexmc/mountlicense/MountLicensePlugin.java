package org.cubexmc.mountlicense;

import org.bukkit.command.PluginCommand;
import org.cubexmc.core.CubexPlugin;
import org.cubexmc.mountlicense.command.MountLicenseCommand;
import org.cubexmc.mountlicense.config.ConfigManager;
import org.cubexmc.mountlicense.config.ProfileRegistry;
import org.cubexmc.mountlicense.lang.LanguageManager;
import org.cubexmc.mountlicense.listener.AutoParkListener;
import org.cubexmc.mountlicense.listener.KeyItemListener;
import org.cubexmc.mountlicense.listener.LicenseHintListener;
import org.cubexmc.mountlicense.listener.ProtectionListener;
import org.cubexmc.mountlicense.listener.RegistrationListener;
import org.cubexmc.mountlicense.persistence.VehicleIndex;
import org.cubexmc.mountlicense.service.ItemFactory;
import org.cubexmc.mountlicense.service.OwnershipService;
import org.cubexmc.mountlicense.service.ParkingService;
import org.cubexmc.mountlicense.service.PdcKeys;
import org.cubexmc.mountlicense.service.RecallService;
import org.cubexmc.mountlicense.service.RegistryService;

public class MountLicensePlugin extends CubexPlugin {

    private ConfigManager configManager;
    private LanguageManager languageManager;
    private ProfileRegistry profileRegistry;
    private PdcKeys pdcKeys;
    private VehicleIndex vehicleIndex;
    private ItemFactory itemFactory;
    private OwnershipService ownershipService;
    private ParkingService parkingService;
    private RegistryService registryService;
    private RecallService recallService;

    @Override
    protected void enablePlugin() {
        saveDefaultResources();

        this.configManager = new ConfigManager(this);
        this.configManager.load();

        this.languageManager = new LanguageManager(this, configManager.getLanguage());
        this.languageManager.load();

        this.profileRegistry = new ProfileRegistry(this);
        this.profileRegistry.load();

        this.pdcKeys = new PdcKeys(this);
        this.vehicleIndex = new VehicleIndex(this);
        this.vehicleIndex.load();
        Runnable flushVehicleIndex = () -> {
            if (vehicleIndex != null) {
                vehicleIndex.flush();
            }
        };
        bind(flushVehicleIndex);

        this.itemFactory = new ItemFactory(this, pdcKeys, languageManager);
        this.ownershipService = new OwnershipService(pdcKeys, vehicleIndex);
        this.parkingService = new ParkingService(this, pdcKeys, vehicleIndex,
                ownershipService, languageManager);
        this.registryService = new RegistryService(this, pdcKeys, vehicleIndex,
                profileRegistry, languageManager);
        this.recallService = new RecallService(this, ownershipService,
                profileRegistry, vehicleIndex, languageManager);
        this.registryService.refreshLoadedDisplayNames();

        getServer().getPluginManager().registerEvents(
                new RegistrationListener(this, registryService, pdcKeys), this);
        getServer().getPluginManager().registerEvents(
                new LicenseHintListener(this, itemFactory, languageManager), this);
        getServer().getPluginManager().registerEvents(
                new ProtectionListener(this, ownershipService, languageManager), this);
        getServer().getPluginManager().registerEvents(
                new AutoParkListener(this, ownershipService, parkingService), this);
        getServer().getPluginManager().registerEvents(
                new KeyItemListener(this, itemFactory, ownershipService, recallService, languageManager), this);

        PluginCommand root = getCommand("mountlicense");
        if (root != null) {
            MountLicenseCommand executor = new MountLicenseCommand(this);
            root.setExecutor(executor);
            root.setTabCompleter(executor);
        }

        new Metrics(this, 31450);

        getLogger().info("MountLicense " + getDescription().getVersion() + " enabled.");
    }

    @Override
    protected void disablePlugin() {
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public LanguageManager languageManager() {
        return languageManager;
    }

    public ProfileRegistry profileRegistry() {
        return profileRegistry;
    }

    public PdcKeys pdcKeys() {
        return pdcKeys;
    }

    public VehicleIndex vehicleIndex() {
        return vehicleIndex;
    }

    public ItemFactory itemFactory() {
        return itemFactory;
    }

    public OwnershipService ownershipService() {
        return ownershipService;
    }

    public ParkingService parkingService() {
        return parkingService;
    }

    public RegistryService registryService() {
        return registryService;
    }

    public RecallService recallService() {
        return recallService;
    }

    public void reloadAll() {
        saveDefaultResources();
        configManager.load();
        languageManager.setLocale(configManager.getLanguage());
        languageManager.load();
        profileRegistry.load();
        if (registryService != null) {
            registryService.refreshLoadedDisplayNames();
        }
    }

    private void saveDefaultResources() {
        saveResourcesIfMissing("config.yml", "vehicle-profiles.yml", "lang/zh_CN.yml", "lang/en_US.yml");
    }
}
