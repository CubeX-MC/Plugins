package org.cubexmc.regions.mode

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import org.cubexmc.regions.RegionsPlugin
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID

class CombatGearStore(private val plugin: RegionsPlugin, fileName: String = "combat-escrow.yml") {
    private val file = File(plugin.dataFolder, fileName)
    private val entries: MutableMap<UUID, StoredGear> = LinkedHashMap()

    @Synchronized
    fun load() {
        entries.clear()
        if (!file.exists()) {
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        for (key in yaml.getKeys(false)) {
            val uuid = try {
                UUID.fromString(key)
            } catch (ex: IllegalArgumentException) {
                continue
            }
            val section = yaml.getConfigurationSection(key) ?: continue
            try {
                entries[uuid] = StoredGear(
                    section.getString("region", "") ?: "",
                    decodeItems(section.getString("contents")),
                    decodeItems(section.getString("armor")),
                    decodeItems(section.getString("offhand")).firstOrNull(),
                    section.getInt("level", 0),
                    section.getDouble("exp", 0.0).toFloat(),
                    parseGameMode(section.getString("game-mode")),
                    parseLocation(section.getString("respawn")),
                )
            } catch (ex: RuntimeException) {
                plugin.logger.warning("Failed to load combat escrow for $uuid: ${ex.message}")
            }
        }
    }

    @Synchronized
    fun put(playerId: UUID, regionId: String, snapshot: CombatModeService.GearSnapshot) {
        entries[playerId] = StoredGear(
            regionId,
            snapshot.contents,
            snapshot.armor,
            snapshot.offhand,
            snapshot.level,
            snapshot.exp,
            snapshot.gameMode,
            snapshot.respawn,
        )
        save()
    }

    @Synchronized
    fun take(playerId: UUID): StoredGear? {
        val removed = entries.remove(playerId)
        if (removed != null) {
            save()
        }
        return removed
    }

    @Synchronized
    fun allPlayerIds(): Set<UUID> = entries.keys.toSet()

    private fun save() {
        val yaml = YamlConfiguration()
        for ((uuid, stored) in entries) {
            val path = uuid.toString()
            yaml.set("$path.region", stored.regionId)
            yaml.set("$path.contents", encodeItems(stored.contents))
            yaml.set("$path.armor", encodeItems(stored.armor))
            yaml.set("$path.offhand", encodeItems(arrayOf(stored.offhand)))
            yaml.set("$path.level", stored.level)
            yaml.set("$path.exp", stored.exp.toDouble())
            yaml.set("$path.game-mode", stored.gameMode.name)
            yaml.set("$path.respawn", formatLocation(stored.respawn))
        }
        file.parentFile?.mkdirs()
        yaml.save(file)
    }

    private fun encodeItems(items: Array<ItemStack?>): String {
        val bytes = ByteArrayOutputStream()
        BukkitObjectOutputStream(bytes).use { output ->
            output.writeInt(items.size)
            for (item in items) {
                output.writeObject(item)
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    private fun decodeItems(value: String?): Array<ItemStack?> {
        if (value.isNullOrBlank()) {
            return emptyArray()
        }
        val bytes = Base64.getDecoder().decode(value)
        BukkitObjectInputStream(ByteArrayInputStream(bytes)).use { input ->
            val size = input.readInt()
            return Array(size) { input.readObject() as? ItemStack }
        }
    }

    private fun parseGameMode(value: String?): GameMode =
        try {
            if (value == null) GameMode.SURVIVAL else GameMode.valueOf(value)
        } catch (ex: IllegalArgumentException) {
            GameMode.SURVIVAL
        }

    private fun parseLocation(value: String?): Location? {
        if (value.isNullOrBlank()) {
            return null
        }
        val parts = value.split(',')
        if (parts.size < 4) {
            return null
        }
        val world = Bukkit.getWorld(parts[0]) ?: return null
        val x = parts[1].toDoubleOrNull() ?: return null
        val y = parts[2].toDoubleOrNull() ?: return null
        val z = parts[3].toDoubleOrNull() ?: return null
        val yaw = parts.getOrNull(4)?.toFloatOrNull() ?: 0.0f
        val pitch = parts.getOrNull(5)?.toFloatOrNull() ?: 0.0f
        return Location(world, x, y, z, yaw, pitch)
    }

    private fun formatLocation(location: Location?): String? {
        if (location == null) {
            return null
        }
        return "${location.world?.name},${location.x},${location.y},${location.z},${location.yaw},${location.pitch}"
    }

    data class StoredGear(
        val regionId: String,
        val contents: Array<ItemStack?>,
        val armor: Array<ItemStack?>,
        val offhand: ItemStack?,
        val level: Int,
        val exp: Float,
        val gameMode: GameMode,
        val respawn: Location?,
    )
}
