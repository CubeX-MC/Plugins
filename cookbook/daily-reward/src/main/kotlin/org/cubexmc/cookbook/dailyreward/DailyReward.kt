package org.cubexmc.cookbook.dailyreward

/** 纯逻辑放在 Bukkit 之外,单测直接打它。 */
object DailyReward {

    /** 把剩余秒数渲染成 `3小时20分` 这样的提示。0 秒返回空串（此时应当直接发奖）。 */
    fun waitText(remainingSeconds: Long): String {
        if (remainingSeconds <= 0L) return ""
        val hours = remainingSeconds / 3600
        val minutes = (remainingSeconds % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}小时${minutes}分"
            hours > 0 -> "${hours}小时"
            minutes > 0 -> "${minutes}分"
            else -> "${remainingSeconds}秒"
        }
    }
}
