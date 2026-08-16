package org.cubexmc.statecharge.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TimeFormatTest {

    private val labels = mapOf(
        "day" to "天",
        "hour" to "小时",
        "minute" to "分",
        "second" to "秒",
    )

    @Test
    fun zeroShowsZeroSeconds() {
        assertEquals("0秒", TimeFormat.format(0, labels))
    }

    @Test
    fun negativeClampsToZero() {
        assertEquals("0秒", TimeFormat.format(-5, labels))
    }

    @Test
    fun secondsOnly() {
        assertEquals("59秒", TimeFormat.format(59, labels))
    }

    @Test
    fun minutesAndSeconds() {
        assertEquals("1分30秒", TimeFormat.format(90, labels))
    }

    @Test
    fun hoursMinutesSeconds() {
        assertEquals("1小时1分1秒", TimeFormat.format(3661, labels))
    }

    @Test
    fun days() {
        assertEquals("1天2小时0秒", TimeFormat.format(93600, labels))
    }

    @Test
    fun englishLabels() {
        val english = mapOf("day" to "d ", "hour" to "h ", "minute" to "m ", "second" to "s")
        assertEquals("1h 1m 1s", TimeFormat.format(3661, english))
    }
}
