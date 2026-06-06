package org.cubexmc.ecobalancer.utils

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.cubexmc.scheduler.BukkitImmediateMode
import org.cubexmc.scheduler.CubexScheduler
import org.cubexmc.scheduler.LegacySchedulerAdapter
import java.util.Collections
import java.util.WeakHashMap

object SchedulerUtils {
    private val adapters: MutableMap<Plugin, LegacySchedulerAdapter> = Collections.synchronizedMap(WeakHashMap())

    @JvmStatic
    fun isFolia(): Boolean = CubexScheduler.detectFolia()

    @JvmStatic
    fun globalRun(plugin: Plugin, task: Runnable, delay: Long, period: Long): Any? =
        adapter(plugin).globalRun(task, delay, period)

    @JvmStatic
    fun cancelTask(task: Any?) {
        LegacySchedulerAdapter.cancelTaskHandle(task)
    }

    @JvmStatic
    fun entityRun(plugin: Plugin, entity: Entity, task: Runnable, delay: Long, period: Long): Any? =
        adapter(plugin).entityRun(entity, task, delay, period)

    @JvmStatic
    fun regionRun(plugin: Plugin, location: Location, task: Runnable, delay: Long, period: Long): Any? =
        adapter(plugin).regionRun(location, task, delay, period)

    @JvmStatic
    fun asyncRun(plugin: Plugin, task: Runnable, delay: Long) {
        adapter(plugin).asyncRun(task, delay)
    }

    @JvmStatic
    fun safeTeleport(plugin: Plugin, player: Player, dest: Location) {
        adapter(plugin).safeTeleport(player, dest)
    }

    @JvmStatic
    fun runTask(plugin: Plugin, task: Runnable): Any? = globalRun(plugin, task, 0L, -1L)

    @JvmStatic
    fun runTaskLater(plugin: Plugin, task: Runnable, delay: Long): Any? = globalRun(plugin, task, delay, -1L)

    @JvmStatic
    fun runTaskTimer(plugin: Plugin, task: Runnable, delay: Long, period: Long): Any? =
        globalRun(plugin, task, delay, period)

    @JvmStatic
    fun runTaskAsync(plugin: Plugin, task: Runnable) {
        asyncRun(plugin, task, 0L)
    }

    @JvmStatic
    fun runTaskLaterAsync(plugin: Plugin, task: Runnable, delay: Long) {
        asyncRun(plugin, task, delay)
    }

    @JvmStatic
    fun cancelAllTasks(plugin: Plugin?) {
        if (plugin == null) {
            return
        }
        if (!isFolia()) {
            try {
                Bukkit.getScheduler().cancelTasks(plugin)
            } catch (_: UnsupportedOperationException) {
            }
        }
        adapter(plugin).cancelAllTasks()
    }

    private fun adapter(plugin: Plugin): LegacySchedulerAdapter =
        adapters.computeIfAbsent(plugin) { createAdapter(it) }

    private fun createAdapter(plugin: Plugin): LegacySchedulerAdapter =
        LegacySchedulerAdapter.builder(plugin)
            .immediateMode(BukkitImmediateMode.INLINE_WHEN_PRIMARY_THREAD)
            .trackTasksForCancelAll(true)
            .tickAccessEnabled(false)
            .build()
}
