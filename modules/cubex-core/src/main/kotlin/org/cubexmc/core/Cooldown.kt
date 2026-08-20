package org.cubexmc.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 按玩家计的冷却。
 *
 * [durationMillis] 是 supplier —— 冷却时长通常来自配置，reload 之后必须立刻生效，
 * 不能在构造时定死。返回 `<= 0` 表示**不设冷却**，[tryUse] 恒为 true 且不记录时间戳。
 *
 * 记录用 `ConcurrentHashMap`：判定常发生在事件线程上。
 */
class Cooldown(
    private val durationMillis: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lastUse: MutableMap<UUID, Long> = ConcurrentHashMap()

    /** 还要等多久（毫秒）。已就绪返回 0。 */
    fun remainingMillis(id: UUID): Long {
        val duration = durationMillis()
        if (duration <= 0L) return 0L
        val last = lastUse[id] ?: return 0L
        val remaining = duration - (clock() - last)
        return if (remaining > 0L) remaining else 0L
    }

    /**
     * 还要等多少秒，**向上取整**（剩 0.1 秒也显示 1 秒，避免提示“还要等 0 秒”）。
     * 与下沉前各处 `冷却秒数 - (已过毫秒 / 1000)` 的算法结果一致。
     */
    fun remainingSeconds(id: UUID): Long {
        val millis = remainingMillis(id)
        return if (millis <= 0L) 0L else (millis + 999L) / 1000L
    }

    fun isReady(id: UUID): Boolean = remainingMillis(id) <= 0L

    /**
     * 就绪则记下本次使用并返回 true；否则返回 false 且**不**刷新时间戳
     * （冷却中的重复尝试不该把冷却续上）。
     */
    fun tryUse(id: UUID): Boolean {
        if (!isReady(id)) return false
        if (durationMillis() > 0L) {
            lastUse[id] = clock()
        }
        return true
    }

    /** 无条件记下一次使用（调用方自己判过就绪时用）。 */
    fun mark(id: UUID) {
        lastUse[id] = clock()
    }

    fun clear(id: UUID) {
        lastUse.remove(id)
    }

    fun clearAll() {
        lastUse.clear()
    }
}
