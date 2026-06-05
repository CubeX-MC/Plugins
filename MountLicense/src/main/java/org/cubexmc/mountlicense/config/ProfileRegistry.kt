package org.cubexmc.mountlicense.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.cubexmc.mountlicense.MountLicensePlugin
import org.cubexmc.mountlicense.model.VehicleFeature
import org.cubexmc.mountlicense.model.VehicleProfile
import java.io.File
import java.util.ArrayList
import java.util.Collections
import java.util.EnumSet
import java.util.Locale

class ProfileRegistry(private val plugin: MountLicensePlugin) {
    private val profilesById: MutableMap<String, VehicleProfile> = LinkedHashMap()
    private val profilesByEntity: MutableMap<EntityType, VehicleProfile> = HashMap()

    fun load() {
        profilesById.clear()
        profilesByEntity.clear()

        val file = File(plugin.dataFolder, "vehicle-profiles.yml")
        if (!file.exists()) {
            plugin.logger.warning("vehicle-profiles.yml missing, no profiles loaded.")
            return
        }

        val cfg = YamlConfiguration.loadConfiguration(file)
        val root = cfg.getConfigurationSection("profiles")
        if (root == null) {
            plugin.logger.warning("vehicle-profiles.yml has no 'profiles' root.")
            return
        }

        for (id in root.getKeys(false)) {
            val section: ConfigurationSection = root.getConfigurationSection(id) ?: continue

            val entityTypes = EnumSet.noneOf(EntityType::class.java)
            for (name in section.getStringList("entityTypes")) {
                try {
                    entityTypes.add(EntityType.valueOf(name.uppercase(Locale.getDefault())))
                } catch (ex: IllegalArgumentException) {
                    plugin.logger.warning("Profile $id references unknown EntityType: $name")
                }
            }

            val features = EnumSet.noneOf(VehicleFeature::class.java)
            val fSec = section.getConfigurationSection("features")
            if (fSec != null) {
                for (fKey in fSec.getKeys(false)) {
                    val feature = VehicleFeature.fromYamlKey(fKey)
                    if (feature != null && fSec.getBoolean(fKey, false)) {
                        features.add(feature)
                    }
                }
            }

            val requiresTamed = section.getBoolean("requiresTamedOwner", false)
            val requiresSaddle = section.getBoolean("requiresSaddle", false)
            if (entityTypes.isEmpty()) {
                plugin.logger.warning(
                    "Profile $id has no valid EntityType on this server API and will not match entities.",
                )
            }

            val profile = VehicleProfile(id, entityTypes, features, requiresTamed, requiresSaddle)
            profilesById[id] = profile
            for (t in entityTypes) {
                val existing = profilesByEntity.put(t, profile)
                if (existing != null && existing.id() != id) {
                    plugin.logger.warning(
                        "EntityType $t is claimed by both '${existing.id()}' and '$id'. Last write wins: $id",
                    )
                }
            }
        }

        plugin.logger.info("Loaded ${profilesById.size} vehicle profile(s).")
    }

    fun byId(id: String?): VehicleProfile? = if (id == null) null else profilesById[id]

    fun byEntityType(type: EntityType?): VehicleProfile? = if (type == null) null else profilesByEntity[type]

    fun all(): List<VehicleProfile> = Collections.unmodifiableList(ArrayList(profilesById.values))

    fun size(): Int = profilesById.size
}
