package org.cubexmc.metro.train

import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.entity.Minecart
import org.cubexmc.metro.Metro

/**
 * Tracks active train movement tasks by minecart UUID.
 */
object TrainTaskRegistry {
    private val ACTIVE_TASKS: MutableMap<UUID, TrainMovementTask> = ConcurrentHashMap()

    @JvmStatic
    fun get(cart: Minecart?): TrainMovementTask? = if (cart == null) null else ACTIVE_TASKS[cart.uniqueId]

    @JvmStatic
    fun register(cart: Minecart?, task: TrainMovementTask?) {
        if (cart != null && task != null) {
            ACTIVE_TASKS[cart.uniqueId] = task
        }
    }

    @JvmStatic
    fun unregister(cart: Minecart?) {
        if (cart != null) {
            ACTIVE_TASKS.remove(cart.uniqueId)
        }
    }

    @JvmStatic
    fun transfer(previousCart: Minecart?, newCart: Minecart?, task: TrainMovementTask?) {
        unregister(previousCart)
        register(newCart, task)
    }

    @JvmStatic
    fun shutdownActiveTasks(): Int = shutdownActiveTasks(null, false)

    @JvmStatic
    fun shutdownActiveTasks(plugin: Metro?, folia: Boolean): Int {
        val tasks = ArrayList(LinkedHashSet(ACTIVE_TASKS.values))
        for (task in tasks) {
            if (folia && plugin != null) {
                task.removeMinecartAndCancelOnEntityScheduler()
            } else {
                task.removeMinecartAndCancel()
            }
        }
        ACTIVE_TASKS.clear()
        return tasks.size
    }
}
