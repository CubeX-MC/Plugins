package org.cubexmc.clarity

import java.util.Locale

/**
 * 纯匹配逻辑(无 Bukkit 依赖,便于单测)。
 *
 * 黑名单驱动:只有显式点名的命名空间/id/类型才会命中。属性 modifier 永远不会命中
 * `minecraft` 命名空间,避免误删原版 modifier。
 */
object ClarityMatcher {
    /** 超过此 tick 数视为"无限/超长"(无限药水效果常用 Integer.MAX_VALUE)。约等于 13.9 小时。 */
    const val INFINITE_THRESHOLD_TICKS = 1_000_000L

    /** 某个 attribute modifier 是否命中黑名单。 */
    @JvmStatic
    fun matchesModifier(fullKey: String?, namespace: String?, blacklist: List<String?>?): Boolean =
        matchesNamespacedKey(fullKey, namespace, blacklist, true)

    /** 命名空间/id 黑名单匹配。支持命名空间、完整 key、完整 key 前缀。 */
    @JvmStatic
    fun matchesNamespacedKey(
        fullKey: String?,
        namespace: String?,
        blacklist: List<String?>?,
        protectMinecraft: Boolean,
    ): Boolean {
        if (fullKey == null || namespace == null || blacklist == null) return false
        if (protectMinecraft && namespace.equals("minecraft", ignoreCase = true)) return false

        val full = fullKey.lowercase(Locale.ROOT)
        val ns = namespace.lowercase(Locale.ROOT)
        for (raw in blacklist) {
            val pattern = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (pattern.isEmpty()) continue
            if (ns == pattern || full == pattern || full.startsWith(pattern)) return true
        }
        return false
    }

    /** 某个药水效果类型是否命中列表(按 path 或完整 key,大小写不敏感)。 */
    @JvmStatic
    fun matchesEffect(fullKey: String?, path: String?, types: List<String?>?): Boolean {
        if (types == null) return false
        val full = fullKey?.lowercase(Locale.ROOT).orEmpty()
        val keyPath = path?.lowercase(Locale.ROOT).orEmpty()
        for (raw in types) {
            val type = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (type.isEmpty()) continue
            if (keyPath == type || full == type) return true
        }
        return false
    }

    /** 持续时间是否为"无限/超长"(负数表示原版的 INFINITE_DURATION,或超过阈值的极大值)。 */
    @JvmStatic
    fun isInfiniteDuration(durationTicks: Int): Boolean =
        durationTicks < 0 || durationTicks > INFINITE_THRESHOLD_TICKS

    /** LevelTools 在 lore 行前写入 "§§";反色显示时可稳定识别为 "&&" 前缀。 */
    @JvmStatic
    fun isLevelToolsLoreLine(text: String?): Boolean = text?.replace('§', '&')?.startsWith("&&") == true
}
