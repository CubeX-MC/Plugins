package org.cubexmc.metro.model;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;

/**
 * Per-entity visual tuning loaded from entity.yml.
 */
final class EntityDisplayConfig {

    private static final EntityDisplayConfig EMPTY = new EntityDisplayConfig(DisplaySettings.defaults(Map.of()));

    private final DisplaySettings defaults;
    private final Map<String, DisplaySettings> entitySettings = new HashMap<>();

    private EntityDisplayConfig(DisplaySettings defaults) {
        this.defaults = defaults;
    }

    static EntityDisplayConfig empty() {
        return EMPTY;
    }

    static EntityDisplayConfig load(File file, Logger logger) {
        if (file == null || !file.exists()) {
            return empty();
        }
        try {
            return fromConfig(YamlConfiguration.loadConfiguration(file));
        } catch (RuntimeException ex) {
            if (logger != null) {
                logger.warning("Failed to load entity.yml: " + ex.getMessage());
            }
            return empty();
        }
    }

    static EntityDisplayConfig fromConfig(YamlConfiguration config) {
        DisplaySettings defaults = DisplaySettings.fromSection(config.getConfigurationSection("defaults"), Map.of());
        EntityDisplayConfig displayConfig = new EntityDisplayConfig(defaults);
        ConfigurationSection entities = config.getConfigurationSection("entities");
        if (entities != null) {
            for (String key : entities.getKeys(false)) {
                ConfigurationSection section = entities.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                String normalized = EntityModelController.normalizeConfiguredEntityName(key);
                if (normalized != null) {
                    displayConfig.entitySettings.put(normalized, DisplaySettings.fromSection(section, defaults.properties()));
                }
            }
        }
        return displayConfig;
    }

    DisplaySettings settingsFor(String entityTypeRaw) {
        String normalized = EntityModelController.normalizeConfiguredEntityName(entityTypeRaw);
        DisplaySettings settings = normalized == null ? null : entitySettings.get(normalized);
        return defaults.merge(settings);
    }

    void applyProperties(LivingEntity entity, DisplaySettings settings) {
        if (entity == null || settings == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : settings.properties().entrySet()) {
            applyProperty(entity, entry.getKey(), entry.getValue());
        }
    }

    private void applyProperty(LivingEntity entity, String rawName, Object value) {
        String name = normalizePropertyName(rawName);
        if (name == null || value == null) {
            return;
        }
        if (isSaddleProperty(name)) {
            applySaddle(entity, asBoolean(value));
            return;
        }
        if (isHarnessProperty(name)) {
            applyHarness(entity, asBoolean(value));
            return;
        }
        if (isGogglesProperty(name)) {
            applyGoggles(entity, asBoolean(value));
            return;
        }
        if (value instanceof Boolean bool) {
            invokeBooleanSetter(entity, name, bool);
        }
    }

    private boolean applySaddle(LivingEntity entity, boolean enabled) {
        if (invokeBooleanSetter(entity, "saddled", enabled)
                || invokeBooleanSetter(entity, "saddle", enabled)
                || invokeBooleanSetter(entity, "hasSaddle", enabled)) {
            return true;
        }
        return applyInventoryItem(entity, "setSaddle", enabled ? materialItem("SADDLE") : null);
    }

    private boolean applyHarness(LivingEntity entity, boolean enabled) {
        if (invokeBooleanSetter(entity, "harness", enabled)
                || invokeBooleanSetter(entity, "hasHarness", enabled)
                || invokeBooleanSetter(entity, "wearingHarness", enabled)) {
            return true;
        }
        return invokeItemStackSetter(entity, "setHarness", enabled ? firstMaterialItem(
                "WHITE_HARNESS", "HARNESS", "SADDLE") : null);
    }

    private boolean applyGoggles(LivingEntity entity, boolean enabled) {
        return invokeBooleanSetter(entity, "goggles", enabled)
                || invokeBooleanSetter(entity, "hasGoggles", enabled)
                || invokeBooleanSetter(entity, "wearingGoggles", enabled);
    }

    private boolean invokeBooleanSetter(Object target, String propertyName, boolean value) {
        String setter = "set" + toPascalCase(propertyName);
        try {
            Method method = target.getClass().getMethod(setter, boolean.class);
            method.invoke(target, value);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private boolean applyInventoryItem(LivingEntity entity, String setterName, ItemStack item) {
        try {
            Method getInventory = entity.getClass().getMethod("getInventory");
            Object inventory = getInventory.invoke(entity);
            if (inventory == null) {
                return false;
            }
            for (Method method : inventory.getClass().getMethods()) {
                if (setterName.equals(method.getName()) && method.getParameterCount() == 1
                        && ItemStack.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    method.invoke(inventory, item);
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return false;
    }

    private boolean invokeItemStackSetter(LivingEntity entity, String setterName, ItemStack item) {
        for (Method method : entity.getClass().getMethods()) {
            if (setterName.equals(method.getName()) && method.getParameterCount() == 1
                    && ItemStack.class.isAssignableFrom(method.getParameterTypes()[0])) {
                try {
                    method.invoke(entity, item);
                    return true;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    private static ItemStack firstMaterialItem(String... names) {
        for (String name : names) {
            ItemStack item = materialItem(name);
            if (item != null) {
                return item;
            }
        }
        return null;
    }

    private static ItemStack materialItem(String name) {
        Material material = Material.matchMaterial(name);
        return material == null ? null : new ItemStack(material);
    }

    private static boolean isSaddleProperty(String name) {
        return "saddle".equals(name) || "saddled".equals(name) || "hasSaddle".equals(name);
    }

    private static boolean isHarnessProperty(String name) {
        return "harness".equals(name) || "hasHarness".equals(name) || "wearingHarness".equals(name);
    }

    private static boolean isGogglesProperty(String name) {
        return "goggles".equals(name) || "hasGoggles".equals(name) || "wearingGoggles".equals(name);
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static String normalizePropertyName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String[] parts = rawName.trim().toLowerCase(Locale.ROOT).split("[_\\-\\s]+");
        StringBuilder result = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                result.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            }
        }
        return result.toString();
    }

    private static String toPascalCase(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    record DisplaySettings(Double spacing, Double offsetY, Map<String, Object> properties) {

        static DisplaySettings defaults(Map<String, Object> properties) {
            return new DisplaySettings(null, null, Map.copyOf(properties));
        }

        static DisplaySettings fromSection(ConfigurationSection section, Map<String, Object> inheritedProperties) {
            if (section == null) {
                return defaults(inheritedProperties);
            }
            Map<String, Object> properties = new HashMap<>(inheritedProperties);
            ConfigurationSection propertySection = section.getConfigurationSection("properties");
            if (propertySection != null) {
                properties.putAll(propertySection.getValues(false));
            }
            for (String key : section.getKeys(false)) {
                if (isReservedKey(key) || section.isConfigurationSection(key)) {
                    continue;
                }
                Object value = section.get(key);
                if (value instanceof Boolean || isKnownDisplayProperty(key)) {
                    properties.put(key, value);
                }
            }
            return new DisplaySettings(
                    section.contains("spacing") ? section.getDouble("spacing") : null,
                    readHeight(section),
                    Map.copyOf(properties));
        }

        DisplaySettings merge(DisplaySettings override) {
            if (override == null) {
                return this;
            }
            Map<String, Object> mergedProperties = new HashMap<>(properties);
            mergedProperties.putAll(override.properties);
            return new DisplaySettings(
                    override.spacing != null ? override.spacing : spacing,
                    override.offsetY != null ? override.offsetY : offsetY,
                    Map.copyOf(mergedProperties));
        }

        private static boolean isReservedKey(String key) {
            return "spacing".equalsIgnoreCase(key)
                    || "offset-y".equalsIgnoreCase(key)
                    || "height".equalsIgnoreCase(key)
                    || "properties".equalsIgnoreCase(key);
        }

        private static Double readHeight(ConfigurationSection section) {
            if (section.contains("height")) {
                return section.getDouble("height");
            }
            return section.contains("offset-y") ? section.getDouble("offset-y") : null;
        }

        private static boolean isKnownDisplayProperty(String key) {
            String normalized = normalizePropertyName(key);
            return isSaddleProperty(normalized)
                    || isHarnessProperty(normalized)
                    || isGogglesProperty(normalized);
        }
    }
}
