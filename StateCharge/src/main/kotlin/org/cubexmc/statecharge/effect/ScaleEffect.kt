package org.cubexmc.statecharge.effect

import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.cubexmc.core.PlayerStateLeaseStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 玩家体型效果。使用 Paper 1.20.5+ 的现代属性 API `Attribute.SCALE`
 * (与 Clarity 同一路线;Paper 1.21.11 的 paper-api 已不含 Entity#setScale 糖),不需要 ProtocolLib。
 */
class ScaleEffect(stateId: String, private val scale: Double) : StateEffect {
    private val token = "statecharge:$stateId"
    private val legacySnapshots = ConcurrentHashMap<UUID, Double>()

    override fun start(player: Player) = apply(player)

    override fun reapply(player: Player) {
        val coordinated = PlayerStateLeaseStack.reapply(player, CHANNEL, token) { setScale(player, it.toDouble()) }
        if (!coordinated) apply(player)
    }

    override fun remove(player: Player) {
        val coordinated = PlayerStateLeaseStack.remove(player, CHANNEL, token) { setScale(player, it.toDouble()) }
        if (!coordinated) setScale(player, legacySnapshots.remove(player.uniqueId) ?: DEFAULT_SCALE)
        legacySnapshots.remove(player.uniqueId)
    }

    private fun apply(player: Player) {
        val attribute = player.getAttribute(Attribute.SCALE) ?: return
        legacySnapshots.putIfAbsent(player.uniqueId, attribute.baseValue)
        if (!PlayerStateLeaseStack.apply(
                player,
                CHANNEL,
                token,
                scale.toString(),
                { attribute.baseValue.toString() },
                { attribute.baseValue = it.toDouble() },
            )
        ) {
            attribute.baseValue = scale
        }
    }

    private fun setScale(player: Player, value: Double) {
        val attribute = player.getAttribute(Attribute.SCALE) ?: return
        attribute.baseValue = value
    }

    private companion object {
        const val DEFAULT_SCALE = 1.0
        const val CHANNEL = "scale"
    }
}
