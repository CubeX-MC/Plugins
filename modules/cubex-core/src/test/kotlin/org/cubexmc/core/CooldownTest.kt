package org.cubexmc.core

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CooldownTest {

    private val player = UUID.randomUUID()
    private var now = 0L

    private fun cooldown(durationMillis: () -> Long) = Cooldown(durationMillis, clock = { now })

    @Test
    fun `first use always succeeds`() {
        assertTrue(cooldown { 10_000 }.tryUse(player))
    }

    @Test
    fun `a second use inside the window is refused`() {
        val cd = cooldown { 10_000 }
        cd.tryUse(player)
        now += 9_999

        assertFalse(cd.tryUse(player))
    }

    @Test
    fun `use succeeds again once the window elapsed`() {
        val cd = cooldown { 10_000 }
        cd.tryUse(player)
        now += 10_000

        assertTrue(cd.tryUse(player))
    }

    @Test
    fun `a refused attempt does not extend the cooldown`() {
        val cd = cooldown { 10_000 }
        cd.tryUse(player)
        now += 5_000
        assertFalse(cd.tryUse(player))

        now += 5_000
        assertTrue(cd.tryUse(player))
    }

    @Test
    fun `a non-positive duration disables the cooldown entirely`() {
        val cd = cooldown { 0 }

        assertTrue(cd.tryUse(player))
        assertTrue(cd.tryUse(player))
        assertEquals(0L, cd.remainingSeconds(player))
    }

    @Test
    fun `remaining seconds round up so the hint never says zero`() {
        val cd = cooldown { 10_000 }
        cd.tryUse(player)

        now += 9_900
        assertEquals(1L, cd.remainingSeconds(player))

        now += 100
        assertEquals(0L, cd.remainingSeconds(player))
    }

    @Test
    fun `remaining seconds match the pre-extraction arithmetic`() {
        val cd = cooldown { 10_000 }
        cd.tryUse(player)

        // 下沉前:10 - (1500 / 1000) = 9
        now += 1_500
        assertEquals(9L, cd.remainingSeconds(player))
    }

    @Test
    fun `the duration supplier is re-read so a reload takes effect`() {
        var duration = 10_000L
        val cd = Cooldown({ duration }, clock = { now })
        cd.tryUse(player)
        now += 5_000
        assertFalse(cd.isReady(player))

        duration = 1_000
        assertTrue(cd.isReady(player))
    }

    @Test
    fun `cooldowns are tracked per player`() {
        val cd = cooldown { 10_000 }
        val other = UUID.randomUUID()
        cd.tryUse(player)

        assertTrue(cd.tryUse(other))
    }

    @Test
    fun `clear forgets a single player and clearAll forgets everyone`() {
        val cd = cooldown { 10_000 }
        val other = UUID.randomUUID()
        cd.tryUse(player)
        cd.tryUse(other)

        cd.clear(player)
        assertTrue(cd.isReady(player))
        assertFalse(cd.isReady(other))

        cd.clearAll()
        assertTrue(cd.isReady(other))
    }
}
