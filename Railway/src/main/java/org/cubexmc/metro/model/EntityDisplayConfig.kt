package org.cubexmc.metro.model

import java.io.File
import java.util.Locale
import java.util.logging.Logger
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack

/**
 * Per-entity visual tuning loaded from entity.yml.
 */
internal class EntityDisplayConfig private constructor(
    private val defaults: DisplaySettings,
) {
    private val entitySettings: MutableMap<String, DisplaySettings> = HashMap()

    fun settingsFor(entityTypeRaw: String?): DisplaySettings {
        val normalized = EntityModelController.normalizeConfiguredEntityName(entityTypeRaw)
        val settings = normalized?.let { entitySettings[it] }
        return defaults.merge(settings)
    }

    fun applyProperties(entity: LivingEntity?, settings: DisplaySettings?) {
        if (entity == null || settings == null) {
            return
        }
        for ((name, value) in settings.properties) {
            applyProperty(entity, name, value)
        }
    }

    private fun applyProperty(entity: LivingEntity, rawName: String, value: Any?) {
        val name = normalizePropertyName(rawName) ?: return
        if (value == null) {
            return
        }
        when {
            isSaddleProperty(name) -> applySaddle(entity, asBoolean(value))
            isHarnessProperty(name) -> applyHarness(entity, asBoolean(value))
            isGogglesProperty(name) -> applyGoggles(entity, asBoolean(value))
            value is Boolean -> invokeBooleanSetter(entity, name, value)
        }
    }

    private fun applySaddle(entity: LivingEntity, enabled: Boolean): Boolean {
        if (invokeBooleanSetter(entity, "saddled", enabled) ||
            invokeBooleanSetter(entity, "saddle", enabled) ||
            invokeBooleanSetter(entity, "hasSaddle", enabled)
        ) {
            return true
        }
        return applyInventoryItem(entity, "setSaddle", if (enabled) materialItem("SADDLE") else null)
    }

    private fun applyHarness(entity: LivingEntity, enabled: Boolean): Boolean {
        if (invokeBooleanSetter(entity, "harness", enabled) ||
            invokeBooleanSetter(entity, "hasHarness", enabled) ||
            invokeBooleanSetter(entity, "wearingHarness", enabled)
        ) {
            return true
        }
        return invokeItemStackSetter(
            entity,
            "setHarness",
            if (enabled) firstMaterialItem("WHITE_HARNESS", "HARNESS", "SADDLE") else null,
        )
    }

    private fun applyGoggles(entity: LivingEntity, enabled: Boolean): Boolean =
        invokeBooleanSetter(entity, "goggles", enabled) ||
            invokeBooleanSetter(entity, "hasGoggles", enabled) ||
            invokeBooleanSetter(entity, "wearingGoggles", enabled)

    private fun invokeBooleanSetter(target: Any, propertyName: String, value: Boolean): Boolean {
        val setter = "set${toPascalCase(propertyName)}"
        return try {
            val method = target.javaClass.getMethod(setter, java.lang.Boolean.TYPE)
            method.invoke(target, value)
            true
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun applyInventoryItem(entity: LivingEntity, setterName: String, item: ItemStack?): Boolean {
        try {
            val getInventory = entity.javaClass.getMethod("getInventory")
            val inventory = getInventory.invoke(entity) ?: return false
            for (method in inventory.javaClass.methods) {
                if (setterName == method.name &&
                    method.parameterCount == 1 &&
                    ItemStack::class.java.isAssignableFrom(method.parameterTypes[0])
                ) {
                    method.invoke(inventory, item)
                    return true
                }
            }
        } catch (_: ReflectiveOperationException) {
            // Unsupported on this server/entity implementation.
        } catch (_: RuntimeException) {
            // Unsupported on this server/entity implementation.
        }
        return false
    }

    private fun invokeItemStackSetter(entity: LivingEntity, setterName: String, item: ItemStack?): Boolean {
        for (method in entity.javaClass.methods) {
            if (setterName == method.name &&
                method.parameterCount == 1 &&
                ItemStack::class.java.isAssignableFrom(method.parameterTypes[0])
            ) {
                return try {
                    method.invoke(entity, item)
                    true
                } catch (_: ReflectiveOperationException) {
                    false
                } catch (_: RuntimeException) {
                    false
                }
            }
        }
        return false
    }

    @JvmRecord
    data class DisplaySettings(
        val spacing: Double?,
        val offsetY: Double?,
        val properties: Map<String, Any>,
    ) {
        fun merge(override: DisplaySettings?): DisplaySettings {
            if (override == null) {
                return this
            }
            val mergedProperties = HashMap(properties)
            mergedProperties.putAll(override.properties)
            return DisplaySettings(
                override.spacing ?: spacing,
                override.offsetY ?: offsetY,
                java.util.Map.copyOf(mergedProperties),
            )
        }

        companion object {
            @JvmStatic
            fun defaults(properties: Map<String, Any>): DisplaySettings =
                DisplaySettings(null, null, java.util.Map.copyOf(properties))

            @JvmStatic
            fun fromSection(
                section: ConfigurationSection?,
                inheritedProperties: Map<String, Any>,
            ): DisplaySettings {
                if (section == null) {
                    return defaults(inheritedProperties)
                }
                val properties = HashMap(inheritedProperties)
                val propertySection = section.getConfigurationSection("properties")
                if (propertySection != null) {
                    properties.putAll(propertySection.getValues(false))
                }
                for (key in section.getKeys(false)) {
                    if (isReservedKey(key) || section.isConfigurationSection(key)) {
                        continue
                    }
                    val value = section.get(key)
                    if (value is Boolean || isKnownDisplayProperty(key)) {
                        properties[key] = value
                    }
                }
                return DisplaySettings(
                    if (section.contains("spacing")) section.getDouble("spacing") else null,
                    readHeight(section),
                    java.util.Map.copyOf(properties),
                )
            }

            private fun isReservedKey(key: String): Boolean =
                "spacing".equals(key, ignoreCase = true) ||
                    "offset-y".equals(key, ignoreCase = true) ||
                    "height".equals(key, ignoreCase = true) ||
                    "properties".equals(key, ignoreCase = true)

            private fun readHeight(section: ConfigurationSection): Double? =
                when {
                    section.contains("height") -> section.getDouble("height")
                    section.contains("offset-y") -> section.getDouble("offset-y")
                    else -> null
                }

            private fun isKnownDisplayProperty(key: String): Boolean {
                val normalized = normalizePropertyName(key)
                return isSaddleProperty(normalized) ||
                    isHarnessProperty(normalized) ||
                    isGogglesProperty(normalized)
            }
        }
    }

    companion object {
        private val EMPTY = EntityDisplayConfig(DisplaySettings.defaults(emptyMap()))

        @JvmStatic
        fun empty(): EntityDisplayConfig = EMPTY

        @JvmStatic
        fun load(file: File?, logger: Logger?): EntityDisplayConfig {
            if (file == null || !file.exists()) {
                return empty()
            }
            return try {
                fromConfig(YamlConfiguration.loadConfiguration(file))
            } catch (ex: RuntimeException) {
                logger?.warning("Failed to load entity.yml: ${ex.message}")
                empty()
            }
        }

        @JvmStatic
        fun fromConfig(config: YamlConfiguration): EntityDisplayConfig {
            val defaults = DisplaySettings.fromSection(config.getConfigurationSection("defaults"), emptyMap())
            val displayConfig = EntityDisplayConfig(defaults)
            val entities = config.getConfigurationSection("entities")
            if (entities != null) {
                for (key in entities.getKeys(false)) {
                    val section = entities.getConfigurationSection(key) ?: continue
                    val normalized = EntityModelController.normalizeConfiguredEntityName(key)
                    if (normalized != null) {
                        displayConfig.entitySettings[normalized] =
                            DisplaySettings.fromSection(section, defaults.properties)
                    }
                }
            }
            return displayConfig
        }

        private fun firstMaterialItem(vararg names: String): ItemStack? {
            for (name in names) {
                val item = materialItem(name)
                if (item != null) {
                    return item
                }
            }
            return null
        }

        private fun materialItem(name: String): ItemStack? =
            Material.matchMaterial(name)?.let { ItemStack(it) }

        private fun isSaddleProperty(name: String?): Boolean =
            name == "saddle" || name == "saddled" || name == "hasSaddle"

        private fun isHarnessProperty(name: String?): Boolean =
            name == "harness" || name == "hasHarness" || name == "wearingHarness"

        private fun isGogglesProperty(name: String?): Boolean =
            name == "goggles" || name == "hasGoggles" || name == "wearingGoggles"

        private fun asBoolean(value: Any): Boolean =
            value as? Boolean ?: value.toString().toBoolean()

        private fun normalizePropertyName(rawName: String?): String? {
            if (rawName.isNullOrBlank()) {
                return null
            }
            val parts = rawName.trim().lowercase(Locale.ROOT).split(Regex("[_\\-\\s]+"))
            val result = StringBuilder(parts[0])
            for (index in 1 until parts.size) {
                val part = parts[index]
                if (part.isNotEmpty()) {
                    result.append(part[0].uppercaseChar()).append(part.substring(1))
                }
            }
            return result.toString()
        }

        private fun toPascalCase(name: String?): String {
            if (name.isNullOrEmpty()) {
                return ""
            }
            return name[0].uppercaseChar() + name.substring(1)
        }
    }
}
