package org.cubexmc.metro.event

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.cubexmc.metro.model.Line
import org.cubexmc.metro.model.LineStatus

/**
 * Fired when a line's operational status changes.
 */
class LineStatusChangeEvent(
    val line: Line,
    val oldStatus: LineStatus,
    val newStatus: LineStatus,
) : Event() {
    override fun getHandlers(): HandlerList = Companion.handlers

    companion object {
        @JvmStatic
        private val handlers = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlers
    }
}
