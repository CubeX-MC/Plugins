package org.cubexmc.metro.train

import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.entity.Minecart
import org.cubexmc.metro.Metro

/**
 * Tracks active train movement tasks by minecart UUID.
 */
internal object TrainTaskRegistry {
    private val activeTasks: MutableMap<UUID, TrainMovementTask> = ConcurrentHashMap()

    @JvmStatic
    fun get(cart: Minecart?): TrainMovementTask? = if (cart == null) null else activeTasks[cart.uniqueId]

    @JvmStatic
    fun register(cart: Minecart?, task: TrainMovementTask?) {
        if (cart != null && task != null) {
            activeTasks[cart.uniqueId] = task
        }
    }

    @JvmStatic
    fun unregister(cart: Minecart?) {
        if (cart != null) {
            activeTasks.remove(cart.uniqueId)
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
        val tasks = ArrayList(LinkedHashSet(activeTasks.values))
        for (task in tasks) {
            if (folia && plugin != null) {
                task.removeMinecartAndCancelOnEntityScheduler()
            } else {
                task.removeMinecartAndCancel()
            }
        }
        activeTasks.clear()
        return tasks.size
    }
}
