package org.cubexmc.metro.event

import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.cubexmc.metro.model.Line
import org.cubexmc.metro.model.Stop

class MetroTrainArrivalEvent(
    val minecart: Minecart?,
    val passenger: Player?,
    val line: Line,
    val currentStop: Stop,
    private val terminus: Boolean,
    val arrivalType: ArrivalType,
) : Event(false) {
    enum class ArrivalType {
        ENTERING,
        DOCKED,
    }

    fun isTerminus(): Boolean = terminus

    override fun getHandlers(): HandlerList = Companion.handlers

    companion object {
        @JvmStatic
        private val handlers = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlers
    }
}
