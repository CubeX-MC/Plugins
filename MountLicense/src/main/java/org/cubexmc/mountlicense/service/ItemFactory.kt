package org.cubexmc.mountlicense.service

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.mountlicense.MountLicensePlugin
import org.cubexmc.mountlicense.lang.LanguageManager
import java.util.UUID
import kotlin.math.max

class ItemFactory(
    private val plugin: MountLicensePlugin,
    private val keys: PdcKeys,
    private val lang: LanguageManager,
) {
    fun createLicense(amount: Int): ItemStack {
        val material = plugin.configManager().getLicenseMaterial()
        val item = ItemStack(material, max(1, amount))
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName(lang.msg(plugin.configManager().getLicenseDisplayKey()))
            val lore = lang.msgList(plugin.configManager().getLicenseLoreKey(), null)
            if (lore.isNotEmpty()) meta.lore = lore
            val cmd = plugin.configManager().getLicenseCustomModelData()
            if (cmd > 0) meta.setCustomModelData(cmd)

            val pdc: PersistentDataContainer = meta.persistentDataContainer
            pdc.set(keys.itemRole(), PersistentDataType.STRING, PdcKeys.ITEM_ROLE_LICENSE)
            pdc.set(keys.schemaVersion(), PersistentDataType.INTEGER, PdcKeys.SCHEMA_VERSION)

            item.itemMeta = meta
        }
        return item
    }

    fun isLicense(item: ItemStack?): Boolean = matchesRole(item, PdcKeys.ITEM_ROLE_LICENSE)

    fun createKey(amount: Int): ItemStack {
        val material = plugin.configManager().getKeyMaterial()
        val item = ItemStack(material, max(1, amount))
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName(lang.msg(plugin.configManager().getKeyDisplayKey()))
            val lore = lang.msgList(plugin.configManager().getKeyLoreKey(), null)
            if (lore.isNotEmpty()) meta.lore = lore
            val cmd = plugin.configManager().getKeyCustomModelData()
            if (cmd > 0) meta.setCustomModelData(cmd)

            val pdc: PersistentDataContainer = meta.persistentDataContainer
            pdc.set(keys.itemRole(), PersistentDataType.STRING, PdcKeys.ITEM_ROLE_KEY)
            pdc.set(keys.schemaVersion(), PersistentDataType.INTEGER, PdcKeys.SCHEMA_VERSION)

            item.itemMeta = meta
        }
        return item
    }

    fun isKey(item: ItemStack?): Boolean = matchesRole(item, PdcKeys.ITEM_ROLE_KEY)

    fun readBoundVehicleId(item: ItemStack?): UUID? {
        if (!isKey(item)) return null
        val meta: ItemMeta = item?.itemMeta ?: return null
        val raw = meta.persistentDataContainer.get(keys.keyBoundVehicle(), PersistentDataType.STRING) ?: return null
        return try {
            UUID.fromString(raw)
        } catch (ex: IllegalArgumentException) {
            null
        }
    }

    fun bindKey(item: ItemStack?, vehicleId: UUID, shortLabel: String): Boolean {
        if (!isKey(item)) return false
        val meta: ItemMeta = item?.itemMeta ?: return false
        meta.persistentDataContainer.set(keys.keyBoundVehicle(), PersistentDataType.STRING, vehicleId.toString())

        val ph = HashMap<String, String>()
        ph["short_id"] = shortLabel
        meta.setDisplayName(lang.msg("key_item.bound_display", ph))
        val lore = ArrayList(lang.msgList(plugin.configManager().getKeyLoreKey(), null))
        lore.add(lang.msg("key_item.bound_lore", ph))
        meta.lore = lore

        item.itemMeta = meta
        return true
    }

    fun unbindKey(item: ItemStack?): Boolean {
        if (!isKey(item)) return false
        val meta: ItemMeta = item?.itemMeta ?: return false
        if (!meta.persistentDataContainer.has(keys.keyBoundVehicle(), PersistentDataType.STRING)) {
            return false
        }
        meta.persistentDataContainer.remove(keys.keyBoundVehicle())
        meta.setDisplayName(lang.msg(plugin.configManager().getKeyDisplayKey()))
        meta.lore = lang.msgList(plugin.configManager().getKeyLoreKey(), null)

        item.itemMeta = meta
        return true
    }

    private fun matchesRole(item: ItemStack?, expectedRole: String): Boolean {
        if (item == null || item.type == Material.AIR) return false
        val meta = item.itemMeta ?: return false
        val role = meta.persistentDataContainer.get(keys.itemRole(), PersistentDataType.STRING)
        return expectedRole == role
    }
}
