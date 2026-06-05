package org.cubexmc.clarity;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** config.yml 的不可变快照。 */
public final class ClarityConfig {

    private static final List<String> DEFAULT_LEVELTOOLS_PDC_KEYS = List.of(
            "leveltools:leveltoolslevel",
            "leveltools:leveltoolsxp",
            "leveltools:leveltoolsreward"
    );

    private final boolean autoCleanOnJoin;
    private final boolean dryRun;
    private final long joinDelayTicks;
    private final List<String> attributeModifierIds;
    private final List<String> effectTypes;
    private final boolean effectsInfiniteOnly;
    private final boolean itemLevelToolsEnabled;
    private final List<String> itemLevelToolsPdcKeys;
    private final boolean itemLevelToolsRemoveLore;
    private final boolean itemLevelToolsRemoveAttributesOnMarkedItems;
    private final List<String> itemAttributeModifierIds;
    private final Map<String, Double> itemAttributeMaxAmounts;

    private ClarityConfig(boolean autoCleanOnJoin, boolean dryRun, long joinDelayTicks,
                          List<String> attributeModifierIds, List<String> effectTypes,
                          boolean effectsInfiniteOnly, boolean itemLevelToolsEnabled,
                          List<String> itemLevelToolsPdcKeys, boolean itemLevelToolsRemoveLore,
                          boolean itemLevelToolsRemoveAttributesOnMarkedItems,
                          List<String> itemAttributeModifierIds, Map<String, Double> itemAttributeMaxAmounts) {
        this.autoCleanOnJoin = autoCleanOnJoin;
        this.dryRun = dryRun;
        this.joinDelayTicks = joinDelayTicks;
        this.attributeModifierIds = List.copyOf(attributeModifierIds);
        this.effectTypes = List.copyOf(effectTypes);
        this.effectsInfiniteOnly = effectsInfiniteOnly;
        this.itemLevelToolsEnabled = itemLevelToolsEnabled;
        this.itemLevelToolsPdcKeys = List.copyOf(itemLevelToolsPdcKeys);
        this.itemLevelToolsRemoveLore = itemLevelToolsRemoveLore;
        this.itemLevelToolsRemoveAttributesOnMarkedItems = itemLevelToolsRemoveAttributesOnMarkedItems;
        this.itemAttributeModifierIds = List.copyOf(itemAttributeModifierIds);
        this.itemAttributeMaxAmounts = Map.copyOf(itemAttributeMaxAmounts);
    }

    public static ClarityConfig load(FileConfiguration c) {
        return new ClarityConfig(
                c.getBoolean("auto-clean-on-join", false),
                c.getBoolean("dry-run", true),
                Math.max(1L, c.getLong("join-delay-ticks", 40L)),
                c.getStringList("attributes.remove-modifier-ids"),
                c.getStringList("effects.remove-types"),
                c.getBoolean("effects.infinite-only", true),
                c.getBoolean("items.leveltools.enabled", true),
                stringListOrDefault(c, "items.leveltools.remove-pdc-keys", DEFAULT_LEVELTOOLS_PDC_KEYS),
                c.getBoolean("items.leveltools.remove-lore", true),
                c.getBoolean("items.leveltools.remove-attributes-on-marked-items", false),
                c.getStringList("items.attributes.remove-modifier-ids"),
                parseMaxAmounts(c.getStringList("items.attributes.max-amounts"))
        );
    }

    private static List<String> stringListOrDefault(FileConfiguration c, String path, List<String> fallback) {
        if (!c.contains(path)) {
            return fallback;
        }
        return c.getStringList(path);
    }

    private static Map<String, Double> parseMaxAmounts(List<String> entries) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String raw : entries) {
            if (raw == null) {
                continue;
            }
            String value = raw.trim();
            int split = value.lastIndexOf('=');
            if (split <= 0 || split >= value.length() - 1) {
                continue;
            }
            String key = value.substring(0, split).trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                continue;
            }
            try {
                double max = Double.parseDouble(value.substring(split + 1).trim());
                if (max >= 0.0D) {
                    out.put(key, max);
                }
            } catch (NumberFormatException ignored) {
                // Invalid config entries are ignored; scan output remains conservative.
            }
        }
        return out;
    }

    public boolean autoCleanOnJoin() {
        return autoCleanOnJoin;
    }

    public boolean dryRun() {
        return dryRun;
    }

    public long joinDelayTicks() {
        return joinDelayTicks;
    }

    public List<String> attributeModifierIds() {
        return attributeModifierIds;
    }

    public List<String> effectTypes() {
        return effectTypes;
    }

    public boolean effectsInfiniteOnly() {
        return effectsInfiniteOnly;
    }

    public boolean itemLevelToolsEnabled() {
        return itemLevelToolsEnabled;
    }

    public List<String> itemLevelToolsPdcKeys() {
        return itemLevelToolsPdcKeys;
    }

    public boolean itemLevelToolsRemoveLore() {
        return itemLevelToolsRemoveLore;
    }

    public boolean itemLevelToolsRemoveAttributesOnMarkedItems() {
        return itemLevelToolsRemoveAttributesOnMarkedItems;
    }

    public List<String> itemAttributeModifierIds() {
        return itemAttributeModifierIds;
    }

    public Map<String, Double> itemAttributeMaxAmounts() {
        return itemAttributeMaxAmounts;
    }
}
