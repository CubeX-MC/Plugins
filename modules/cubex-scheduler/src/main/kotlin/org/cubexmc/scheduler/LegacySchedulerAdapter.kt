package org.cubexmc.scheduler

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.cubexmc.core.CubexPlugin
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** Compatibility bridge for existing SchedulerUtil public surfaces. */
class LegacySchedulerAdapter private constructor(builder: Builder) {
    private val scheduler = builder.scheduler
    private val immediateMode = builder.immediateMode
    private val trackTasksForCancelAll = builder.trackTasksForCancelAll
    private val tickAccessEnabled = builder.tickAccessEnabled
    private val tickCounter = AtomicLong()

    @Volatile
    private var tickCounterStarted = false

    @Volatile
    private var currentTickMethodChecked = false

    @Volatile
    private var reflectedTickMethod: Method? = null

    val isFolia: Boolean
        get() = CubexScheduler.detectFolia()

    fun globalRun(task: Runnable, delayTicks: Long, periodTicks: Long): Any? {
        val delay = max(0L, delayTicks)
        if (isFolia) {
            return if (periodTicks <= 0L) scheduleFoliaGlobal(task, delay)
            else scheduler.runGlobalTimer(task, max(1L, delay), periodTicks)
        }
        return if (periodTicks < 0L) scheduleBukkitOneShot(task, delay)
        else Bukkit.getScheduler().runTaskTimer(scheduler.plugin(), task, delay, periodTicks)
    }

    fun entityRun(entity: Entity, task: Runnable, delayTicks: Long, periodTicks: Long): Any? {
        val delay = max(0L, delayTicks)
        if (isFolia) {
            return if (periodTicks <= 0L) {
                if (delay == 0L) scheduler.runAtEntity(entity, task)
                else scheduler.runAtEntityLater(entity, task, delay)
            } else {
                scheduler.runAtEntityTimer(entity, task, max(1L, delay), periodTicks)
            }
        }
        return if (periodTicks <= 0L) scheduleBukkitOneShot(task, delay)
        else Bukkit.getScheduler().runTaskTimer(scheduler.plugin(), task, delay, periodTicks)
    }

    fun regionRun(location: Location, task: Runnable, delayTicks: Long, periodTicks: Long): Any? {
        val delay = max(0L, delayTicks)
        if (isFolia) {
            return if (periodTicks <= 0L) {
                if (delay == 0L) scheduler.runAtLocation(location, task)
                else scheduler.runAtLocationLater(location, task, delay)
            } else {
                scheduler.runAtLocationTimer(location, task, max(1L, delay), periodTicks)
            }
        }
        return if (periodTicks <= 0L) scheduleBukkitOneShot(task, delay)
        else Bukkit.getScheduler().runTaskTimer(scheduler.plugin(), task, delay, periodTicks)
    }

    fun asyncRun(task: Runnable, delayTicks: Long) {
        val delay = max(0L, delayTicks)
        if (isFolia) {
            if (delay == 0L) scheduler.runAsync(task) else scheduler.runAsyncLater(task, delay)
            return
        }
        Bukkit.getScheduler().runTaskLaterAsynchronously(
            scheduler.plugin(),
            task,
            if (delay <= 0L) 0L else max(1L, delay),
        )
    }

    fun cancelTask(taskHandle: Any?) = cancelTaskHandle(taskHandle)

    fun cancelAllTasks() {
        if (trackTasksForCancelAll) scheduler.cancelAll()
        else Bukkit.getScheduler().cancelTasks(scheduler.plugin())
    }

    fun safeTeleport(player: Player?, destination: Location?) {
        if (player == null || destination == null) return
        if (isFolia || scheduler.isPaper) {
            scheduler.teleportAsync(player, destination)
        } else if (Bukkit.isPrimaryThread()) {
            player.teleport(destination)
        } else {
            Bukkit.getScheduler().runTask(scheduler.plugin(), Runnable { player.teleport(destination) })
        }
    }

    fun teleportEntity(entity: Entity?, destination: Location?): CompletableFuture<Boolean> =
        if (entity == null || destination == null) CompletableFuture.completedFuture(false)
        else scheduler.teleportAsync(entity, destination)

    val currentTick: Long
        get() {
            if (!tickAccessEnabled) throw UnsupportedOperationException("Tick access is not enabled for this adapter.")
            return reflectedCurrentTick() ?: tickCounter.get()
        }

    fun ensureTickCounter() {
        if (!tickAccessEnabled) throw UnsupportedOperationException("Tick access is not enabled for this adapter.")
        if (tickCounterStarted) return
        synchronized(this) {
            if (tickCounterStarted) return
            tickCounterStarted = true
            if (reflectedCurrentTick() != null) return
            val handle = globalRun(Runnable { tickCounter.incrementAndGet() }, 0L, 1L)
            val plugin = scheduler.plugin()
            if (handle !is CubexTask && plugin is CubexPlugin) {
                plugin.bind(Runnable { cancelTask(handle) })
            }
        }
    }

    private fun scheduleFoliaGlobal(task: Runnable, delay: Long): Any =
        if (delay == 0L) scheduler.runGlobal(task) else scheduler.runGlobalLater(task, delay)

    private fun currentTickMethod(): Method? {
        if (currentTickMethodChecked) return reflectedTickMethod
        synchronized(this) {
            if (!currentTickMethodChecked) {
                reflectedTickMethod = try {
                    Bukkit::class.java.getMethod("getCurrentTick")
                } catch (_: NoSuchMethodException) {
                    null
                }
                currentTickMethodChecked = true
            }
            return reflectedTickMethod
        }
    }

    private fun reflectedCurrentTick(): Long? {
        val method = currentTickMethod() ?: return null
        return try {
            (method.invoke(null) as Number).toLong()
        } catch (_: Exception) {
            null
        }
    }

    private fun scheduleBukkitOneShot(task: Runnable, delay: Long): Any? {
        if (delay == 0L) {
            if (immediateMode == BukkitImmediateMode.INLINE_WHEN_PRIMARY_THREAD && Bukkit.isPrimaryThread()) {
                task.run()
                return null
            }
            return Bukkit.getScheduler().runTask(scheduler.plugin(), task)
        }
        return Bukkit.getScheduler().runTaskLater(scheduler.plugin(), task, delay)
    }

    class Builder internal constructor(internal val scheduler: CubexScheduler) {
        internal var immediateMode = BukkitImmediateMode.ALWAYS_SCHEDULE
        internal var trackTasksForCancelAll = false
        internal var tickAccessEnabled = false

        fun immediateMode(immediateMode: BukkitImmediateMode): Builder = apply { this.immediateMode = immediateMode }

        fun trackTasksForCancelAll(trackTasksForCancelAll: Boolean): Builder =
            apply { this.trackTasksForCancelAll = trackTasksForCancelAll }

        fun tickAccessEnabled(tickAccessEnabled: Boolean): Builder = apply { this.tickAccessEnabled = tickAccessEnabled }

        fun build(): LegacySchedulerAdapter = LegacySchedulerAdapter(this)
    }

    companion object {
        @JvmStatic
        fun builder(plugin: Plugin): Builder = Builder(CubexScheduler.create(plugin))

        @JvmStatic
        fun builder(scheduler: CubexScheduler): Builder = Builder(scheduler)

        @JvmStatic
        fun cancelTaskHandle(taskHandle: Any?) = ManagedCubexTask.cancelNative(taskHandle)
    }
}
