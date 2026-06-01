package org.cubexmc.mountlicense.config;

import java.io.File;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.cubexmc.mountlicense.MountLicensePlugin;
import org.cubexmc.mountlicense.model.VehicleFeature;
import org.cubexmc.mountlicense.model.VehicleProfile;

public class ProfileRegistry {

    private final MountLicensePlugin plugin;
    private final Map<String, VehicleProfile> profilesById = new LinkedHashMap<>();
    private final Map<EntityType, VehicleProfile> profilesByEntity = new HashMap<>();

    public ProfileRegistry(MountLicensePlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        profilesById.clear();
        profilesByEntity.clear();

        File file = new File(plugin.getDataFolder(), "vehicle-profiles.yml");
        if (!file.exists()) {
            plugin.getLogger().warning("vehicle-profiles.yml missing, no profiles loaded.");
            return;
        }

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("profiles");
        if (root == null) {
            plugin.getLogger().warning("vehicle-profiles.yml has no 'profiles' root.");
            return;
        }

        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) continue;

            Set<EntityType> entityTypes = EnumSet.noneOf(EntityType.class);
            for (String name : section.getStringList("entityTypes")) {
                try {
                    entityTypes.add(EntityType.valueOf(name.toUpperCase()));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Profile " + id + " references unknown EntityType: " + name);
                }
            }

            Set<VehicleFeature> features = EnumSet.noneOf(VehicleFeature.class);
            ConfigurationSection fSec = section.getConfigurationSection("features");
            if (fSec != null) {
                for (String fKey : fSec.getKeys(false)) {
                    VehicleFeature feature = VehicleFeature.fromYamlKey(fKey);
                    if (feature != null && fSec.getBoolean(fKey, false)) {
                        features.add(feature);
                    }
                }
            }

            boolean requiresTamed = section.getBoolean("requiresTamedOwner", false);
            boolean requiresSaddle = section.getBoolean("requiresSaddle", false);
            if (entityTypes.isEmpty()) {
                plugin.getLogger().warning("Profile " + id
                        + " has no valid EntityType on this server API and will not match entities.");
            }

            VehicleProfile profile = new VehicleProfile(id, entityTypes, features, requiresTamed,
                    requiresSaddle);
            profilesById.put(id, profile);
            for (EntityType t : entityTypes) {
                VehicleProfile existing = profilesByEntity.put(t, profile);
                if (existing != null && !existing.id().equals(id)) {
                    plugin.getLogger().warning("EntityType " + t + " is claimed by both '"
                            + existing.id() + "' and '" + id + "'. Last write wins: " + id);
                }
            }
        }

        plugin.getLogger().info("Loaded " + profilesById.size() + " vehicle profile(s).");
    }

    public VehicleProfile byId(String id) {
        return id == null ? null : profilesById.get(id);
    }

    public VehicleProfile byEntityType(EntityType type) {
        return type == null ? null : profilesByEntity.get(type);
    }

    public List<VehicleProfile> all() {
        return List.copyOf(profilesById.values());
    }

    public int size() {
        return profilesById.size();
    }
}
