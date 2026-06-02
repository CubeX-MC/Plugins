package org.cubexmc.metro.util;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.cubexmc.scheduler.BukkitImmediateMode;
import org.cubexmc.scheduler.LegacySchedulerAdapter;

/**
 * 调度器工具类，用于兼容 Bukkit 和 Folia 调度器。
 *
 * <p>迁移期保留 Railway 既有 public surface;实际调度委托给 cubex-scheduler。
 */
public class SchedulerUtil {

    private static final boolean IS_FOLIA = VersionUtil.isFolia();
    private static final Map<Plugin, LegacySchedulerAdapter> ADAPTERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile LegacySchedulerAdapter tickAdapter;
    private static boolean warnedUnsafeBukkitFallback = false;

    private SchedulerUtil() {
    }

    public static boolean isFolia() {
        return IS_FOLIA;
    }

    public static long getCurrentTick() {
        LegacySchedulerAdapter adapter = tickAdapter;
        if (adapter != null) {
            return adapter.getCurrentTick();
        }
        try {
            Method method = Bukkit.class.getMethod("getCurrentTick");
            return (long) method.invoke(null);
        } catch (Exception ignored) {
        }
        return 0L;
    }

    public static void ensureTickCounter(Plugin plugin) {
        LegacySchedulerAdapter adapter = adapter(plugin);
        tickAdapter = adapter;
        adapter.ensureTickCounter();
    }

    public static Object globalRun(Plugin plugin, Runnable task, long delay, long period) {
        Object handle = adapter(plugin).globalRun(task, delay, period);
        warnIfBukkitFallbackOnFolia(plugin, handle, "global scheduler fallback returned BukkitTask");
        return handle;
    }

    public static void cancelTask(Object task) {
        LegacySchedulerAdapter.cancelTaskHandle(task);
    }

    public static Object entityRun(Plugin plugin, Entity entity, Runnable task, long delay, long period) {
        Object handle = adapter(plugin).entityRun(entity, task, delay, period);
        warnIfBukkitFallbackOnFolia(plugin, handle, "entity scheduler fallback returned BukkitTask");
        return handle;
    }

    public static CompletableFuture<Boolean> teleportEntity(Entity entity, Location location) {
        if (entity == null || location == null) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        if (!IS_FOLIA) {
            boolean success = entity.teleport(location);
            return CompletableFuture.completedFuture(success);
        }
        Plugin plugin = entity.getServer().getPluginManager().getPlugin("Railway");
        if (plugin == null) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        return adapter(plugin).teleportEntity(entity, location);
    }

    public static Object regionRun(Plugin plugin, Location location, Runnable task, long delay, long period) {
        Object handle = adapter(plugin).regionRun(location, task, delay, period);
        warnIfBukkitFallbackOnFolia(plugin, handle, "region scheduler fallback returned BukkitTask");
        return handle;
    }

    public static void asyncRun(Plugin plugin, Runnable task, long delay) {
        adapter(plugin).asyncRun(task, delay);
    }

    private static LegacySchedulerAdapter adapter(Plugin plugin) {
        return ADAPTERS.computeIfAbsent(plugin, SchedulerUtil::createAdapter);
    }

    private static LegacySchedulerAdapter createAdapter(Plugin plugin) {
        return LegacySchedulerAdapter.builder(plugin)
                .immediateMode(BukkitImmediateMode.ALWAYS_SCHEDULE)
                .trackTasksForCancelAll(false)
                .tickAccessEnabled(true)
                .build();
    }

    private static void warnIfBukkitFallbackOnFolia(Plugin plugin, Object handle, String reason) {
        if (!IS_FOLIA || warnedUnsafeBukkitFallback || !(handle instanceof BukkitTask)) {
            return;
        }
        warnedUnsafeBukkitFallback = true;
        plugin.getLogger().warning(
                "Folia scheduler fallback to Bukkit scheduler; this may not be fully Folia-safe. Reason: " + reason);
    }
}
