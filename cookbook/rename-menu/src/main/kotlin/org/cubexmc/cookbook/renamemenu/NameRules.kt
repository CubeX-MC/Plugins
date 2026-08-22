package org.cubexmc.cookbook.renamemenu

/** 纯逻辑：玩家输入 → 可用的物品名。不碰 Bukkit，单测直接打它。 */
object NameRules {

    const val MAX_LENGTH: Int = 32

    /** 返回清洗后的名字；空白或全是空格时返回 null，表示这次输入不作数。 */
    fun sanitise(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.take(MAX_LENGTH)
    }
}
