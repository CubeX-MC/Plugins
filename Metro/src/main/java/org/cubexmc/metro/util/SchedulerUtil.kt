package org.cubexmc.metro.util

import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.cubexmc.scheduler.BukkitImmediateMode
import org.cubexmc.scheduler.LegacySchedulerAdapter

/**
 * 调度器工具类，用于兼容 Bukkit 和 Folia 调度器。
 *
 * 迁移期保留 Metro 既有 public surface;实际调度委托给 cubex-scheduler。
 */
object SchedulerUtil {
    private val IS_FOLIA: Boolean = VersionUtil.isFolia()
    private val ADAPTERS: MutableMap<Plugin, LegacySchedulerAdapter> =
        Collections.synchronizedMap(WeakHashMap())
    private var warnedUnsafeBukkitFallback = false

    @JvmStatic
    fun globalRun(plugin: Plugin, task: Runnable, delay: Long, period: Long): Any? {
        val handle = adapter(plugin).globalRun(task, delay, period)
        warnIfBukkitFallbackOnFolia(plugin, handle, "global scheduler fallback returned BukkitTask")
        return handle
    }

    @JvmStatic
    fun cancelTask(task: Any?) {
        LegacySchedulerAdapter.cancelTaskHandle(task)
    }

    @JvmStatic
    fun entityRun(plugin: Plugin, entity: Entity, task: Runnable, delay: Long, period: Long): Any? {
        val handle = adapter(plugin).entityRun(entity, task, delay, period)
        warnIfBukkitFallbackOnFolia(plugin, handle, "entity scheduler fallback returned BukkitTask")
        return handle
    }

    @JvmStatic
    fun teleportEntity(entity: Entity?, location: Location?): CompletableFuture<Boolean> {
        if (entity == null || location == null) {
            return CompletableFuture.completedFuture(false)
        }
        if (!IS_FOLIA) {
            val success = entity.teleport(location)
            return CompletableFuture.completedFuture(success)
        }
        val plugin = entity.server.pluginManager.getPlugin("Metro")
            ?: return CompletableFuture.completedFuture(false)
        return adapter(plugin).teleportEntity(entity, location)
    }

    @JvmStatic
    fun regionRun(plugin: Plugin, location: Location, task: Runnable, delay: Long, period: Long): Any? {
        val handle = adapter(plugin).regionRun(location, task, delay, period)
        warnIfBukkitFallbackOnFolia(plugin, handle, "region scheduler fallback returned BukkitTask")
        return handle
    }

    @JvmStatic
    fun asyncRun(plugin: Plugin, task: Runnable, delay: Long) {
        adapter(plugin).asyncRun(task, delay)
    }

    private fun adapter(plugin: Plugin): LegacySchedulerAdapter =
        ADAPTERS.computeIfAbsent(plugin) { currentPlugin -> createAdapter(currentPlugin) }

    private fun createAdapter(plugin: Plugin): LegacySchedulerAdapter =
        LegacySchedulerAdapter.builder(plugin)
            .immediateMode(BukkitImmediateMode.ALWAYS_SCHEDULE)
            .trackTasksForCancelAll(false)
            .tickAccessEnabled(false)
            .build()

    private fun warnIfBukkitFallbackOnFolia(plugin: Plugin, handle: Any?, reason: String) {
        if (!IS_FOLIA || warnedUnsafeBukkitFallback || handle !is BukkitTask) {
            return
        }
        warnedUnsafeBukkitFallback = true
        plugin.logger.warning(
            "Folia scheduler fallback to Bukkit scheduler; this may not be fully Folia-safe. Reason: $reason",
        )
    }
}
