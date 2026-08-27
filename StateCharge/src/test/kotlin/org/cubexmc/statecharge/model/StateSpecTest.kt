package org.cubexmc.statecharge.model

import java.math.BigDecimal
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.cubexmc.statecharge.effect.StateEffect

/**
 * 计费算术是这个插件唯一会直接动玩家钱包的地方，所以单独锁住。
 *
 * 服务层的其余部分要真实的 Bukkit / Vault 才跑得动，那部分靠 `REAL_SERVER_TEST.md` 覆盖。
 */
class StateSpecTest {

    private fun spec(price: String, unitSeconds: Long): StateSpec =
        StateSpec(
            "small",
            "变小",
            BigDecimal(price),
            unitSeconds,
            null,
            "scale",
            mock(StateEffect::class.java),
            true,
            Material.NAME_TAG,
        )

    @Test
    fun `a full period costs exactly the configured price`() {
        assertEquals(0, BigDecimal("100").compareTo(spec("100", 1800).costFor(1800)))
    }

    @Test
    fun `a partial period is charged pro rata, not rounded away`() {
        // 60 秒 = 1800 秒费率的 1/30 → 100 / 30
        val cost = spec("100", 1800).costFor(60)

        assertTrue(cost.toDouble() > 3.32 && cost.toDouble() < 3.34, "expected ~3.333, got $cost")
    }

    @Test
    fun `small slices do not round down to zero`() {
        // 取整会让"每分钟结算"把不足一元的零头全抹掉,玩家开一小时几乎不花钱。
        val cost = spec("100", 86_400).costFor(60)

        assertTrue(cost.signum() > 0, "a one-minute slice must still cost something, got $cost")
    }

    @Test
    fun `charging N slices equals charging the whole span`() {
        val subject = spec("100", 1800)
        val whole = subject.costFor(600)
        val slices = (1..10).fold(BigDecimal.ZERO) { acc, _ -> acc.add(subject.costFor(60)) }

        assertEquals(0, whole.compareTo(slices), "whole=$whole slices=$slices")
    }

    @Test
    fun `a free state never costs anything`() {
        val free = spec("0", 1800)

        assertTrue(free.isFree())
        assertEquals(0, BigDecimal.ZERO.compareTo(free.costFor(99_999)))
    }

    @Test
    fun `zero or negative durations cost nothing`() {
        val subject = spec("100", 1800)

        assertEquals(0, BigDecimal.ZERO.compareTo(subject.costFor(0)))
        assertEquals(0, BigDecimal.ZERO.compareTo(subject.costFor(-30)))
    }

    @Test
    fun `a priced state is not free`() {
        assertFalse(spec("0.01", 60).isFree())
    }
}
