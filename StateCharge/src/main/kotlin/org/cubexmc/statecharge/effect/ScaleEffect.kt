package org.cubexmc.statecharge.effect

import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player

/**
 * 玩家体型效果。使用 Paper 1.20.5+ 的现代属性 API `Attribute.SCALE`
 * (与 Clarity 同一路线;Paper 1.21.11 的 paper-api 已不含 Entity#setScale 糖),不需要 ProtocolLib。
 */
class ScaleEffect(private val scale: Double) : StateEffect {

    override fun start(player: Player) = setScale(player, scale)

    override fun reapply(player: Player) = setScale(player, scale)

    override fun remove(player: Player) = setScale(player, DEFAULT_SCALE)

    private fun setScale(player: Player, value: Double) {
        val attribute = player.getAttribute(Attribute.SCALE) ?: return
        attribute.baseValue = value
    }

    private companion object {
        const val DEFAULT_SCALE = 1.0
    }
}
