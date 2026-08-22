package org.cubexmc.core

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CubexEventsTest {

    private open class AlphaEvent : Event() {
        override fun getHandlers(): HandlerList = HANDLERS

        companion object {
            val HANDLERS = HandlerList()
        }
    }

    /** Bukkit 会把子类事件派发给父类的注册,所以子类必须仍然命中。 */
    private class AlphaSubEvent : AlphaEvent()

    private class BetaEvent : Event() {
        override fun getHandlers(): HandlerList = HANDLERS

        companion object {
            val HANDLERS = HandlerList()
        }
    }

    @Test
    fun `invokes the handler for a matching event`() {
        var seen = 0
        val executor = CubexEvents.executor(AlphaEvent::class.java) { seen++ }

        executor.execute(object : org.bukkit.event.Listener {}, AlphaEvent())

        assertEquals(1, seen)
    }

    @Test
    fun `invokes the handler for a subclass of the registered type`() {
        var seen = 0
        val executor = CubexEvents.executor(AlphaEvent::class.java) { seen++ }

        executor.execute(object : org.bukkit.event.Listener {}, AlphaSubEvent())

        assertEquals(1, seen)
    }

    @Test
    fun `ignores an event of an unrelated type instead of throwing`() {
        var seen = 0
        val executor = CubexEvents.executor(AlphaEvent::class.java) { seen++ }

        executor.execute(object : org.bukkit.event.Listener {}, BetaEvent())

        assertEquals(0, seen)
    }

    @Test
    fun `passes the event through without copying it`() {
        val original = AlphaEvent()
        var received: AlphaEvent? = null
        val executor = CubexEvents.executor(AlphaEvent::class.java) { received = it }

        executor.execute(object : org.bukkit.event.Listener {}, original)

        assertEquals(original, received)
    }
}
