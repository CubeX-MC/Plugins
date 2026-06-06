package org.cubexmc.ecobalancer.utils

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PlaytimeUtils {
    private val playtimeTicks: MutableMap<UUID, Long> = ConcurrentHashMap()
    private val fileMtime: MutableMap<UUID, Long> = ConcurrentHashMap()
    private val gson = Gson()

    @JvmStatic
    fun loadAllAsync(plugin: JavaPlugin, statsWorldName: String?) {
        SchedulerUtils.runTaskAsync(plugin, Runnable { loadAll(statsWorldName) })
    }

    @JvmStatic
    fun loadAll(statsWorldName: String?) {
        val statsDir = resolveStatsDir(statsWorldName)
        if (statsDir == null || !statsDir.isDirectory) {
            return
        }

        val files = statsDir.listFiles { _, name -> name.endsWith(".json") } ?: return

        for (file in files) {
            try {
                val base = file.name.substring(0, file.name.length - 5)
                val uuid = UUID.fromString(base)
                val lm = file.lastModified()
                val cachedMtime = fileMtime[uuid]
                if (cachedMtime != null && cachedMtime == lm) {
                    continue
                }

                val ticks = extractTicks(file)
                playtimeTicks[uuid] = ticks
                fileMtime[uuid] = lm
            } catch (throwable: Throwable) {
                Bukkit.getLogger().fine("[EcoBalancer] Failed to load playtime cache for ${file.name}: ${throwable.message}")
            }
        }
    }

    @JvmStatic
    fun ensureLoadedFor(uuid: UUID, statsWorldName: String?) {
        if (playtimeTicks.containsKey(uuid)) {
            return
        }
        val statsDir = resolveStatsDir(statsWorldName)
        if (statsDir == null) {
            playtimeTicks[uuid] = 0L
            return
        }
        val file = File(statsDir, "$uuid.json")
        if (!file.exists()) {
            playtimeTicks[uuid] = 0L
            return
        }
        val ticks = extractTicks(file)
        playtimeTicks[uuid] = ticks
        fileMtime[uuid] = file.lastModified()
    }

    @JvmStatic
    fun getPlaytimeTicks(uuid: UUID): Long = playtimeTicks.getOrDefault(uuid, 0L)

    @JvmStatic
    fun getPlaytimeHours(uuid: UUID): Double = getPlaytimeTicks(uuid) / 20.0 / 3600.0

    private fun resolveStatsDir(statsWorldName: String?): File? {
        val world: World? = if (statsWorldName.isNullOrEmpty()) {
            if (Bukkit.getWorlds().isEmpty()) null else Bukkit.getWorlds()[0]
        } else {
            Bukkit.getWorld(statsWorldName)
        }
        if (world == null) {
            return null
        }
        return File(world.worldFolder, "stats")
    }

    private fun extractTicks(jsonFile: File): Long =
        try {
            InputStreamReader(FileInputStream(jsonFile), StandardCharsets.UTF_8).use { reader ->
                val root = gson.fromJson(reader, JsonObject::class.java) ?: return 0L
                val stats = if (root.has("stats") && root.get("stats").isJsonObject) root.getAsJsonObject("stats") else null
                if (stats == null) {
                    return 0L
                }
                val custom = if (stats.has("minecraft:custom") && stats.get("minecraft:custom").isJsonObject) {
                    stats.getAsJsonObject("minecraft:custom")
                } else {
                    null
                }
                if (custom == null) {
                    return 0L
                }
                if (custom.has("minecraft:play_time")) {
                    return safeLong(custom.get("minecraft:play_time"))
                }
                if (custom.has("minecraft:play_one_minute")) {
                    return safeLong(custom.get("minecraft:play_one_minute"))
                }
                0L
            }
        } catch (_: Throwable) {
            0L
        }

    private fun safeLong(el: JsonElement): Long =
        try {
            el.asLong
        } catch (throwable: Throwable) {
            Bukkit.getLogger().fine("[EcoBalancer] Failed to parse playtime value: ${throwable.message}")
            0L
        }
}
