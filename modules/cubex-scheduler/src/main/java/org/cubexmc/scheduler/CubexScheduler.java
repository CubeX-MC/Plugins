package org.cubexmc.scheduler;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.cubexmc.core.CubexPlugin;

public final class CubexScheduler {

    private final Plugin plugin;
    private final Set<ManagedCubexTask> tasks = ConcurrentHashMap.newKeySet();
    private volatile FoliaLib foliaLib;
    private volatile boolean foliaInitFailed;

    private CubexScheduler(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public static CubexScheduler bindTo(CubexPlugin plugin) {
        CubexScheduler scheduler = new CubexScheduler(plugin);
        plugin.bind((Runnable) scheduler::cancelAll);
        return scheduler;
    }

    public static CubexScheduler create(Plugin plugin) {
        if (plugin instanceof CubexPlugin) {
            return bindTo((CubexPlugin) plugin);
        }
        return new CubexScheduler(plugin);
    }

    public static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    public Plugin plugin() {
        return plugin;
    }

    public boolean isFolia() {
        FoliaLib lib = foliaLib();
        return lib != null && lib.isFolia();
    }

    public boolean isPaper() {
        FoliaLib lib = foliaLib();
        return lib != null && lib.isPaper();
    }

    public boolean isSpigot() {
        FoliaLib lib = foliaLib();
        return lib != null && lib.isSpigot();
    }

    public CubexTask runGlobal(Runnable task) {
        return runGlobalLater(task, 0L);
    }

    public CubexTask runGlobal(Consumer<CubexTask> task) {
        return runGlobalLater(task, 0L);
    }

    public CubexTask runGlobalLater(Runnable task, long delayTicks) {
        return runGlobalLater(ignored -> task.run(), delayTicks);
    }

    public CubexTask runGlobalLater(Consumer<CubexTask> task, long delayTicks) {
        long delay = Math.max(0L, delayTicks);
        ManagedCubexTask managed = newTask();
        Runnable wrapped = oneShot(managed, task);
        FoliaLib lib = foliaLib();
        if (lib != null) {
            if (delay == 0L) {
                CompletableFuture<Void> future = lib.getScheduler().runNextTick(foliaTask -> {
                    managed.attach(foliaTask);
                    wrapped.run();
                });
                managed.attach(future);
            } else {
                WrappedTask nativeTask = lib.getScheduler().runLater(wrapped, delay);
                managed.attach(nativeTask);
            }
            return managed;
        }
        BukkitTask nativeTask = delay == 0L
                ? Bukkit.getScheduler().runTask(plugin, wrapped)
                : Bukkit.getScheduler().runTaskLater(plugin, wrapped, delay);
        managed.attach(nativeTask);
        return managed;
    }

    public CubexTask runGlobalTimer(Runnable task, long delayTicks, long periodTicks) {
        return runGlobalTimer(ignored -> task.run(), delayTicks, periodTicks);
    }

    public CubexTask runGlobalTimer(Consumer<CubexTask> task, long delayTicks, long periodTicks) {
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        ManagedCubexTask managed = newTask();
        Runnable wrapped = repeating(managed, task);
        FoliaLib lib = foliaLib();
        Object nativeTask = lib != null
                ? lib.getScheduler().runTimer(wrapped, delay, period)
                : Bukkit.getScheduler().runTaskTimer(plugin, wrapped, delay, period);
        managed.attach(nativeTask);
        return managed;
    }

    public CubexTask runAsync(Runnable task) {
        return runAsyncLater(task, 0L);
    }

    public CubexTask runAsync(Consumer<CubexTask> task) {
        return runAsyncLater(task, 0L);
    }

    public CubexTask runAsyncLater(Runnable task, long delayTicks) {
        return runAsyncLater(ignored -> task.run(), delayTicks);
    }

    public CubexTask runAsyncLater(Consumer<CubexTask> task, long delayTicks) {
        long delay = Math.max(0L, delayTicks);
        ManagedCubexTask managed = newTask();
        Runnable wrapped = oneShot(managed, task);
        FoliaLib lib = foliaLib();
        Object nativeTask = lib != null
                ? (delay == 0L ? lib.getScheduler().runAsync(foliaTask -> {
                    managed.attach(foliaTask);
                    wrapped.run();
                }) : lib.getScheduler().runLaterAsync(wrapped, delay))
                : Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, wrapped, delay);
        managed.attach(nativeTask);
        return managed;
    }

    public CubexTask runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
        return runAsyncTimer(ignored -> task.run(), delayTicks, periodTicks);
    }

    public CubexTask runAsyncTimer(Consumer<CubexTask> task, long delayTicks, long periodTicks) {
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        ManagedCubexTask managed = newTask();
        Runnable wrapped = repeating(managed, task);
        FoliaLib lib = foliaLib();
        Object nativeTask = lib != null
                ? lib.getScheduler().runTimerAsync(wrapped, delay, period)
                : Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, wrapped, delay, period);
        managed.attach(nativeTask);
        return managed;
    }

    public CubexTask runAtEntity(Entity entity, Runnable task) {
        return runAtEntityLater(entity, task, 0L);
    }

    public CubexTask runAtEntity(Entity entity, Consumer<CubexTask> task) {
        return runAtEntityLater(entity, task, 0L);
    }

    public CubexTask runAtEntityLater(Entity entity, Runnable task, long delayTicks) {
        return runAtEntityLater(entity, ignored -> task.run(), delayTicks);
    }

    public CubexTask runAtEntityLater(Entity entity, Consumer<CubexTask> task, long delayTicks) {
        Objects.requireNonNull(entity, "entity");
        long delay = Math.max(0L, delayTicks);
        ManagedCubexTask managed = newTask();
        Runnable wrapped = oneShot(managed, task);
        FoliaLib lib = foliaLib();
        if (lib != null) {
            if (delay == 0L) {
                CompletableFuture<?> future = lib.getScheduler().runAtEntity(entity, foliaTask -> {
                    managed.attach(foliaTask);
                    wrapped.run();
                });
                managed.attach(future);
            } else {
                managed.attach(lib.getScheduler().runAtEntityLater(entity, wrapped, delay));
            }
            return managed;
        }
        BukkitTask nativeTask = delay == 0L
                ? Bukkit.getScheduler().runTask(plugin, wrapped)
                : Bukkit.getScheduler().runTaskLater(plugin, wrapped, delay);
        managed.attach(nativeTask);
        return managed;
    }

    public CubexTask runAtEntityTimer(Entity entity, Runnable task, long delayTicks, long periodTicks) {
        return runAtEntityTimer(entity, ignored -> task.run(), delayTicks, periodTicks);
    }

    public CubexTask runAtEntityTimer(Entity entity, Consumer<CubexTask> task, long delayTicks, long periodTicks) {
        Objects.requireNonNull(entity, "entity");
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        ManagedCubexTask managed = newTask();
        Runnable wrapped = repeating(managed, task);
        FoliaLib lib = foliaLib();
        Object nativeTask = lib != null
                ? lib.getScheduler().runAtEntityTimer(entity, wrapped, delay, period)
                : Bukkit.getScheduler().runTaskTimer(plugin, wrapped, delay, period);
        managed.attach(nativeTask);
        return managed;
    }

    public CubexTask runAtLocation(Location location, Runnable task) {
        return runAtLocationLater(location, task, 0L);
    }

    public CubexTask runAtLocation(Location location, Consumer<CubexTask> task) {
        return runAtLocationLater(location, task, 0L);
    }

    public CubexTask runAtLocationLater(Location location, Runnable task, long delayTicks) {
        return runAtLocationLater(location, ignored -> task.run(), delayTicks);
    }

    public CubexTask runAtLocationLater(Location location, Consumer<CubexTask> task, long delayTicks) {
        Objects.requireNonNull(location, "location");
        long delay = Math.max(0L, delayTicks);
        ManagedCubexTask managed = newTask();
        Runnable wrapped = oneShot(managed, task);
        FoliaLib lib = foliaLib();
        if (lib != null) {
            if (delay == 0L) {
                CompletableFuture<Void> future = lib.getScheduler().runAtLocation(location, foliaTask -> {
                    managed.attach(foliaTask);
                    wrapped.run();
                });
                managed.attach(future);
            } else {
                managed.attach(lib.getScheduler().runAtLocationLater(location, wrapped, delay));
            }
            return managed;
        }
        BukkitTask nativeTask = delay == 0L
                ? Bukkit.getScheduler().runTask(plugin, wrapped)
                : Bukkit.getScheduler().runTaskLater(plugin, wrapped, delay);
        managed.attach(nativeTask);
        return managed;
    }

    public CubexTask runAtLocationTimer(Location location, Runnable task, long delayTicks, long periodTicks) {
        return runAtLocationTimer(location, ignored -> task.run(), delayTicks, periodTicks);
    }

    public CubexTask runAtLocationTimer(Location location, Consumer<CubexTask> task, long delayTicks, long periodTicks) {
        Objects.requireNonNull(location, "location");
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        ManagedCubexTask managed = newTask();
        Runnable wrapped = repeating(managed, task);
        FoliaLib lib = foliaLib();
        Object nativeTask = lib != null
                ? lib.getScheduler().runAtLocationTimer(location, wrapped, delay, period)
                : Bukkit.getScheduler().runTaskTimer(plugin, wrapped, delay, period);
        managed.attach(nativeTask);
        return managed;
    }

    public CompletableFuture<Boolean> teleportAsync(Entity entity, Location location) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(location, "location");
        FoliaLib lib = foliaLib();
        if (lib != null) {
            return lib.getScheduler().teleportAsync(entity, location);
        }
        try {
            return CompletableFuture.completedFuture(entity.teleport(location));
        } catch (Throwable throwable) {
            CompletableFuture<Boolean> failed = new CompletableFuture<>();
            failed.completeExceptionally(throwable);
            return failed;
        }
    }

    public void cancelAll() {
        for (ManagedCubexTask task : tasks.toArray(new ManagedCubexTask[0])) {
            task.cancel();
        }
        FoliaLib lib = foliaLib;
        if (lib != null) {
            try {
                lib.getScheduler().cancelAllTasks();
            } catch (Throwable ignored) {
            }
        }
    }

    private ManagedCubexTask newTask() {
        final ManagedCubexTask[] holder = new ManagedCubexTask[1];
        holder[0] = new ManagedCubexTask(() -> tasks.remove(holder[0]));
        tasks.add(holder[0]);
        return holder[0];
    }

    private Runnable oneShot(ManagedCubexTask managed, Consumer<CubexTask> task) {
        Objects.requireNonNull(task, "task");
        return () -> {
            if (managed.isCancelled()) {
                return;
            }
            try {
                task.accept(managed);
            } finally {
                managed.complete();
            }
        };
    }

    private Runnable repeating(ManagedCubexTask managed, Consumer<CubexTask> task) {
        Objects.requireNonNull(task, "task");
        return () -> {
            if (!managed.isCancelled()) {
                task.accept(managed);
            }
        };
    }

    private FoliaLib foliaLib() {
        if (foliaInitFailed) {
            return null;
        }
        FoliaLib lib = foliaLib;
        if (lib != null) {
            return lib;
        }
        try {
            lib = new FoliaLib(plugin);
            foliaLib = lib;
            return lib;
        } catch (Throwable ignored) {
            foliaInitFailed = true;
            return null;
        }
    }
}
