package org.cubexmc.ecobalancer.utils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility to read vanilla player playtime from world stats files.
 * Reads stats/<uuid>.json and extracts minecraft:play_time or minecraft:play_one_minute.
 */
public final class PlaytimeUtils {
    private static final Map<UUID, Long> playtimeTicks = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> fileMtime = new ConcurrentHashMap<>();
    private static final Gson GSON = new Gson();

    private PlaytimeUtils() {}

    public static void loadAllAsync(JavaPlugin plugin, String statsWorldName) {
        SchedulerUtils.runTaskAsync(plugin, () -> loadAll(statsWorldName));
    }

    public static void loadAll(String statsWorldName) {
        File statsDir = resolveStatsDir(statsWorldName);
        if (statsDir == null || !statsDir.isDirectory()) return;

        File[] files = statsDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;

        for (File f : files) {
            try {
                String base = f.getName().substring(0, f.getName().length() - 5);
                UUID uuid = UUID.fromString(base);
                long lm = f.lastModified();
                Long cachedMtime = fileMtime.get(uuid);
                if (cachedMtime != null && cachedMtime == lm) continue;

                long ticks = extractTicks(f);
                playtimeTicks.put(uuid, ticks);
                fileMtime.put(uuid, lm);
            } catch (Throwable t) {
                Bukkit.getLogger().fine("[EcoBalancer] Failed to load playtime cache for " + f.getName() + ": " + t.getMessage());
            }
        }
    }

    public static void ensureLoadedFor(UUID uuid, String statsWorldName) {
        if (playtimeTicks.containsKey(uuid)) return;
        File statsDir = resolveStatsDir(statsWorldName);
        if (statsDir == null) {
            playtimeTicks.put(uuid, 0L);
            return;
        }
        File f = new File(statsDir, uuid.toString() + ".json");
        if (!f.exists()) {
            playtimeTicks.put(uuid, 0L);
            return;
        }
        long ticks = extractTicks(f);
        playtimeTicks.put(uuid, ticks);
        fileMtime.put(uuid, f.lastModified());
    }

    public static long getPlaytimeTicks(UUID uuid) {
        return playtimeTicks.getOrDefault(uuid, 0L);
    }

    public static double getPlaytimeHours(UUID uuid) {
        return getPlaytimeTicks(uuid) / 20.0 / 3600.0;
    }

    private static File resolveStatsDir(String statsWorldName) {
        World w = (statsWorldName == null || statsWorldName.isEmpty())
                ? (Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0))
                : Bukkit.getWorld(statsWorldName);
        if (w == null) return null;
        return new File(w.getWorldFolder(), "stats");
    }

    private static long extractTicks(File jsonFile) {
        try (Reader r = new InputStreamReader(new FileInputStream(jsonFile), StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(r, JsonObject.class);
            if (root == null) return 0L;
            JsonObject stats = root.has("stats") && root.get("stats").isJsonObject() ? root.getAsJsonObject("stats") : null;
            if (stats == null) return 0L;
            JsonObject custom = stats.has("minecraft:custom") && stats.get("minecraft:custom").isJsonObject()
                    ? stats.getAsJsonObject("minecraft:custom") : null;
            if (custom == null) return 0L;
            if (custom.has("minecraft:play_time")) {
                return safeLong(custom.get("minecraft:play_time"));
            }
            if (custom.has("minecraft:play_one_minute")) {
                return safeLong(custom.get("minecraft:play_one_minute"));
            }
            return 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static long safeLong(JsonElement el) {
        try {
            return el.getAsLong();
        } catch (Throwable t) {
            Bukkit.getLogger().fine("[EcoBalancer] Failed to parse playtime value: " + t.getMessage());
            return 0L;
        }
    }
}


