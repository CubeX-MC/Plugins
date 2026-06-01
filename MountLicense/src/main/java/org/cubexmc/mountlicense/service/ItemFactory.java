package org.cubexmc.mountlicense.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.cubexmc.mountlicense.MountLicensePlugin;
import org.cubexmc.mountlicense.lang.LanguageManager;

public class ItemFactory {

    private final MountLicensePlugin plugin;
    private final PdcKeys keys;
    private final LanguageManager lang;

    public ItemFactory(MountLicensePlugin plugin, PdcKeys keys, LanguageManager lang) {
        this.plugin = plugin;
        this.keys = keys;
        this.lang = lang;
    }

    public ItemStack createLicense(int amount) {
        Material material = plugin.configManager().getLicenseMaterial();
        ItemStack item = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(lang.msg(plugin.configManager().getLicenseDisplayKey()));
            List<String> lore = lang.msgList(plugin.configManager().getLicenseLoreKey(), null);
            if (!lore.isEmpty()) meta.setLore(lore);
            int cmd = plugin.configManager().getLicenseCustomModelData();
            if (cmd > 0) meta.setCustomModelData(cmd);

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(keys.itemRole(), PersistentDataType.STRING, PdcKeys.ITEM_ROLE_LICENSE);
            pdc.set(keys.schemaVersion(), PersistentDataType.INTEGER, PdcKeys.SCHEMA_VERSION);

            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isLicense(ItemStack item) {
        return matchesRole(item, PdcKeys.ITEM_ROLE_LICENSE);
    }

    public ItemStack createKey(int amount) {
        Material material = plugin.configManager().getKeyMaterial();
        ItemStack item = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(lang.msg(plugin.configManager().getKeyDisplayKey()));
            List<String> lore = lang.msgList(plugin.configManager().getKeyLoreKey(), null);
            if (!lore.isEmpty()) meta.setLore(lore);
            int cmd = plugin.configManager().getKeyCustomModelData();
            if (cmd > 0) meta.setCustomModelData(cmd);

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(keys.itemRole(), PersistentDataType.STRING, PdcKeys.ITEM_ROLE_KEY);
            pdc.set(keys.schemaVersion(), PersistentDataType.INTEGER, PdcKeys.SCHEMA_VERSION);

            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isKey(ItemStack item) {
        return matchesRole(item, PdcKeys.ITEM_ROLE_KEY);
    }

    public UUID readBoundVehicleId(ItemStack item) {
        if (!isKey(item)) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String raw = meta.getPersistentDataContainer()
                .get(keys.keyBoundVehicle(), PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public boolean bindKey(ItemStack item, UUID vehicleId, String shortLabel) {
        if (!isKey(item)) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        meta.getPersistentDataContainer()
                .set(keys.keyBoundVehicle(), PersistentDataType.STRING, vehicleId.toString());

        Map<String, String> ph = new HashMap<>();
        ph.put("short_id", shortLabel);
        meta.setDisplayName(lang.msg("key_item.bound_display", ph));
        List<String> lore = new ArrayList<>(lang.msgList(plugin.configManager().getKeyLoreKey(), null));
        lore.add(lang.msg("key_item.bound_lore", ph));
        meta.setLore(lore);

        item.setItemMeta(meta);
        return true;
    }

    public boolean unbindKey(ItemStack item) {
        if (!isKey(item)) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        if (!meta.getPersistentDataContainer().has(keys.keyBoundVehicle(), PersistentDataType.STRING)) {
            return false;
        }
        meta.getPersistentDataContainer().remove(keys.keyBoundVehicle());
        meta.setDisplayName(lang.msg(plugin.configManager().getKeyDisplayKey()));
        List<String> lore = lang.msgList(plugin.configManager().getKeyLoreKey(), null);
        meta.setLore(lore);

        item.setItemMeta(meta);
        return true;
    }

    private boolean matchesRole(ItemStack item, String expectedRole) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        String role = meta.getPersistentDataContainer().get(keys.itemRole(), PersistentDataType.STRING);
        return expectedRole.equals(role);
    }
}
