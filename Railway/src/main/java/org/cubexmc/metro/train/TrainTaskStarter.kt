package org.cubexmc.metro.train

import org.bukkit.Bukkit
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import org.cubexmc.metro.Metro

/**
 * Creates and registers train movement tasks after a player boards.
 */
object TrainTaskStarter {
    @JvmStatic
    fun start(plugin: Metro, minecart: Minecart, passenger: Player?, lineId: String, currentStopId: String) {
        val lineManager = plugin.lineManager
        val line = lineManager.getLine(lineId) ?: return

        if (passenger == null || !passenger.isOnline || passenger.vehicle != minecart) {
            if (minecart.isValid) {
                minecart.remove()
            }
            return
        }

        val trainTask = TrainMovementTask(plugin, minecart, passenger, lineId, currentStopId)
        Bukkit.getPluginManager().registerEvents(trainTask, plugin)
        TrainTaskRegistry.register(minecart, trainTask)

        minecart.maxSpeed = 0.0
        minecart.velocity = Vector(0, 0, 0)
        trainTask.startAtStation()
    }
}
