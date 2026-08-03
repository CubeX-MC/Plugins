package org.cubexmc.metro.event

import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.cubexmc.metro.model.Line
import org.cubexmc.metro.model.Stop

class MetroTrainDepartureEvent(
    val minecart: Minecart?,
    val passenger: Player?,
    val line: Line,
    val currentStop: Stop,
    val nextStop: Stop,
) : Event(false) {
    override fun getHandlers(): HandlerList = Companion.handlers

    companion object {
        @JvmStatic
        private val handlers = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlers
    }
}
