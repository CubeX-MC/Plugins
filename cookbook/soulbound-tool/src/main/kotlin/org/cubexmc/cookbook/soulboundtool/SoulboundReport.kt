package org.cubexmc.cookbook.soulboundtool

/** 纯逻辑：把审计结果拼成一行行文本。不碰 Bukkit，单测直接打它。 */
object SoulboundReport {

    fun summarise(foreignSlots: List<String>): String = when {
        foreignSlots.isEmpty() -> "没有发现属于别人的绑定物品。"
        foreignSlots.size == 1 -> "发现 1 件属于别人的绑定物品:${foreignSlots.first()}"
        else -> "发现 ${foreignSlots.size} 件属于别人的绑定物品:${foreignSlots.joinToString(", ")}"
    }
}
