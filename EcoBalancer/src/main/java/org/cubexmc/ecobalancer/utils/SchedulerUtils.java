package org.cubexmc.ecobalancer.utils;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class SchedulerUtils {
    private static final Map<Plugin, Set<Object>> TRACKED_TASKS = new ConcurrentHashMap<>();
    private static final Map<Object, Plugin> TASK_OWNERS = new ConcurrentHashMap<>();

    private SchedulerUtils() {
        throw new AssertionError("This utility class cannot be instantiated.");
    }

    public static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void trackTask(Plugin plugin, Object handle) {
        if (plugin == null || handle == null) {
            return;
        }
        TRACKED_TASKS.computeIfAbsent(plugin, key -> ConcurrentHashMap.newKeySet()).add(handle);
        TASK_OWNERS.put(handle, plugin);
    }

    private static void untrackTask(Object handle) {
        if (handle == null) {
            return;
        }
        Plugin owner = TASK_OWNERS.remove(handle);
        if (owner == null) {
            return;
        }
        Set<Object> handles = TRACKED_TASKS.get(owner);
        if (handles == null) {
            return;
        }
        handles.remove(handle);
        if (handles.isEmpty()) {
            TRACKED_TASKS.remove(owner, handles);
        }
    }

    private static Runnable wrapOneShotRunnable(Runnable task, Object[] handleHolder) {
        return () -> {
            try {
                task.run();
            } finally {
                untrackTask(handleHolder[0]);
            }
        };
    }

    private static Consumer<Object> wrapFoliaTask(Runnable task, boolean repeating) {
        return scheduledTask -> {
            try {
                task.run();
            } finally {
                if (!repeating) {
                    untrackTask(scheduledTask);
                }
            }
        };
    }

    public static Object globalRun(Plugin plugin, Runnable task, long delay, long period) {
        delay = Math.max(0L, delay);
        boolean repeating = period > 0L;
        if (isFolia()) {
            try {
                Server server = Bukkit.getServer();
                Object globalScheduler = server.getClass().getMethod("getGlobalRegionScheduler").invoke(server);
                Consumer<Object> foliaTask = wrapFoliaTask(task, repeating);
                Class<?> pluginClass = Plugin.class;
                Class<?> consumerClass = Consumer.class;

                Object handle;
                if (period <= 0L) {
                    if (delay == 0L) {
                        Method run = globalScheduler.getClass().getMethod("run", pluginClass, consumerClass);
                        handle = run.invoke(globalScheduler, plugin, foliaTask);
                    } else {
                        Method runDelayed = globalScheduler.getClass().getMethod("runDelayed", pluginClass, consumerClass, long.class);
                        handle = runDelayed.invoke(globalScheduler, plugin, foliaTask, delay);
                    }
                } else {
                    Method runAtFixedRate = globalScheduler.getClass().getMethod("runAtFixedRate", pluginClass, consumerClass, long.class, long.class);
                    handle = runAtFixedRate.invoke(globalScheduler, plugin, foliaTask, Math.max(1L, delay), period);
                }
                trackTask(plugin, handle);
                return handle;
            } catch (Throwable ignored) {
                // fall through to Bukkit scheduler
            }
        }

        if (period < 0L) {
            if (delay == 0L) {
                if (Bukkit.isPrimaryThread()) {
                    task.run();
                    return null;
                }
                final Object[] handleHolder = new Object[1];
                Runnable wrapped = wrapOneShotRunnable(task, handleHolder);
                Object handle = Bukkit.getScheduler().runTask(plugin, wrapped);
                handleHolder[0] = handle;
                trackTask(plugin, handle);
                return handle;
            }
            final Object[] handleHolder = new Object[1];
            Runnable wrapped = wrapOneShotRunnable(task, handleHolder);
            Object handle = Bukkit.getScheduler().runTaskLater(plugin, wrapped, delay);
            handleHolder[0] = handle;
            trackTask(plugin, handle);
            return handle;
        }

        Object handle = Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
        trackTask(plugin, handle);
        return handle;
    }

    public static void cancelTask(Object task) {
        if (task == null) {
            return;
        }
        try {
            Method cancel = task.getClass().getMethod("cancel");
            cancel.invoke(task);
        } catch (Throwable ignored) {
            try {
                if (task instanceof BukkitTask) {
                    ((BukkitTask) task).cancel();
                }
            } catch (Throwable ignoredAgain) {
            }
        } finally {
            untrackTask(task);
        }
    }

    public static Object entityRun(Plugin plugin, Entity entity, Runnable task, long delay, long period) {
        delay = Math.max(0L, delay);
        boolean repeating = period > 0L;
        if (isFolia()) {
            try {
                Object entityScheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
                Consumer<Object> foliaTask = wrapFoliaTask(task, repeating);
                Runnable retiredCallback = () -> {
                    try {
                        plugin.getLogger().fine("Entity scheduler task cancelled: entity no longer exists");
                    } catch (Throwable ignored) {
                    }
                };
                Class<?> pluginClass = Plugin.class;
                Class<?> consumerClass = Consumer.class;
                Class<?> runnableClass = Runnable.class;

                Object handle;
                if (period <= 0L) {
                    if (delay == 0L) {
                        Method run = entityScheduler.getClass().getMethod("run", pluginClass, consumerClass, runnableClass);
                        handle = run.invoke(entityScheduler, plugin, foliaTask, retiredCallback);
                    } else {
                        Method runDelayed = entityScheduler.getClass().getMethod("runDelayed", pluginClass, consumerClass, runnableClass, long.class);
                        handle = runDelayed.invoke(entityScheduler, plugin, foliaTask, retiredCallback, delay);
                    }
                } else {
                    Method runAtFixedRate = entityScheduler.getClass().getMethod("runAtFixedRate", pluginClass, consumerClass, runnableClass, long.class, long.class);
                    handle = runAtFixedRate.invoke(entityScheduler, plugin, foliaTask, retiredCallback, Math.max(1L, delay), period);
                }
                trackTask(plugin, handle);
                return handle;
            } catch (Throwable ignored) {
                // fall through to Bukkit scheduler
            }
        }

        if (period <= 0L) {
            if (delay == 0L) {
                if (Bukkit.isPrimaryThread()) {
                    task.run();
                    return null;
                }
                final Object[] handleHolder = new Object[1];
                Runnable wrapped = wrapOneShotRunnable(task, handleHolder);
                Object handle = Bukkit.getScheduler().runTask(plugin, wrapped);
                handleHolder[0] = handle;
                trackTask(plugin, handle);
                return handle;
            }
            final Object[] handleHolder = new Object[1];
            Runnable wrapped = wrapOneShotRunnable(task, handleHolder);
            Object handle = Bukkit.getScheduler().runTaskLater(plugin, wrapped, delay);
            handleHolder[0] = handle;
            trackTask(plugin, handle);
            return handle;
        }

        Object handle = Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
        trackTask(plugin, handle);
        return handle;
    }

    public static Object regionRun(Plugin plugin, Location location, Runnable task, long delay, long period) {
        delay = Math.max(0L, delay);
        boolean repeating = period > 0L;
        if (isFolia()) {
            try {
                Server server = Bukkit.getServer();
                Object regionScheduler = server.getClass().getMethod("getRegionScheduler").invoke(server);
                Consumer<Object> foliaTask = wrapFoliaTask(task, repeating);
                Class<?> pluginClass = Plugin.class;
                Class<?> consumerClass = Consumer.class;
                Class<?> locationClass = Location.class;

                Object handle;
                if (period <= 0L) {
                    if (delay == 0L) {
                        Method run = regionScheduler.getClass().getMethod("run", pluginClass, locationClass, consumerClass);
                        handle = run.invoke(regionScheduler, plugin, location, foliaTask);
                    } else {
                        Method runDelayed = regionScheduler.getClass().getMethod("runDelayed", pluginClass, locationClass, consumerClass, long.class);
                        handle = runDelayed.invoke(regionScheduler, plugin, location, foliaTask, delay);
                    }
                } else {
                    Method runAtFixedRate = regionScheduler.getClass().getMethod("runAtFixedRate", pluginClass, locationClass, consumerClass, long.class, long.class);
                    handle = runAtFixedRate.invoke(regionScheduler, plugin, location, foliaTask, Math.max(1L, delay), period);
                }
                trackTask(plugin, handle);
                return handle;
            } catch (Throwable ignored) {
                // fall through to Bukkit scheduler
            }
        }

        if (period <= 0L) {
            if (delay == 0L) {
                if (Bukkit.isPrimaryThread()) {
                    task.run();
                    return null;
                }
                final Object[] handleHolder = new Object[1];
                Runnable wrapped = wrapOneShotRunnable(task, handleHolder);
                Object handle = Bukkit.getScheduler().runTask(plugin, wrapped);
                handleHolder[0] = handle;
                trackTask(plugin, handle);
                return handle;
            }
            final Object[] handleHolder = new Object[1];
            Runnable wrapped = wrapOneShotRunnable(task, handleHolder);
            Object handle = Bukkit.getScheduler().runTaskLater(plugin, wrapped, delay);
            handleHolder[0] = handle;
            trackTask(plugin, handle);
            return handle;
        }

        Object handle = Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
        trackTask(plugin, handle);
        return handle;
    }

    public static void asyncRun(Plugin plugin, Runnable task, long delay) {
        delay = Math.max(0L, delay);
        if (isFolia()) {
            try {
                Server server = Bukkit.getServer();
                Object asyncScheduler = server.getClass().getMethod("getAsyncScheduler").invoke(server);
                Consumer<Object> foliaTask = wrapFoliaTask(task, false);
                Class<?> pluginClass = Plugin.class;
                Class<?> consumerClass = Consumer.class;

                Object handle;
                if (delay <= 0L) {
                    Method runNow = asyncScheduler.getClass().getMethod("runNow", pluginClass, consumerClass);
                    handle = runNow.invoke(asyncScheduler, plugin, foliaTask);
                } else {
                    Method runDelayed = asyncScheduler.getClass().getMethod("runDelayed", pluginClass, consumerClass, long.class, TimeUnit.class);
                    handle = runDelayed.invoke(asyncScheduler, plugin, foliaTask, delay * 50L, TimeUnit.MILLISECONDS);
                }
                trackTask(plugin, handle);
                return;
            } catch (Throwable ignored) {
                // fall through to Bukkit scheduler
            }
        }

        final Object[] handleHolder = new Object[1];
        Runnable wrapped = wrapOneShotRunnable(task, handleHolder);
        long ticks = delay <= 0L ? 0L : Math.max(1L, delay);
        Object handle = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, wrapped, ticks);
        handleHolder[0] = handle;
        trackTask(plugin, handle);
    }

    public static void safeTeleport(Plugin plugin, org.bukkit.entity.Player player, Location dest) {
        if (player == null || dest == null) {
            return;
        }
        try {
            Method teleportAsync = player.getClass().getMethod("teleportAsync", Location.class);
            teleportAsync.invoke(player, dest);
            return;
        } catch (NoSuchMethodException ignored) {
            // method not present
        } catch (Throwable ignored) {
            // fall back to sync teleport
        }

        if (isFolia()) {
            entityRun(plugin, player, () -> {
                try {
                    player.teleport(dest);
                } catch (Throwable ignored) {
                }
            }, 0L, -1L);
        } else if (Bukkit.isPrimaryThread()) {
            player.teleport(dest);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> player.teleport(dest));
        }
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
        // On Folia, Bukkit's legacy scheduler operations are unsupported and will throw
        // UnsupportedOperationException. Only cancel our tracked tasks there.
        if (!isFolia()) {
            try {
                Bukkit.getScheduler().cancelTasks(plugin);
            } catch (UnsupportedOperationException ignored) {
                // In case of unexpected platform behavior, fall back to tracked handles only
            }
        }

        Set<Object> handles = TRACKED_TASKS.remove(plugin);
        if (handles == null) {
            return;
        }
        for (Object handle : handles) {
            cancelTask(handle);
        }
    }
}