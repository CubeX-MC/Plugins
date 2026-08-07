package org.cubexmc.regions.mode

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.cubexmc.regions.RegionsPlugin
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
        val version = yaml.getInt("escrow-version", -1)
        require(version == ESCROW_VERSION) {
            "Unsupported combat escrow version $version; expected $ESCROW_VERSION."
        }
        for (key in yaml.getKeys(false)) {
            if (key == "escrow-version") continue
            val uuid = try {
                UUID.fromString(key)
            } catch (ex: IllegalArgumentException) {
                throw IllegalStateException("Invalid combat escrow player id '$key'.", ex)
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
                throw IllegalStateException(
                    "Refusing to load Regions with unreadable combat escrow for $uuid: ${ex.message}",
                    ex,
                )
            }
        }
    }

    @Synchronized
    fun put(playerId: UUID, regionId: String, snapshot: CombatModeService.GearSnapshot) {
        val previous = entries.put(playerId, StoredGear(
            regionId,
            snapshot.contents,
            snapshot.armor,
            snapshot.offhand,
            snapshot.level,
            snapshot.exp,
            snapshot.gameMode,
            snapshot.respawn,
        ))
        try {
            save()
        } catch (error: RuntimeException) {
            if (previous == null) entries.remove(playerId) else entries[playerId] = previous
            throw error
        } catch (error: java.io.IOException) {
            if (previous == null) entries.remove(playerId) else entries[playerId] = previous
            throw error
        }
    }

    @Synchronized
    fun take(playerId: UUID): StoredGear? {
        val removed = entries.remove(playerId)
        if (removed != null) {
            try {
                save()
            } catch (error: RuntimeException) {
                entries[playerId] = removed
                throw error
            } catch (error: java.io.IOException) {
                entries[playerId] = removed
                throw error
            }
        }
        return removed
    }

    @Synchronized
    fun allPlayerIds(): Set<UUID> = entries.keys.toSet()

    private fun save() {
        val yaml = YamlConfiguration()
        yaml.set("escrow-version", ESCROW_VERSION)
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
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            yaml.save(temporary)
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun encodeItems(items: Array<ItemStack?>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(items.size)
            for (item in items) {
                output.writeBoolean(item != null)
                if (item != null) {
                    val itemBytes = item.serializeAsBytes()
                    output.writeInt(itemBytes.size)
                    output.write(itemBytes)
                }
            }
        }
        return PAPER_FORMAT_PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    private fun decodeItems(value: String?): Array<ItemStack?> {
        if (value.isNullOrBlank()) {
            return emptyArray()
        }
        require(value.startsWith(PAPER_FORMAT_PREFIX)) {
            "Unsupported pre-release combat escrow format; only $PAPER_FORMAT_PREFIX data is accepted."
        }
        return decodePaperItems(value.removePrefix(PAPER_FORMAT_PREFIX))
    }

    private fun decodePaperItems(value: String): Array<ItemStack?> {
        val bytes = Base64.getDecoder().decode(value)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val size = input.readInt()
            require(size in 0..MAX_ITEM_COUNT) { "Invalid Paper item array size $size." }
            return Array(size) {
                if (!input.readBoolean()) {
                    null
                } else {
                    val byteCount = input.readInt()
                    require(byteCount in 1..MAX_ITEM_BYTES) { "Invalid Paper item payload size $byteCount." }
                    ItemStack.deserializeBytes(input.readNBytes(byteCount).also {
                        require(it.size == byteCount) { "Truncated Paper item payload." }
                    })
                }
            }
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
        val world = plugin.server.getWorld(parts[0]) ?: return null
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

    private companion object {
        const val ESCROW_VERSION = 1
        const val PAPER_FORMAT_PREFIX = "paper-v1:"
        const val MAX_ITEM_COUNT = 256
        const val MAX_ITEM_BYTES = 4 * 1024 * 1024
    }
}
