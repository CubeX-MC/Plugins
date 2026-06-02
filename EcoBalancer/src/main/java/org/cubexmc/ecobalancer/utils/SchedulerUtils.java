package org.cubexmc.ecobalancer.utils;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.cubexmc.scheduler.BukkitImmediateMode;
import org.cubexmc.scheduler.CubexScheduler;
import org.cubexmc.scheduler.LegacySchedulerAdapter;

public final class SchedulerUtils {

    private static final Map<Plugin, LegacySchedulerAdapter> ADAPTERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private SchedulerUtils() {
        throw new AssertionError("This utility class cannot be instantiated.");
    }

    public static boolean isFolia() {
        return CubexScheduler.detectFolia();
    }

    public static Object globalRun(Plugin plugin, Runnable task, long delay, long period) {
        return adapter(plugin).globalRun(task, delay, period);
    }

    public static void cancelTask(Object task) {
        LegacySchedulerAdapter.cancelTaskHandle(task);
    }

    public static Object entityRun(Plugin plugin, Entity entity, Runnable task, long delay, long period) {
        return adapter(plugin).entityRun(entity, task, delay, period);
    }

    public static Object regionRun(Plugin plugin, Location location, Runnable task, long delay, long period) {
        return adapter(plugin).regionRun(location, task, delay, period);
    }

    public static void asyncRun(Plugin plugin, Runnable task, long delay) {
        adapter(plugin).asyncRun(task, delay);
    }

    public static void safeTeleport(Plugin plugin, org.bukkit.entity.Player player, Location dest) {
        adapter(plugin).safeTeleport(player, dest);
    }

    public static Object runTask(Plugin plugin, Runnable task) {
        return globalRun(plugin, task, 0L, -1L);
    }

    public static Object runTaskLater(Plugin plugin, Runnable task, long delay) {
        return globalRun(plugin, task, delay, -1L);
    }

    public static Object runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
        return globalRun(plugin, task, delay, period);
    }

    public static void runTaskAsync(Plugin plugin, Runnable task) {
        asyncRun(plugin, task, 0L);
    }

    public static void runTaskLaterAsync(Plugin plugin, Runnable task, long delay) {
        asyncRun(plugin, task, delay);
    }

    public static void cancelAllTasks(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        if (!isFolia()) {
            try {
                Bukkit.getScheduler().cancelTasks(plugin);
            } catch (UnsupportedOperationException ignored) {
            }
        }
        adapter(plugin).cancelAllTasks();
    }

    private static LegacySchedulerAdapter adapter(Plugin plugin) {
        return ADAPTERS.computeIfAbsent(plugin, SchedulerUtils::createAdapter);
    }

    private static LegacySchedulerAdapter createAdapter(Plugin plugin) {
        return LegacySchedulerAdapter.builder(plugin)
                .immediateMode(BukkitImmediateMode.INLINE_WHEN_PRIMARY_THREAD)
                .trackTasksForCancelAll(true)
                .tickAccessEnabled(false)
                .build();
    }
}
