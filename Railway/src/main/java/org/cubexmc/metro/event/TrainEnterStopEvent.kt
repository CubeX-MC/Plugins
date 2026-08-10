package org.cubexmc.metro.event

import org.bukkit.entity.Minecart
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.cubexmc.metro.model.Stop

/**
 * 矿车进入地铁停靠区时触发的事件
 */
class TrainEnterStopEvent(
    val minecart: Minecart,
    val stop: Stop,
) : Event() {
    override fun getHandlers(): HandlerList = Companion.handlers

    companion object {
        @JvmStatic
        private val handlers = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlers
    }
}
