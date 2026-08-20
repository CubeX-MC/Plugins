package org.cubexmc.cookbook.dailyreward

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DailyRewardTest {

    @Test
    fun `renders hours and minutes together`() {
        assertEquals("3小时20分", DailyReward.waitText(3 * 3600L + 20 * 60L))
    }

    @Test
    fun `drops the minute part when it is zero`() {
        assertEquals("3小时", DailyReward.waitText(3 * 3600L))
    }

    @Test
    fun `falls back to seconds under a minute`() {
        assertEquals("45秒", DailyReward.waitText(45))
    }

    @Test
    fun `returns an empty string when nothing is left to wait`() {
        assertEquals("", DailyReward.waitText(0))
        assertEquals("", DailyReward.waitText(-5))
    }
}
