package org.cubexmc.metro.lifecycle

import org.bukkit.Bukkit
import org.cubexmc.metro.Metro
import org.cubexmc.metro.gui.GuiListener
import org.cubexmc.metro.listener.PlayerInteractListener
import org.cubexmc.metro.listener.PlayerMoveListener
import org.cubexmc.metro.listener.VehicleListener
import org.cubexmc.metro.manager.RailProtectionManager
import org.cubexmc.metro.train.TrainDisplayController

/**
 * Creates and registers Bukkit event listeners.
 */
class ListenerRegistration(
    private val plugin: Metro,
    private val railProtectionManager: RailProtectionManager,
) {
    fun register(): Result {
        val playerInteractListener = PlayerInteractListener(plugin)
        val vehicleListener = VehicleListener(plugin)
        val playerMoveListener = PlayerMoveListener(plugin)
        val guiListener = GuiListener(plugin)
        val trainDisplayController = TrainDisplayController(plugin)

        Bukkit.getPluginManager().registerEvents(playerInteractListener, plugin)
        Bukkit.getPluginManager().registerEvents(vehicleListener, plugin)
        Bukkit.getPluginManager().registerEvents(playerMoveListener, plugin)
        Bukkit.getPluginManager().registerEvents(guiListener, plugin)
        Bukkit.getPluginManager().registerEvents(trainDisplayController, plugin)
        Bukkit.getPluginManager().registerEvents(railProtectionManager, plugin)

        return Result(
            playerInteractListener,
            vehicleListener,
            playerMoveListener,
            guiListener,
            trainDisplayController,
        )
    }

    data class Result(
        private val playerInteractListener: PlayerInteractListener,
        private val vehicleListener: VehicleListener,
        private val playerMoveListener: PlayerMoveListener,
        private val guiListener: GuiListener,
        private val trainDisplayController: TrainDisplayController,
    ) {
        fun playerInteractListener(): PlayerInteractListener = playerInteractListener

        fun vehicleListener(): VehicleListener = vehicleListener

        fun playerMoveListener(): PlayerMoveListener = playerMoveListener

        fun guiListener(): GuiListener = guiListener

        fun trainDisplayController(): TrainDisplayController = trainDisplayController
    }
}
