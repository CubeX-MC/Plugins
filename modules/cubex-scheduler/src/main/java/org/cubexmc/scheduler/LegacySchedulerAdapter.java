package org.cubexmc.scheduler;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Temporary bridge for migrating existing SchedulerUtil classes without changing
 * their public surface. New code should depend on CubexScheduler directly.
 */
public final class LegacySchedulerAdapter {

    private final CubexScheduler scheduler;
    private final BukkitImmediateMode immediateMode;
    private final boolean trackTasksForCancelAll;
    private final boolean tickAccessEnabled;

    private LegacySchedulerAdapter(Builder builder) {
        this.scheduler = builder.scheduler;
        this.immediateMode = builder.immediateMode;
        this.trackTasksForCancelAll = builder.trackTasksForCancelAll;
        this.tickAccessEnabled = builder.tickAccessEnabled;
    }

    public static Builder builder(Plugin plugin) {
        return new Builder(CubexScheduler.create(plugin));
    }

    public static Builder builder(CubexScheduler scheduler) {
        return new Builder(scheduler);
    }

    public boolean isFolia() {
        return CubexScheduler.detectFolia();
    }

    public Object globalRun(Runnable task, long delayTicks, long periodTicks) {
        long delay = Math.max(0L, delayTicks);
        if (isFolia()) {
            return periodTicks <= 0L
                    ? scheduleFoliaGlobal(task, delay)
                    : scheduler.runGlobalTimer(task, Math.max(1L, delay), periodTicks);
        }
        if (periodTicks < 0L) {
            return scheduleBukkitOneShot(task, delay);
        }
        return Bukkit.getScheduler().runTaskTimer(scheduler.plugin(), task, delay, periodTicks);
    }

    public Object entityRun(Entity entity, Runnable task, long delayTicks, long periodTicks) {
        long delay = Math.max(0L, delayTicks);
        if (isFolia()) {
            return periodTicks <= 0L
                    ? (delay == 0L ? scheduler.runAtEntity(entity, task) : scheduler.runAtEntityLater(entity, task, delay))
                    : scheduler.runAtEntityTimer(entity, task, Math.max(1L, delay), periodTicks);
        }
        if (periodTicks <= 0L) {
            return scheduleBukkitOneShot(task, delay);
        }
        return Bukkit.getScheduler().runTaskTimer(scheduler.plugin(), task, delay, periodTicks);
    }

    public Object regionRun(Location location, Runnable task, long delayTicks, long periodTicks) {
        long delay = Math.max(0L, delayTicks);
        if (isFolia()) {
            return periodTicks <= 0L
                    ? (delay == 0L ? scheduler.runAtLocation(location, task) : scheduler.runAtLocationLater(location, task, delay))
                    : scheduler.runAtLocationTimer(location, task, Math.max(1L, delay), periodTicks);
        }
        if (periodTicks <= 0L) {
            return scheduleBukkitOneShot(task, delay);
        }
        return Bukkit.getScheduler().runTaskTimer(scheduler.plugin(), task, delay, periodTicks);
    }

    public void asyncRun(Runnable task, long delayTicks) {
        long delay = Math.max(0L, delayTicks);
        if (isFolia()) {
            if (delay == 0L) {
                scheduler.runAsync(task);
            } else {
                scheduler.runAsyncLater(task, delay);
            }
            return;
        }
        long ticks = delay <= 0L ? 0L : Math.max(1L, delay);
        Bukkit.getScheduler().runTaskLaterAsynchronously(scheduler.plugin(), task, ticks);
    }

    public void cancelTask(Object taskHandle) {
        cancelTaskHandle(taskHandle);
    }

    public static void cancelTaskHandle(Object taskHandle) {
        ManagedCubexTask.cancelNative(taskHandle);
    }

    public void cancelAllTasks() {
        if (trackTasksForCancelAll) {
            scheduler.cancelAll();
            return;
        }
        Bukkit.getScheduler().cancelTasks(scheduler.plugin());
    }

    public void safeTeleport(Player player, Location destination) {
        if (player == null || destination == null) {
            return;
        }
        if (isFolia() || scheduler.isPaper()) {
            scheduler.teleportAsync(player, destination);
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            player.teleport(destination);
        } else {
            Bukkit.getScheduler().runTask(scheduler.plugin(), () -> player.teleport(destination));
        }
    }

    public CompletableFuture<Boolean> teleportEntity(Entity entity, Location destination) {
        if (entity == null || destination == null) {
            return CompletableFuture.completedFuture(false);
        }
        return scheduler.teleportAsync(entity, destination);
    }

    public long getCurrentTick() {
        if (!tickAccessEnabled) {
            throw new UnsupportedOperationException("Tick access is not enabled for this adapter.");
        }
        throw new UnsupportedOperationException("Tick access is outside the RuleGems trial scope.");
    }

    public void ensureTickCounter() {
        if (!tickAccessEnabled) {
            throw new UnsupportedOperationException("Tick access is not enabled for this adapter.");
        }
        throw new UnsupportedOperationException("Tick counter is outside the RuleGems trial scope.");
    }

    private Object scheduleFoliaGlobal(Runnable task, long delay) {
        return delay == 0L ? scheduler.runGlobal(task) : scheduler.runGlobalLater(task, delay);
    }

    private Object scheduleBukkitOneShot(Runnable task, long delay) {
        if (delay == 0L) {
            if (immediateMode == BukkitImmediateMode.INLINE_WHEN_PRIMARY_THREAD && Bukkit.isPrimaryThread()) {
                task.run();
                return null;
            }
            return Bukkit.getScheduler().runTask(scheduler.plugin(), task);
        }
        return Bukkit.getScheduler().runTaskLater(scheduler.plugin(), task, delay);
    }

    public static final class Builder {

        private final CubexScheduler scheduler;
        private BukkitImmediateMode immediateMode = BukkitImmediateMode.ALWAYS_SCHEDULE;
        private boolean trackTasksForCancelAll;
        private boolean tickAccessEnabled;

        private Builder(CubexScheduler scheduler) {
            this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        }

        public Builder immediateMode(BukkitImmediateMode immediateMode) {
            this.immediateMode = Objects.requireNonNull(immediateMode, "immediateMode");
            return this;
        }

        public Builder trackTasksForCancelAll(boolean trackTasksForCancelAll) {
            this.trackTasksForCancelAll = trackTasksForCancelAll;
            return this;
        }

        public Builder tickAccessEnabled(boolean tickAccessEnabled) {
            this.tickAccessEnabled = tickAccessEnabled;
            return this;
        }

        public LegacySchedulerAdapter build() {
            return new LegacySchedulerAdapter(this);
        }
    }
}
