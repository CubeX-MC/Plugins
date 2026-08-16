package org.cubexmc.statecharge.util

import kotlin.math.max

/**
 * 秒数 → 人类可读时长。单位标签由调用方提供(经语言文件),零组件省略,秒永远显示。
 *
 * 例如 3661s + 中文标签 → "1小时1分1秒";90s + 英文标签 → "1m 30s"。
 */
object TimeFormat {

    fun format(totalSeconds: Long, labels: Map<String, String>): String {
        val seconds = max(0L, totalSeconds)
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        val parts = mutableListOf<String>()
        if (days > 0) {
            parts.add("$days${labels.getValue("day")}")
        }
        if (hours > 0) {
            parts.add("$hours${labels.getValue("hour")}")
        }
        if (minutes > 0) {
            parts.add("$minutes${labels.getValue("minute")}")
        }
        parts.add("$secs${labels.getValue("second")}")
        return parts.joinToString("")
    }
}
