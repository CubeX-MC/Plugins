package org.cubexmc.scheduler

import com.tcoded.folialib.FoliaLib
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import org.cubexmc.core.CubexPlugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import kotlin.math.max

class CubexScheduler private constructor(private val plugin: Plugin) {
    private val tasks: MutableSet<ManagedCubexTask> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var foliaLib: FoliaLib? = null

    @Volatile
    private var foliaInitFailed = false

    fun plugin(): Plugin = plugin

    val isFolia: Boolean
        get() = foliaLib()?.isFolia == true

    val isPaper: Boolean
        get() = foliaLib()?.isPaper == true

    val isSpigot: Boolean
        get() = foliaLib()?.isSpigot == true

    fun runGlobal(task: Runnable): CubexTask = runGlobalLater(task, 0L)

    fun runGlobal(task: Consumer<CubexTask>): CubexTask = runGlobalLater(task, 0L)

    fun runGlobalLater(task: Runnable, delayTicks: Long): CubexTask =
        runGlobalLater(Consumer { task.run() }, delayTicks)

    fun runGlobalLater(task: Consumer<CubexTask>, delayTicks: Long): CubexTask {
        val delay = max(0L, delayTicks)
        val managed = newTask()
        val wrapped = oneShot(managed, task)
        val lib = foliaLib()
        if (lib != null) {
            if (delay == 0L) {
                val future = lib.scheduler.runNextTick { foliaTask ->
                    managed.attach(foliaTask)
                    wrapped.run()
                }
                managed.attach(future)
            } else {
                managed.attach(lib.scheduler.runLater(wrapped, delay))
            }
            return managed
        }
        managed.attach(
            if (delay == 0L) Bukkit.getScheduler().runTask(plugin, wrapped)
            else Bukkit.getScheduler().runTaskLater(plugin, wrapped, delay),
        )
        return managed
    }

    fun runGlobalTimer(task: Runnable, delayTicks: Long, periodTicks: Long): CubexTask =
        runGlobalTimer(Consumer { task.run() }, delayTicks, periodTicks)

    fun runGlobalTimer(task: Consumer<CubexTask>, delayTicks: Long, periodTicks: Long): CubexTask {
        val delay = max(1L, delayTicks)
        val period = max(1L, periodTicks)
        val managed = newTask()
        val wrapped = repeating(managed, task)
        managed.attach(
            foliaLib()?.scheduler?.runTimer(wrapped, delay, period)
                ?: Bukkit.getScheduler().runTaskTimer(plugin, wrapped, delay, period),
        )
        return managed
    }

    fun runAsync(task: Runnable): CubexTask = runAsyncLater(task, 0L)

    fun runAsync(task: Consumer<CubexTask>): CubexTask = runAsyncLater(task, 0L)

    fun runAsyncLater(task: Runnable, delayTicks: Long): CubexTask =
        runAsyncLater(Consumer { task.run() }, delayTicks)

    fun runAsyncLater(task: Consumer<CubexTask>, delayTicks: Long): CubexTask {
        val delay = max(0L, delayTicks)
        val managed = newTask()
        val wrapped = oneShot(managed, task)
        val lib = foliaLib()
        managed.attach(
            if (lib == null) {
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, wrapped, delay)
            } else if (delay == 0L) {
                lib.scheduler.runAsync { foliaTask ->
                    managed.attach(foliaTask)
                    wrapped.run()
                }
            } else {
                lib.scheduler.runLaterAsync(wrapped, delay)
            },
        )
        return managed
    }

    fun runAsyncTimer(task: Runnable, delayTicks: Long, periodTicks: Long): CubexTask =
        runAsyncTimer(Consumer { task.run() }, delayTicks, periodTicks)

    fun runAsyncTimer(task: Consumer<CubexTask>, delayTicks: Long, periodTicks: Long): CubexTask {
        val delay = max(1L, delayTicks)
        val period = max(1L, periodTicks)
        val managed = newTask()
        val wrapped = repeating(managed, task)
        managed.attach(
            foliaLib()?.scheduler?.runTimerAsync(wrapped, delay, period)
                ?: Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, wrapped, delay, period),
        )
        return managed
    }

    fun runAtEntity(entity: Entity, task: Runnable): CubexTask = runAtEntityLater(entity, task, 0L)

    fun runAtEntity(entity: Entity, task: Consumer<CubexTask>): CubexTask = runAtEntityLater(entity, task, 0L)

    fun runAtEntityLater(entity: Entity, task: Runnable, delayTicks: Long): CubexTask =
        runAtEntityLater(entity, Consumer { task.run() }, delayTicks)

    fun runAtEntityLater(entity: Entity, task: Consumer<CubexTask>, delayTicks: Long): CubexTask {
        val delay = max(0L, delayTicks)
        val managed = newTask()
        val wrapped = oneShot(managed, task)
        val lib = foliaLib()
        if (lib != null) {
            managed.attach(
                if (delay == 0L) {
                    lib.scheduler.runAtEntity(entity) { foliaTask ->
                        managed.attach(foliaTask)
                        wrapped.run()
                    }
                } else {
                    lib.scheduler.runAtEntityLater(entity, wrapped, delay)
                },
            )
            return managed
        }
        managed.attach(
            if (delay == 0L) Bukkit.getScheduler().runTask(plugin, wrapped)
            else Bukkit.getScheduler().runTaskLater(plugin, wrapped, delay),
        )
        return managed
    }

    fun runAtEntityTimer(entity: Entity, task: Runnable, delayTicks: Long, periodTicks: Long): CubexTask =
        runAtEntityTimer(entity, Consumer { task.run() }, delayTicks, periodTicks)

    fun runAtEntityTimer(
        entity: Entity,
        task: Consumer<CubexTask>,
        delayTicks: Long,
        periodTicks: Long,
    ): CubexTask {
        val delay = max(1L, delayTicks)
        val period = max(1L, periodTicks)
        val managed = newTask()
        val wrapped = repeating(managed, task)
        managed.attach(
            foliaLib()?.scheduler?.runAtEntityTimer(entity, wrapped, delay, period)
                ?: Bukkit.getScheduler().runTaskTimer(plugin, wrapped, delay, period),
        )
        return managed
    }

    fun runAtLocation(location: Location, task: Runnable): CubexTask = runAtLocationLater(location, task, 0L)

    fun runAtLocation(location: Location, task: Consumer<CubexTask>): CubexTask = runAtLocationLater(location, task, 0L)

    fun runAtLocationLater(location: Location, task: Runnable, delayTicks: Long): CubexTask =
        runAtLocationLater(location, Consumer { task.run() }, delayTicks)

    fun runAtLocationLater(location: Location, task: Consumer<CubexTask>, delayTicks: Long): CubexTask {
        val delay = max(0L, delayTicks)
        val managed = newTask()
        val wrapped = oneShot(managed, task)
        val lib = foliaLib()
        if (lib != null) {
            managed.attach(
                if (delay == 0L) {
                    lib.scheduler.runAtLocation(location) { foliaTask ->
                        managed.attach(foliaTask)
                        wrapped.run()
                    }
                } else {
                    lib.scheduler.runAtLocationLater(location, wrapped, delay)
                },
            )
            return managed
        }
        managed.attach(
            if (delay == 0L) Bukkit.getScheduler().runTask(plugin, wrapped)
            else Bukkit.getScheduler().runTaskLater(plugin, wrapped, delay),
        )
        return managed
    }

    fun runAtLocationTimer(location: Location, task: Runnable, delayTicks: Long, periodTicks: Long): CubexTask =
        runAtLocationTimer(location, Consumer { task.run() }, delayTicks, periodTicks)

    fun runAtLocationTimer(
        location: Location,
        task: Consumer<CubexTask>,
        delayTicks: Long,
        periodTicks: Long,
    ): CubexTask {
        val delay = max(1L, delayTicks)
        val period = max(1L, periodTicks)
        val managed = newTask()
        val wrapped = repeating(managed, task)
        managed.attach(
            foliaLib()?.scheduler?.runAtLocationTimer(location, wrapped, delay, period)
                ?: Bukkit.getScheduler().runTaskTimer(plugin, wrapped, delay, period),
        )
        return managed
    }

    fun teleportAsync(entity: Entity, location: Location): CompletableFuture<Boolean> {
        val lib = foliaLib()
        if (lib != null) return lib.scheduler.teleportAsync(entity, location)
        return try {
            CompletableFuture.completedFuture(entity.teleport(location))
        } catch (throwable: Throwable) {
            CompletableFuture<Boolean>().also { it.completeExceptionally(throwable) }
        }
    }

    fun cancelAll() {
        tasks.toTypedArray().forEach { it.cancel() }
        foliaLib?.let { lib ->
            try {
                lib.scheduler.cancelAllTasks()
            } catch (_: Throwable) {
                // Best-effort cleanup during shutdown.
            }
        }
    }

    private fun newTask(): ManagedCubexTask {
        lateinit var managed: ManagedCubexTask
        managed = ManagedCubexTask { tasks.remove(managed) }
        tasks.add(managed)
        return managed
    }

    private fun oneShot(managed: ManagedCubexTask, task: Consumer<CubexTask>): Runnable = Runnable {
        if (managed.isCancelled()) return@Runnable
        try {
            task.accept(managed)
        } finally {
            managed.complete()
        }
    }

    private fun repeating(managed: ManagedCubexTask, task: Consumer<CubexTask>): Runnable = Runnable {
        if (!managed.isCancelled()) task.accept(managed)
    }

    private fun foliaLib(): FoliaLib? {
        if (foliaInitFailed) return null
        foliaLib?.let { return it }
        return try {
            FoliaLib(plugin).also { foliaLib = it }
        } catch (_: Throwable) {
            foliaInitFailed = true
            null
        }
    }

    companion object {
        @JvmStatic
        fun bindTo(plugin: CubexPlugin): CubexScheduler =
            CubexScheduler(plugin).also { scheduler -> plugin.bind(Runnable { scheduler.cancelAll() }) }

        @JvmStatic
        fun create(plugin: Plugin): CubexScheduler =
            if (plugin is CubexPlugin) bindTo(plugin) else CubexScheduler(plugin)

        @JvmStatic
        fun detectFolia(): Boolean = try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}
