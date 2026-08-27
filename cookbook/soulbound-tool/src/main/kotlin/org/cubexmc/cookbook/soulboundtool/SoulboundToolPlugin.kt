package org.cubexmc.cookbook.soulboundtool

import org.bukkit.NamespacedKey
import org.bukkit.command.CommandExecutor
import org.bukkit.entity.Player
import org.cubexmc.core.CubexPlugin
import org.cubexmc.core.PlayerItems
import org.cubexmc.core.getUuid
import org.cubexmc.core.hasFlag
import org.cubexmc.core.setFlag
import org.cubexmc.core.setUuid

/**
 * Cookbook 04 —— **灵魂绑定**。演示 `CubexPdc` 与 `PlayerItems`。
 *
 * 看点：
 * 1. [hasFlag] / [setFlag] 把"BYTE 当布尔用"这个 Bukkit 惯例收成一个词。
 * 2. [getUuid] 读到**被手改坏**的值时返回 null 而不是抛 —— PDC 里的东西不可信，
 *    这正是下沉它的理由。
 * 3. [PlayerItems.allSlots] 一次拿到背包 + 装备 + 末影箱，每个槽位**自带写回的 setter**，
 *    不必再关心"这一格该用哪个 API 放回去"。
 */
class SoulboundToolPlugin : CubexPlugin() {

    private lateinit var boundFlag: NamespacedKey
    private lateinit var ownerKey: NamespacedKey

    override fun enablePlugin() {
        boundFlag = NamespacedKey(this, "soulbound")
        ownerKey = NamespacedKey(this, "owner")

        registerCommand("soulbind", CommandExecutor { sender, _, _, _ ->
            val player = sender as? Player ?: return@CommandExecutor true
            bindHeldItem(player)
            true
        })

        registerCommand("soulaudit", CommandExecutor { sender, _, _, _ ->
            val player = sender as? Player ?: return@CommandExecutor true
            messager().send(player, SoulboundReport.summarise(foreignSlots(player)))
            true
        })
    }

    private fun bindHeldItem(player: Player) {
        val held = player.inventory.itemInMainHand
        val meta = held.itemMeta
        if (meta == null) {
            messager().send(player, text().color("&c手上没有可绑定的物品。"))
            return
        }
        meta.persistentDataContainer.setFlag(boundFlag)
        meta.persistentDataContainer.setUuid(ownerKey, player.uniqueId)
        held.itemMeta = meta
        messager().send(player, text().color("&a已绑定到你名下。"))
    }

    /** 身上所有"带绑定标记、但主人不是自己"的槽位标签。 */
    private fun foreignSlots(player: Player): List<String> =
        PlayerItems.allSlots(player).mapNotNull { slot ->
            val meta = slot.stack?.itemMeta ?: return@mapNotNull null
            if (!meta.persistentDataContainer.hasFlag(boundFlag)) return@mapNotNull null
            // 值被改坏时 getUuid 返回 null,当作"不属于本人"处理,而不是让命令炸掉
            val owner = meta.persistentDataContainer.getUuid(ownerKey)
            if (owner == player.uniqueId) null else slot.label
        }
}
