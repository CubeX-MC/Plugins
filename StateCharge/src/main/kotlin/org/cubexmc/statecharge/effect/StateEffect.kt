package org.cubexmc.statecharge.effect

import org.bukkit.entity.Player

/**
 * 一个可在玩家身上施加/重放/移除的限时状态效果。
 *
 * - [start]: 购买(或赠送)瞬间调用,可带一次性"起跳"行为(如自动起飞);
 * - [reapply]: 幂等重放,用于 join/respawn/换世界/换模式等效果丢失的场景;
 * - [remove]: 到期或清除时清理。
 */
interface StateEffect {
    fun start(player: Player)

    fun reapply(player: Player)

    fun remove(player: Player)
}
